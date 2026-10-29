package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class RecordingLivePnlPresenter : LivePnlPresenter {
    val shown = mutableListOf<LivePnlUpdate>()
    var cleared = 0
    override fun show(update: LivePnlUpdate) { shown += update }
    override fun clear() { cleared++ }
}

/** Account whose snapshot the test sets directly; no engine, clock or randomness involved. */
private class FakeSession(override val account: AccountInfo, balanceMicros: Long) : AccountSession {
    override val state = MutableStateFlow(AccountSnapshot(account.id, balanceMicros))
    override fun preview(request: OrderRequest) = OrderPreview(null)
    override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(null)
    override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unsupported")
    override suspend fun placeOrder(requestId: String, request: OrderRequest) = TradeResult(false, "unsupported")
    override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unsupported")
    override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unsupported")
    override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unsupported")
}

private class FakePlugin : BrokerPlugin {
    override val descriptor = BrokerDescriptor("fake", "Fake", "test double")
    override val capabilities = BrokerCapabilities()
    override val instruments = emptyList<Instrument>()
    override val accounts = listOf(AccountInfo("a1", "主账户"))
    override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
    override val market = MutableStateFlow(MarketSnapshot())
    override fun openSession(accountId: String): AccountSession = FakeSession(accounts.first { it.id == accountId }, BALANCE)
    override fun history(symbol: String, timeframe: Timeframe) = emptyList<Candle>()
    companion object { const val BALANCE = 10_000_000_000L }
}

class LivePnlTest {
    private val broker = BrokerDescriptor("atlas", "Atlas", "test")
    private val account = AccountInfo("a1", "主账户")
    private val position = Position("p1", "EURUSD", Side.BUY, 10, 1_087_100, 0, marginMicros = 50_000_000)

    @Test fun updateFormatsFloatingPnlEquityPositionsAndConnection() {
        val profit = LivePnlUpdate.from(broker, account, AccountSnapshot("a1", balanceMicros = 10_000_000_000, equityMicros = 10_128_500_000, positions = listOf(position)), connected = true, nowMs = 5)
        assertEquals("Atlas · 主账户", profit.title)
        assertEquals("+128.50 USD", profit.pnlText)
        assertEquals(128_500_000, profit.pnlMicros)
        assertEquals(1, profit.trend)
        assertEquals("净值 10128.50 · 持仓 1", profit.detail)
        assertEquals(5, profit.timeMs)
        val loss = LivePnlUpdate.from(broker, account, AccountSnapshot("a1", balanceMicros = 10_000_000_000, equityMicros = 9_935_750_000), connected = false, nowMs = 6)
        assertEquals("-64.25 USD", loss.pnlText)
        assertEquals(-1, loss.trend)
        assertEquals("净值 9935.75 · 持仓 0 · 已断开", loss.detail)
        assertNotEquals(profit.key, loss.key)
        assertEquals(profit.key, profit.copy(timeMs = 99).key)
        val unknown = LivePnlUpdate.from(broker, account, AccountSnapshot("a1", balanceMicros = 1, equityKnown = false), connected = true, nowMs = 7)
        assertEquals("—", unknown.pnlText)
        assertNull(unknown.pnlMicros)
        assertEquals(0, unknown.trend)
        assertEquals("净值未知 · 持仓 0", unknown.detail)
        val estimated = LivePnlUpdate.from(broker, account, AccountSnapshot("a1", balanceMicros = 2_000_000, equityEstimated = true), connected = true, nowMs = 8)
        assertEquals("0.00 USD", estimated.pnlText)
        assertEquals(0, estimated.trend)
        assertEquals("净值 2.00（估算） · 持仓 0", estimated.detail)
    }

    /** Everything runs on the test scheduler: the store writer too, so no real thread hop races the virtual clock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun controllerMirrorsTheTrackedAccountRateLimitsHeartbeatsAndClears() = runTest {
        val store = MemorySnapshotStore()
        val controller = LivePnlController(store, StandardTestDispatcher(testScheduler))
        val plugin = FakePlugin()
        val session = plugin.openSession("a1") as FakeSession
        val presenter = RecordingLivePnlPresenter()
        fun snapshot(equityMicros: Long) = AccountSnapshot("a1", FakePlugin.BALANCE, equityMicros, positions = listOf(position))
        assertFalse(controller.available.value)
        val job = launch { controller.run(presenter) { currentTime } }
        runCurrent()
        assertTrue(controller.available.value)
        assertTrue(presenter.shown.isEmpty())
        controller.track(plugin, session)
        runCurrent()
        assertEquals(1, presenter.shown.size)
        assertEquals("Fake · 主账户", presenter.shown.single().title)
        assertEquals("0.00 USD", presenter.shown.single().pnlText)
        assertEquals(0, presenter.shown.single().timeMs)
        // A burst within one second collapses into a single refresh carrying the latest values.
        session.state.value = snapshot(FakePlugin.BALANCE + 50_000_000)
        session.state.value = snapshot(FakePlugin.BALANCE + 100_000_000)
        session.state.value = snapshot(FakePlugin.BALANCE + 128_500_000)
        runCurrent()
        assertEquals(1, presenter.shown.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, presenter.shown.size)
        assertEquals("+128.50 USD", presenter.shown.last().pnlText)
        assertEquals("净值 10128.50 · 持仓 1", presenter.shown.last().detail)
        // Losing the market connection changes the text as well.
        plugin.market.value = MarketSnapshot(connected = false)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, presenter.shown.size)
        assertFalse(presenter.shown.last().connected)
        assertTrue(presenter.shown.last().detail.endsWith("已断开"))
        // Nothing changes: one heartbeat re-posts the same notice so platform expiry timers stay alive.
        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(LivePnlController.HEARTBEAT_MS); runCurrent()
        assertEquals(4, presenter.shown.size)
        assertEquals(presenter.shown[2].key, presenter.shown[3].key)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, presenter.shown.size)
        // Turning the notice off clears it and persists; turning it on shows it again.
        assertTrue(controller.setEnabled(false)); runCurrent()
        assertEquals(1, presenter.cleared)
        assertFalse(LivePnlController(store).settings.value.enabled)
        assertTrue(controller.setEnabled(true)); runCurrent()
        assertEquals(5, presenter.shown.size)
        advanceTimeBy(1_000); runCurrent()
        // Untracking another session is ignored; untracking the displayed one clears.
        controller.untrack(plugin.openSession("a1")); runCurrent()
        assertEquals(1, presenter.cleared)
        controller.untrack(session); runCurrent()
        assertEquals(2, presenter.cleared)
        job.cancelAndJoin()
        assertFalse(controller.available.value)
        assertEquals(3, presenter.cleared)
    }

    @Test fun runtimeControllerMirrorsADemoSessionWithTheDefaultWriter() = runTest {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        val demo = runtime.brokers.first()
        val session = demo.openSession(demo.accounts.first().id)
        val presenter = RecordingLivePnlPresenter()
        val job = launch { runtime.livePnl.run(presenter) { currentTime } }
        runCurrent()
        runtime.livePnl.track(demo, session)
        runCurrent()
        assertEquals("${demo.descriptor.name} · ${session.account.name}", presenter.shown.single().title)
        assertEquals(session.state.value.floatingPnlMicros, presenter.shown.single().pnlMicros)
        job.cancelAndJoin()
        assertEquals(1, presenter.cleared)
    }

    @Test fun corruptOrFailingStoreKeepsTheDefaultAndReportsErrors() = runTest {
        val store = object : SnapshotStore {
            override fun read(key: String) = "broken"
            override fun write(key: String, value: String) = error("disk full")
        }
        val controller = LivePnlController(store, StandardTestDispatcher(testScheduler))
        assertTrue(controller.settings.value.enabled)
        assertNotNull(controller.error.value)
        assertFalse(controller.setEnabled(false))
        assertTrue(controller.settings.value.enabled)
        assertNotNull(controller.error.value)
        val memory = MemorySnapshotStore()
        assertTrue(LivePnlController(memory, StandardTestDispatcher(testScheduler)).setEnabled(false))
        assertEquals("""{"enabled":false}""", memory.read(LivePnlController.KEY))
    }
}
