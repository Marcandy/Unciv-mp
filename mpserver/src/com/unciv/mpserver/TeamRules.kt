package com.unciv.mpserver

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType

/**
 * Fixed teams (e.g. Axis vs Allies) built mostly from what Unciv already has:
 *
 * - An in-memory extension mod carries the `Diplomatic relationships cannot change` mod option.
 *   Unciv's AI then never declares war or offers peace, trade logic drops war/peace offers and the
 *   diplomacy screen hides those buttons. Because it is listed in GameParameters.mods, every
 *   reload of the save rebuilds the same ruleset.
 * - At game start every major civ meets every other; teammates get a permanent defensive pact and open borders.
 *
 * Clients must ship the same mod (one ModOptions.json) so their UI agrees; the server enforces it anyway.
 */
object TeamRules {
    const val MOD_NAME = "MP Fixed Teams"

    /** Call once after RulesetCache.loadRulesets(). */
    fun registerMod() {
        RulesetCache[MOD_NAME] = Ruleset().apply {
            name = MOD_NAME
            modOptions.uniques.add(UniqueType.DiplomaticRelationshipsCannotChange.text)
        }
    }

    fun teamOf(teams: Map<String, List<String>>, civName: String): String? =
        teams.entries.firstOrNull { civName in it.value }?.key

    /**
     * Meet everyone; peace inside a team. Run once, right after GameStarter.
     * Teams start at war only when [atWar]; otherwise [startWar] is called when the peace period ends.
     */
    fun applyAtStart(game: GameInfo, teams: Map<String, List<String>>, atWar: Boolean) {
        val majors = game.civilizations.filter { it.isMajorCiv() }
        for ((i, a) in majors.withIndex()) for (b in majors.drop(i + 1)) {
            if (!a.knows(b)) a.diplomacyFunctions.makeCivilizationsMeet(b)
            if (teamOf(teams, a.civName) == teamOf(teams, b.civName)) makeTeammates(a, b)
            else setStatus(a, b, if (atWar) DiplomaticStatus.War else DiplomaticStatus.Peace)
        }
        // First-contact popups are noise in a team game where everyone is introduced at once
        majors.forEach { it.popupAlerts.clear() }
    }

    /** End of the peace period: every pair of majors on different teams goes to war. */
    fun startWar(game: GameInfo, teams: Map<String, List<String>>) {
        val majors = game.civilizations.filter { it.isMajorCiv() }
        for ((i, a) in majors.withIndex()) for (b in majors.drop(i + 1))
            if (teamOf(teams, a.civName) != teamOf(teams, b.civName)) setStatus(a, b, DiplomaticStatus.War)
        majors.forEach { it.addNotification("The peace is over: your team is now at war", NotificationCategory.War, NotificationIcon.War) }
    }

    /**
     * Teammates: a defensive pact with no expiry flag (so it never lapses), which the AI reads as "ally"
     * (AlliedMilitaryAutomation: shared sieges, defending each other's cities), plus open borders both ways
     * so defenders can actually enter. Open borders live in trades, which the engine re-reads on load.
     */
    private fun makeTeammates(a: Civilization, b: Civilization) {
        setStatus(a, b, DiplomaticStatus.DefensivePact)
        for ((civ, other) in listOf(a to b, b to a)) {
            val dm = civ.getDiplomacyManager(other)!!
            dm.trades.add(Trade().apply {
                ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, duration = PERMANENT))
                theirOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, duration = PERMANENT))
            })
            dm.updateHasOpenBorders()
        }
    }

    /** Trade offer durations count down each turn; this outlasts any game. */
    private const val PERMANENT = 1_000_000

    private fun setStatus(a: Civilization, b: Civilization, status: DiplomaticStatus) {
        a.getDiplomacyManager(b)!!.diplomaticStatus = status
        b.getDiplomacyManager(a)!!.diplomaticStatus = status
    }

    /**
     * A team wins when every major civ of every other team is gone, or when it holds every enemy
     * original capital. Unciv's own victories (science, culture, diplomacy, time) count for the winner's team.
     */
    fun winningTeam(game: GameInfo, teams: Map<String, List<String>>): String? {
        for (team in teams.keys) {
            val enemies = majorsOf(game, teams) { it != team }
            if (enemies.isEmpty()) continue
            if (enemies.none { it.isAlive() }) return team
            val enemyCapitals = game.getCities().filter { it.isOriginalCapital && it.foundingCivObject in enemies }.toList()
            if (enemyCapitals.isNotEmpty() && enemyCapitals.all { teamOf(teams, it.civ.civName) == team }) return team
        }
        val engineWinner = game.civilizations.firstOrNull { it.victoryManager.hasWon() } ?: return null
        return teamOf(teams, engineWinner.civName)
    }

    private fun majorsOf(game: GameInfo, teams: Map<String, List<String>>, teamFilter: (String) -> Boolean): List<Civilization> =
        game.civilizations.filter { it.isMajorCiv() && teamOf(teams, it.civName)?.let(teamFilter) == true }
}
