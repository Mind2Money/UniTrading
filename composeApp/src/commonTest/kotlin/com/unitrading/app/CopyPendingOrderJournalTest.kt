package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyPendingOrderJournalTest {
    private val order = CopyPendingOrder("tradovate-demo-42", "leader", "source", 1, "follower", "request",
        OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 200, entryPrice = 10_000, automated = true))

    @Test fun recordsAUniqueLinkAndRecoversIt() = runTest {
        val store = MemorySnapshotStore()
        val journal = CopyPendingOrderJournal(store)
        assertTrue(journal.begin(order))
        assertFalse(journal.begin(order.copy(requestId = "duplicate")))
        assertTrue(journal.accepted("request", "follower-order"))
        val restored = CopyPendingOrderJournal(store).read()!!.single()
        assertEquals("follower-order", restored.followerOrderId)
        assertEquals(CopyPendingState.ACCEPTED, restored.state)
    }

    @Test fun unknownOutcomeCannotBeResentOrPromotedAutomatically() = runTest {
        val store = MemorySnapshotStore()
        val journal = CopyPendingOrderJournal(store)
        assertTrue(journal.begin(order))
        assertTrue(journal.uncertain("request"))
        val restored = CopyPendingOrderJournal(store)
        assertFalse(restored.begin(order.copy(requestId = "retry")))
        assertFalse(restored.accepted("request", "unverified"))
    }

    @Test fun rejectsMarketOrdersAndNonAutomatedRequests() = runTest {
        val journal = CopyPendingOrderJournal(MemorySnapshotStore())
        assertFalse(journal.begin(order.copy(request = order.request.copy(automated = false))))
        assertFalse(journal.begin(order.copy(request = order.request.copy(kind = OrderKind.MARKET))))
    }

    @Test fun cannotClaimTerminalForAnotherFollowerOrder() = runTest {
        val journal = CopyPendingOrderJournal(MemorySnapshotStore())
        assertTrue(journal.begin(order))
        assertTrue(journal.accepted("request", "follower-order"))
        assertFalse(journal.terminal("request", "different-order"))
        assertTrue(journal.terminal("request", "follower-order"))
    }
}
