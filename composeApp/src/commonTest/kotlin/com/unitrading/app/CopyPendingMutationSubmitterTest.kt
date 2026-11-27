package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CopyPendingMutationSubmitterTest {
    private val request = OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, entryPrice = 5_000)
    private val sourceOrder = PendingOrder("source", request, 0, version = 2)
    private val followerOrder = PendingOrder("target", request.copy(entryPrice = 4_975), 0, version = 3)
    private fun snapshot(id: String, orders: List<PendingOrder> = emptyList(), lifecycle: List<CopyOrderLifecycle> = emptyList()) =
        AccountSnapshot(id, 0, orders = orders, copyRevision = 7, copyEpoch = 1, copyPositionsComplete = true,
            copyOrdersComplete = true, copyOrdersClear = orders.isEmpty(), copyOrderLifecycle = lifecycle)

    private class Session(override val account: AccountInfo, initial: AccountSnapshot) : AccountSession {
        override val state = MutableStateFlow(initial)
        var outcome = CommandOutcome.ACCEPTED
        var writes = 0
        override fun preview(request: OrderRequest) = OrderPreview(request.entryPrice)
        override suspend fun placeOrder(requestId: String, request: OrderRequest) = TradeResult(false, "unused")
        override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(entryPrice)
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long): TradeResult {
            writes++
            return TradeResult(outcome == CommandOutcome.ACCEPTED, "result", outcome = outcome)
        }
        override suspend fun cancelOrder(requestId: String, orderId: String): TradeResult {
            writes++
            return TradeResult(outcome == CommandOutcome.ACCEPTED, "result", outcome = outcome)
        }
        override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
    }
    private class Broker(val source: Session, val follower: Session) : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-42", "Test", "Demo", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val instruments = listOf(Instrument("MESZ6", "Micro ES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1,
            minLotsHundredths = 100, maxLotsHundredths = 10_000))
        override val accounts = listOf(source.account, follower.account)
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(5_025, 5_050, 1000))))
        override fun openSession(accountId: String) = when (accountId) { "leader" -> source; "follower" -> follower; else -> error("unknown") }
        override fun openAutomatedSession(accountId: String, expectedRevision: Long): AccountSession = openSession(accountId)
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }
    private data class Fixture(val broker: Broker, val service: CopyPendingMutationSubmitter, val journal: CopyPendingMutationJournal)
    private suspend fun fixture(cancel: Boolean = false, sourceStatus: String = "Canceled"): Fixture {
        val store = MemorySnapshotStore()
        val sources = CopySourceOrders(store)
        assertTrue(sources.begin(CopySourceOrder("tradovate-demo-42", "leader", "source-request", "group", kind = OrderKind.LIMIT, request = request)))
        assertTrue(sources.accepted("source-request", "source"))
        val mutations = CopySourceMutationJournal(store, sources)
        val kind = if (cancel) CopySourceMutationKind.CANCEL_ENTRY else CopySourceMutationKind.CHANGE_ENTRY_PRICE
        assertTrue(mutations.begin(CopySourceMutation("tradovate-demo-42", "leader", "group", "source", "source-change", 1, kind,
            newPrice = if (cancel) null else 5_000)))
        assertTrue(mutations.accepted("source-change"))
        if (cancel) assertTrue(mutations.confirmCancellation("source-change", CopyOrderLifecycle("source", "Canceled", 2, 0)))
        else assertTrue(mutations.confirmPrice("source-change", sourceOrder))
        val links = CopyPendingOrderJournal(store)
        assertTrue(links.begin(CopyPendingOrder("tradovate-demo-42", "leader", "source", 1, "follower", "copy-place", request.copy(automated = true))))
        assertTrue(links.accepted("copy-place", "target"))
        val source = Session(AccountInfo("leader", "Leader"), if (cancel) snapshot("leader", lifecycle = listOf(CopyOrderLifecycle("source", sourceStatus, 2, 0))) else snapshot("leader", listOf(sourceOrder)))
        val follower = Session(AccountInfo("follower", "Follower"), snapshot("follower", listOf(followerOrder)))
        val broker = Broker(source, follower)
        val journal = CopyPendingMutationJournal(store)
        return Fixture(broker, CopyPendingMutationSubmitter(BrokerRegistry(listOf(broker)), AccountRiskController(store), sources, mutations, links, journal), journal)
    }
    @Test fun modificationIsSentOnceAndNeedsBrokerConfirmation() = runTest {
        val f = fixture()
        assertIs<CopyPendingMutationSubmission.Accepted>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "action"))
        assertIs<CopyPendingMutationSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "action-again"))
        assertEquals(1, f.broker.follower.writes)
        assertFalse(f.journal.confirmPrice("action", followerOrder.copy(request = request)))
        assertTrue(f.journal.confirmPrice("action", followerOrder.copy(request = request, version = 4)))
    }
    @Test fun unknownResultDoesNotRetry() = runTest {
        val f = fixture()
        f.broker.follower.outcome = CommandOutcome.UNKNOWN
        assertIs<CopyPendingMutationSubmission.Review>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "action"))
        assertIs<CopyPendingMutationSubmission.Stopped>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "another"))
        assertEquals(1, f.broker.follower.writes)
        assertEquals(CopyPendingMutationState.UNCERTAIN, f.journal.read()!!.single().state)
    }
    @Test fun cancellationRequiresCanceledSourceAndTargetConfirmation() = runTest {
        val wrong = fixture(cancel = true, sourceStatus = "Filled")
        assertIs<CopyPendingMutationSubmission.Stopped>(wrong.service.submit(wrong.broker, "leader", "group", "follower", "source-change", "action"))
        assertEquals(0, wrong.broker.follower.writes)
        val f = fixture(cancel = true)
        assertIs<CopyPendingMutationSubmission.Accepted>(f.service.submit(f.broker, "leader", "group", "follower", "source-change", "action"))
        assertFalse(f.journal.confirmCancellation("action", CopyOrderLifecycle("target", "Filled", 4, 100)))
        assertTrue(f.journal.confirmCancellation("action", CopyOrderLifecycle("target", "Canceled", 4, 0)))
    }
}
