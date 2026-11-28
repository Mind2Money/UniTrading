package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopySkippedEventsTest {
    private val event = CopySkippedEvent("tradovate-demo-42", "group", "one", "fill:123")
    @Test fun pausedFollowersDoNotReplaySkippedEventsAfterRestart() = runTest {
        val store = MemorySnapshotStore()
        val ledger = CopySkippedEvents(store)
        assertFalse(ledger.skip(event, CopyFollowerRunState.RUNNING))
        assertTrue(ledger.skip(event, CopyFollowerRunState.NEEDS_REVIEW))
        assertTrue(ledger.skip(event, CopyFollowerRunState.NEEDS_REVIEW))
        val restored = CopySkippedEvents(store)
        assertEquals(false, restored.mayDispatch(event, CopyFollowerRunState.RUNNING))
        assertEquals(true, restored.mayDispatch(event.copy(followerId = "two"), CopyFollowerRunState.RUNNING))
        assertEquals(false, restored.mayDispatch(event.copy(eventId = "fill:124"), CopyFollowerRunState.WAITING_FOR_RECONNECT))
    }
    @Test fun unreadableOrUnwritableLedgerCannotAdvanceCheckpoint() = runTest {
        val failing = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        val ledger = CopySkippedEvents(failing)
        assertFalse(ledger.skip(event, CopyFollowerRunState.NEEDS_REVIEW))
        assertNull(ledger.mayDispatch(event.copy(eventId = "fill:124"), CopyFollowerRunState.RUNNING))
        val corrupt = object : SnapshotStore {
            override fun read(key: String) = "broken"
            override fun write(key: String, value: String) = error("must not overwrite")
        }
        assertNull(CopySkippedEvents(corrupt).mayDispatch(event, CopyFollowerRunState.RUNNING))
    }
}
