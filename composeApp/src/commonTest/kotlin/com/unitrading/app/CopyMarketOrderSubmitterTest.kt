package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyMarketOrderSubmitterTest {
    private class FakeSession(override val account: AccountInfo, snapshot: AccountSnapshot) : AccountSession {
        override val state = MutableStateFlow(snapshot)
        var outcome = CommandOutcome.ACCEPTED
        val writes = mutableListOf<OrderRequest>()
        override fun preview(request: OrderRequest) = OrderPreview(10_100)
        override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
            writes += request
            return if (outcome == CommandOutcome.ACCEPTED) TradeResult(true, "accepted", "target-${writes.size}")
                else TradeResult(false, "unknown", outcome = outcome)
        }
        override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(entryPrice)
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
        override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
        override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
    }

    private val spec = Instrument("MESZ6", "Micro ES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1,
        minLotsHundredths = 100, maxLotsHundredths = 10_000)
    private fun position(side: Side, quantity: Long = 1) = Position("position", "MESZ6", side, quantity * 100, 10_000, 0, marginMicros = 0)
    private fun state(id: String, positions: List<Position> = emptyList(), executions: List<Execution> = emptyList()) =
        AccountSnapshot(id, 0, positions = positions, executions = executions, copyRevision = 7, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = true)

    private class FakeBroker(val source: FakeSession, val follower: FakeSession, spec: Instrument) : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-42", "Test", "Demo", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val instruments = listOf(spec)
        override val accounts = listOf(source.account, follower.account)
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(10_050, 10_100, 1000))))
        override fun openSession(accountId: String) = when (accountId) { source.account.id -> source; follower.account.id -> follower; else -> error("unknown") }
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }

    private suspend fun sources(store: SnapshotStore, side: Side = Side.BUY, stopLoss: Long? = null) = CopySourceOrders(store).also {
        assertTrue(it.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group",
            request = OrderRequest("MESZ6", side, OrderKind.MARKET, 200, stopLoss = stopLoss))))
        assertTrue(it.accepted("source-request", "source-order"))
    }

    @Test fun sameSourceFillIsDispatchedOnlyOncePerFollower() = runTest {
        val fill = CopySourceFill("fill", "source-order", "leader", 1, 0, 1)
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(position(Side.BUY)),
            listOf(Execution("fill", "MESZ6", Side.BUY, 100, 10_100, 1000, "source-order"))))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower"))
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val journal = CopyDispatchJournal(store)
        val service = CopyMarketOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources(store), journal)
        val follower = CopyFollower("follower", 1, 5, fixedContracts = 2)
        assertIs<CopyMarketSubmission.Accepted>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "copy-a"))
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "copy-b"))
        assertEquals(1, target.writes.size)
        assertEquals(200L, target.writes.single().lotsHundredths)
        assertTrue(target.writes.single().automated)
        assertEquals(CopyDispatchState.ACCEPTED, journal.read()!!.single().state)
    }

    @Test fun reversalWaitsForConfirmedCloseBeforeOpening() = runTest {
        val fill = CopySourceFill("fill", "source-order", "leader", -2, 1, -1)
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(position(Side.SELL)),
            listOf(Execution("fill", "MESZ6", Side.SELL, 200, 10_000, 1000, "source-order"))))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower", listOf(position(Side.BUY))))
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val journal = CopyDispatchJournal(store)
        val service = CopyMarketOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources(store, Side.SELL), journal)
        val follower = CopyFollower("follower", 1, 5)
        assertIs<CopyMarketSubmission.Accepted>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "close"))
        target.state.value = state("follower")
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "open-early"))
        assertTrue(journal.confirmed("close", "target-1", 1, 0, true))
        assertIs<CopyMarketSubmission.Accepted>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "open"))
        assertEquals(listOf(Side.SELL, Side.SELL), target.writes.map { it.side })
    }

    @Test fun unknownResultAndExternalFillCannotTriggerRetry() = runTest {
        val fill = CopySourceFill("fill", "source-order", "leader", 1, 0, 1)
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(position(Side.BUY)),
            listOf(Execution("fill", "MESZ6", Side.BUY, 100, 10_100, 1000, "source-order"))))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower")).apply { outcome = CommandOutcome.UNKNOWN }
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val journal = CopyDispatchJournal(store)
        val service = CopyMarketOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources(store), journal)
        val follower = CopyFollower("follower", 1, 5)
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "MESZ6", fill.copy(orderId = "external"), "external"))
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "new-group", follower, "MESZ6", fill, "old-group"))
        assertIs<CopyMarketSubmission.Review>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "copy-a"))
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "MESZ6", fill, "copy-b"))
        assertEquals(1, target.writes.size)
        assertEquals(CopyDispatchState.UNCERTAIN, journal.read()!!.single().state)
    }

    @Test fun sourceWithProtectionIsNotCopiedUntilProtectedExecutionIsImplemented() = runTest {
        val fill = CopySourceFill("fill", "source-order", "leader", 1, 0, 1)
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(position(Side.BUY)),
            listOf(Execution("fill", "MESZ6", Side.BUY, 100, 10_100, 1000, "source-order"))))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower"))
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val service = CopyMarketOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources(store, stopLoss = 9_900), CopyDispatchJournal(store))
        assertIs<CopyMarketSubmission.Stopped>(service.submit(broker, "leader", "group", CopyFollower("follower", 1, 2), "MESZ6", fill, "copy-a"))
        assertTrue(target.writes.isEmpty())
    }
}
