package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopySourceCaptureTest {
    private val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)

    @Test fun tracksOnlyManualLeaderOrders() = runTest {
        val journal = CopySourceOrders(MemorySnapshotStore())
        val capture = CopySourceCapture(journal) { fail(it) }
        var sent = 0
        suspend fun accepted(): TradeResult { sent++; return TradeResult(true, "accepted", "order-1") }
        assertTrue(capture.submit("tradovate", "leader", "group", "follower", "outside", request, true, ::accepted).accepted)
        assertTrue(capture.submit("tradovate", "leader", "group", "leader", "automatic", request.copy(automated = true), true, ::accepted).accepted)
        assertTrue(journal.read()!!.isEmpty())
        assertTrue(capture.submit("tradovate", "leader", "group", "leader", "user-order", request, true, ::accepted).accepted)
        assertEquals(3, sent)
        assertEquals("order-1", journal.read()!!.single().brokerOrderId)
    }

    @Test fun failedStoragePausesFollowersBeforeAllowingLeaderOrder() = runTest {
        val storage = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        var sent = false
        var stopped = false
        val capture = CopySourceCapture(CopySourceOrders(storage)) { stopped = true }
        val result = capture.submit("tradovate", "leader", "group", "leader", "user", request, true) {
            assertTrue(stopped)
            sent = true; TradeResult(true, "accepted", "order")
        }
        assertTrue(result.accepted)
        assertTrue(sent)
        assertTrue(stopped)
    }

    @Test fun remembersWhetherSourceWasAnIndependentPendingOrder() = runTest {
        val journal = CopySourceOrders(MemorySnapshotStore())
        val capture = CopySourceCapture(journal) { fail(it) }
        capture.submit("tradovate", "leader", "group", "leader", "pending", request.copy(kind = OrderKind.LIMIT, entryPrice = 500), true) {
            TradeResult(true, "accepted", "order")
        }
        assertEquals(OrderKind.LIMIT, journal.read()!!.single().kind)
    }

    @Test fun uncertainOutcomeIsRecordedAndNeverRetried() = runTest {
        val journal = CopySourceOrders(MemorySnapshotStore())
        var sent = 0
        var stopped = false
        val capture = CopySourceCapture(journal) { stopped = true }
        val result = capture.submit("tradovate", "leader", "group", "leader", "user", request, true) {
            sent++; TradeResult(false, "unknown", outcome = CommandOutcome.UNKNOWN)
        }
        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertTrue(stopped)
        assertEquals(CopySourceState.UNCERTAIN, journal.read()!!.single().state)
        val retry = capture.submit("tradovate", "leader", "group", "leader", "user", request, true) { sent++; TradeResult(true, "accepted", "order") }
        assertFalse(retry.accepted)
        assertEquals(1, sent)
    }
}
