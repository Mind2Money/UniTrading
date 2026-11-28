package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyLeaderFillAssessmentTest {
    private val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 200)
    private val origin = CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group",
        CopySourceState.ACCEPTED, "source-order", request = request)
    private val first = Execution("fill1", "MESZ6", Side.BUY, 100, 10_000, 1000, "source-order")
    private val second = Execution("fill2", "MESZ6", Side.BUY, 100, 10_025, 2000, "source-order")
    private fun snapshot(fills: List<Execution>, positions: List<Position>) = AccountSnapshot("leader", 0, positions = positions,
        executions = fills, copyRevision = 7, copyEpoch = 1, copyPositionsComplete = true, copyOrdersComplete = true)
    private fun position(qty: Long = 100) = Position("leader:1", "MESZ6", Side.BUY, qty, 10_000, 0, marginMicros = 0)
    private fun assess(s: AccountSnapshot, orders: List<CopySourceOrder>? = listOf(origin), previous: Map<String, Int> = emptyMap(),
        seen: Map<String, Execution> = emptyMap(), protections: Map<String, String> = emptyMap(), complete: Boolean = true) =
        CopyLeaderFillAssessment.assess(s, "tradovate-demo-42", "group", orders, previous, seen, protections, complete)

    @Test fun partialFillsAreDistinctAndDoNotReplay() {
        val one = assertIs<CopyLeaderFillResult.Ready>(assess(snapshot(listOf(first), listOf(position()))))
        val event = assertIs<CopyLeaderEvent.EntryOrExit>(one.events.single())
        assertEquals(0, event.fill.leaderBefore)
        assertEquals(1, event.fill.leaderAfter)
        val two = assertIs<CopyLeaderFillResult.Ready>(assess(snapshot(listOf(first, second), listOf(position(200))), previous = one.netBySymbol,
            seen = one.observedExecutions))
        assertEquals("fill2", assertIs<CopyLeaderEvent.EntryOrExit>(two.events.single()).fill.executionId)
        assertEquals(2, two.netBySymbol["MESZ6"])
    }
    @Test fun independentlyMirroredPendingFillIsNotTurnedIntoMarketEntry() {
        val limit = request.copy(kind = OrderKind.LIMIT, entryPrice = 10_000)
        val result = assertIs<CopyLeaderFillResult.Ready>(assess(snapshot(listOf(first), listOf(position())),
            orders = listOf(origin.copy(kind = OrderKind.LIMIT, request = limit))))
        assertEquals(CopySourceExecutionMode.INDEPENDENT_PENDING, assertIs<CopyLeaderEvent.EntryOrExit>(result.events.single()).fill.mode)
    }
    @Test fun onlyLinkedProtectionCanCloseTheTrackedPosition() {
        val stop = Execution("stop-fill", "MESZ6", Side.SELL, 100, 9_900, 2000, "stop-order")
        val s = snapshot(listOf(first, stop), emptyList())
        assertIs<CopyLeaderFillResult.Blocked>(assess(s, previous = mapOf("MESZ6" to 1), seen = mapOf("fill1" to first)))
        val ready = assertIs<CopyLeaderFillResult.Ready>(assess(s, previous = mapOf("MESZ6" to 1), seen = mapOf("fill1" to first),
            protections = mapOf("stop-order" to "source-order")))
        assertIs<CopyLeaderEvent.ProtectionFill>(ready.events.single())
        assertTrue(ready.netBySymbol.isEmpty())
    }
    @Test fun unexplainedFillsGapsAndPositionMismatchBlockFollowing() {
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first.copy(orderId = "external")), listOf(position()))))
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first), listOf(position())), seen = mapOf("missing" to first.copy(id = "missing"))))
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first.copy(price = 10_025)), listOf(position())),
            previous = mapOf("MESZ6" to 1), seen = mapOf("fill1" to first)))
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first), emptyList())))
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first), listOf(position())), complete = false))
    }
    @Test fun delayedAndSimultaneousEarlierFillsDoNotGetAppliedAfterCheckpoint() {
        val earlier = second.copy(timeMs = first.timeMs - 1)
        val simultaneous = second.copy(timeMs = first.timeMs)
        for (late in listOf(earlier, simultaneous)) {
            assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(first, late), listOf(position(200))),
                previous = mapOf("MESZ6" to 1), seen = mapOf(first.id to first)))
        }
    }
    @Test fun oppositeSideFillsAtTheSameTimeAreAmbiguousEvenWithAnInterleavedSymbol() {
        val sell = first.copy(id = "z", side = Side.SELL, orderId = "sell-order")
        val other = first.copy(id = "m", symbol = "MNQZ6", orderId = "other-order")
        val buy = first.copy(id = "a")
        val sellOrigin = origin.copy(requestId = "sell-request", brokerOrderId = "sell-order", request = request.copy(side = Side.SELL))
        val otherOrigin = origin.copy(requestId = "other-request", brokerOrderId = "other-order", request = request.copy(symbol = "MNQZ6"))
        assertIs<CopyLeaderFillResult.Blocked>(assess(snapshot(listOf(buy, other, sell), listOf(position())),
            orders = listOf(origin, sellOrigin, otherOrigin)))
    }
}
