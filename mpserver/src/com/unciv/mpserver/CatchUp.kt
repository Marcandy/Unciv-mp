package com.unciv.mpserver

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import kotlin.math.roundToInt

/**
 * Anti-snowball rules for team wars: leading should usually win, but not always.
 *
 * In 15 all-AI games the team ahead at turn 150 won 13, and the trailing team rarely took a city back:
 * more cities meant more production meant more units, and nothing pushed back.
 * - **Overextension:** a team holding more than [OVEREXTENDED] of the majors' cities loses production in every
 *   city, 10% at the line, +1% per extra percent of cities, at most [MAX_PENALTY]%.
 * - **Bounty:** while one team holds more than [BOUNTY_FROM] of the cities, each city the other team takes from it
 *   pays the capturer gold, and gives the capturer's whole team a production boost for a few turns.
 *   The leader stays ahead; the trailing team gets a reason to attack instead of a free gift.
 *
 * Effects are temporary uniques, so they live in the save and reach clients like any other modifier.
 * Called by the server once per round, after resolution.
 */
object CatchUp {
    const val OVEREXTENDED = 0.60f
    const val BOUNTY_FROM = 0.55f
    const val MAX_PENALTY = 30
    const val BOUNTY_BOOST = 20
    const val BOUNTY_TURNS = 5

    /** City owners before a round; [apply] compares against it to find captures. */
    fun owners(game: GameInfo): Map<HexCoord, String> = game.getCities().associate { it.location to it.civ.civName }

    /** Applies overextension and pays bounties for captures since [before]. Returns one line per effect, for logs. */
    fun apply(game: GameInfo, teams: Map<String, List<String>>, before: Map<HexCoord, String>): List<String> {
        val log = ArrayList<String>()
        val shares = cityShares(game, teams)
        val (leader, share) = shares.maxByOrNull { it.value } ?: return log

        if (share > OVEREXTENDED) {
            val penalty = minOf(MAX_PENALTY, 10 + ((share - OVEREXTENDED) * 100).roundToInt())
            for (civ in teamCivs(game, teams, leader)) {
                grant(civ, "[-$penalty]% [Production] [in all cities] <for [1] turns>")
                log += "${civ.civName} overextended: -$penalty% production"
            }
        }

        if (share > BOUNTY_FROM) {
            for (city in game.getCities()) {
                val previous = before[city.location] ?: continue
                if (TeamRules.teamOf(teams, previous) != leader) continue
                val capturerTeam = TeamRules.teamOf(teams, city.civ.civName) ?: continue
                if (capturerTeam == leader) continue
                val gold = 100 + 2 * game.turns
                grant(city.civ, "Gain [$gold] [Gold]")
                city.civ.addNotification("Bounty: [$gold] Gold for taking [${city.name}] from the leading team",
                    city.location, NotificationCategory.War, NotificationIcon.Gold)
                for (civ in teamCivs(game, teams, capturerTeam))
                    grant(civ, "[+$BOUNTY_BOOST]% [Production] [in all cities] <for [$BOUNTY_TURNS] turns>")
                log += "${city.civ.civName} took ${city.name} from $previous: bounty $gold gold, team +$BOUNTY_BOOST% production"
            }
        }
        return log
    }

    /** Each team's share of all cities owned by major civs (city-states don't count). */
    fun cityShares(game: GameInfo, teams: Map<String, List<String>>): Map<String, Float> {
        val majorCities = game.getCities().filter { it.civ.isMajorCiv() }.toList()
        if (majorCities.isEmpty()) return emptyMap()
        return teams.keys.associateWith { team ->
            majorCities.count { TeamRules.teamOf(teams, it.civ.civName) == team }.toFloat() / majorCities.size
        }
    }

    private fun teamCivs(game: GameInfo, teams: Map<String, List<String>>, team: String): List<Civilization> =
        game.civilizations.filter { it.isAlive() && it.civName in teams[team].orEmpty() }

    private fun grant(civ: Civilization, text: String) {
        UniqueTriggerActivation.triggerUnique(Unique(text), civ)
        for (city in civ.cities) city.cityStats.update() // stats are cached at turn start, before we add the modifier
    }
}
