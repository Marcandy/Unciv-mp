package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.nation.PersonalityValue
import yairm210.purity.annotations.Readonly

/**
 * Military judgement based on what is actually nearby, counting defensive pact partners as allies.
 *
 * Comparing a civ's whole army with the sum of every enemy's army breaks down as soon as a civ fights
 * several wars at once: the comparison is always lost, so every city trains units forever and the civ
 * stops growing. Local comparisons scale to any number of wars, and allies fighting the same enemies
 * can defend and besiege together.
 */
object AlliedMilitaryAutomation {
    /** Radius within which units count toward a city's local threat and defense. */
    const val LOCAL_RADIUS = 6

    /** Defensive pact partners: they are bound to fight our enemies, so their armies count with ours. */
    @Readonly
    fun isAlly(civ: Civilization, other: Civilization): Boolean =
        other != civ && civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    @Readonly
    private fun power(unit: MapUnit): Int = maxOf(unit.baseUnit.strength, unit.baseUnit.rangedStrength) * unit.health / 100

    /**
     * True when visible enemy units near [city] outweigh our and our allies' units there.
     * Cautious personalities (low Aggressive) react to smaller threats.
     */
    @Readonly
    fun isCityLocallyThreatened(city: City): Boolean = reinforcementNeeded(city) > 0

    /**
     * How much unit power [city] is missing to hold against the visible enemy units within [LOCAL_RADIUS]; 0 when safe.
     * Aggressive 5 (neutral) -> act when threat > 1.5x defense; 9 -> 2.3x; 2 -> 0.9x
     */
    @Readonly
    fun reinforcementNeeded(city: City): Int {
        val civ = city.civ
        var threat = 0
        var defense = 0
        for (tile in city.getCenterTile().getTilesInDistance(LOCAL_RADIUS)) {
            for (unit in tile.getUnits()) {
                if (!unit.isMilitary()) continue
                when {
                    unit.civ == civ || isAlly(civ, unit.civ) -> defense += power(unit)
                    civ.isAtWarWith(unit.civ) && tile.isVisible(civ) -> threat += power(unit)
                }
            }
        }
        if (threat == 0) return 0
        val tolerance = 0.5f + civ.getPersonality()[PersonalityValue.Aggressive] / 5f
        return if (threat > defense * tolerance) threat - defense else 0
    }

    /** Our units and our allies' units within [radius] of [tile] — they besiege a city together. */
    @Readonly
    fun friendlyMilitaryAround(tile: Tile, civ: Civilization, radius: Int): Sequence<MapUnit> =
        tile.getTilesInDistance(radius).flatMap { it.getUnits() }
            .filter { it.isMilitary() && (it.civ == civ || isAlly(civ, it.civ)) }

    /** How many turns of siege this civ will commit to: aggressive leaders accept longer sieges. */
    @Readonly
    fun maxSiegeTurns(civ: Civilization): Float = 3f + civ.getPersonality()[PersonalityValue.Aggressive] / 2f

    /** How far a unit will travel to help a damaged allied city: loyal leaders go further. */
    @Readonly
    fun allyHelpRadius(civ: Civilization): Int = 4 + civ.getPersonality()[PersonalityValue.Loyal].toInt()

    /** Damaged cities of our allies, closest first. */
    @Readonly
    fun alliedCitiesUnderAttack(unit: MapUnit): List<City> {
        val radius = allyHelpRadius(unit.civ)
        return unit.civ.gameInfo.civilizations.asSequence()
            .filter { isAlly(unit.civ, it) }
            .flatMap { it.cities }
            .filter { it.health < it.getMaxHealth() && it.getCenterTile().aerialDistanceTo(unit.getTile()) <= radius }
            .sortedBy { it.getCenterTile().aerialDistanceTo(unit.getTile()) }
            .toList()
    }

    // Team reinforcement: civs far from the front send units to their team's threatened cities.
    // Without it, the rear half of a team never fights: its cities see no threat, so it trains no army,
    // and the "help a damaged ally city" radius never reaches the front on a Huge map.

    private var cachedTurn = -1
    private val threatenedCitiesByCiv = HashMap<String, List<City>>()
    /** Unit power already sent to each threatened city this turn, so the whole team doesn't pile onto one city. */
    private val powerSentTo = HashMap<City, Int>()

    private fun refreshCache(civ: Civilization) {
        if (civ.gameInfo.turns == cachedTurn) return
        cachedTurn = civ.gameInfo.turns
        threatenedCitiesByCiv.clear()
        powerSentTo.clear()
    }

    /** Our and our allies' cities that are outnumbered right now (computed once per civ per turn). */
    fun threatenedTeamCities(civ: Civilization): List<City> {
        refreshCache(civ)
        return threatenedCitiesByCiv.getOrPut(civ.civName) {
            civ.gameInfo.civilizations.filter { it == civ || isAlly(civ, it) }
                .flatMap { it.cities }.filter { isCityLocallyThreatened(it) }
        }
    }

    @Readonly
    private fun hasAllies(civ: Civilization): Boolean = civ.isMajorCiv() && civ.gameInfo.civilizations.any { isAlly(civ, it) }

    @Readonly
    private fun isEnemyNear(unit: MapUnit, radius: Int): Boolean = unit.getTile().getTilesInDistance(radius).any { tile ->
        tile.isVisible(unit.civ) && tile.getUnits().any { it.isMilitary() && unit.civ.isAtWarWith(it.civ) }
    }

    /**
     * Send an idle land unit to the nearest outnumbered city of our team, at any distance, before the siege starts.
     * Garrisons stay home, and units already fighting keep fighting. Returns true when the unit used its movement.
     */
    fun tryReinforceTeam(unit: MapUnit): Boolean {
        if (!unit.baseUnit.isLandUnit || !hasAllies(unit.civ)) return false
        val here = unit.getTile()
        if (here.isCityCenter() && here.getOwner() == unit.civ) return false // garrison
        if (isEnemyNear(unit, 3)) return false
        val targets = threatenedTeamCities(unit.civ)
            .filter { powerSentTo.getOrElse(it) { 0 } < reinforcementNeeded(it) }
            .sortedBy { it.getCenterTile().aerialDistanceTo(here) }
            .take(3)
        for (city in targets) {
            if (city.getCenterTile().aerialDistanceTo(here) <= 2) return false // already there
            val tile = city.getCenterTile().getTilesInDistance(2)
                .sortedBy { it.aerialDistanceTo(here) }
                .firstOrNull { unit.movement.canMoveTo(it) && unit.movement.canReach(it) && unit.getDamageFromTerrain(it) <= 0 }
                ?: continue
            powerSentTo[city] = powerSentTo.getOrElse(city) { 0 } + power(unit)
            unit.movement.headTowards(tile)
            return !unit.hasMovement()
        }
        return false
    }

    /**
     * A unit next to an allied city with enemies around holds there instead of walking back to garrison its own cities.
     * Called after attack and siege options, so it only applies to units with nothing better to do.
     */
    fun tryHoldNearAlliedCity(unit: MapUnit): Boolean {
        if (!unit.baseUnit.isLandUnit || !hasAllies(unit.civ)) return false
        val here = unit.getTile()
        val nearAllyCity = here.getTilesInDistance(2).any { it.isCityCenter() && it.getOwner()?.let { o -> isAlly(unit.civ, o) } == true }
        if (!nearAllyCity || !isEnemyNear(unit, LOCAL_RADIUS)) return false
        unit.fortifyIfCan()
        return true
    }

    /**
     * Rear cities train units for the team: while a teammate's city is outnumbered and our own cities are safe,
     * keep an army of (1 + Military/10) units per city. Without this the safe half of a team builds almost nothing.
     */
    fun shouldTrainForTeam(city: City): Boolean {
        val civ = city.civ
        if (!hasAllies(civ)) return false
        val threatened = threatenedTeamCities(civ)
        if (threatened.isEmpty() || threatened.any { it.civ == civ }) return false
        val wanted = civ.cities.size * (1f + civ.getPersonality()[PersonalityValue.Military] / 10f)
        return civ.units.getCivUnits().count { it.isMilitary() } < wanted
    }
}
