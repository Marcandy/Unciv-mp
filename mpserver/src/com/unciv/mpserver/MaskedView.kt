package com.unciv.mpserver

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.view.GameView

/**
 * Builds what one civ is allowed to know, on top of upstream's com.unciv.view layer
 * (GameView / TileView), which already encodes "legal access to known data".
 *
 * This replaces the v3 GameInfoMasker (clone + strip a whole GameInfo). Because the output is a
 * purpose-built view model, unexplored tiles are simply absent: no terrain leak (v3 A1), no
 * deep clone per recipient (v3 A7), and nothing a client could deserialize into hidden state.
 */
object MaskedView {

    fun build(game: GameInfo, civ: Civilization, teamOf: (String) -> String? = { null }): CivViewDto {
        val gv = GameView(game, civ)
        val ruleset = game.ruleset

        val tiles = game.tileMap.tileList.mapNotNull { tile ->
            val tv = gv.getTile(tile)
            if (!tv.isExplored()) return@mapNotNull null
            val visible = tv.isVisible()
            val unit = if (visible) tv.getVisibleUnits().firstOrNull() else null
            TileDto(
                x = tile.position.x, y = tile.position.y,
                terrain = tv.baseTerrain,
                features = tv.terrainFeatures.toList(),
                visible = visible,
                owner = tv.getOwner()?.civName,
                city = if (tv.isCityCenter()) tv.getCity()?.name else null,
                unit = unit?.name,
                unitOwner = unit?.civName,
                resource = tv.getViewableResource(gv.civView)?.name,
                improvement = tv.getShownImprovement(),
            )
        }

        val cities = civ.cities.map { city ->
            val cc = city.cityConstructions
            val current = cc.currentConstructionName()
            CityDto(
                city.name, city.location.x, city.location.y, city.population.population, current,
                runCatching { cc.turnsToConstruction(current) }.getOrNull()?.takeIf { it in 0..999 },
                city.isCapital(),
            )
        }

        val known = game.civilizations
            .filter { it != civ && !it.isBarbarian && !it.isSpectator() && civ.knows(it) }
            .map { other ->
                KnownCivDto(
                    other.civName,
                    civ.getDiplomacyManager(other)?.diplomaticStatus?.name ?: "Unknown",
                    other.isCityState,
                    other.getStatForRanking(RankingType.Score),
                    teamOf(other.civName),
                )
            }

        val researchable = ruleset.technologies.values
            .filter { civ.tech.canBeResearched(it.name) }
            .sortedBy { it.cost }
            .map { it.name }

        val buildOptions = civ.cities.associate { city ->
            val cc = city.cityConstructions
            val buildings = ruleset.buildings.values.filter { it.isBuildable(cc) }.sortedBy { it.cost }.map { it.name }
            val units = ruleset.units.values.filter { it.isBuildable(cc) }.sortedBy { it.cost }.map { it.name }
            city.name to (buildings.take(15) + units.take(10))
        }

        val stats = civ.stats.statsForNextTurn
        val me = OwnCivDto(
            civ = civ.civName,
            gold = civ.gold,
            goldPerTurn = stats.gold.toInt(),
            sciencePerTurn = stats.science.toInt(),
            culturePerTurn = stats.culture.toInt(),
            happiness = civ.getHappiness(),
            researching = civ.tech.currentTechnologyName(),
            researchTurns = civ.tech.currentTechnologyName()?.let { runCatching { civ.tech.turnsToTech(it) }.getOrNull() },
            techsKnown = civ.tech.techsResearched.size,
            units = civ.units.getCivUnits().groupingBy { it.name }.eachCount(),
            score = civ.getStatForRanking(RankingType.Score),
            team = teamOf(civ.civName),
        )

        return CivViewDto(
            turn = game.turns, me = me, cities = cities, knownCivs = known,
            researchable = researchable, buildOptions = buildOptions,
            notifications = civ.notifications.map { it.text }.take(20),
            tiles = tiles,
        )
    }

    /** Narrated text for LLM seats (lesson from civ6-mcp: models reason better over prose than raw state). */
    fun brief(v: CivViewDto, recentEvents: List<String>): String = buildString {
        val m = v.me
        appendLine("Turn ${v.turn}. You lead ${m.civ}${m.team?.let { " ($it)" } ?: ""}. Score ${m.score}.")
        appendLine("Economy: ${m.gold} gold (${signed(m.goldPerTurn)}/turn), science +${m.sciencePerTurn}, culture +${m.culturePerTurn}, happiness ${m.happiness}.")
        appendLine("Research: ${m.researching ?: "nothing"}${m.researchTurns?.let { " ($it turns)" } ?: ""}. Techs known: ${m.techsKnown}.")
        appendLine("Army and workers: " + m.units.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "none" } + ".")
        appendLine("Cities:")
        v.cities.forEach { appendLine("- ${it.name}${if (it.isCapital) " (capital)" else ""}: pop ${it.population}, building ${it.producing}${it.turnsLeft?.let { t -> " ($t turns)" } ?: ""}") }
        if (v.knownCivs.isEmpty()) appendLine("You have not met anyone yet.")
        else {
            appendLine("Known civilizations:")
            v.knownCivs.forEach { appendLine("- ${it.civ}${if (it.isCityState) " (city-state)" else ""}${it.team?.let { t -> " [$t]" } ?: ""}: ${it.status}, score ${it.score}") }
        }
        val explored = v.tiles.size
        val visibleEnemies = v.tiles.filter { it.visible && it.unitOwner != null && it.unitOwner != m.civ }
        appendLine("Map: $explored tiles explored. Foreign units in sight: ${visibleEnemies.size}.")
        visibleEnemies.take(5).forEach { appendLine("- ${it.unitOwner} ${it.unit} at (${it.x},${it.y})") }
        appendLine("Researchable now: " + v.researchable.take(8).joinToString())
        v.buildOptions.forEach { (city, opts) -> appendLine("$city can build: " + opts.take(10).joinToString()) }
        if (recentEvents.isNotEmpty()) {
            appendLine("Since your last turn:")
            recentEvents.take(10).forEach { appendLine("- $it") }
        }
        appendLine("Intents you can submit: research {tech}, build {city,item}, note {text}. Diplomacy is fixed by team.")
    }

    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
}
