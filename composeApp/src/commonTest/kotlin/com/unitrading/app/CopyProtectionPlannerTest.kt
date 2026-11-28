package com.unitrading.app

import com.unitrading.broker.Position
import com.unitrading.broker.Side
import kotlin.test.*

class CopyProtectionPlannerTest {
    private fun position(id: String, stop: Long? = 9_900, take: Long? = 10_200) = Position(id, "MESZ6", Side.BUY,
        100, 10_000, 0, stopLoss = stop, takeProfit = take, marginMicros = 0, version = 3,
        stopLossOrderId = "stop-$id", takeProfitOrderId = "take-$id")

    @Test fun usesTheSameSourcePriceAndFollowerVersion() {
        val leader = position("leader", stop = 9_925)
        val follower = position("follower")
        assertEquals(CopyProtectionAction.Change("follower", 3, 9_925, 10_200), CopyProtectionPlanner.plan(true, leader, follower, 25))
    }

    @Test fun removesOnlyTheLinkedTargetLeg() {
        assertEquals(CopyProtectionAction.Cancel("stop-follower"), CopyProtectionPlanner.plan(true, position("leader", stop = null), position("follower"), 25))
        assertIs<CopyProtectionAction.Stop>(CopyProtectionPlanner.plan(true, position("leader", stop = null), position("follower").copy(stopLossOrderId = null), 25))
    }

    @Test fun externalUnlinkedOrInvalidChangesDoNotProduceAnAction() {
        assertIs<CopyProtectionAction.Stop>(CopyProtectionPlanner.plan(false, position("leader"), position("follower"), 25))
        assertIs<CopyProtectionAction.Stop>(CopyProtectionPlanner.plan(true, position("leader"), null, 25))
        assertIs<CopyProtectionAction.Stop>(CopyProtectionPlanner.plan(true, position("leader", stop = 9_901), position("follower"), 25))
        assertIs<CopyProtectionAction.Stop>(CopyProtectionPlanner.plan(true, position("leader"), position("follower").copy(protectionEditable = false), 25))
    }
}
