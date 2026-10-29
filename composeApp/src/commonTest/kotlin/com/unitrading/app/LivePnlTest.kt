package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class RecordingLivePnlPresenter : LivePnlPresenter {
    val shown = mutableListOf<LivePnlUpdate>()
    var cleared = 0
    override fun show(update: LivePnlUpdate) { shown += update }
    override fun clear() { cleared++ }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun controllerMirrorsTheTrackedAccountRateLimitsHeartbeatsAndClears() = runTest {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = true)
        val demo = runtime.brokers.first()
        val session = demo.openSession(demo.accounts.first().id)
        val presenter = RecordingLivePnlPresenter()
        val controller = runtime.livePnl
        assertFalse(controller.available.value)
        val job = launch { controller.run(presenter) { currentTime } }
        runCurrent()
        assertTrue(controller.available.value)
        assertTrue(presenter.shown.isEmpty())
        controller.track(demo, session)
        runCurrent()
        assertEquals(1, presenter.shown.size)
        assertEquals("${demo.descriptor.name} · ${session.account.name}", presenter.shown.single().title)
        assertEquals(session.state.value.floatingPnlMicros, presenter.shown.single().pnlMicros)
        // Open a position so quotes move the floating P&L; bursts of changes collapse into one refresh per second.
        assertTrue(session.placeOrder(requestId(), OrderRequest(demo.instruments.first().symbol, Side.BUY, OrderKind.MARKET, 100)).accepted)
        var refreshes = presenter.shown.size
        var moved = false
        repeat(20) {
            repeat(3) { demo.advance(15) }
            runCurrent()
            assertTrue(presenter.shown.size <= refreshes + 1, "more than one refresh within a second")
            advanceTimeBy(1_000); runCurrent()
            if (presenter.shown.size > refreshes) moved = true
            refreshes = presenter.shown.size
        }
        assertTrue(moved)
        assertEquals(session.state.value.floatingPnlMicros, presenter.shown.last().pnlMicros)
        // Nothing changes: one heartbeat re-posts the same notice so platform expiry timers stay alive.
        val before = presenter.shown.size
        advanceTimeBy(LivePnlController.HEARTBEAT_MS + 1_500); runCurrent()
        assertEquals(before + 1, presenter.shown.size)
        assertEquals(presenter.shown[before - 1].key, presenter.shown.last().key)
        // Turning the notice off clears it and persists; turning it on shows it again.
        assertTrue(controller.setEnabled(false)); runCurrent()
        assertEquals(1, presenter.cleared)
        assertFalse(LivePnlController(store).settings.value.enabled)
        assertTrue(controller.setEnabled(true)); runCurrent()
        assertEquals(before + 2, presenter.shown.size)
        advanceTimeBy(1_000); runCurrent()
        // Untracking another session is ignored; untracking the displayed one clears.
        controller.untrack(demo.openSession(demo.accounts.first().id)); runCurrent()
        assertEquals(1, presenter.cleared)
        controller.untrack(session); runCurrent()
        assertEquals(2, presenter.cleared)
        job.cancelAndJoin()
        assertFalse(controller.available.value)
        assertEquals(3, presenter.cleared)
    }

    @Test fun corruptOrFailingStoreKeepsTheDefaultAndReportsErrors() = runTest {
        val store = object : SnapshotStore {
            override fun read(key: String) = "broken"
            override fun write(key: String, value: String) = error("disk full")
        }
        val controller = LivePnlController(store)
        assertTrue(controller.settings.value.enabled)
        assertNotNull(controller.error.value)
        assertFalse(controller.setEnabled(false))
        assertTrue(controller.settings.value.enabled)
        assertNotNull(controller.error.value)
        val memory = MemorySnapshotStore()
        assertTrue(LivePnlController(memory).setEnabled(false))
        assertEquals("""{"enabled":false}""", memory.read(LivePnlController.KEY))
    }
}
