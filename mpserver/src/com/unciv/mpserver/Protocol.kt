package com.unciv.mpserver

import kotlinx.serialization.Serializable

/*
 * Wire protocol. New code only, kotlinx.serialization (codegen, no reflection), so the same
 * types can later compile to iOS/JS clients. The save file format is untouched.
 */

@Serializable
data class CreateGameRequest(
    val mapSize: String = "Small",
    val majorCivs: Int = 4,
    val cityStates: Int = 2,
    /** Seats controlled from outside (humans or LLM agents). Everything else is Unciv AI. */
    val externalSeats: List<String> = listOf("Rome"),
    val nations: List<String> = listOf("Rome", "Greece", "Egypt", "China"),
    val seed: Long = 42,
    val difficulty: String = "Prince",
)

@Serializable
data class SeatInfo(val civ: String, val controller: String, val presence: String)

@Serializable
data class GameSummary(val gameId: String, val turn: Int, val seats: List<SeatInfo>, val winner: String? = null)

/** One player's intent. Validated against that player's view before it touches the game. */
@Serializable
data class Intent(
    /** research | build | declareWar | makePeace | note */
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

@Serializable
data class EndTurnRequest(val civ: String)

@Serializable
data class TurnTimings(
    val turn: Int,
    /** Seat automation for external seats (Unciv AI moving the agent's units). */
    val seatAutomationMs: Long,
    /** nextTurn(): every AI civ plans and moves, city states, barbarians. */
    val aiMs: Long,
    /** Building masked views for present seats. */
    val maskMs: Long,
    /** Serialize + gzip the save. */
    val saveMs: Long,
    /** Deserialize the save (measured every N turns). */
    val loadMs: Long?,
    val saveBytes: Int,
)

@Serializable
data class EndTurnResult(val turn: Int, val timings: TurnTimings, val events: List<String>, val winner: String? = null)

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
data class KnownCivDto(val civ: String, val status: String, val isCityState: Boolean, val score: Int)

@Serializable
data class OwnCivDto(
    val civ: String, val gold: Int, val goldPerTurn: Int, val sciencePerTurn: Int,
    val culturePerTurn: Int, val happiness: Int, val researching: String?, val researchTurns: String?,
    val techsKnown: Int, val units: Map<String, Int>, val score: Int,
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

// ---- Replay (spectator snapshots for the demo viewer) ----

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
    /** True when an external seat was OFFLINE and Unciv AI played it this turn. */
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
