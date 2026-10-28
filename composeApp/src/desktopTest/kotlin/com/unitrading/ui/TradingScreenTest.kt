package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class TradingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore()).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val session = plugin.openSession(plugin.accounts.first().id)
    private val instrument = plugin.instruments.first()

    private fun render(density: Float = .6f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.fillMaxSize()) { TradingScreen(runtime) }
            }
        }
        compose.waitForIdle()
    }
    @Test fun orderPreviewConfirmationAndOhlcTable() {
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        assertEquals(before, session.state.value.positions.size)
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before + 1 }
        compose.onNodeWithText("数据表").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
    }
    @Test fun chartDragIsPreviewUntilConfirmed() {
        val p = session.state.value.positions.first { it.symbol == instrument.symbol }
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val sl = if (p.side == Side.BUY) q.bid - 400 * instrument.tickSize else q.ask + 400 * instrument.tickSize
        runBlocking { session.modifyProtection("ui-initial-protection", p.id, p.version, sl, p.takeProfit) }
        render()
        val current = session.state.value.positions.first { it.id == p.id }
        val bars = plugin.history(instrument.symbol, Timeframe.M5).takeLast(80)
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val extras = listOfNotNull(q.bid, q.ask, current.entryPrice, current.stopLoss, current.takeProfit)
        val scale = PriceScale.from(bars, extras, 18f * .6f, bounds.height - 30f * .6f)
        val y = scale.y(current.stopLoss!!)
        chart.performTouchInput {
            down(Offset(bounds.width * .55f, y))
            moveBy(Offset(0f, if (p.side == Side.BUY) 6f else -6f))
            moveBy(Offset(0f, if (p.side == Side.BUY) 18f else -18f))
            up()
        }
        compose.onNodeWithTag("protection-dialog").assertExists()
        assertEquals(current.stopLoss, session.state.value.positions.first { it.id == p.id }.stopLoss)
        compose.onNodeWithTag("confirm-protection").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.first { it.id == p.id }.version > current.version }
        assertNotEquals(current.stopLoss, session.state.value.positions.first { it.id == p.id }.stopLoss)
    }
    @Test fun cancelProtectionAndSwitchAccountDiscardDrafts() {
        render()
        val position = session.state.value.positions.first()
        compose.onNodeWithText("保护价").performClick()
        compose.onNodeWithTag("protect-sl").performTextReplacement("1.50000")
        compose.onNodeWithText("取消").performClick()
        assertEquals(position, session.state.value.positions.first())
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.30")
        compose.onNodeWithText(plugin.accounts[1].name).performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        assertEquals(position, session.state.value.positions.first())
    }
    @Test fun closingPositionAddsHistory() {
        render()
        val before = session.state.value
        compose.onNodeWithText("平仓").performClick()
        compose.onNodeWithText("确认平仓").assertExists()
        assertEquals(before.positions, session.state.value.positions)
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before.positions.size - 1 }
        assertEquals(before.trades.size + 1, session.state.value.trades.size)
        compose.onNodeWithTag("terminal-tab-2").performClick()
        compose.onNodeWithText("${money(session.state.value.trades.last().netPnlMicros, true)} USD").assertExists()
    }
    @Test fun switchingBrokerChangesCatalog() {
        render()
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText(runtime.registry.plugins[1].descriptor.name).performClick()
        compose.onNodeWithTag("symbol-BTCUSD").assertExists()
        compose.onNodeWithTag("symbol-EURUSD").assertDoesNotExist()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
    }
    private fun dragEntry(price: Long, direction: Int, draftPrices: List<Long> = emptyList()): Long {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val position = session.state.value.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit) +
            session.state.value.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice } + draftPrices
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .6f, bounds.height - 30f * .6f)
        val y = scale.y(price)
        chart.performTouchInput {
            down(Offset(bounds.width * .55f, y))
            moveBy(Offset(0f, 6f * direction))
            moveBy(Offset(0f, 18f * direction))
            up()
        }
        return scale.price(y + 24f * direction, instrument.tickSize)
    }
    private fun draftEntryDrag(kind: OrderKind) {
        render()
        val before = session.state.value
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask + if (kind == OrderKind.LIMIT) -500 else 500
        compose.onNodeWithText(kind.label()).performClick()
        compose.onNodeWithTag("order-entry").performTextReplacement(instrument.formatPrice(entry))
        val updated = dragEntry(entry, if (kind == OrderKind.LIMIT) 1 else -1, listOf(entry))
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(updated))
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        assertEquals(before.positions, session.state.value.positions)
        assertEquals(before.orders, session.state.value.orders)
    }
    @Test fun limitDraftEntryCanBeDraggedWithoutSubmitting() = draftEntryDrag(OrderKind.LIMIT)
    @Test fun stopDraftEntryCanBeDraggedWithoutSubmitting() = draftEntryDrag(OrderKind.STOP)

    private fun pendingEntryDrag(kind: OrderKind) {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask + if (kind == OrderKind.LIMIT) -500 else 500
        runBlocking { assertTrue(session.placeOrder("ui-pending", OrderRequest(instrument.symbol, Side.BUY, kind, 10, entry)).accepted) }
        val order = session.state.value.orders.single()
        render()
        val updated = dragEntry(entry, if (kind == OrderKind.LIMIT) 1 else -1)
        compose.onNodeWithTag("pending-entry-dialog").assertExists()
        assertEquals(order, session.state.value.orders.single())
        compose.onNodeWithTag("pending-entry").assertTextContains(instrument.formatPrice(updated))
        compose.onNodeWithTag("confirm-pending-entry").performClick()
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 2L }
        assertEquals(order.request.copy(entryPrice = updated), session.state.value.orders.single().request)
    }
    @Test fun limitOrderDragWaitsForConfirmation() = pendingEntryDrag(OrderKind.LIMIT)
    @Test fun stopOrderDragWaitsForConfirmation() = pendingEntryDrag(OrderKind.STOP)
    @Test fun cancelledOrderCannotBeConfirmedAfterDragging() {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        runBlocking { session.placeOrder("ui-pending", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, entry)) }
        val order = session.state.value.orders.single()
        render()
        dragEntry(entry, 1)
        compose.onNodeWithTag("pending-entry-dialog").assertExists()
        runBlocking { session.cancelOrder("cancel-during-edit", order.id) }
        compose.onNodeWithTag("confirm-pending-entry").assertIsNotEnabled()
        assertTrue(session.state.value.orders.isEmpty())
    }
    @Test fun workstationScreenshot() {
        val quote = plugin.market.value.quotes.getValue(instrument.symbol)
        runBlocking {
            session.placeOrder("screenshot-limit", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, quote.ask - 350))
            session.placeOrder("screenshot-stop", OrderRequest(instrument.symbol, Side.BUY, OrderKind.STOP, 10, quote.ask + 300))
        }
        render()
        val image = compose.onRoot().captureToImage().toAwtImage()
        val target = File("../artifacts/desktop-workstation.png")
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
        compose.onNodeWithTag("trading-chart").assertExists()
    }
    @Test fun compactLayoutHasNavigationAndOrderSheet() {
        render(2f)
        compose.onNodeWithTag("nav-0").assertExists()
        assertTrue(compose.onNodeWithTag("trading-chart").fetchSemanticsNode().size.height >= 240, "Short layouts must reserve usable chart height")
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertExists()
        compose.onNodeWithTag("review-order").performScrollTo().assertExists()
        val target = File("../artifacts/compact-order.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
}
