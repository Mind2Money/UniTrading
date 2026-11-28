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
    @Test fun capturePersistsBeforeSendingAndSerializesChanges() = runTest {
        val (journal, _) = fixture()
        var paused = false
        var sent = 0
        val capture = CopySourceMutationCapture(journal) { paused = true }
        assertTrue(capture.submit(priceChange, true) {
            sent++
            assertEquals(CopySourceMutationState.INTENDED, journal.read()!!.single().state)
            TradeResult(true, "accepted")
        }.accepted)
        assertEquals(CopySourceMutationState.ACCEPTED, journal.read()!!.single().state)
        assertFalse(capture.submit(priceChange.copy(requestId = "cancel", kind = CopySourceMutationKind.CANCEL_ENTRY, newPrice = null), true) {
            sent++; TradeResult(true, "unexpected")
        }.accepted)
        assertTrue(paused)
        assertEquals(1, sent)
    }
    @Test fun storageFailurePausesFollowersButAllowsLeaderMutation() = runTest {
        val backing = MemorySnapshotStore()
        val store = object : SnapshotStore {
            override fun read(key: String): String? = backing.read(key)
            override fun write(key: String, value: String) {
                if (key == "copy-source-mutations-v1") error("disk full")
                backing.write(key, value)
            }
        }
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group", kind = OrderKind.LIMIT)))
        assertTrue(sources.accepted("source-request", "source"))
        var paused = false
        val capture = CopySourceMutationCapture(CopySourceMutationJournal(store, sources)) { paused = true }
        assertTrue(capture.submit(priceChange, true) { assertTrue(paused); TradeResult(true, "accepted") }.accepted)
        var duplicate = false
        assertFalse(capture.submit(priceChange, true) { duplicate = true; TradeResult(true, "unexpected") }.accepted)
        assertFalse(duplicate)
    }
}
