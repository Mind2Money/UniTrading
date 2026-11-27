package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CopyTradingControllerTest {
    private class FakeBroker(private val mode: CommandOutcome = CommandOutcome.ACCEPTED) : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-test", "Demo", "test", environment = BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities(hedging = false)
        override val accounts = listOf(AccountInfo("leader", "主账户"), AccountInfo("follower", "跟随账户"))
        override val instruments = listOf(Instrument("MESZ6", "MES", "futures", 2, tickValueMicrosPerLot = 1))
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot())
        private var revision = 1L
        val snapshots = accounts.associate { it.id to MutableStateFlow(AccountSnapshot(it.id, 0, copyRevision = revision, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersClear = true)) }
        val writes = mutableListOf<OrderRequest>()

        fun change(id: String, position: Int, execution: Execution? = null) {
            val rows = snapshots.mapValues { it.value.value }.toMutableMap()
            val value = rows.getValue(id)
            val positions = if (position == 0) emptyList() else listOf(Position("$id:p", "MESZ6", if (position > 0) Side.BUY else Side.SELL,
                kotlin.math.abs(position).toLong() * 100, 100, 0, marginMicros = 0))
            rows[id] = value.copy(positions = positions, executions = value.executions + listOfNotNull(execution))
            revision++
            rows.forEach { (key, row) -> snapshots.getValue(key).value = row.copy(copyRevision = revision) }
        }

        fun simulateResync() {
            revision++
            snapshots.values.forEach { it.value = it.value.copy(copyEpoch = 2, copyRevision = revision) }
        }
        override suspend fun resynchronize() = simulateResync()

        override fun openSession(accountId: String): AccountSession {
            val info = accounts.first { it.id == accountId }
            return object : AccountSession {
                override val account = info
                override val state = snapshots.getValue(accountId)
                override fun preview(request: OrderRequest) = OrderPreview(100)
                override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult {
                    writes.add(request)
                    if (request.copyExpectedRevision != revision) return TradeResult(false, "revision mismatch")
                    if (mode != CommandOutcome.ACCEPTED) return TradeResult(false, "unknown", outcome = mode)
                    val prior = state.value.positions.singleOrNull()?.let { (it.lotsHundredths / 100).toInt() * if (it.side == Side.BUY) 1 else -1 } ?: 0
                    val delta = (request.lotsHundredths / 100).toInt() * if (request.side == Side.BUY) 1 else -1
                    change(accountId, prior + delta, Execution("fill-${writes.size}", request.symbol, request.side, request.lotsHundredths, 100, 0, "order-${writes.size}"))
                    return TradeResult(true, "accepted", "order-${writes.size}")
                }
                override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(entryPrice)
                override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
                override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
                override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
                override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
            }
        }
        override fun history(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    }

    private val settings = CopyPlanSettings("leader", listOf(CopyFollower("follower", 1, 2)), "MESZ6")

    @Test fun copiesOnlyConfirmedChangesAndNeverDuplicatesAnOrder() = runTest {
        val broker = FakeBroker()
        val controller = CopyTradingController(BrokerRegistry(listOf(broker)), AccountRiskController(MemorySnapshotStore()), MemorySnapshotStore())
        assertNull(controller.start(broker, settings))
        val monitor = backgroundScope.launch { controller.monitor() }
        broker.change("leader", 1)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, broker.writes.size)
        assertTrue(broker.writes.single().automated)
        assertNotNull(broker.writes.single().copyExpectedRevision)
        assertEquals(100L, broker.snapshots.getValue("follower").value.positions.single().lotsHundredths)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, broker.writes.size)
        broker.change("leader", 0)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, broker.writes.size)
        assertTrue(broker.snapshots.getValue("follower").value.positions.isEmpty())
        monitor.cancelAndJoin()
        assertFalse(controller.status.value.running)
    }

    @Test fun unknownOutcomeStopsWithoutRetry() = runTest {
        val broker = FakeBroker(CommandOutcome.UNKNOWN)
        val store = MemorySnapshotStore()
        val controller = CopyTradingController(BrokerRegistry(listOf(broker)), AccountRiskController(store), store)
        assertNull(controller.start(broker, settings))
        backgroundScope.launch { controller.monitor() }
        broker.change("leader", 1)
        advanceTimeBy(4_000); runCurrent()
        assertFalse(controller.status.value.running)
        assertEquals(1, broker.writes.size)
        val reopened = CopyTradingController(BrokerRegistry(listOf(broker)), AccountRiskController(store), store)
        assertNotNull(reopened.recovery.value)
        assertNotNull(reopened.start(broker, settings))
        broker.change("leader", 0)
        assertNull(reopened.clearRecovery(broker))
        assertNull(reopened.recovery.value)
    }

    @Test fun disconnectStopsAndDoesNotResume() = runTest {
        val broker = FakeBroker()
        val controller = CopyTradingController(BrokerRegistry(listOf(broker)), AccountRiskController(MemorySnapshotStore()), MemorySnapshotStore())
        assertNull(controller.start(broker, settings))
        backgroundScope.launch { controller.monitor() }
        broker.market.value = MarketSnapshot(connected = false, tradingReady = false)
        advanceTimeBy(1_000); runCurrent()
        assertFalse(controller.status.value.running)
        broker.market.value = MarketSnapshot()
        broker.change("leader", 1)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(broker.writes.isEmpty())
    }

    @Test fun aResynchronizationStopsRatherThanSilentlyResuming() = runTest {
        val broker = FakeBroker()
        val controller = CopyTradingController(BrokerRegistry(listOf(broker)), AccountRiskController(MemorySnapshotStore()), MemorySnapshotStore())
        assertNull(controller.start(broker, settings))
        backgroundScope.launch { controller.monitor() }
        broker.simulateResync()
        advanceTimeBy(1_000); runCurrent()
        assertFalse(controller.status.value.running)
        broker.change("leader", 1)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(broker.writes.isEmpty())
    }
}
