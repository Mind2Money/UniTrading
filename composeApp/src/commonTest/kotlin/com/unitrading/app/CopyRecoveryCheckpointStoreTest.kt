package com.unitrading.app

import com.unitrading.broker.AccountSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyRecoveryCheckpointStoreTest {
    private fun account(id: String) = requireNotNull(CopyRecoveryAssessment.checkpoint(AccountSnapshot(id, 0, copyRevision = 7, copyEpoch = 1,
        copyPositionsComplete = true, copyOrdersComplete = true), true))
    private val checkpoint = CopyRecoveryCheckpoint("tradovate-demo-42", "group", "leader",
        listOf("leader", "one", "two").associateWith { id -> account(id) }, mapOf("one" to CopyFollowerRunStatus(CopyFollowerRunState.RUNNING),
            "two" to CopyFollowerRunStatus(CopyFollowerRunState.NEEDS_REVIEW, "结果未知")))
    @Test fun runningAccountsWaitAfterRestartWhileFailedAccountsStayPaused() = runTest {
        val storage = MemorySnapshotStore()
        assertTrue(CopyRecoveryCheckpointStore(storage).save(checkpoint))
        val restored = CopyRecoveryCheckpointStore(storage).readAfterRestart(checkpoint.brokerId)!!
        assertEquals(CopyFollowerRunState.WAITING_FOR_RECONNECT, restored.followerStatus.getValue("one").state)
        assertEquals(CopyFollowerRunState.NEEDS_REVIEW, restored.followerStatus.getValue("two").state)
        assertEquals(checkpoint.accounts, restored.accounts)
    }
    @Test fun invalidOrUnreadableCheckpointCannotBeSavedOver() = runTest {
        val store = CopyRecoveryCheckpointStore(MemorySnapshotStore())
        assertFalse(store.save(checkpoint.copy(accounts = checkpoint.accounts - "one")))
        assertFalse(store.save(checkpoint.copy(leaderId = "one")))
        val corrupt = object : SnapshotStore {
            override fun read(key: String) = "invalid"
            override fun write(key: String, value: String) = error("must not overwrite")
        }
        assertFalse(CopyRecoveryCheckpointStore(corrupt).save(checkpoint))
    }
}
