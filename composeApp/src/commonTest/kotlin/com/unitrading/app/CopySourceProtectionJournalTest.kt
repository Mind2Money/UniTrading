package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopySourceProtectionJournalTest {
    private val position = Position("leader:1", "MESZ6", Side.BUY, 100, 10_000, 0,
        stopLoss = 9_900, takeProfit = 10_200, marginMicros = 0, version = 3)
    private val execution = Execution("fill", "MESZ6", Side.BUY, 100, 10_000, 0, "entry")
    private fun snapshot(position: Position = this.position) = AccountSnapshot("leader", 0, positions = listOf(position),
        executions = listOf(execution), copyRevision = 7, copyEpoch = 1, copyPositionsComplete = true, copyOrdersComplete = true)
    private val change = CopySourceProtection("tradovate-demo-42", "leader", "group", "entry", "fill", "leader:1", "MESZ6", "change",
        previousVersion = 3, previousRevision = 7, epoch = 1, expectedSide = Side.BUY, expectedLotsHundredths = 100,
        previousStop = 9_900, previousTarget = 10_200,
        requestedStop = 9_925, requestedTarget = 10_200)
    private suspend fun fixture(): CopySourceProtectionJournal {
        val store = MemorySnapshotStore()
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "entry-request", "group", kind = OrderKind.MARKET,
            request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))))
        assertTrue(sources.accepted("entry-request", "entry"))
        return CopySourceProtectionJournal(store, sources)
    }
    @Test fun requiresAppOrderExecutionAndFreshCompletePosition() = runTest {
        val journal = fixture()
        assertFalse(journal.begin(change.copy(sourceOrderId = "outside"), snapshot()))
        assertFalse(journal.begin(change.copy(groupId = "old-group"), snapshot()))
        assertFalse(journal.begin(change.copy(sourceExecutionId = "external-fill"), snapshot()))
        assertFalse(journal.begin(change, snapshot().copy(copyOrdersComplete = false)))
        assertFalse(journal.begin(change, snapshot(position.copy(version = 4))))
        assertTrue(journal.begin(change, snapshot()))
        assertFalse(journal.begin(change.copy(requestId = "second"), snapshot()))
    }
    @Test fun brokerAcceptanceAloneIsNotConfirmation() = runTest {
        val journal = fixture()
        assertTrue(journal.begin(change, snapshot()))
        assertTrue(journal.accepted("change"))
        val changed = position.copy(version = 4, stopLoss = 9_925)
        assertFalse(journal.confirm("change", snapshot(changed)))
        assertFalse(journal.confirm("change", snapshot(changed).copy(copyRevision = 8, copyEpoch = 2)))
        assertFalse(journal.confirm("change", snapshot(changed.copy(side = Side.SELL)).copy(copyRevision = 8)))
        assertFalse(journal.confirm("change", snapshot(changed.copy(lotsHundredths = 200)).copy(copyRevision = 8)))
        assertFalse(journal.confirm("change", snapshot(position.copy(version = 4)).copy(copyRevision = 8)))
        assertTrue(journal.confirm("change", snapshot(changed).copy(copyRevision = 8)))
        assertEquals(CopySourceProtectionState.CONFIRMED, journal.read()!!.single().state)
    }
    @Test fun unknownOutcomeCannotBeReusedOrConfirmed() = runTest {
        val journal = fixture()
        assertTrue(journal.begin(change, snapshot()))
        assertTrue(journal.uncertain("change"))
        assertFalse(journal.accepted("change"))
        assertFalse(journal.confirm("change", snapshot(position.copy(version = 4, stopLoss = 9_925)).copy(copyRevision = 8)))
        assertFalse(journal.begin(change.copy(requestId = "second"), snapshot()))
    }
    @Test fun captureRecordsBeforeSendingAndWaitsForSnapshot() = runTest {
        val journal = fixture()
        var calls = 0
        var stopped = false
        val capture = CopySourceProtectionCapture(journal) { stopped = true }
        val result = capture.submit(change, snapshot(), true) {
            calls++
            assertEquals(CopySourceProtectionState.INTENDED, journal.read()!!.single().state)
            TradeResult(true, "accepted")
        }
        assertTrue(result.accepted)
        assertFalse(stopped)
        assertEquals(1, calls)
        assertEquals(CopySourceProtectionState.ACCEPTED, journal.read()!!.single().state)
        assertTrue(journal.confirm("change", snapshot(position.copy(version = 4, stopLoss = 9_925)).copy(copyRevision = 8)))
    }
    @Test fun captureNeverSendsWhenOriginIsNotProven() = runTest {
        val journal = fixture()
        var calls = 0
        var stopped = false
        val capture = CopySourceProtectionCapture(journal) { stopped = true }
        val result = capture.submit(change.copy(sourceOrderId = "outside"), snapshot(), true) {
            calls++
            TradeResult(true, "unexpected")
        }
        assertFalse(result.accepted)
        assertTrue(stopped)
        assertEquals(0, calls)
    }
    @Test fun captureStopsOnUnknownAndDoesNotResend() = runTest {
        val journal = fixture()
        var calls = 0
        var stopped = false
        val capture = CopySourceProtectionCapture(journal) { stopped = true }
        val result = capture.submit(change, snapshot(), true) {
            calls++
            error("connection lost")
        }
        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertTrue(stopped)
        assertEquals(1, calls)
        assertEquals(CopySourceProtectionState.UNCERTAIN, journal.read()!!.single().state)
        assertFalse(capture.submit(change, snapshot(), true) { calls++; TradeResult(true, "unexpected") }.accepted)
        assertEquals(1, calls)
    }
    @Test fun storageFailurePausesFollowersAndAllowsLeaderProtection() = runTest {
        val backing = MemorySnapshotStore()
        val store = object : SnapshotStore {
            override fun read(key: String): String? = backing.read(key)
            override fun write(key: String, value: String) {
                if (key == "copy-source-protections-v1") error("disk full")
                backing.write(key, value)
            }
        }
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "entry-request", "group",
            request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))))
        assertTrue(sources.accepted("entry-request", "entry"))
        val journal = CopySourceProtectionJournal(store, sources)
        var paused = false
        val result = CopySourceProtectionCapture(journal) { paused = true }.submit(change, snapshot(), true) {
            assertTrue(paused)
            TradeResult(true, "accepted")
        }
        assertTrue(result.accepted)
        assertTrue(paused)
        assertTrue(journal.read()!!.isEmpty())
    }
}
