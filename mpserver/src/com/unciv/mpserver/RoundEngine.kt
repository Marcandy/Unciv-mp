package com.unciv.mpserver

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TurnManager
import kotlin.random.Random

/**
 * One Plan & Commit round on top of Unciv's per-civ TurnManager, replacing the sequential
 * GameInfo.nextTurn() loop without changing core.
 *
 * Planning: every civ has already had startTurn() for this round; humans plan against that state.
 * Resolution: civs act one at a time in a seeded order (the engine's "exactly one civ is active"
 * invariant holds, and currentPlayerCiv points at the acting civ, as in nextTurn()), each followed by
 * its endTurn(). Then the turn counter advances and every civ gets startTurn() for the next round.
 *
 * Differences from nextTurn(), on purpose: civ order is shuffled per round (no permanent first-mover
 * advantage), and startTurn() for everyone happens at the round boundary instead of just before each civ acts.
 */
object RoundEngine {

    /**
     * Teams interleaved, each shuffled with the round seed (A1, B1, A2, B2, ...), then city-states, then barbarians.
     * Which team leads also comes from the seed, so no team moves first every round.
     */
    fun resolutionOrder(game: GameInfo, teams: Map<String, List<String>>, seed: Long): List<Civilization> {
        val rng = Random(seed)
        val byTeam = teams.values.shuffled(rng).map { names -> game.civilizations.filter { it.civName in names }.shuffled(rng) }
        val interleaved = (0 until (byTeam.maxOfOrNull { it.size } ?: 0)).flatMap { i -> byTeam.mapNotNull { it.getOrNull(i) } }
        val rest = game.civilizations.filter { it !in interleaved && !it.isSpectator() }
        return interleaved + rest.filter { !it.isBarbarian }.shuffled(rng) + rest.filter { it.isBarbarian }
    }

    /** [act] plays one civ's turn (plan, AI, or both); it is not called for defeated civs. */
    fun resolve(game: GameInfo, order: List<Civilization>, act: (Civilization) -> Unit) {
        for (civ in order) {
            game.currentPlayerCiv = civ
            game.currentPlayer = civ.civID
            if (civ.isAlive()) act(civ)
            TurnManager(civ).endTurn()
        }

        recordRankingStats(game)
        game.turns++

        for (civ in order) TurnManager(civ).startTurn()
        game.currentTurnStartTime = System.currentTimeMillis()
    }

    /** Mirrors the private GameInfo.recordRankingStats(), run when nextTurn() wraps to a new turn. */
    private fun recordRankingStats(game: GameInfo) {
        for (civ in game.civilizations) {
            if (!civ.isMajorCiv() || !civ.isAlive()) continue
            civ.resetMilitaryMightCache()
            civ.statsHistory.recordRankingStats(civ)
        }
    }
}
