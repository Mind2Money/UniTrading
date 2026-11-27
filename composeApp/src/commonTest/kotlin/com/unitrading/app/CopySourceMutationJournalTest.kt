package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopySourceMutationJournalTest {
    private val priceChange = CopySourceMutation("tradovate-demo-42", "leader", "group", "source", "change", 3,
        CopySourceMutationKind.CHANGE_ENTRY_PRICE, newPrice = 5_000)

    private suspend fun fixture(): Pair<CopySourceMutationJournal, MemorySnapshotStore> {
        val store = MemorySnapshotStore()
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group", kind = OrderKind.LIMIT)))
        assertTrue(sources.accepted("source-request", "source"))
        return CopySourceMutationJournal(store, sources) to store
    }

    @Test fun requiresAppOriginAndConfirmedBrokerPrice() = runTest {
        val (journal, _) = fixture()
        assertFalse(journal.begin(priceChange.copy(sourceOrderId = "external")))
        assertTrue(journal.begin(priceChange))
        assertTrue(journal.accepted("change"))
        val source = PendingOrder("source", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, entryPrice = 5_000), 0, version = 4)
        assertFalse(journal.confirmPrice("change", source.copy(version = 3)))
        assertFalse(journal.confirmPrice("change", source.copy(request = source.request.copy(entryPrice = 5_025))))
        assertTrue(journal.confirmPrice("change", source))
        assertEquals(CopySourceMutationState.CONFIRMED, journal.read()!!.single().state)
    }

    @Test fun filledSourceIsNotMistakenForCancellation() = runTest {
        val (journal, _) = fixture()
        val cancel = priceChange.copy(requestId = "cancel", kind = CopySourceMutationKind.CANCEL_ENTRY, newPrice = null)
        assertTrue(journal.begin(cancel))
        assertTrue(journal.accepted("cancel"))
        assertFalse(journal.confirmCancellation("cancel", CopyOrderLifecycle("source", "Filled", 4, 100)))
        assertTrue(journal.confirmCancellation("cancel", CopyOrderLifecycle("source", "Canceled", 4, 0)))
    }

    @Test fun uncertainMutationIsNotAutomaticallyConfirmedOrReused() = runTest {
        val (journal, _) = fixture()
        assertTrue(journal.begin(priceChange))
        assertTrue(journal.uncertain("change"))
        assertFalse(journal.accepted("change"))
        assertFalse(journal.begin(priceChange))
    }
}
