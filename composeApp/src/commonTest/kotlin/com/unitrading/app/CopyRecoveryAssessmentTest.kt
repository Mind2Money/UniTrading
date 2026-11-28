package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyRecoveryAssessmentTest {
    private fun snapshot(id: String) = AccountSnapshot(id, 0, copyRevision = 7, copyEpoch = 1,
        copyPositionsComplete = true, copyOrdersComplete = true)
    private val ids = listOf("leader", "one", "two")
    private val initial = ids.associateWith(::snapshot)
    private val saved = initial.mapValues { requireNotNull(CopyRecoveryAssessment.checkpoint(it.value, true)) }
    private fun assess(observed: Map<String, AccountSnapshot> = initial, sourceUnresolved: Boolean = false,
        unresolved: Set<String> = emptySet(), history: Set<String> = ids.toSet()) =
        CopyRecoveryAssessment.assess("leader", saved, observed, listOf("one", "two"), sourceUnresolved, unresolved, history)

    @Test fun reconnectAllowsNewEpochOnlyAfterAllBooksAndHistoryMatch() {
        val resynced = initial.mapValues { it.value.copy(copyRevision = 8, copyEpoch = 2) }
        val result = assess(resynced)
        assertTrue(result.leaderReady)
        assertEquals(mapOf("one" to null, "two" to null), result.followerReasons)
        assertNotNull(assess(resynced, history = setOf("one", "two")).leaderReason)
        assertNotNull(assess(resynced, history = setOf("leader", "two")).followerReasons["one"])
    }
    @Test fun failureOrMismatchBlocksOnlyTheAffectedFollower() {
        val result = assess(unresolved = setOf("one"))
        assertTrue(result.leaderReady)
        assertNotNull(result.followerReasons["one"])
        assertNull(result.followerReasons["two"])
        val changed = initial + ("one" to snapshot("one").copy(positions = listOf(Position("p", "MESZ6", Side.BUY, 100, 5_000, 0, marginMicros = 0))))
        assertNotNull(assess(changed).followerReasons["one"])
        assertNull(assess(changed).followerReasons["two"])
    }
    @Test fun unconfirmedLeaderOrMissedExecutionStopsAllFollowers() {
        assertNotNull(assess(sourceUnresolved = true).leaderReason)
        assertTrue(assess(sourceUnresolved = true).followerReasons.values.all { it != null })
        val fill = Execution("fill", "MESZ6", Side.BUY, 100, 5_000, 0, "source-order")
        val changed = initial + ("leader" to snapshot("leader").copy(executions = listOf(fill)))
        assertNotNull(assess(changed).leaderReason)
    }
    @Test fun unavailableOrAmbiguousBrokerBooksCannotBeCheckpointed() {
        assertNull(CopyRecoveryAssessment.checkpoint(snapshot("one"), false))
        val unresolvedOrder = PendingOrder("id", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, 5_000), 0, role = OrderRole.UNRESOLVED)
        assertNull(CopyRecoveryAssessment.checkpoint(snapshot("one").copy(orders = listOf(unresolvedOrder)), true))
        assertNull(CopyRecoveryAssessment.checkpoint(snapshot("one").copy(copyPositionsComplete = false), true))
    }
}
