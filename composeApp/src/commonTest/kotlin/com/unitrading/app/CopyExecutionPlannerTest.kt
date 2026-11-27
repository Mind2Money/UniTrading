package com.unitrading.app

import kotlin.test.*

class CopyExecutionPlannerTest {
    private val fixed = CopyFollower("follow", 1, 10, fixedContracts = 2)
    private fun fill(id: String, quantity: Int, before: Int, after: Int, order: String = "source") =
        CopySourceFill(id, order, "leader", quantity, before, after)
    private fun plan(fill: CopySourceFill, follower: CopyFollower = fixed, position: Int = 0) =
        CopyExecutionPlanner.plan(fill, setOf("source"), "leader", follower, position)

    @Test fun eachDistinctPartialFillAddsTheFixedAmount() {
        assertEquals(CopyFillPlan.Ready(2, 0, 2), plan(fill("one", 1, 0, 1)))
        assertEquals(CopyFillPlan.Ready(4, 0, 2), plan(fill("two", 1, 1, 2), position = 2))
        assertEquals(CopyFillPlan.Ready(6, 0, 2), plan(fill("three", 1, 2, 3), position = 4))
    }

    @Test fun externalOrdersAndConflictingReplayAreRejected() {
        assertIs<CopyFillPlan.Blocked>(plan(fill("a", 1, 0, 1, order = "external")))
        val seen = CopyFillDeduplicator()
        val first = fill("a", 1, 0, 1)
        assertEquals(true, seen.observe(first))
        assertEquals(false, seen.observe(first))
        assertNull(seen.observe(first.copy(signedContracts = 2, leaderAfter = 2)))
    }

    @Test fun anIndependentlyMirroredPendingOrderDoesNotGenerateAnotherMarketOrder() {
        val event = fill("partial", 1, 0, 1).copy(mode = CopySourceExecutionMode.INDEPENDENT_PENDING)
        assertEquals(CopyFillPlan.IndependentlyMirrored, plan(event))
        assertIs<CopyFillPlan.Blocked>(plan(event.copy(orderId = "external")))
    }

    @Test fun fixedPartialReductionAndLeaderFlatNeverReverseFollower() {
        assertEquals(CopyFillPlan.Ready(3, 2, 0), plan(fill("reduce", -1, 3, 2), position = 5))
        assertEquals(CopyFillPlan.Ready(0, 3, 0), plan(fill("flat", -2, 2, 0), position = 3))
        assertEquals(CopyFillPlan.Ready(0, 1, 0), plan(fill("reduce-small", -1, 3, 2), position = 1))
    }

    @Test fun reversalClosesBeforeOpeningAndMultiplierUsesActualFillQuantity() {
        val proportional = CopyFollower("follow", 3, 20)
        assertEquals(CopyFillPlan.Ready(-3, 6, 3), plan(fill("reverse", -3, 2, -1), proportional, 6))
        assertEquals(CopyFillPlan.Ready(-6, 0, 6), plan(fill("short", -2, 0, -2), proportional))
    }

    @Test fun inconsistentPositionsAndMaximumAreRejected() {
        assertIs<CopyFillPlan.Blocked>(plan(fill("wrong", 1, 0, 2)))
        assertIs<CopyFillPlan.Blocked>(plan(fill("wrong-side", 1, 1, 2), position = -1))
        assertIs<CopyFillPlan.Blocked>(plan(fill("limit", 1, 3, 4), position = 9))
        assertIs<CopyFillPlan.Blocked>(plan(fill("bad-fixed", 1, 0, 1), fixed.copy(fixedContracts = 11)))
    }
}
