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
    fun isCityLocallyThreatened(city: City): Boolean {
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
        if (threat == 0) return false
        // Aggressive 5 (neutral) -> act when threat > 1.5x defense; 9 -> 2.3x; 2 -> 0.9x
        val tolerance = 0.5f + civ.getPersonality()[PersonalityValue.Aggressive] / 5f
        return threat > defense * tolerance
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
}
