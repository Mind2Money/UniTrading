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
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
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
    private fun skipConfirmations() = runBlocking { assertTrue(runtime.tradingPreferences.setSkipConfirmation(true)) }

    @Test fun directOrderCloseAndCancelExecuteWithoutConfirmation() {
        skipConfirmations()
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("review-order").assertTextContains("模拟买入").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before + 1 }
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        compose.onAllNodesWithText("平仓").onFirst().performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before }
        compose.onNodeWithText("确认平仓").assertDoesNotExist()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        runBlocking { assertTrue(session.placeOrder("direct-cancel-fixture", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, q.ask - 500)).accepted) }
        val order = session.state.value.orders.single()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-${order.id}")
        compose.onNodeWithText("撤单").performClick()
        compose.waitUntil(5_000) { session.state.value.orders.isEmpty() }
        compose.onNodeWithText("确认撤单").assertDoesNotExist()
    }

    @Test fun directPendingEntryAndProtectionDropsApplyWithoutEditors() {
        skipConfirmations()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        runBlocking { assertTrue(session.placeOrder("direct-pending-fixture", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, entry, entry - 400, entry + 800)).accepted) }
        render()
        val updated = dragLine(entry, 1)
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 2L }
        assertEquals(updated, session.state.value.orders.single().request.entryPrice)
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        val stop = dragLine(entry - 400, 1)
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 3L }
        assertEquals(stop, session.state.value.orders.single().request.stopLoss)
        compose.onNodeWithTag("pending-protection-dialog").assertDoesNotExist()
        // Manual editing still needs an input form; merely opening it must not submit.
        val order = session.state.value.orders.single()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-${order.id}")
        compose.onNodeWithTag("edit-order-${order.id}").performClick()
        compose.onNodeWithTag("pending-entry-dialog").assertExists()
        assertEquals(order, session.state.value.orders.single())
    }

    @Test fun directPositionProtectionDropAppliesOnceReleased() {
        skipConfirmations()
        val p = session.state.value.positions.first { it.symbol == instrument.symbol }
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val sl = if (p.side == Side.BUY) q.bid - 400 * instrument.tickSize else q.ask + 400 * instrument.tickSize
        runBlocking { assertTrue(session.modifyProtection("direct-position-fixture", p.id, p.version, sl, p.takeProfit).accepted) }
        val before = session.state.value.positions.first { it.id == p.id }
        render()
        val updated = dragLine(sl, if (p.side == Side.BUY) 1 else -1, release = false)
        assertEquals(before.version, session.state.value.positions.first { it.id == p.id }.version)
        compose.onNodeWithTag("trading-chart").performTouchInput { up() }
        compose.waitUntil(5_000) { session.state.value.positions.first { it.id == p.id }.version > before.version }
        assertEquals(updated, session.state.value.positions.first { it.id == p.id }.stopLoss)
        compose.onNodeWithTag("protection-dialog").assertDoesNotExist()
    }

    @Test fun theOrderFormRefusesProtectionAgainstItsDirection() {
        render()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("review-order").assertIsEnabled()
        compose.onNodeWithTag("order-sl").performTextReplacement(instrument.formatPrice(q.bid))
        compose.onNodeWithTag("order-error").assertTextContains("买入单的止损须低于当前 Bid ${instrument.formatPrice(q.bid)}")
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
    }
    @Test fun orderPreviewConfirmationAndOhlcTable() {
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        assertEquals(before, session.state.value.positions.size)
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before + 1 }
        compose.onNodeWithTag("chart-layers").performClick()
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
        compose.onNodeWithText("止损/止盈").performClick()
        compose.onNodeWithTag("protect-sl").performTextReplacement("1.50000")
        compose.onNodeWithText("取消").performClick()
        assertEquals(position, session.state.value.positions.first())
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.30")
        compose.onNodeWithTag("quick-account-picker").performClick()
        compose.onNodeWithTag("quick-account-${plugin.accounts[1].id}").performClick()
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
        compose.onNodeWithTag("trade-terminal").performScrollToKey("trade-${session.state.value.trades.last().id}")
        compose.onNodeWithText("${money(requireNotNull(session.state.value.trades.last().netPnlMicros), true)} USD").assertExists()
    }
    @Test fun switchingBrokerChangesCatalog() {
        render()
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${runtime.registry.plugins[1].descriptor.id}").performClick()
        compose.onNodeWithTag("symbol-BTCUSD").assertExists()
        compose.onNodeWithTag("symbol-EURUSD").assertDoesNotExist()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
    }
    /** Drags the line at [price] 24px down (direction 1) or up (-1); the chart's auto scale includes [draftPrices]. */
    private fun dragLine(price: Long, direction: Int, draftPrices: List<Long> = emptyList(), release: Boolean = true): Long {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val position = session.state.value.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit) +
            session.state.value.orders.filter { it.request.symbol == instrument.symbol }.flatMap { order ->
                listOfNotNull(order.request.entryPrice) + if (order.role == OrderRole.ENTRY) listOfNotNull(order.request.stopLoss, order.request.takeProfit) else emptyList()
            } + draftPrices
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .6f, bounds.height - 30f * .6f)
        val y = scale.y(price)
        chart.performTouchInput {
            down(Offset(bounds.width * .55f, y))
            moveBy(Offset(0f, 6f * direction))
            moveBy(Offset(0f, 18f * direction))
            if (release) up()
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
        val updated = dragLine(entry, if (kind == OrderKind.LIMIT) 1 else -1, listOf(entry))
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
        val updated = dragLine(entry, if (kind == OrderKind.LIMIT) 1 else -1)
        compose.onNodeWithTag("pending-entry-dialog").assertExists()
        assertEquals(order, session.state.value.orders.single())
        compose.onNodeWithTag("pending-entry").assertTextContains(instrument.formatPrice(updated))
        compose.onNodeWithTag("confirm-pending-entry").performClick()
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 2L }
        assertEquals(order.request.copy(entryPrice = updated), session.state.value.orders.single().request)
    }
    @Test fun limitOrderDragWaitsForConfirmation() = pendingEntryDrag(OrderKind.LIMIT)
    @Test fun stopOrderDragWaitsForConfirmation() = pendingEntryDrag(OrderKind.STOP)
    @Test fun draftStopLossDragShowsItsResultAndOnlyChangesTheTicket() {
        render()
        val before = session.state.value
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        val stop = entry - 400
        compose.onNodeWithText(OrderKind.LIMIT.label()).performClick()
        compose.onNodeWithTag("order-entry").performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl").performTextReplacement(instrument.formatPrice(stop))
        val updated = dragLine(stop, 1, listOf(entry, entry, stop), release = false)
        compose.onNodeWithTag("chart-readout").assertTextContains("止损预览 ${instrument.formatPrice(updated)}", substring = true)
        compose.onNodeWithTag("chart-readout").assertTextContains("USD", substring = true)
        compose.onNodeWithTag("trading-chart").performTouchInput { up() }
        compose.onNodeWithTag("order-sl").assertTextContains(instrument.formatPrice(updated))
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(entry))
        compose.onNodeWithTag("pending-protection-dialog").assertDoesNotExist()
        assertEquals(before.positions, session.state.value.positions)
        assertEquals(before.orders, session.state.value.orders)
    }
    @Test fun pendingStopLossDragWaitsForConfirmation() {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        runBlocking { assertTrue(session.placeOrder("ui-pending-sl", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, entry, entry - 400, entry + 800)).accepted) }
        val order = session.state.value.orders.single()
        render()
        val updated = dragLine(entry - 400, 1)
        compose.onNodeWithTag("pending-protection-dialog").assertExists()
        compose.onNodeWithTag("pending-entry-dialog").assertDoesNotExist()
        assertEquals(order, session.state.value.orders.single())
        compose.onNodeWithTag("pending-protect-sl").assertTextContains(instrument.formatPrice(updated))
        compose.onNodeWithTag("pending-protect-tp").assertTextContains(instrument.formatPrice(entry + 800))
        compose.onNodeWithTag("confirm-pending-protection").performClick()
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 2L }
        assertEquals(order.request.copy(stopLoss = updated), session.state.value.orders.single().request)
    }
    @Test fun pendingOrderRowOpensItsStopAndTarget() {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        runBlocking { assertTrue(session.placeOrder("ui-pending-row", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, entry)).accepted) }
        val order = session.state.value.orders.single()
        render()
        compose.onNodeWithTag("trade-terminal").performScrollToKey("order-${order.id}")
        compose.onNodeWithTag("protect-order-${order.id}").performClick()
        compose.onNodeWithTag("pending-protect-tp").performTextReplacement(instrument.formatPrice(entry + 700))
        compose.onNode(hasText("+", substring = true) and hasAnyAncestor(hasTestTag("pending-protection-dialog"))).assertExists()
        compose.onNodeWithTag("confirm-pending-protection").performClick()
        compose.waitUntil(5_000) { session.state.value.orders.single().version == 2L }
        assertEquals(order.request.copy(takeProfit = entry + 700), session.state.value.orders.single().request)
    }
    @Test fun cancelledOrderCannotBeConfirmedAfterDragging() {
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        runBlocking { session.placeOrder("ui-pending", OrderRequest(instrument.symbol, Side.BUY, OrderKind.LIMIT, 10, entry)) }
        val order = session.state.value.orders.single()
        render()
        dragLine(entry, 1)
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
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        val target = File("../artifacts/compact-order.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    @Test fun legendFloatsOverChartAndTradeButtonsPickSide() {
        render()
        val chart = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        val legend = compose.onNodeWithTag("chart-legend").fetchSemanticsNode().boundsInRoot
        assertTrue(legend.left >= chart.left && legend.top >= chart.top && legend.right <= chart.right && legend.bottom <= chart.bottom, "Legend must float inside the chart, not in a separate banner")
        assertTrue(legend.top - chart.top < 80f, "Legend sits at the top-left corner of the plot")
        compose.onNodeWithTag("chart-readout").assertExists()
        compose.onNodeWithTag("legend-sell").performClick()
        compose.onNode(hasText(Side.SELL.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("legend-buy").performClick()
        compose.onNode(hasText(Side.BUY.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("chart-readout").assertExists()
    }
    @Test fun chartReopensLastSymbolAndIntervalAndPicksFromTheWatchList() {
        val store = MemorySnapshotStore()
        val demo = TradingRuntime(store, includeDemoBrokers = true).registry.plugins.first()
        val (watchedFirst, unwatched, watchedLast) = demo.instruments.take(3)
        store.write("market-watch-v1", """{"${demo.descriptor.id}":["${watchedLast.symbol}","${watchedFirst.symbol}"]}""")
        store.write("chart-v1", """{"lastSymbols":{"${demo.descriptor.id}":"${watchedLast.symbol}"},"lastTimeframeMinutes":15}""")
        val reopened = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) { Box(Modifier.fillMaxSize()) { TradingScreen(reopened) } }
        }
        compose.onNodeWithText("${watchedLast.name} · 15分钟 · ${watchedLast.category}").assertExists()
        compose.onNodeWithTag("chart-symbol").performClick()
        val last = compose.onNodeWithTag("symbol-option-${watchedLast.symbol}").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("symbol-option-${watchedFirst.symbol}").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top < first.top, "the menu keeps the watch list's order")
        compose.onNodeWithTag("symbol-option-${unwatched.symbol}").assertDoesNotExist()
        compose.onNodeWithTag("symbol-option-${watchedFirst.symbol}").performClick()
        compose.onNodeWithTag("interval-menu").performClick()
        compose.onNodeWithTag("interval-option-60").performClick()
        compose.waitUntil(5_000) {
            reopened.chartPreferences.preferences.value.let { it.lastSymbols[demo.descriptor.id] == watchedFirst.symbol && it.lastTimeframeMinutes == 60 }
        }
        // Stored immediately, so even an abrupt exit reopens here.
        val saved = TradingRuntime(store).chartPreferences.preferences.value
        assertEquals(watchedFirst.symbol, saved.lastSymbols[demo.descriptor.id])
        assertEquals(Timeframe(60), saved.lastTimeframe)
    }

    @Test fun intervalMenuOffersPresetsFavouritesCustomIntervalsAndSymbols() {
        render()
        compose.onNodeWithTag("interval-menu").performClick()
        compose.onNodeWithTag("interval-option-240").performScrollTo().performClick()
        compose.onNodeWithText("${instrument.name} · 4小时 · ${instrument.category}").assertExists()
        compose.onNodeWithTag("interval-inline-240").assertExists()
        compose.onNodeWithTag("interval-menu").performClick()
        compose.onNodeWithTag("interval-favorite-240").performClick()
        compose.waitUntil(5_000) { 240 in runtime.chartPreferences.preferences.value.favoriteMinutes }
        compose.onNodeWithTag("interval-custom-count").performTextReplacement("7")
        compose.onNodeWithTag("interval-custom-add").performClick()
        compose.onNodeWithText("${instrument.name} · 7分钟 · ${instrument.category}").assertExists()
        compose.waitUntil(5_000) { 7 in runtime.chartPreferences.preferences.value.customMinutes }
        val other = plugin.instruments[1]
        compose.onNodeWithTag("chart-symbol").performClick()
        compose.onNodeWithTag("symbol-filter").performTextReplacement(other.symbol.take(3))
        compose.onNodeWithTag("symbol-option-${other.symbol}").performClick()
        compose.onNodeWithText("${other.name} · 7分钟 · ${other.category}").assertExists()
        compose.onNodeWithTag("order-quantity").assertExists()
    }
}
