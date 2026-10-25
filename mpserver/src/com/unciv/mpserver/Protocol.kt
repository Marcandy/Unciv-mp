package com.unciv.mpserver

import kotlinx.serialization.Serializable

/*
 * Wire protocol. New code only, kotlinx.serialization (codegen, no reflection), so the same
 * types can later compile to iOS/JS clients. The save file format is untouched.
 */

val DEFAULT_TEAMS: Map<String, List<String>> = linkedMapOf(
    "Allies" to listOf("America", "England", "France", "Russia", "China", "The Netherlands", "Greece", "India"),
    "Axis" to listOf("Germany", "Japan", "Rome", "The Ottomans", "Austria", "Mongolia", "The Huns", "Persia"),
)

@Serializable
data class CreateGameRequest(
    val mapSize: String = "Huge",
    val cityStates: Int = 3,
    /** Fixed teams: teammates are permanently at peace, teams are permanently at war. */
    val teams: Map<String, List<String>> = DEFAULT_TEAMS,
    /** Seats played through the API (humans or bots). Every other civ is Unciv AI. */
    val externalSeats: List<String> = listOf("America", "Germany"),
    val seed: Long = 42,
    val difficulty: String = "Prince",
    val turnTimer: TurnTimer = TurnTimer(),
    /** Teams are at peace (fixed, no war possible) until this turn; 0 = at war from the start. */
    val warFromTurn: Int = 20,
    /** False = Unciv's normal free diplomacy, teams only label civs (used as the bench control). */
    val fixedTeams: Boolean = true,
    /** Each team starts on its own half of the map (false = Unciv's random, interleaved starts). */
    val groupedStarts: Boolean = true,
    /** City-states sit on the front between the teams, rich in resources, at war with everyone. */
    val contestedCityStates: Boolean = true,
    /** Fill every non-seat slot with a random major nation (seeded); team names and sizes come from [teams]. */
    val randomLeaders: Boolean = true,
) {
    fun problems(): List<String> = buildList {
        val nations = teams.values.flatten()
        if (teams.size < 2) add("need at least 2 teams")
        if (teams.values.any { it.isEmpty() }) add("every team needs at least one nation")
        if (nations.size != nations.toSet().size) add("a nation is on more than one team")
        externalSeats.filter { it !in nations }.forEach { add("seat '$it' is not on any team") }
        if (cityStates !in 0..24) add("cityStates must be 0-24")
    }
}

/**
 * Plan & Commit round timer: short while there is little to do, longer once empires grow.
 * Defaults: 12 s per turn up to turn 50 (about 10 min for the first 50 turns), ramping to 36 s by turn 100.
 */
@Serializable
data class TurnTimer(
    val earlySeconds: Int = 12,
    val lateSeconds: Int = 36,
    val rampFromTurn: Int = 50,
    val rampToTurn: Int = 100,
) {
    fun secondsFor(turn: Int): Int = when {
        turn <= rampFromTurn -> earlySeconds
        turn >= rampToTurn -> lateSeconds
        else -> earlySeconds + (lateSeconds - earlySeconds) * (turn - rampFromTurn) / (rampToTurn - rampFromTurn)
    }
}

@Serializable
data class SeatInfo(val civ: String, val team: String?, val controller: String, val presence: String, val committed: Boolean = false)

@Serializable
data class GameSummary(
    val gameId: String,
    val turn: Int,
    val seats: List<SeatInfo>,
    /** Epoch millis when the current round seals even if PRESENT seats have not committed. Null while paused. */
    val roundDeadline: Long?,
    val winningTeam: String? = null,
)

/** One player's intent. Checked against that player's knowledge on submit, applied when the round resolves. */
@Serializable
data class Intent(
    /** research | build | note */
    val type: String,
    val tech: String? = null,
    val city: String? = null,
    val item: String? = null,
    val target: String? = null,
    val text: String? = null,
)

@Serializable
data class IntentBatch(val civ: String, val intents: List<Intent>, val reasoning: String? = null)

@Serializable
data class IntentResult(val intent: Intent, val accepted: Boolean, val reason: String)

@Serializable
data class IntentBatchResult(val turn: Int, val results: List<IntentResult>)

/**
 * Ends this seat's planning for the round.
 * [delegateRest]: let Unciv's AI handle whatever the plan does not cover (units, empty queues), without
 * diplomacy or spending. Without it, anything not ordered simply waits, as in single player.
 */
@Serializable
data class CommitRequest(val civ: String, val delegateRest: Boolean = true)

@Serializable
data class CommitResult(
    val turn: Int,
    /** True when this commit sealed the round; [round] then holds its result. */
    val sealed: Boolean,
    val waitingFor: List<String>,
    val round: RoundResult? = null,
)

@Serializable
data class TurnTimings(
    val turn: Int,
    /** Applying plans and AI stewardship for external seats. */
    val seatMs: Long,
    /** Unciv AI civs, city-states and barbarians. */
    val aiMs: Long,
    /** endTurn + startTurn bookkeeping for every civ. */
    val turnMs: Long,
    /** Serialize + gzip + atomic write of the save. */
    val saveMs: Long,
    val saveBytes: Int,
)

@Serializable
data class RoundResult(
    val turn: Int,
    val timings: TurnTimings,
    /** How each external seat was played this round: PLAN, PLAN+AI, AI. */
    val seatModes: Map<String, String>,
    val intentResults: Map<String, List<IntentResult>>,
    val winningTeam: String? = null,
)

// ---- Masked view model (what one civ is allowed to know) ----

@Serializable
data class TileDto(
    val x: Int, val y: Int,
    val terrain: String,
    val features: List<String>,
    val visible: Boolean,
    val owner: String? = null,
    val city: String? = null,
    val unit: String? = null,
    val unitOwner: String? = null,
    val resource: String? = null,
    val improvement: String? = null,
)

@Serializable
data class CityDto(
    val name: String, val x: Int, val y: Int, val population: Int,
    val producing: String, val turnsLeft: Int? = null, val isCapital: Boolean,
)

@Serializable
data class KnownCivDto(val civ: String, val status: String, val isCityState: Boolean, val score: Int, val team: String? = null)

@Serializable
data class OwnCivDto(
    val civ: String, val gold: Int, val goldPerTurn: Int, val sciencePerTurn: Int,
    val culturePerTurn: Int, val happiness: Int, val researching: String?, val researchTurns: String?,
    val techsKnown: Int, val units: Map<String, Int>, val score: Int, val team: String? = null,
)

@Serializable
data class CivViewDto(
    val turn: Int,
    val me: OwnCivDto,
    val cities: List<CityDto>,
    val knownCivs: List<KnownCivDto>,
    val researchable: List<String>,
    val buildOptions: Map<String, List<String>>,
    val notifications: List<String>,
    val tiles: List<TileDto>,
)

// ---- Replay (spectator snapshots for the demo viewer, admin only) ----

@Serializable
data class CivColor(val civ: String, val outer: String, val inner: String, val isCityState: Boolean)

@Serializable
data class ReplayFrame(
    val turn: Int,
    /** Owner index per tile (into ReplayData.civs), -1 = unowned. */
    val owners: List<Int>,
    val cities: List<CityMarker>,
    val units: List<UnitMarker>,
    val scores: Map<String, Int>,
    val timings: TurnTimings? = null,
    val agentDecisions: List<AgentDecision> = emptyList(),
    val events: List<String> = emptyList(),
    /** True when an external seat was played by Unciv AI this round. */
    val offline: Boolean = false,
)

@Serializable
data class CityMarker(val x: Int, val y: Int, val name: String, val civ: Int, val pop: Int)

@Serializable
data class UnitMarker(val x: Int, val y: Int, val civ: Int, val military: Boolean)

@Serializable
data class AgentDecision(val civ: String, val reasoning: String?, val results: List<IntentResult>)

@Serializable
data class ReplayData(
    val gameId: String,
    val mapSize: String,
    val tiles: List<ReplayTile>,
    val civs: List<CivColor>,
    val externalSeats: List<String>,
    val frames: List<ReplayFrame>,
)

@Serializable
data class ReplayTile(val x: Int, val y: Int, val terrain: String, val features: List<String>)

// ---- Persistence (server-side only) ----

/** Everything needed to rebuild a [GameHost] after a restart, next to save.txt. */
@Serializable
data class GameMeta(val gameId: String, val request: CreateGameRequest, val seatTokens: Map<String, String>)

/** One line of plans.jsonl: a submitted batch or a commit, tagged with the round it belongs to. */
@Serializable
data class PlanLogEntry(val turn: Int, val batch: IntentBatch? = null, val commit: CommitRequest? = null)
