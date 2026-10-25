package com.unciv.mpserver

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.IConstruction

/**
 * Every intent is an authorisation question first (is it yours, is it allowed for you to know),
 * then a legality question (does the engine allow it). Humans, bots and modified clients all go
 * through the same gate.
 *
 * Plan & Commit uses it twice: [check] on submit (read-only, immediate feedback against the planning
 * snapshot) and [apply] at resolution, which re-checks because earlier civs in the order may have
 * changed the world.
 */
object IntentValidator {

    /** Null when the intent is acceptable right now, otherwise the reason it is not. Never mutates. */
    fun check(civ: Civilization, intent: Intent): String? {
        val ruleset = civ.gameInfo.ruleset
        return when (intent.type) {
            "research" -> {
                val name = intent.tech ?: return "missing tech"
                val tech = ruleset.technologies[name] ?: return "no such tech '$name'"
                if (civ.tech.isResearched(name)) return "$name already researched"
                if (civ.tech.getRequiredTechsToDestination(tech).isEmpty()) return "$name cannot be researched"
                null
            }

            "build" -> {
                val cityName = intent.city ?: return "missing city"
                val item = intent.item ?: return "missing item"
                // Authorisation: only your own cities. A foreign city name is indistinguishable from a missing one.
                val city = civ.cities.firstOrNull { it.name == cityName } ?: return "you have no city '$cityName'"
                val construction = construction(civ, item) ?: return "no such building or unit '$item'"
                if (!city.cityConstructions.canAddToQueue(construction)) return "$cityName cannot build $item now"
                null
            }

            "declareWar", "makePeace" -> "diplomacy is fixed in team games"

            "note" -> if ((intent.text?.length ?: 0) > MAX_NOTE) "note longer than $MAX_NOTE characters" else null

            else -> "unknown intent type '${intent.type}'"
        }
    }

    fun apply(civ: Civilization, intent: Intent): IntentResult {
        check(civ, intent)?.let { return IntentResult(intent, false, it) }
        val message = when (intent.type) {
            "research" -> {
                val path = civ.tech.getRequiredTechsToDestination(civ.gameInfo.ruleset.technologies[intent.tech]!!).map { it.name }
                civ.tech.techsToResearch.clear()
                civ.tech.techsToResearch.addAll(path)
                if (path.size == 1) "researching ${intent.tech}" else "queued ${path.size} techs toward ${intent.tech}"
            }
            "build" -> {
                val city = civ.cities.first { it.name == intent.city }
                city.cityConstructions.addToQueue(construction(civ, intent.item!!)!!, addToTop = true)
                "${intent.city} now building ${intent.item}"
            }
            else -> "noted"
        }
        return IntentResult(intent, true, message)
    }

    private fun construction(civ: Civilization, item: String): IConstruction? =
        civ.gameInfo.ruleset.buildings[item] ?: civ.gameInfo.ruleset.units[item]

    const val MAX_NOTE = 2000
}
