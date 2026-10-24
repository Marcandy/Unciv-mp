package com.unciv.mpserver

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class Presence { PRESENT, OFFLINE }

class Seat(val civ: String, val token: String) {
    @Volatile var presence: Presence = Presence.PRESENT
    @Volatile var lastSeen: Long = System.currentTimeMillis()
}

/**
 * One authoritative game. Every method must run on the single game thread (see [GameRegistry]):
 * Unciv's engine is not thread-safe and still reaches through the UncivGame.Current singleton.
 *
 * Round model (MVP, sequential): Unciv AI civs resolve inside nextTurn(); nextTurn() stops at each
 * external seat. A PRESENT seat is waited for; an OFFLINE seat is played by Unciv AI immediately
 * ("never wait for someone who isn't there").
 */
class GameHost(val gameId: String, val request: CreateGameRequest, dataDir: File) {
    private val json = Json { encodeDefaults = true }
    private val dir = File(dataDir, gameId).apply { mkdirs() }
    private val intentLog = File(dir, "intents.jsonl")
    val seats: Map<String, Seat> = request.externalSeats.associateWith { Seat(it, UUID.randomUUID().toString()) }
    lateinit var game: GameInfo
        private set
    private val frames = ArrayList<ReplayFrame>()
    private val pendingDecisions = ArrayList<AgentDecision>()
    private var lastTimings: TurnTimings? = null
    private var lastEvents: List<String> = emptyList()

    fun create() {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        val params = GameParameters().apply {
            difficulty = request.difficulty
            numberOfCityStates = request.cityStates
            noBarbarians = false
            players = ArrayList(request.nations.take(request.majorCivs).map { nation ->
                Player(nation, if (nation in request.externalSeats) PlayerType.Human else PlayerType.AI)
            })
            victoryTypes = ArrayList(ruleset.victories.keys)
            baseRuleset = BaseRuleset.Civ_V_GnK.fullName
        }
        val mapParams = MapParameters().apply {
            mapSize = MapSize(request.mapSize)
            seed = request.seed
            noRuins = false
        }
        game = GameStarter.startNewGame(GameSetupInfo(params, mapParams))
        UncivGame.Current.gameInfo = game
        recordFrame()
        persist()
    }

    // ---------------- Views ----------------

    fun civ(name: String): Civilization? = game.civilizations.firstOrNull { it.civName == name }

    fun view(civName: String): CivViewDto = MaskedView.build(game, civ(civName) ?: error("Unknown civ $civName"))

    fun brief(civName: String): String = MaskedView.brief(view(civName), lastEvents)

    fun summary() = GameSummary(
        gameId, game.turns,
        seats.values.map { SeatInfo(it.civ, "external", it.presence.name) } +
            game.civilizations.filter { it.isMajorCiv() && it.civName !in seats }.map { SeatInfo(it.civName, "unciv-ai", "PRESENT") },
        winner()
    )

    private fun winner(): String? = game.civilizations.firstOrNull { it.victoryManager.hasWon() }?.civName

    // ---------------- Intents ----------------

    fun submitIntents(batch: IntentBatch): IntentBatchResult {
        val civ = civ(batch.civ) ?: error("Unknown civ ${batch.civ}")
        require(batch.civ in seats) { "${batch.civ} is not an external seat" }
        seats[batch.civ]!!.apply { presence = Presence.PRESENT; lastSeen = System.currentTimeMillis() }
        // Durable before applied: a crash replays instead of losing acknowledged input.
        intentLog.appendText(json.encodeToString(batch.copy()) + "\n")
        val results = batch.intents.map { IntentValidator.apply(civ, it) }
        pendingDecisions += AgentDecision(batch.civ, batch.reasoning, results)
        return IntentBatchResult(game.turns, results)
    }

    // ---------------- Turns ----------------

    fun currentSeat(): String? = game.currentPlayerCiv.civName.takeIf { it in seats }

    /** The external seat [civName] ends its turn. Runs AI for any OFFLINE seats after it. */
    fun endTurn(civName: String): EndTurnResult {
        require(game.currentPlayerCiv.civName == civName) {
            "Not $civName's turn (current: ${game.currentPlayerCiv.civName})"
        }
        seats[civName]?.lastSeen = System.currentTimeMillis()
        return advance()
    }

    /** Mark every external seat OFFLINE and let the world run for [turns] turns: never wait. */
    fun autoplay(turns: Int): List<EndTurnResult> {
        seats.values.forEach { it.presence = Presence.OFFLINE }
        val out = ArrayList<EndTurnResult>()
        repeat(turns) { if (winner() == null) out += advance() }
        return out
    }

    fun setPresence(civName: String, present: Boolean) {
        seats[civName]?.presence = if (present) Presence.PRESENT else Presence.OFFLINE
    }

    private fun advance(): EndTurnResult {
        val startTurn = game.turns
        var seatMs = 0L
        var aiMs = 0L
        // Run until the world reaches a PRESENT external seat, or one full world turn has passed.
        while (true) {
            val seatCiv = game.currentPlayerCiv
            // Seat stewardship: Unciv AI moves units and fills empty queues, but keeps the
            // seat's chosen research and production (queues set via intents are respected).
            seatMs += timed { NextTurnAutomation.automateCivMoves(seatCiv) }
            aiMs += timed { game.nextTurn() }
            if (winner() != null) break
            val nextSeat = currentSeat()?.let { seats[it] } ?: continue
            if (nextSeat.presence == Presence.PRESENT) break
            if (game.turns > startTurn) break // OFFLINE seat: AI plays it, but return once per world turn
        }

        val events = seats.keys.flatMap { s -> civ(s)?.notifications?.map { "$s: ${it.text}" } ?: emptyList() }
            .take(30)
        val maskMs = timed { seats.values.filter { it.presence == Presence.PRESENT }.forEach { view(it.civ) } }
        var save = ""
        val saveMs = timed { save = UncivFiles.gameInfoToString(game, forceZip = true) }
        File(dir, "save.txt").writeText(save)
        val loadMs = if (game.turns % 10 == 0) timed { UncivFiles.gameInfoFromString(save) }.also {
            UncivGame.Current.gameInfo = game // loading touches the global; restore the live game
        } else null
        val timings = TurnTimings(game.turns, seatMs, aiMs, maskMs, saveMs, loadMs, save.length)
        lastTimings = timings
        lastEvents = events
        recordFrame(events)
        return EndTurnResult(game.turns, timings, events, winner())
    }

    private fun persist() = File(dir, "save.txt").writeText(UncivFiles.gameInfoToString(game, forceZip = true))

    // ---------------- Replay ----------------

    private val civOrder: List<Civilization> by lazy {
        game.civilizations.filter { !it.isSpectator() }
    }

    private fun recordFrame(events: List<String> = emptyList()) {
        val index = civOrder.withIndex().associate { it.value.civName to it.index }
        val tiles = game.tileMap.tileList
        frames += ReplayFrame(
            turn = game.turns,
            owners = tiles.map { t -> t.getOwner()?.let { index[it.civName] } ?: -1 },
            cities = civOrder.flatMap { c -> c.cities.map { CityMarker(it.location.x, it.location.y, it.name, index[c.civName]!!, it.population.population) } },
            units = civOrder.flatMap { c -> c.units.getCivUnits().map { UnitMarker(it.getTile().position.x, it.getTile().position.y, index[c.civName]!!, it.isMilitary()) } },
            scores = civOrder.filter { it.isMajorCiv() }.associate { it.civName to it.getStatForRanking(RankingType.Score) },
            timings = lastTimings,
            agentDecisions = ArrayList(pendingDecisions),
            events = events,
            offline = seats.values.any { it.presence == Presence.OFFLINE },
        )
        pendingDecisions.clear()
    }

    fun replay(): ReplayData = ReplayData(
        gameId, request.mapSize,
        game.tileMap.tileList.map { ReplayTile(it.position.x, it.position.y, it.baseTerrain, it.terrainFeatures.toList()) },
        civOrder.map { c ->
            val outer = runCatching { c.nation.getOuterColor() }.getOrNull()
            val inner = runCatching { c.nation.getInnerColor() }.getOrNull()
            CivColor(c.civName, outer?.let { "#" + it.toString().take(6) } ?: "#888888",
                inner?.let { "#" + it.toString().take(6) } ?: "#222222", c.isCityState)
        },
        request.externalSeats,
        frames,
    )

    companion object {
        inline fun timed(block: () -> Unit): Long {
            val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
        }
    }
}

@Suppress("unused")
private val spectator = Constants.spectator
