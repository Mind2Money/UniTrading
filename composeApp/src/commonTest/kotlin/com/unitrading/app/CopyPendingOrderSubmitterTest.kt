package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyPendingOrderSubmitterTest {
    private class FakeSession(override val account: AccountInfo, snapshot: AccountSnapshot) : AccountSession {
        override val state = MutableStateFlow(snapshot)
        var outcome = CommandOutcome.ACCEPTED
        var writes = 0
        override fun preview(request: OrderRequest) = OrderPreview(request.entryPrice)
        override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
            writes++
            return if (outcome == CommandOutcome.ACCEPTED) TradeResult(true, "accepted", "target-order")
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
    private val order = PendingOrder("source-order", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 200, entryPrice = 10_000), 0, version = 1)
    private fun state(id: String, orders: List<PendingOrder> = emptyList()) = AccountSnapshot(id, 0, orders = orders,
        copyRevision = 7, copyEpoch = 1, copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = orders.isEmpty())

    private class FakeBroker(val source: FakeSession, val follower: FakeSession, spec: Instrument) : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-42", "Test", "Demo", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val instruments = listOf(spec)
        override val accounts = listOf(source.account, follower.account)
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(10_050, 10_100, 1000))))
        override fun openSession(accountId: String) = when (accountId) { source.account.id -> source; follower.account.id -> follower; else -> error("unknown") }
        override fun openAutomatedSession(accountId: String): AccountSession = openSession(accountId)
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }

    private suspend fun register(store: SnapshotStore): CopySourceOrders = CopySourceOrders(store).also {
        assertTrue(it.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group", kind = OrderKind.LIMIT, request = order.request)))
        assertTrue(it.accepted("source-request", "source-order"))
    }

    @Test fun acceptedPendingOrderIsSubmittedOnlyOnce() = runTest {
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(order)))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower"))
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val journal = CopyPendingOrderJournal(store)
        val service = CopyPendingOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), register(store), journal) { 1000 }
        val follower = CopyFollower("follower", 1, 2, fixedContracts = 1)
        assertIs<CopyPendingSubmission.Accepted>(service.submit(broker, "leader", "group", follower, "source-order", "copy-a"))
        assertIs<CopyPendingSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "source-order", "copy-b"))
        assertEquals(1, target.writes)
        assertEquals(CopyPendingState.ACCEPTED, journal.read()!!.single().state)
    }

    @Test fun unknownResponseBlocksRetry() = runTest {
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(order)))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower")).apply { outcome = CommandOutcome.UNKNOWN }
        val broker = FakeBroker(source, target, spec)
        val store = MemorySnapshotStore()
        val journal = CopyPendingOrderJournal(store)
        val service = CopyPendingOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), register(store), journal) { 1000 }
        val follower = CopyFollower("follower", 1, 2, fixedContracts = 1)
        assertIs<CopyPendingSubmission.Review>(service.submit(broker, "leader", "group", follower, "source-order", "copy-a"))
        assertIs<CopyPendingSubmission.Stopped>(service.submit(broker, "leader", "group", follower, "source-order", "copy-b"))
        assertEquals(1, target.writes)
        assertEquals(CopyPendingState.UNCERTAIN, journal.read()!!.single().state)
    }

    @Test fun changedSourceAfterJournalPreventsSending() = runTest {
        val source = FakeSession(AccountInfo("leader", "Leader"), state("leader", listOf(order)))
        val target = FakeSession(AccountInfo("follower", "Follower"), state("follower"))
        val broker = FakeBroker(source, target, spec)
        val backing = MemorySnapshotStore()
        val sources = register(backing)
        val store = object : SnapshotStore {
            override fun read(key: String) = backing.read(key)
            override fun write(key: String, value: String) {
                backing.write(key, value)
                if (key == "copy-pending-orders-v1") source.state.value = source.state.value.copy(copyRevision = 8)
            }
        }
        val service = CopyPendingOrderSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources, CopyPendingOrderJournal(store)) { 1000 }
        assertIs<CopyPendingSubmission.Review>(service.submit(broker, "leader", "group", CopyFollower("follower", 1, 2), "source-order", "copy-a"))
        assertEquals(0, target.writes)
    }
}
