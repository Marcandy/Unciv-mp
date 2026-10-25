package com.unciv.mpserver

import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.unique.UniqueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Round rules on a small 2v2 so the suite stays fast; the 8v8 cost is measured by `bench`. */
class RoundTest {
    companion object {
        @BeforeClass @JvmStatic fun boot() = bootEngine()

        val teams = linkedMapOf("Allies" to listOf("America", "England"), "Axis" to listOf("Germany", "Japan"))
    }

    private val dataDir: File = Files.createTempDirectory("mpserver-test").toFile()

    private fun newHost(seats: List<String> = listOf("America", "Germany"), warFromTurn: Int = 0) = GameHost.create(
        "t", CreateGameRequest(mapSize = "Tiny", cityStates = 2, teams = teams, externalSeats = seats, seed = 3, warFromTurn = warFromTurn), dataDir
    )

    @Test fun teamsArePermanentAlliesAndEnemies() {
        val host = newHost()
        val america = host.civ("America")!!
        assertEquals(DiplomaticStatus.DefensivePact, america.getDiplomacyManager(host.civ("England")!!)!!.diplomaticStatus)
        assertEquals(DiplomaticStatus.War, america.getDiplomacyManager(host.civ("Germany")!!)!!.diplomaticStatus)
        assertEquals(DiplomaticStatus.War, america.getDiplomacyManager(host.civ("Japan")!!)!!.diplomaticStatus)
        assertTrue(host.game.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange))
    }

    @Test fun peacePeriodEndsInWarAtTheConfiguredTurn() {
        val host = newHost(warFromTurn = 2)
        val america = host.civ("America")!!
        val germany = host.civ("Germany")!!
        assertEquals(DiplomaticStatus.Peace, america.getDiplomacyManager(germany)!!.diplomaticStatus)
        host.autoplay(1)
        assertEquals(DiplomaticStatus.Peace, america.getDiplomacyManager(germany)!!.diplomaticStatus)
        host.autoplay(1)
        assertEquals(2, host.game.turns)
        assertEquals(DiplomaticStatus.War, america.getDiplomacyManager(germany)!!.diplomaticStatus)
        assertEquals(DiplomaticStatus.DefensivePact, america.getDiplomacyManager(host.civ("England")!!)!!.diplomaticStatus)
    }

    @Test fun diplomacyIntentsAreRejected() {
        val host = newHost()
        host.touch("America")
        val res = host.submitIntents(IntentBatch("America", listOf(Intent("declareWar", target = "England"))))
        assertFalse(res.results.single().accepted)
    }

    @Test fun roundSealsWhenEveryPresentSeatHasCommitted() {
        val host = newHost()
        host.touch("America"); host.touch("Germany")
        val first = host.commit(CommitRequest("America"))
        assertFalse(first.sealed)
        assertEquals(listOf("Germany"), first.waitingFor)
        val second = host.commit(CommitRequest("Germany"))
        assertTrue(second.sealed)
        assertEquals(1, host.game.turns)
        assertEquals(mapOf("America" to "PLAN+AI", "Germany" to "PLAN+AI"), second.round!!.seatModes)
    }

    @Test fun offlineSeatNeverBlocksTheRound() {
        val host = newHost()
        host.touch("America") // Germany never connects
        val res = host.commit(CommitRequest("America"))
        assertTrue(res.sealed)
        assertEquals("AI", res.round!!.seatModes["Germany"])
    }

    @Test fun timerSealsTheRoundAndAiFinishesTheLateSeat() {
        val host = newHost()
        host.touch("America"); host.touch("Germany")
        host.commit(CommitRequest("America"))
        val deadline = host.deadline()!!
        assertNull(host.tick(deadline - 1))
        val round = host.tick(deadline + 1)
        assertNotNull(round)
        assertEquals("PLAN+AI", round!!.seatModes["Germany"])
    }

    @Test fun gamePausesWhenNobodyIsPresent() {
        val host = newHost()
        assertNull(host.tick(System.currentTimeMillis() + 3_600_000))
        assertNull(host.deadline())
        assertEquals(0, host.game.turns)
    }

    @Test fun planIsAppliedAtResolution() {
        val host = newHost(seats = listOf("America"))
        host.touch("America")
        val tech = host.view("America").researchable.first()
        assertTrue(host.submitIntents(IntentBatch("America", listOf(Intent("research", tech = tech)))).results.single().accepted)
        val round = host.commit(CommitRequest("America", delegateRest = false)).round!!
        assertEquals("PLAN", round.seatModes["America"])
        assertTrue(round.intentResults["America"]!!.single().accepted)
    }

    @Test fun restartKeepsAcknowledgedPlansAndCommits() {
        val host = newHost()
        host.touch("America"); host.touch("Germany")
        val tech = host.view("America").researchable.first()
        host.submitIntents(IntentBatch("America", listOf(Intent("research", tech = tech))))
        host.commit(CommitRequest("America"))

        val restored = GameHost.restore(File(dataDir, "t"))!!
        assertEquals(0, restored.game.turns)
        restored.touch("America"); restored.touch("Germany")
        assertTrue(restored.summary().seats.first { it.civ == "America" }.committed)
        val round = restored.commit(CommitRequest("Germany")).round!!
        assertTrue(round.intentResults["America"]!!.single().accepted)
        assertEquals(1, restored.game.turns)
    }

    @Test fun teamOrderInterleavesTeams() {
        val host = newHost()
        val order = RoundEngine.resolutionOrder(host.game, teams, 1).map { it.civName }
        val teamSeq = order.take(4).map { TeamRules.teamOf(teams, it) }
        assertEquals(listOf("Allies", "Axis", "Allies", "Axis"), teamSeq)
    }
}
