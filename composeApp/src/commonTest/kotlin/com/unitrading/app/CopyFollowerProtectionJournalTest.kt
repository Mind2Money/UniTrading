package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyFollowerProtectionJournalTest {
    private val source = Position("leader:1", "MESZ6", Side.BUY, 100, 10_000, 0,
        stopLoss = 9_900, takeProfit = 10_200, marginMicros = 0, version = 3)
    private val target = source.copy(id = "follower:1")
    private val entry = Execution("source-fill", "MESZ6", Side.BUY, 100, 10_000, 0, "source-order")
    private val targetEntry = Execution("target-fill", "MESZ6", Side.BUY, 100, 10_000, 0, "target-order")
    private fun snap(id: String, pos: Position, fill: Execution, revision: Long) = AccountSnapshot(id, 0, positions = listOf(pos),
        executions = listOf(fill), copyRevision = revision, copyEpoch = 1, copyPositionsComplete = true, copyOrdersComplete = true)
    private val change = CopySourceProtection("tradovate-demo-42", "leader", "group", "source-order", "source-fill", "leader:1", "MESZ6", "source-change",
        previousVersion = 3, previousRevision = 7, epoch = 1, expectedSide = Side.BUY, expectedLotsHundredths = 100,
        previousStop = 9_900, previousTarget = 10_200, requestedStop = 9_925, requestedTarget = 10_200)
    private val action = CopyFollowerProtection("tradovate-demo-42", "leader", "group", "source-change", "follower", "follower:1", "MESZ6",
        CopyProtectionLeg.STOP, "copy-change", previousVersion = 3, previousRevision = 8, epoch = 1, side = Side.BUY,
        lotsHundredths = 100, previousStop = 9_900, previousTarget = 10_200, requestedStop = 9_925, requestedTarget = 10_200)
    private suspend fun fixture(pendingEntry: Boolean = false): CopyFollowerProtectionJournal {
        val store = MemorySnapshotStore()
        val sources = CopySourceOrders(store)
        val kind = if (pendingEntry) OrderKind.LIMIT else OrderKind.MARKET
        val request = OrderRequest("MESZ6", Side.BUY, kind, 100, entryPrice = if (pendingEntry) 10_000 else null)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group", kind = kind, request = request)))
        assertTrue(sources.accepted("source-request", "source-order"))
        val origins = CopySourceProtectionJournal(store, sources)
        assertTrue(origins.begin(change, snap("leader", source, entry, 7)))
        assertTrue(origins.accepted("source-change"))
        assertTrue(origins.confirm("source-change", snap("leader", source.copy(stopLoss = 9_925, version = 4), entry, 8)))
        val dispatches = CopyDispatchJournal(store)
        val pending = CopyPendingOrderJournal(store)
        if (pendingEntry) {
            assertTrue(pending.begin(CopyPendingOrder("tradovate-demo-42", "leader", "source-order", 1, "follower", "follower-entry",
                request.copy(automated = true))))
            assertTrue(pending.accepted("follower-entry", "target-order"))
        } else {
            assertTrue(dispatches.begin(CopyDispatch("tradovate-demo-42", "leader", "source-order", "source-fill", "follower", CopyDispatchLeg.OPEN,
                "follower-entry", 1, 0, 1)))
            assertTrue(dispatches.accepted("follower-entry", "target-order"))
            assertTrue(dispatches.confirmed("follower-entry", "target-order", 1, 1, true))
        }
        return CopyFollowerProtectionJournal(store, origins, dispatches, pending)
    }
    @Test fun requiresConfirmedSourceAndLinkedFollowerFill() = runTest {
        val journal = fixture()
        val follower = snap("follower", target, targetEntry, 8)
        assertFalse(journal.begin(action.copy(groupId = "other"), follower))
        assertFalse(journal.begin(action.copy(requestedStop = 9_950), follower))
        assertFalse(journal.begin(action, follower.copy(executions = listOf(targetEntry.copy(orderId = "external")))))
        assertFalse(journal.begin(action, follower.copy(copyOrdersComplete = false)))
        assertTrue(journal.begin(action, follower))
        assertFalse(journal.begin(action.copy(requestId = "retry"), follower))
    }
    @Test fun confirmsOnlyMatchingFreshBrokerPosition() = runTest {
        val journal = fixture()
        val follower = snap("follower", target, targetEntry, 8)
        assertTrue(journal.begin(action, follower))
        assertTrue(journal.accepted("copy-change"))
        val updated = target.copy(version = 4, stopLoss = 9_925)
        assertFalse(journal.confirm("copy-change", snap("follower", updated, targetEntry, 8)))
        assertFalse(journal.confirm("copy-change", snap("follower", updated, targetEntry, 9).copy(copyEpoch = 2)))
        assertFalse(journal.confirm("copy-change", snap("follower", updated.copy(takeProfit = null), targetEntry, 9)))
        assertTrue(journal.confirm("copy-change", snap("follower", updated, targetEntry, 9)))
    }
    @Test fun independentlyPlacedPendingOrderNeedsItsOwnFollowerExecution() = runTest {
        val journal = fixture(pendingEntry = true)
        val follower = snap("follower", target, targetEntry, 8)
        assertFalse(journal.begin(action, follower.copy(executions = emptyList())))
        assertTrue(journal.begin(action, follower))
    }
    @Test fun unknownOutcomeBlocksAutomaticRetry() = runTest {
        val journal = fixture()
        val follower = snap("follower", target, targetEntry, 8)
        assertTrue(journal.begin(action, follower))
        assertTrue(journal.uncertain("copy-change"))
        assertFalse(journal.accepted("copy-change"))
        assertFalse(journal.begin(action.copy(requestId = "retry"), follower))
    }
}
