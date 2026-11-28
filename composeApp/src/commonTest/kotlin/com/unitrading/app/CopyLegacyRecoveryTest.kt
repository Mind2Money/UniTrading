package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CopyLegacyRecoveryTest {
    private class FakeBroker : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-test", "Demo", "test", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val accounts = listOf(AccountInfo("leader", "Leader"), AccountInfo("follower", "Follower"))
        override val instruments = emptyList<Instrument>()
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot())
        val books = accounts.associate { it.id to MutableStateFlow(AccountSnapshot(it.id, 0, copyRevision = 1, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = true)) }
        var resyncs = 0
        var writes = 0
        override suspend fun resynchronize() { resyncs++ }
        override fun openSession(accountId: String): AccountSession = object : AccountSession {
            override val account = accounts.first { it.id == accountId }
            override val state = books.getValue(accountId)
            override fun preview(request: OrderRequest) = OrderPreview(null)
            override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(null)
            override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult { writes++; return TradeResult(false, "unused") }
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
            override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
            override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
        }
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }

    @Test fun preservesOldRecordUntilExplicitCompleteAccountReview() = runTest {
        val broker = FakeBroker()
        val store = MemorySnapshotStore()
        val record = CopyLegacyRecoveryRecord(broker.descriptor.id, listOf("leader", "follower"), "MESZ6", "request")
        store.write("copy-unconfirmed-v1", Json.encodeToString(CopyLegacyRecoveryRecord.serializer(), record))
        val recovery = CopyLegacyRecovery(BrokerRegistry(listOf(broker)), store)
        assertEquals(record, recovery.recovery.value)
        broker.books.getValue("leader").value = broker.books.getValue("leader").value.copy(
            positions = listOf(Position("p", "MESZ6", Side.BUY, 100, 10_000, 0, marginMicros = 0)))
        assertNotNull(recovery.clearRecovery(broker))
        assertEquals(record, recovery.recovery.value)
        broker.books.getValue("leader").value = broker.books.getValue("leader").value.copy(positions = emptyList())
        assertNull(recovery.clearRecovery(broker))
        assertNull(recovery.recovery.value)
        assertEquals("", store.read("copy-unconfirmed-v1"))
        assertEquals(0, broker.writes)
    }

    @Test fun unreadableRecordIsNotOverwritten() = runTest {
        val broker = FakeBroker()
        val store = MemorySnapshotStore().apply { write("copy-unconfirmed-v1", "invalid") }
        val recovery = CopyLegacyRecovery(BrokerRegistry(listOf(broker)), store)
        assertTrue(recovery.unreadable)
        assertNotNull(recovery.clearRecovery(broker))
        assertEquals("invalid", store.read("copy-unconfirmed-v1"))
        assertEquals(0, broker.resyncs)
        assertEquals(0, broker.writes)
    }
}
