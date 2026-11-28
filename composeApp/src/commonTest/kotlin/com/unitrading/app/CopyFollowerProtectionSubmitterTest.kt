package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyFollowerProtectionSubmitterTest {
    private val spec = Instrument("MESZ6", "Micro ES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1,
        minLotsHundredths = 100, maxLotsHundredths = 10_000)
    private val leaderBefore = Position("leader:1", "MESZ6", Side.BUY, 100, 10_000, 0, stopLoss = 9_900, takeProfit = 10_200,
        marginMicros = 0, version = 3, stopLossOrderId = "leader-stop", takeProfitOrderId = "leader-target")
    private val leaderAfter = leaderBefore.copy(stopLoss = 9_925, version = 4)
    private val follower = leaderBefore.copy(id = "follower:1", stopLossOrderId = "follower-stop", takeProfitOrderId = "follower-target")
    private val sourceFill = Execution("source-fill", "MESZ6", Side.BUY, 100, 10_000, 0, "source-order")
    private val targetFill = Execution("target-fill", "MESZ6", Side.BUY, 100, 10_000, 0, "target-order")
    private fun snapshot(id: String, position: Position, fill: Execution, revision: Long) = AccountSnapshot(id, 0,
        positions = listOf(position), executions = listOf(fill), copyRevision = revision, copyEpoch = 1,
        copyPositionsComplete = true, copyOrdersComplete = true)
    private class Session(override val account: AccountInfo, snapshot: AccountSnapshot) : AccountSession {
        override val state = MutableStateFlow(snapshot)
        var writes = 0
        var outcome = CommandOutcome.ACCEPTED
        override fun preview(request: OrderRequest) = OrderPreview(request.entryPrice)
        override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(entryPrice)
        override suspend fun placeOrder(requestId: String, request: OrderRequest) = TradeResult(false, "unused")
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
        override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
        override suspend fun cancelOrder(requestId: String, orderId: String): TradeResult { writes++; return TradeResult(outcome == CommandOutcome.ACCEPTED, "cancel", outcome = outcome) }
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?): TradeResult {
            writes++
            assertEquals("follower:1", positionId)
            assertEquals(9_925L, stopLoss)
            return TradeResult(outcome == CommandOutcome.ACCEPTED, "protect", outcome = outcome)
        }
    }
    private class Broker(val source: Session, val target: Session, spec: Instrument) : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-42", "Test", "Demo", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val instruments = listOf(spec)
        override val accounts = listOf(source.account, target.account)
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(10_000, 10_025, 1000))))
        override fun openSession(accountId: String) = when (accountId) { "leader" -> source; "follower" -> target; else -> error("unknown") }
        override fun openAutomatedSession(accountId: String, expectedRevision: Long): AccountSession = openSession(accountId)
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }
    private data class Fixture(val broker: Broker, val service: CopyFollowerProtectionSubmitter, val journal: CopyFollowerProtectionJournal)
    private suspend fun fixture(): Fixture {
        val store = MemorySnapshotStore()
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "entry", "group", kind = OrderKind.MARKET,
            request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))))
        assertTrue(sources.accepted("entry", "source-order"))
        val origins = CopySourceProtectionJournal(store, sources)
        val origin = CopySourceProtection("tradovate-demo-42", "leader", "group", "source-order", "source-fill", "leader:1", "MESZ6", "source-change",
            3, 7, 1, Side.BUY, 100, 9_900, 10_200, 9_925, 10_200)
        assertTrue(origins.begin(origin, snapshot("leader", leaderBefore, sourceFill, 7)))
        assertTrue(origins.accepted("source-change"))
        assertTrue(origins.confirm("source-change", snapshot("leader", leaderAfter, sourceFill, 8)))
        val dispatches = CopyDispatchJournal(store)
        assertTrue(dispatches.begin(CopyDispatch("tradovate-demo-42", "leader", "source-order", "source-fill", "follower", CopyDispatchLeg.OPEN,
            "target-entry", 1, 0, 1)))
        assertTrue(dispatches.accepted("target-entry", "target-order"))
        assertTrue(dispatches.confirmed("target-entry", "target-order", 1, 1, true))
        val journal = CopyFollowerProtectionJournal(store, origins, dispatches, CopyPendingOrderJournal(store))
        val broker = Broker(Session(AccountInfo("leader", "Leader"), snapshot("leader", leaderAfter, sourceFill, 8)),
            Session(AccountInfo("follower", "Follower"), snapshot("follower", follower, targetFill, 8)), spec)
        val service = CopyFollowerProtectionSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), origins, journal, now = { 1000 })
        return Fixture(broker, service, journal)
    }
    @Test fun confirmedSourceChangeIsSentOnceAndWaitsForFollowerState() = runTest {
        val f = fixture()
        assertIs<CopyFollowerProtectionSubmission.Accepted>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "copy-change"))
        assertIs<CopyFollowerProtectionSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "retry"))
        assertEquals(1, f.broker.target.writes)
        assertEquals(CopyFollowerProtectionState.ACCEPTED, f.journal.read()!!.single().state)
        assertTrue(f.journal.confirm("copy-change", snapshot("follower", follower.copy(stopLoss = 9_925, version = 4), targetFill, 9)))
    }
    @Test fun unknownOutcomeIsNotAutomaticallyResent() = runTest {
        val f = fixture()
        f.broker.target.outcome = CommandOutcome.UNKNOWN
        assertIs<CopyFollowerProtectionSubmission.Review>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "copy-change"))
        assertIs<CopyFollowerProtectionSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "retry"))
        assertEquals(1, f.broker.target.writes)
        assertEquals(CopyFollowerProtectionState.UNCERTAIN, f.journal.read()!!.single().state)
    }
    @Test fun noFollowerExecutionOrStaleBookMeansNoWrite() = runTest {
        val f = fixture()
        f.broker.target.state.value = f.broker.target.state.value.copy(executions = emptyList())
        assertIs<CopyFollowerProtectionSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "copy-change"))
        assertEquals(0, f.broker.target.writes)
    }
    @Test fun invalidOrUnavailableFollowerPriceDoesNotSendProtection() = runTest {
        val f = fixture()
        for (quotes in listOf(emptyMap(), mapOf("MESZ6" to Quote(9_900, 9_925, 1000)),
            mapOf("MESZ6" to Quote(10_000, 10_025, -20_000)))) {
            f.broker.market.value = MarketSnapshot(quotes = quotes)
            assertIs<CopyFollowerProtectionSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "copy-change"))
        }
        assertEquals(0, f.broker.target.writes)
    }
}
