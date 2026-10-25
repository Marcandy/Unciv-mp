package com.unciv.mpserver

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.random.Random

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

    /**
     * Each team starts on its own half of the map, so there is one front and a safe rear.
     *
     * Unciv's GameStarter knows nothing about teams: it spreads all starts evenly and assigns them at random,
     * which interleaves the teams (median capital ~9 tiles from the nearest enemy city on Huge). This keeps
     * GameStarter's start tiles, splits them at the median along the map's wider axis, and gives each team one
     * half (which team gets which half comes from [seed]). Run before turn 0 is played: only starting units exist.
     * Start biases (coast, tundra...) are not re-applied to the swapped tiles.
     */
    fun groupStarts(game: GameInfo, teams: Map<String, List<String>>, seed: Long) {
        val majors = game.civilizations.filter { it.isMajorCiv() && teamOf(teams, it.civName) != null }
        val startOf = majors.associateWith { startTile(it) }
        val world = startOf.values.associateWith { HexMath.hex2WorldCoords(it.position) }
        val xSpread = world.values.maxOf { it.x } - world.values.minOf { it.x }
        val ySpread = world.values.maxOf { it.y } - world.values.minOf { it.y }
        val sorted = startOf.values.sortedBy { if (xSpread >= ySpread) world[it]!!.x else world[it]!!.y }

        val rng = Random(seed)
        val teamOrder = teams.keys.shuffled(rng)
        val newStart = HashMap<Civilization, Tile>()
        var next = 0
        for (team in teamOrder) {
            val members = majors.filter { teamOf(teams, it.civName) == team }.shuffled(rng)
            for (civ in members) newStart[civ] = sorted[next++]
        }

        // Lift every major's starting units first, so nobody is blocked by a unit that is about to move away
        val unitNames = majors.associateWith { civ -> civ.units.getCivUnits().map { it.name }.toList() }
        for (civ in majors) civ.units.getCivUnits().toList().forEach { it.destroy() }
        for (civ in majors) {
            for (name in unitNames[civ]!!) civ.units.placeUnitNearTile(newStart[civ]!!.position, name)
            // Forget what was seen from the old start
            for (tile in game.tileMap.tileList) if (tile.isExplored(civ)) tile.setExplored(civ, false)
            civ.cache.updateViewableTiles()
        }
    }

    /**
     * City-states as shared objectives: founded on the line between the two teams' halves, spread evenly along it,
     * with radius-2 borders full of luxury and strategic resources, and at war with every major civ forever.
     * Whoever takes and holds one gets those resources. Run after [groupStarts], before turn 0 is played.
     */
    fun placeContestedCityStates(game: GameInfo, teams: Map<String, List<String>>, seed: Long) {
        val cityStates = game.civilizations.filter { it.isCityState && it.units.getCivUnits().any() }
        if (cityStates.isEmpty() || teams.size != 2) return
        val rng = Random(seed + 7)
        val majors = game.civilizations.filter { it.isMajorCiv() && teamOf(teams, it.civName) != null }
        val starts = majors.associateWith { HexMath.hex2WorldCoords(startTile(it).position) }

        // The front: halfway between the two teams along the axis they were split on
        val xSpread = starts.values.maxOf { it.x } - starts.values.minOf { it.x }
        val ySpread = starts.values.maxOf { it.y } - starts.values.minOf { it.y }
        val alongSplit = { v: com.badlogic.gdx.math.Vector2 -> if (xSpread >= ySpread) v.x else v.y }
        val across = { v: com.badlogic.gdx.math.Vector2 -> if (xSpread >= ySpread) v.y else v.x }
        val (teamA, teamB) = teams.keys.toList()
        val aSide = starts.filterKeys { teamOf(teams, it.civName) == teamA }.values.map(alongSplit)
        val bSide = starts.filterKeys { teamOf(teams, it.civName) == teamB }.values.map(alongSplit)
        val front = if (aSide.average() < bSide.average()) (aSide.max() + bSide.min()) / 2 else (bSide.max() + aSide.min()) / 2

        val land = game.tileMap.tileList.filter { it.isLand && !it.isImpassible() && it.getUnits().none() && it.naturalWonder == null }
        val world = land.associateWith { HexMath.hex2WorldCoords(it.position) }
        val acrossMin = world.values.minOf(across)
        val acrossMax = world.values.maxOf(across)
        val startTiles = majors.map { startTile(it) }
        val chosen = ArrayList<Tile>()
        for ((i, cs) in cityStates.withIndex()) {
            val target = acrossMin + (acrossMax - acrossMin) * (i + 1) / (cityStates.size + 1)
            val site = land
                .filter { t -> startTiles.none { it.aerialDistanceTo(t) < 6 } && chosen.none { it.aerialDistanceTo(t) < 8 } }
                .minByOrNull { t -> val w = world[t]!!; Math.abs(alongSplit(w) - front) + Math.abs(across(w) - target) }
                ?: continue
            chosen += site
            val keep = cs.units.getCivUnits().filter { !it.hasUnique(UniqueType.FoundCity) }.map { it.name }.toList()
            cs.units.getCivUnits().toList().forEach { it.destroy() }
            val city = cs.addCity(site.position)
            for (tile in site.getTilesInDistance(2)) if (tile.getOwner() == null) city.expansion.takeOwnership(tile)
            for (name in keep) cs.units.placeUnitNearTile(site.position, name)
            addRichResources(game, site, rng)
            for (major in majors) {
                if (!cs.knows(major)) cs.diplomacyFunctions.makeCivilizationsMeet(major, warOnContact = true)
                setStatus(cs, major, DiplomaticStatus.War)
            }
            cs.cache.updateViewableTiles()
        }
        majors.forEach { it.popupAlerts.clear() }
    }

    /** Up to 6 luxury/strategic resources on suitable tiles within 2 of the city, strategic ones as major deposits. */
    private fun addRichResources(game: GameInfo, center: Tile, rng: Random) {
        val pool = game.ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury || it.resourceType == ResourceType.Strategic }
        var placed = 0
        for (tile in center.getTilesInDistance(2).filter { it != center && it.resource == null && it.isLand }.shuffled(rng)) {
            if (placed >= 6) break
            val options = pool.filter { it.generatesNaturallyOn(tile) }
            if (options.isEmpty()) continue
            tile.setTileResource(options.random(rng), majorDeposit = true)
            placed++
        }
    }

    private fun startTile(civ: Civilization): Tile {
        civ.cities.firstOrNull()?.let { return it.getCenterTile() }
        val units = civ.units.getCivUnits().toList()
        return (units.firstOrNull { it.hasUnique(UniqueType.FoundCity) } ?: units.first()).getTile()
    }

    /**
     * Random leaders: each team keeps its external (human) seats and fills its other slots with random major
     * nations from the ruleset, drawn with [seed]. Team names and sizes come from [teams].
     */
    fun randomizeTeams(teams: Map<String, List<String>>, keep: Collection<String>, majorNations: List<String>, seed: Long): Map<String, List<String>> {
        val pool = majorNations.filter { it !in keep }.shuffled(Random(seed + 13)).toMutableList()
        return teams.mapValuesTo(LinkedHashMap()) { (_, nations) ->
            val seats = nations.filter { it in keep }
            seats + List(nations.size - seats.size) { pool.removeAt(0) }
        }
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
