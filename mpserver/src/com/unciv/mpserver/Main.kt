package com.unciv.mpserver

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * All game work runs on ONE thread. Unciv's engine reaches through UncivGame.Current and
 * RulesetCache globals (v3 A2/A11), so one JVM process hosts exactly one game.
 * Scale-out is process-per-game, not threads.
 */
val gameThread = Executors.newSingleThreadExecutor { Thread(it, "game").apply { isDaemon = true } }.asCoroutineDispatcher()

object GameRegistry {
    /** The one game this process hosts. Only touched on [gameThread]. */
    @Volatile var host: GameHost? = null
    val dataDir = File(System.getProperty("mp.data", "../../mpserver/data/games")).absoluteFile
    /** Required for creating games, autoplay and the full-map replay. */
    val adminToken: String = System.getenv("MP_ADMIN_TOKEN") ?: UUID.randomUUID().toString()
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
    TeamRules.registerMod()
}

private const val MAX_BODY_BYTES = 256 * 1024

private suspend fun RoutingCall.host(): GameHost? {
    val host = GameRegistry.host?.takeIf { it.gameId == parameters["id"] }
    if (host == null) respond(HttpStatusCode.NotFound, mapOf("error" to "no game ${parameters["id"]}"))
    return host
}

/** Seat tokens: a seat can only read its own view and act for itself. Any authorized call counts as presence. */
private suspend fun RoutingCall.authorizedFor(host: GameHost, civ: String): Boolean {
    val seat = host.seats[civ]
    if (seat == null || seat.token != request.headers["X-Seat-Token"]) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "seat token required for $civ"))
        return false
    }
    host.touch(civ)
    return true
}

private suspend fun RoutingCall.isAdmin(): Boolean {
    if (request.headers["X-Admin-Token"] == GameRegistry.adminToken) return true
    respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
    return false
}

/** Runs [block] on the game thread; rule violations become 400/409 instead of 500s. */
private suspend inline fun <reified T : Any> RoutingCall.onGame(crossinline block: () -> T) {
    val result = withContext(gameThread) { runCatching { block() } }
    result.fold({ respond(it) }, { e ->
        when (e) {
            is IllegalArgumentException -> respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request")))
            is IllegalStateException -> respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "conflict")))
            else -> {
                e.printStackTrace()
                respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            }
        }
    })
}

fun main(args: Array<String>) {
    onGameThread { bootEngine() }
    if (args.firstOrNull() == "bench") return bench(args.drop(1))

    val port = args.firstOrNull()?.toIntOrNull() ?: 8090
    onGameThread { GameRegistry.host = restoreLatest() }
    println("Engine booted. Data dir: ${GameRegistry.dataDir}")
    println("Restored: ${GameRegistry.host?.let { "${it.gameId} at turn ${it.game.turns}" } ?: "none"}")
    if (System.getenv("MP_ADMIN_TOKEN") == null) println("Admin token (set MP_ADMIN_TOKEN to fix it): ${GameRegistry.adminToken}")

    // Round scheduler: seals rounds on timeout and pauses when nobody is present.
    CoroutineScope(Dispatchers.Default).launch {
        while (true) {
            delay(1000)
            withContext(gameThread) { runCatching { GameRegistry.host?.tick() }.onFailure { it.printStackTrace() } }
        }
    }

    embeddedServer(Netty, port = port, host = System.getProperty("mp.host", "127.0.0.1")) {
        install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
        intercept(ApplicationCallPipeline.Plugins) {
            if ((call.request.contentLength() ?: 0) > MAX_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "body over $MAX_BODY_BYTES bytes"))
                finish()
            }
        }
        routing {
            get("/health") { call.respondText("ok") }

            post("/games") {
                if (!call.isAdmin()) return@post
                val req = call.receive<CreateGameRequest>()
                val problems = req.problems()
                if (problems.isNotEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("errors" to problems))
                call.onGame {
                    check(GameRegistry.host == null) { "this process already hosts ${GameRegistry.host!!.gameId}; run one process per game" }
                    val id = "g" + UUID.randomUUID().toString().take(8)
                    val host = GameHost.create(id, req, GameRegistry.dataDir)
                    GameRegistry.host = host
                    mapOf("gameId" to id) + host.seats.mapValues { it.value.token }
                }
            }

            get("/games/{id}") {
                val host = call.host() ?: return@get
                call.onGame { host.summary() }
            }

            /** Long-poll: returns once the game has passed turn `after` (or after 25 s). With a seat token it is also a heartbeat. */
            get("/games/{id}/wait") {
                val host = call.host() ?: return@get
                val after = call.request.queryParameters["after"]?.toIntOrNull() ?: -1
                call.request.queryParameters["civ"]?.let { civ -> if (!call.authorizedFor(host, civ)) return@get }
                withTimeoutOrNull(25_000) { host.turn.first { it > after } }
                call.onGame { host.summary() }
            }

            get("/games/{id}/view/{civ}") {
                val host = call.host() ?: return@get
                val civ = call.parameters["civ"]!!
                if (!call.authorizedFor(host, civ)) return@get
                call.onGame { host.view(civ) }
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
                call.onGame { host.submitIntents(batch) }
            }

            /** Commit this seat's plan for the round. Kept at /end-turn for existing clients. */
            for (path in listOf("/games/{id}/commit", "/games/{id}/end-turn")) post(path) {
                val host = call.host() ?: return@post
                val req = call.receive<CommitRequest>()
                if (!call.authorizedFor(host, req.civ)) return@post
                call.onGame { host.commit(req) }
            }

            /** present=false: the seat leaves and the AI plays it from the next resolution on. */
            post("/games/{id}/presence/{civ}") {
                val host = call.host() ?: return@post
                val civ = call.parameters["civ"]!!
                if (!call.authorizedFor(host, civ)) return@post
                val present = call.request.queryParameters["present"] != "false"
                call.onGame {
                    if (!present) host.leave(civ)
                    mapOf("civ" to civ, "presence" to if (present) "PRESENT" else "OFFLINE")
                }
            }

            post("/games/{id}/autoplay") {
                if (!call.isAdmin()) return@post
                val host = call.host() ?: return@post
                val turns = call.request.queryParameters["turns"]?.toIntOrNull() ?: 10
                call.onGame { host.autoplay(turns) }
            }

            get("/games/{id}/replay") {
                if (!call.isAdmin()) return@get
                val host = call.host() ?: return@get
                call.onGame { host.replay() }
            }
        }
    }.start(wait = true)
}

/** Picks up the most recently saved game in the data dir, if any. */
private fun restoreLatest(): GameHost? {
    val dirs = GameRegistry.dataDir.listFiles { f -> File(f, "meta.json").exists() }.orEmpty()
    val latest = dirs.maxByOrNull { File(it, "save.txt").lastModified() } ?: return null
    return runCatching { GameHost.restore(latest) }.onFailure {
        println("Could not restore ${latest.name}: ${it.message}")
    }.getOrNull()
}

/**
 * 8v8 timing + AI sanity benchmark, all 16 civs Unciv AI (worst case for server time per round):
 * `./gradlew :mpserver:run -PserverArgs="bench <mapSize> <turns> [warFromTurn|vanilla] [cityStates]"`.
 * "vanilla" keeps Unciv's free diplomacy (teams are only labels), as a control.
 */
private fun bench(args: List<String>) {
    val mapSize = args.getOrNull(0) ?: "Huge"
    val turns = args.getOrNull(1)?.toIntOrNull() ?: 100
    val mode = args.getOrNull(2)
    val defaults = CreateGameRequest()
    val request = defaults.copy(mapSize = mapSize, externalSeats = emptyList(), fixedTeams = mode != "vanilla",
        warFromTurn = mode?.toIntOrNull() ?: defaults.warFromTurn, cityStates = args.getOrNull(3)?.toIntOrNull() ?: defaults.cityStates)
    val dir = File(System.getProperty("java.io.tmpdir"), "mpserver-bench").apply { deleteRecursively() }
    onGameThread {
        val host = GameHost.create("bench", request, dir)
        println("bench: $mapSize (${host.game.tileMap.tileList.size} tiles), ${request.teams.values.sumOf { it.size }} majors, " +
            "${request.cityStates} city-states, " + if (request.fixedTeams) "fixed teams, war from turn ${request.warFromTurn}" else "vanilla diplomacy")
        val roundMs = ArrayList<Long>()
        for (r in host.autoplay(turns)) {
            roundMs += r.timings.run { seatMs + aiMs + turnMs + saveMs }
            if (host.game.turns % 50 == 0 || r.winningTeam != null) {
                println(teamSnapshot(host) + "  | round ms avg ${roundMs.average().toLong()} max ${roundMs.max()}, save ${r.timings.saveBytes / 1024} KB")
                roundMs.clear()
            }
            if (r.winningTeam != null) { println("WINNER at turn ${host.game.turns}: ${r.winningTeam}"); break }
        }
        println("final, per civ:")
        for ((team, nations) in request.teams) for (civ in host.game.civilizations.filter { it.civName in nations }) {
            println("  %-6s %-16s %-5s cities %2d (captured %d)  pop %3d  military %3d".format(team, civ.civName,
                if (civ.isAlive()) "" else "DEAD", civ.cities.size, civ.cities.count { it.foundingCivObject != civ },
                civ.cities.sumOf { it.population.population }, civ.units.getCivUnits().count { it.isMilitary() }))
        }
        val loadMs = timed { GameStore(File(dir, "bench")).load() }
        UncivGame.Current.gameInfo = host.game
        println("load save.txt: $loadMs ms")
    }
    System.exit(0)
}

/** One line per checkpoint: turn, and per team alive civs, cities, cities captured from the other team, techs. */
private fun teamSnapshot(host: GameHost): String = "turn %3d  ".format(host.game.turns) +
    host.request.teams.entries.joinToString("  ") { (team, nations) ->
        val civs = host.game.civilizations.filter { it.civName in nations }
        val captured = civs.flatMap { it.cities }.count { it.foundingCivObject != null && it.foundingCivObject!!.civName !in nations }
        "$team: ${civs.count { it.isAlive() }}/${civs.size} alive, ${civs.sumOf { it.cities.size }} cities ($captured taken), techs ${civs.sumOf { it.tech.techsResearched.size }}"
    }

private fun onGameThread(block: () -> Unit) {
    gameThread.executor.let { (it as java.util.concurrent.ExecutorService).submit(block).get() }
}
