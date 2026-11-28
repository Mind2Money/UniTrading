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
        assertIs<CopyProtectionExitAction.Finished>(plan(snapshot(emptyList(), emptyList()), setOf("stop", "target")))
    }
    @Test fun unknownOperationOrFollowerFillStopsBeforeCancelOrClose() {
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(), unresolved = true))
        val exit = fill.copy(id = "stop-fill", side = Side.SELL, orderId = "stop")
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(executions = listOf(fill, exit))))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot(executions = emptyList())))
        assertIs<CopyProtectionExitAction.Stop>(plan(snapshot().copy(copyOrdersComplete = false)))
    }
}
