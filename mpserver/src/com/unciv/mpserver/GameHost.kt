package com.unciv.mpserver

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

enum class Presence { PRESENT, OFFLINE }

class Seat(val civ: String, val token: String, val team: String?) {
    /** Last authenticated request. A seat is PRESENT while this is recent; 0 = explicitly left. */
    @Volatile var lastSeen: Long = 0

    fun presence(now: Long): Presence = if (now - lastSeen < PRESENT_WINDOW_MS) Presence.PRESENT else Presence.OFFLINE

    companion object { const val PRESENT_WINDOW_MS = 120_000L }
}

/**
 * One authoritative game, Plan & Commit. Every method must run on the single game thread
 * ([gameThread]): Unciv's engine is not thread-safe and reaches through UncivGame.Current.
 *
 * Round lifecycle:
 * 1. Planning: PRESENT seats submit intents (checked, buffered, logged) and commit.
 * 2. Sealing: when every PRESENT seat has committed, or the turn timer runs out. With nobody present the
 *    game pauses; it never waits for a seat that is not there.
 * 3. Resolution ([RoundEngine]): plans applied in a seeded, team-interleaved order. A seat that did not
 *    commit in time, or asked for it, gets AI stewardship for the rest; an OFFLINE seat is played by the AI.
 */
class GameHost private constructor(
    val gameId: String,
    val request: CreateGameRequest,
    private val store: GameStore,
    val seats: Map<String, Seat>,
) {
    lateinit var game: GameInfo
        private set
    private val plans = HashMap<String, MutableList<Intent>>()
    private val reasoning = HashMap<String, String>()
    private val commits = HashMap<String, CommitRequest>()
    private var roundOpenedAt = System.currentTimeMillis()
    private var paused = true
    private var events: Map<String, List<String>> = emptyMap()
    /** Every overextension and bounty applied so far (not persisted; for benches and logs). */
    val catchUpLog = ArrayList<String>()
    private val frames = ArrayList<ReplayFrame>()
    private var lastResult: RoundResult? = null
    var winningTeam: String? = null
        private set

    private val _turn = MutableStateFlow(0)
    /** Readable from any thread: lets HTTP long-polls wait without touching the game thread. */
    val turn: StateFlow<Int> get() = _turn

    companion object {
        const val MAX_PLAN = 500

        fun create(gameId: String, requested: CreateGameRequest, dataDir: File): GameHost {
            val request = if (!requested.randomLeaders) requested else requested.copy(teams = TeamRules.randomizeTeams(
                requested.teams, requested.externalSeats,
                RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.nations.values.filter { it.isMajorCiv }.map { it.name }, requested.seed))
            val seats = request.externalSeats.associateWith { Seat(it, UUID.randomUUID().toString(), TeamRules.teamOf(request.teams, it)) }
            val host = GameHost(gameId, request, GameStore(File(dataDir, gameId)), seats)
            host.game = host.startGame()
            host.store.writeMeta(GameMeta(gameId, request, seats.mapValues { it.value.token }))
            host.store.writeSave(host.game)
            host.recordFrame()
            return host
        }

        /** Rebuilds a game from disk after a restart: last round boundary + acknowledged plans. */
        fun restore(dir: File): GameHost? {
            val store = GameStore(dir)
            val (meta, game, planLog) = store.load() ?: return null
            val seats = meta.seatTokens.mapValues { (civ, token) -> Seat(civ, token, TeamRules.teamOf(meta.request.teams, civ)) }
            val host = GameHost(meta.gameId, meta.request, store, seats)
            host.game = game
            UncivGame.Current.gameInfo = game
            for (entry in planLog) {
                entry.batch?.let { host.bufferBatch(it) }
                entry.commit?.let { host.commits[it.civ] = it }
            }
            host.winningTeam = TeamRules.winningTeam(game, meta.request.teams)
            host._turn.value = game.turns
            return host
        }
    }

    private fun startGame(): GameInfo {
        val nations = request.teams.values.flatten()
        val unknown = nations.filter { RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.nations[it]?.isMajorCiv != true }
        require(unknown.isEmpty()) { "not major nations in Gods & Kings: $unknown" }
        val params = GameParameters().apply {
            difficulty = request.difficulty
            numberOfCityStates = request.cityStates
            noBarbarians = false
            players = ArrayList(nations.map { Player(it, if (it in seats) PlayerType.Human else PlayerType.AI) })
            // Unciv needs one human player; an all-AI game (bench) gets a spectator, which never acts
            if (seats.isEmpty()) players.add(Player(Constants.spectator, PlayerType.Human))
            baseRuleset = BaseRuleset.Civ_V_GnK.fullName
            if (request.fixedTeams) mods = linkedSetOf(TeamRules.MOD_NAME)
            victoryTypes = ArrayList(RulesetCache.getComplexRuleset(this).victories.keys)
        }
        val mapParams = MapParameters().apply {
            mapSize = MapSize(request.mapSize)
            seed = request.seed
            noRuins = false
        }
        val game = GameStarter.startNewGame(GameSetupInfo(params, mapParams))
        UncivGame.Current.gameInfo = game
        if (request.groupedStarts) TeamRules.groupStarts(game, request.teams, request.seed)
        if (request.contestedCityStates) TeamRules.placeContestedCityStates(game, request.teams, request.seed)
        if (request.fixedTeams) TeamRules.applyAtStart(game, request.teams, atWar = request.warFromTurn <= 0)
        return game
    }

    // ---------------- Views ----------------

    fun civ(name: String): Civilization? = game.civilizations.firstOrNull { it.civName == name }

    fun touch(civName: String) { seats[civName]?.lastSeen = System.currentTimeMillis() }

    fun view(civName: String): CivViewDto =
        MaskedView.build(game, civ(civName) ?: error("Unknown civ $civName")) { TeamRules.teamOf(request.teams, it) }

    fun brief(civName: String): String = MaskedView.brief(view(civName), events[civName].orEmpty())

    fun deadline(): Long? = if (paused || winningTeam != null) null
        else roundOpenedAt + request.turnTimer.secondsFor(game.turns) * 1000L

    fun summary(now: Long = System.currentTimeMillis()) = GameSummary(
        gameId, game.turns,
        seats.values.map { SeatInfo(it.civ, it.team, "external", it.presence(now).name, it.civ in commits) } +
            game.civilizations.filter { it.isMajorCiv() && it.civName !in seats }
                .map { SeatInfo(it.civName, TeamRules.teamOf(request.teams, it.civName), "unciv-ai", Presence.PRESENT.name) },
        deadline(),
        winningTeam,
    )

    // ---------------- Planning ----------------

    fun submitIntents(batch: IntentBatch): IntentBatchResult {
        val civ = civ(batch.civ) ?: error("Unknown civ ${batch.civ}")
        require(batch.civ in seats) { "${batch.civ} is not an external seat" }
        check(winningTeam == null) { "game is over" }
        check(batch.civ !in commits) { "${batch.civ} already committed turn ${game.turns}" }
        require(batch.intents.size + plans[batch.civ].orEmpty().size <= MAX_PLAN) { "plan larger than $MAX_PLAN intents" }
        // Durable before acknowledged: a crash re-applies it on restore.
        store.appendPlan(PlanLogEntry(game.turns, batch = batch))
        return IntentBatchResult(game.turns, bufferBatch(batch, civ))
    }

    private fun bufferBatch(batch: IntentBatch, civ: Civilization = civ(batch.civ)!!): List<IntentResult> {
        val results = batch.intents.map { intent ->
            val problem = IntentValidator.check(civ, intent)
            IntentResult(intent, problem == null, problem ?: "planned for turn ${game.turns}")
        }
        plans.getOrPut(batch.civ) { ArrayList() } += results.filter { it.accepted }.map { it.intent }
        batch.reasoning?.let { reasoning[batch.civ] = it.take(IntentValidator.MAX_NOTE) }
        return results
    }

    fun commit(req: CommitRequest): CommitResult {
        require(req.civ in seats) { "${req.civ} is not an external seat" }
        check(winningTeam == null) { "game is over" }
        if (req.civ !in commits) {
            store.appendPlan(PlanLogEntry(game.turns, commit = req))
            commits[req.civ] = req
        }
        val turnBefore = game.turns
        val result = tick()
        return CommitResult(if (result != null) turnBefore else game.turns, result != null, waitingFor(), result)
    }

    /** Seat leaves: from now on its civ is played by the AI and no round waits for it. */
    fun leave(civName: String) {
        seats[civName]?.lastSeen = 0
        tick()
    }

    private fun waitingFor(now: Long = System.currentTimeMillis()) =
        seats.values.filter { it.presence(now) == Presence.PRESENT && it.civ !in commits }.map { it.civ }

    /** Seals and resolves the round when it is due. Called on every commit and once a second by the scheduler. */
    fun tick(now: Long = System.currentTimeMillis()): RoundResult? {
        if (winningTeam != null) return null
        val present = seats.values.filter { it.presence(now) == Presence.PRESENT }
        if (present.isEmpty()) {
            // Nobody to wait for, and nobody to play for: pause rather than let the world run unattended.
            paused = true
            return null
        }
        if (paused) { paused = false; roundOpenedAt = now }
        val allCommitted = present.all { it.civ in commits }
        if (!allCommitted && now < deadline()!!) return null
        return resolveRound(now)
    }

    // ---------------- Resolution ----------------

    private fun resolveRound(now: Long = System.currentTimeMillis()): RoundResult {
        val turnAtStart = game.turns
        val order = RoundEngine.resolutionOrder(game, request.teams, request.seed * 1_000_003 + turnAtStart)
        val modes = HashMap<String, String>()
        val results = HashMap<String, List<IntentResult>>()
        var seatMs = 0L
        var aiMs = 0L

        val ownersBefore = CatchUp.owners(game)
        val totalMs = timed {
            RoundEngine.resolve(game, order) { civ ->
                val seat = seats[civ.civName]
                if (seat == null) {
                    aiMs += timed { TurnManager(civ).automateTurn() }
                    return@resolve
                }
                seatMs += timed {
                    results[civ.civName] = plans[civ.civName].orEmpty().map { IntentValidator.apply(civ, it) }
                    val commit = commits[civ.civName]
                    modes[civ.civName] = when {
                        seat.presence(now) == Presence.OFFLINE -> {
                            NextTurnAutomation.automateCivMoves(civ); "AI"
                        }
                        commit == null || commit.delegateRest -> {
                            // Stewardship: units and empty queues, no diplomacy or spending
                            NextTurnAutomation.automateCivMoves(civ, tradeAndChangeState = false); "PLAN+AI"
                        }
                        else -> "PLAN"
                    }
                }
            }
            if (request.fixedTeams && game.turns == request.warFromTurn) TeamRules.startWar(game, request.teams)
            if (request.fixedTeams && request.catchUp && game.turns > request.warFromTurn)
                catchUpLog += CatchUp.apply(game, request.teams, ownersBefore)
        }

        var saveBytes = 0
        val saveMs = timed { saveBytes = store.writeSave(game) }
        store.clearPlans()

        winningTeam = TeamRules.winningTeam(game, request.teams)
        events = seats.keys.associateWith { s -> civ(s)?.notifications?.map { it.text }?.take(30).orEmpty() }
        val timings = TurnTimings(turnAtStart, seatMs, aiMs, totalMs - seatMs - aiMs, saveMs, saveBytes)
        val result = RoundResult(turnAtStart, timings, modes, results, winningTeam)
        lastResult = result
        recordFrame(timings, modes)

        plans.clear()
        reasoning.clear()
        commits.clear()
        roundOpenedAt = System.currentTimeMillis()
        _turn.value = game.turns
        return result
    }

    /** Admin/bench: every seat leaves and the world runs [turns] rounds with no one waiting. */
    fun autoplay(turns: Int): List<RoundResult> {
        seats.values.forEach { it.lastSeen = 0 }
        val out = ArrayList<RoundResult>()
        repeat(turns.coerceIn(0, 1000)) { if (winningTeam == null) out += resolveRound() }
        return out
    }

    // ---------------- Replay (admin only: it shows the whole map) ----------------

    private val civOrder: List<Civilization> by lazy { game.civilizations.filter { !it.isSpectator() } }

    private fun recordFrame(timings: TurnTimings? = null, modes: Map<String, String> = emptyMap()) {
        val index = civOrder.withIndex().associate { it.value.civName to it.index }
        frames += ReplayFrame(
            turn = game.turns,
            owners = game.tileMap.tileList.map { t -> t.getOwner()?.let { index[it.civName] } ?: -1 },
            cities = civOrder.flatMap { c -> c.cities.map { CityMarker(it.location.x, it.location.y, it.name, index[c.civName]!!, it.population.population) } },
            units = civOrder.flatMap { c -> c.units.getCivUnits().map { UnitMarker(it.getTile().position.x, it.getTile().position.y, index[c.civName]!!, it.isMilitary()) } },
            scores = civOrder.filter { it.isMajorCiv() }.associate { it.civName to it.getStatForRanking(RankingType.Score) },
            timings = timings,
            agentDecisions = reasoning.map { (civ, why) -> AgentDecision(civ, why, lastResult?.intentResults?.get(civ).orEmpty()) },
            events = events.flatMap { (civ, list) -> list.map { "$civ: $it" } }.take(30),
            offline = modes.values.any { it == "AI" },
        )
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
}

inline fun timed(block: () -> Unit): Long {
    val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
}
