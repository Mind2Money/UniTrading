package com.unitrading.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyDispatchJournalTest {
    private fun dispatch(request: String = "a", follower: String = "follower") = CopyDispatch(
        brokerId = "tradovate-demo-42", leaderId = "leader", sourceOrderId = "source-order", sourceExecutionId = "fill-1",
        followerId = follower, leg = CopyDispatchLeg.OPEN, requestId = request, signedContracts = 2, expectedBefore = 0, expectedAfter = 2)

    @Test fun duplicateFillForSameFollowerCannotBeSentTwice() = runTest {
        val store = MemorySnapshotStore()
        val journal = CopyDispatchJournal(store)
        assertTrue(journal.begin(dispatch()))
        assertFalse(journal.begin(dispatch(request = "another-request")))
        assertTrue(journal.begin(dispatch(request = "b", follower = "other")))
        assertEquals(2, CopyDispatchJournal(store).read()!!.size)
    }

    @Test fun acceptanceDoesNotMeanFillAndWrongNetCannotBeConfirmed() = runTest {
        val journal = CopyDispatchJournal(MemorySnapshotStore())
        assertTrue(journal.begin(dispatch()))
        assertTrue(journal.accepted("a", "follower-order"))
        assertFalse(journal.confirmed("a", "wrong-order", 2, 2, true))
        assertFalse(journal.confirmed("a", "follower-order", 2, 1, true))
        assertFalse(journal.confirmed("a", "follower-order", 1, 2, true))
        assertFalse(journal.confirmed("a", "follower-order", 2, 2, false))
        assertTrue(journal.confirmed("a", "follower-order", 2, 2, true))
        assertFalse(journal.confirmed("a", "follower-order", 2, 2, true))
    }

    @Test fun unknownOutcomeRemainsLockedAfterRestart() = runTest {
        val store = MemorySnapshotStore()
        val journal = CopyDispatchJournal(store)
        assertTrue(journal.begin(dispatch()))
        assertTrue(journal.uncertain("a"))
        val reloaded = CopyDispatchJournal(store)
        assertEquals(CopyDispatchState.UNCERTAIN, reloaded.read()!!.single().state)
        assertFalse(reloaded.begin(dispatch(request = "retry")))
        assertFalse(reloaded.accepted("a", "unverified-order"))
    }

    @Test fun failedPersistenceDoesNotCreateAnIntent() = runTest {
        val broken = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = error("disk full")
        }
        val journal = CopyDispatchJournal(broken)
        assertFalse(journal.begin(dispatch()))
        assertTrue(journal.read()!!.isEmpty())
    }
}
