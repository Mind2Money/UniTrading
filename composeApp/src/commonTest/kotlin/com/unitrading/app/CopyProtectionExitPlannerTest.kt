package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyProtectionExitPlannerTest {
    private val event = CopyLeaderEvent.ProtectionFill("leader-fill", "leader-stop", "entry", "MESZ6", -1, 1, 0)
    private val position = Position("follower-pos", "MESZ6", Side.BUY, 200, 10_000, 0, marginMicros = 0)
    private fun order(id: String, role: OrderRole) = PendingOrder(id, OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 200), 0,
        role = role, positionId = position.id)
    private val stop = order("stop", OrderRole.STOP_LOSS)
    private val target = order("target", OrderRole.TAKE_PROFIT)
    private val fill = Execution("entry-fill", "MESZ6", Side.BUY, 200, 10_000, 1, "follower-entry")
    private fun snapshot(orders: List<PendingOrder> = listOf(stop, target), positions: List<Position> = listOf(position),
        executions: List<Execution> = listOf(fill)) = AccountSnapshot("follower", 0, positions = positions, orders = orders,
            executions = executions, copyEpoch = 1, copyRevision = 7, copyOrdersComplete = true, copyPositionsComplete = true)
    private fun plan(s: AccountSnapshot, cancelled: Set<String> = emptySet(), unresolved: Boolean = false) =
        CopyProtectionExitPlanner.fullExit(event, "follower", "follower-pos", s, 1, setOf("stop", "target"), cancelled,
            mapOf(fill.id to fill), true, unresolved)

    @Test fun cancellationMustBeConfirmedBeforeAnyMarketClose() {
        assertIs<CopyProtectionExitAction.Cancel>(plan(snapshot()))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(listOf(target))))
        assertIs<CopyProtectionExitAction.Cancel>(plan(snapshot(listOf(target)), setOf("stop")))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(emptyList()), setOf("stop")))
        val close = assertIs<CopyProtectionExitAction.Close>(plan(snapshot(emptyList()), setOf("stop", "target")))
        assertEquals(Side.SELL, close.side)
        assertEquals(200L, close.lotsHundredths)
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(emptyList(), emptyList()), setOf("stop", "target")))
    }
    @Test fun unknownOperationOrFollowerFillStopsBeforeCancelOrClose() {
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(), unresolved = true))
        val exit = fill.copy(id = "stop-fill", side = Side.SELL, orderId = "stop")
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(executions = listOf(fill, exit))))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(executions = emptyList())))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot().copy(copyOrdersComplete = false)))
    }
    @Test fun onlyExactConfirmedExitFillCanCompleteTheOperation() {
        val exit = fill.copy(id = "close-fill", side = Side.SELL, orderId = "close-order")
        val flat = snapshot(emptyList(), emptyList(), listOf(fill, exit))
        val evidence = CopyProtectionCloseEvidence("close-order", 200)
        assertIs<CopyProtectionExitAction.Finished>(CopyProtectionExitPlanner.fullExit(event, "follower", "follower-pos", flat,
            1, setOf("stop", "target"), setOf("stop", "target"), mapOf(fill.id to fill), true, false, evidence))
        assertIs<CopyProtectionExitAction.Stop>(CopyProtectionExitPlanner.fullExit(event, "follower", "follower-pos", flat,
            1, setOf("stop", "target"), setOf("stop", "target"), mapOf(fill.id to fill), true, false, evidence.copy(lotsHundredths = 100)))
    }
    @Test fun partialLeaderProtectionFillClosesFollowerAndRequiresReview() {
        val partial = event.copy(leaderBefore = 2, leaderAfter = 1)
        assertIs<CopyProtectionExitAction.Cancel>(CopyProtectionExitPlanner.fullExit(partial, "follower", "follower-pos", snapshot(),
            1, setOf("stop", "target"), emptySet(), mapOf(fill.id to fill), true, false))
        assertIs<CopyProtectionExitAction.Close>(CopyProtectionExitPlanner.fullExit(partial, "follower", "follower-pos", snapshot(emptyList()),
            1, setOf("stop", "target"), setOf("stop", "target"), mapOf(fill.id to fill), true, false))
        assertIs<CopyProtectionExitAction.Stop>(CopyProtectionExitPlanner.fullExit(partial, "follower", "follower-pos", snapshot(emptyList(), emptyList()),
            1, setOf("stop", "target"), setOf("stop", "target"), mapOf(fill.id to fill), true, false))
    }
}
