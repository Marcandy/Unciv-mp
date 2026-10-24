package com.unciv.mpserver

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.IConstruction

/**
 * Every intent is an authorisation question first (is it yours, is it allowed for you to know),
 * then a legality question (does the engine allow it). Humans, LLM agents and modified clients
 * all go through the same gate, so an agent's output is treated as untrusted input.
 */
object IntentValidator {

    fun apply(civ: Civilization, intent: Intent): IntentResult {
        fun ok(msg: String) = IntentResult(intent, true, msg)
        fun no(msg: String) = IntentResult(intent, false, msg)
        val ruleset = civ.gameInfo.ruleset

        return when (intent.type) {
            "research" -> {
                val name = intent.tech ?: return no("missing tech")
                val tech = ruleset.technologies[name] ?: return no("no such tech '$name'")
                if (civ.tech.isResearched(name)) return no("$name already researched")
                val path = civ.tech.getRequiredTechsToDestination(tech).map { it.name }
                if (path.isEmpty()) return no("$name cannot be researched")
                civ.tech.techsToResearch.clear()
                civ.tech.techsToResearch.addAll(path)
                ok(if (path.size == 1) "researching $name" else "queued ${path.size} techs toward $name")
            }

            "build" -> {
                val cityName = intent.city ?: return no("missing city")
                val item = intent.item ?: return no("missing item")
                // Authorisation: only your own cities. A foreign city name is indistinguishable from a missing one.
                val city = civ.cities.firstOrNull { it.name == cityName } ?: return no("you have no city '$cityName'")
                val construction: IConstruction = ruleset.buildings[item] ?: ruleset.units[item]
                    ?: return no("no such building or unit '$item'")
                val cc = city.cityConstructions
                if (!cc.canAddToQueue(construction)) return no("$cityName cannot build $item now")
                cc.addToQueue(construction, addToTop = true)
                ok("$cityName now building $item")
            }

            "declareWar" -> {
                val targetName = intent.target ?: return no("missing target")
                val target = civ.gameInfo.civilizations.firstOrNull { it.civName == targetName }
                // Not met == not known to exist from this civ's view.
                if (target == null || !civ.knows(target)) return no("you have not met '$targetName'")
                if (civ.isAtWarWith(target)) return no("already at war with $targetName")
                val dm = civ.getDiplomacyManager(target) ?: return no("no relations with $targetName")
                if (!dm.canDeclareWar()) return no("cannot declare war on $targetName now (treaty or truce)")
                dm.declareWar()
                ok("war declared on $targetName")
            }

            "makePeace" -> no("peace needs the other side's consent; negotiation ships in Phase 5")

            "note" -> ok("noted")

            else -> no("unknown intent type '${intent.type}'")
        }
    }
}
