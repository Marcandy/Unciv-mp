package com.unciv.mpserver

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.badlogic.gdx.Gdx
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.RoutingCall
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * All game work runs on ONE thread. Unciv's engine reaches through UncivGame.Current and
 * RulesetCache globals (v3 A2/A11), so one JVM process = one game thread for the MVP.
 * Scale-out is process-per-shard, not threads.
 */
val gameThread = Executors.newSingleThreadExecutor { Thread(it, "game").apply { isDaemon = true } }.asCoroutineDispatcher()

object GameRegistry {
    val games = ConcurrentHashMap<String, GameHost>()
    val dataDir = File(System.getProperty("mp.data", "../../mpserver/data/games")).absoluteFile
}

/** The canonical settings profile (v3 A2): settings that change game logic are game rules here, not preferences. */
fun canonicalSettings() = GameSettings().apply {
    showTutorials = false
    turnsBetweenAutosaves = 10000
    autoAssignCityProduction = true
    automatedUnitsMoveOnTurnStart = false
    citiesAutoBombardAtEndOfTurn = true
    automatedUnitsChoosePromotions = true
    useAStarPathfinding = false // the experimental A* path never reaches enemy city tiles, so the AI could not pick siege targets
}

fun bootEngine() {
    HeadlessApplication(object : ApplicationAdapter() {}, HeadlessApplicationConfiguration().apply { updatesPerSecond = -1 })
    val game = UncivGame(true)
    UncivGame.Current = game
    game.files = UncivFiles(Gdx.files, System.getProperty("mp.local", "/tmp/unciv-mpserver-local"))
    game.settings = canonicalSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f }
    game.musicController = com.unciv.ui.audio.MusicController() // silent; nextTurn touches it every 10 turns
    RulesetCache.loadRulesets(true)
}

private suspend fun RoutingCall.host(): GameHost? {
    val id = parameters["id"]!!
    val host = GameRegistry.games[id]
    if (host == null) respond(HttpStatusCode.NotFound, mapOf("error" to "no game $id"))
    return host
}

/** Seat tokens: a seat can only read its own view and act for itself. */
private suspend fun RoutingCall.authorizedFor(host: GameHost, civ: String): Boolean {
    val token = request.headers["X-Seat-Token"]
    val seat = host.seats[civ]
    if (seat == null || seat.token != token) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "seat token required for $civ"))
        return false
    }
    return true
}

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8090
    runCatchingOnGameThread { bootEngine() }
    println("Engine booted. Data dir: ${GameRegistry.dataDir}")

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
        routing {
            get("/health") { call.respondText("ok") }

            post("/games") {
                val req = call.receive<CreateGameRequest>()
                val id = "g" + System.currentTimeMillis().toString(36)
                val host = withContext(gameThread) { GameHost(id, req, GameRegistry.dataDir).also { it.create() } }
                GameRegistry.games[id] = host
                call.respond(mapOf("gameId" to id) + host.seats.mapValues { it.value.token })
            }

            get("/games/{id}") {
                val host = call.host() ?: return@get
                call.respond(withContext(gameThread) { host.summary() })
            }

            get("/games/{id}/view/{civ}") {
                val host = call.host() ?: return@get
                val civ = call.parameters["civ"]!!
                if (!call.authorizedFor(host, civ)) return@get
                call.respond(withContext(gameThread) { host.view(civ) })
            }

            get("/games/{id}/brief/{civ}") {
                val host = call.host() ?: return@get
                val civ = call.parameters["civ"]!!
                if (!call.authorizedFor(host, civ)) return@get
                call.respondText(withContext(gameThread) { host.brief(civ) })
            }

            post("/games/{id}/intents") {
                val host = call.host() ?: return@post
                val batch = call.receive<IntentBatch>()
                if (!call.authorizedFor(host, batch.civ)) return@post
                call.respond(withContext(gameThread) { host.submitIntents(batch) })
            }

            post("/games/{id}/end-turn") {
                val host = call.host() ?: return@post
                val req = call.receive<EndTurnRequest>()
                if (!call.authorizedFor(host, req.civ)) return@post
                val result = withContext(gameThread) { runCatching { host.endTurn(req.civ) } }
                result.fold({ call.respond(it) }, {
                    it.printStackTrace()
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (it.message ?: it.javaClass.simpleName)))
                })
            }

            post("/games/{id}/presence/{civ}") {
                val host = call.host() ?: return@post
                val civ = call.parameters["civ"]!!
                if (!call.authorizedFor(host, civ)) return@post
                val present = call.request.queryParameters["present"] != "false"
                withContext(gameThread) { host.setPresence(civ, present) }
                call.respond(mapOf("civ" to civ, "presence" to if (present) "PRESENT" else "OFFLINE"))
            }

            /** Everyone goes offline; the world keeps playing. Demonstrates "never wait". */
            post("/games/{id}/autoplay") {
                val host = call.host() ?: return@post
                val turns = call.request.queryParameters["turns"]?.toIntOrNull() ?: 10
                call.respond(withContext(gameThread) { host.autoplay(turns) })
            }

            get("/games/{id}/replay") {
                val host = call.host() ?: return@get
                call.respond(withContext(gameThread) { host.replay() })
            }
        }
    }.start(wait = true)
}

private fun runCatchingOnGameThread(block: () -> Unit) {
    gameThread.executor.let { (it as java.util.concurrent.ExecutorService).submit(block).get() }
}
