package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.OrderPanelLayout
import com.unitrading.app.TradingPreferencesController
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * The docked order page of phones, chosen in the settings before every test: the dock's own versions of the order
 * tests in [MobileLayoutTest], and what only the dock has, which is its own space beside the chart and "更多设置".
 */
@OptIn(ExperimentalTestApi::class)
class MobileOrderDockTest {
    private lateinit var compose: ComposeUiTest

    private fun phoneTest(width: Int = 300, height: Int = 600, block: () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            compose = this
            block()
        }
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val session = plugin.openSession(plugin.accounts.first().id)
    private var shown by mutableStateOf(runtime)

    private fun render(width: Dp = 400.dp, height: Dp = 800.dp, fontScale: Float = 1f, first: TradingRuntime = runtime) {
        runBlocking { assertTrue(first.tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) }
        shown = first
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f, fontScale)) {
            Box(Modifier.requiredSize(width, height).testTag("test-host")) { key(shown) { TradingScreen(shown) } }
        } }
        compose.waitForIdle()
    }
    /** The app as it starts again on what it saved. */
    private fun restart() {
        compose.runOnIdle { shown = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    /** Shown whole, with nothing of it scrolled out of the form. */
    private fun assertShownWhole(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f, "$tag is cut by the edge of the form")
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun portraitDockTakesItsOwnSpaceBelowTheChartAndGivesItBackWhenClosed() = phoneTest {
        render()
        val host = height("test-host")
        val closed = height("trading-chart")
        assertTrue(closed >= host * .66f, "chart $closed px of $host px")
        compose.onNodeWithTag("order-quantity").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()

        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(hasText(Side.SELL.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("mobile-navigation").assertDoesNotExist()
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        compose.onNodeWithTag("order-sheet-handle", useUnmergedTree = true).assertDoesNotExist()
        // The account bar above the chart is folded away while the dock is open, so the dock names the account.
        compose.onNodeWithTag("order-dock").assert(hasAnyDescendant(hasText(plugin.accounts.first().name)))
        assertTrue(height("trading-chart") in (host * .4f).toInt()..(host * .6f).toInt(), "chart ${height("trading-chart")} px of $host px")
        assertTrue(bounds("trading-chart").bottom <= bounds("order-dock").top, "the dock does not cover the chart")
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity").assertIsDisplayed()
        capture("mobile-dock-order-panel")

        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        compose.waitForIdle()
        assertEquals(closed, height("trading-chart"), "closing gives the chart the height it had before the order")
    }

    @Test fun stopLossAndTakeProfitAreShownWithoutUnfoldingAndOnlyTheRestWaitsBehindMoreSettings() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        // A market order with no protection yet: everything the folded dock offers is on screen without scrolling.
        for (tag in listOf("order-side-BUY", "order-quantity", "order-sl-enabled", "order-tp-enabled", "order-more")) assertShownWhole(tag)
        compose.onNodeWithTag("order-sl-enabled").assertIsOff().performClick()
        compose.onNodeWithTag("order-tp-enabled").assertIsOff().performClick()
        compose.onNodeWithTag("order-sl").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order-tp").performScrollTo().assertIsDisplayed()
        assertFalse(runtime.tradingPreferences.preferences.value.orderMoreSettings)
        for (tag in listOf("order-quantity-slider", "reference-protection", "clear-protection", "order-estimates-toggle", "order-summary"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("order-more").performScrollTo().assertTextEquals("更多设置")
        val folded = height("order-dock")
        capture("mobile-dock-protection-folded")

        compose.onNodeWithTag("order-more").performClick()
        compose.onNodeWithTag("order-more").assertTextEquals("收起更多设置")
        assertTrue(height("order-dock") > folded, "unfolded, the dock takes more of the screen")
        for (tag in listOf("order-quantity-slider", "reference-protection", "clear-protection", "order-estimates-toggle"))
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order-summary").assertIsDisplayed()
        compose.onNodeWithTag("order-sl").performScrollTo().assertIsDisplayed()
        capture("mobile-dock-more-settings")

        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
        compose.onNodeWithTag("order-summary").assertDoesNotExist()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn()
        compose.onNodeWithTag("order-tp-enabled").assertIsOn()
        assertEquals(folded, height("order-dock"))
    }

    @Test fun moreSettingsStayAsLeftForTheNextOrderAndAfterARestart() = phoneTest {
        render()
        val positions = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) { TradingPreferencesController(store).preferences.value.orderMoreSettings }

        // Discarding the order and starting another keeps the fold open.
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("order-more").performScrollTo().assertTextEquals("收起更多设置")
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().assertIsDisplayed()

        // So does an order that was placed.
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.positions.size == positions + 1 }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("order-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("close-order").performClick()

        restart()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-dock").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().assertIsDisplayed()

        // Folding it is remembered the same way.
        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) { !TradingPreferencesController(store).preferences.value.orderMoreSettings }
        compose.onNodeWithTag("close-order").performClick()
        restart()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-more").performScrollTo().assertTextEquals("更多设置")
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
    }

    @Test fun dockTitleNamesTheContinuousCodeWithTheContractThatTradesOrWhyThereIsNone() = phoneTest {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val dated = demo.instruments.first()
        val alias = dated.copy(symbol = "@TEST", isContinuous = true, tradeSymbol = dated.symbol)
        val changes = MutableStateFlow(BrokerCatalog(listOf(alias) + demo.instruments, demo.accounts))
        val continuous = object : BrokerPlugin by demo {
            override val catalog = changes
            override val instruments get() = changes.value.instruments
            override fun history(symbol: String, timeframe: Timeframe) = demo.history(dated.symbol, timeframe)
        }
        render(first = TradingRuntime(MemorySnapshotStore(), listOf(continuous)).apply { paused.value = true })
        compose.onNodeWithTag("open-order").performClick()
        val title = compose.onNodeWithTag("order-dock-title", useUnmergedTree = true)
        title.assertTextEquals("@TEST · ${dated.symbol}").assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsEnabled()
        capture("mobile-dock-continuous-title")
        // Minimized, the bar still names both.
        compose.onNodeWithTag("minimize-order").performClick()
        title.assertTextEquals("@TEST · ${dated.symbol}").assertIsDisplayed()
        compose.onNodeWithTag("close-order").performClick()

        // While the front month is not confirmed the title keeps the chart's code and says what is missing.
        compose.runOnIdle {
            changes.value = changes.value.copy(instruments = listOf(alias.copy(tradeSymbol = null, tradeSymbolStatus = "正在核实主力 2/5")) + demo.instruments)
        }
        compose.onNodeWithTag("open-order").performClick()
        title.assertTextEquals("@TEST · 正在核实主力 2/5").assertIsDisplayed()
        compose.onNodeWithTag("order-error").assertTextEquals("正在核实主力 2/5，暂不可下单")
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.onNodeWithTag("close-order").performClick()

        compose.runOnIdle { changes.value = changes.value.copy(instruments = listOf(alias.copy(tradeSymbol = null)) + demo.instruments) }
        compose.onNodeWithTag("open-order").performClick()
        title.assertTextEquals("@TEST · 主力待确认")
    }

    @Test fun minimizeAndRestorePreserveTheDraft() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("order-type-strip").assertIsDisplayed()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
    }

    @Test fun chartTapDoesNotDismissTheDockAndCloseDiscardsTheDraft() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("trading-chart").performTouchInput { click(Offset(5f, 60f)) }
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-sl").performClick().assertIsOn()
        compose.onNodeWithTag("order-kind-LIMIT").performClick().assertIsSelected()
        compose.onNodeWithTag("order-peek-side-SELL").performClick().assertIsSelected()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn()
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
    }

    @Test fun confirmedOrderClosesTheDock() = phoneTest {
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.positions.size == before + 1 }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("order-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mobile-navigation").assertExists()
    }

    @Test fun compactArrowSubmitsImmediatelyWhenConfirmationIsSkipped() = phoneTest {
        runBlocking { runtime.tradingPreferences.setSkipConfirmation(true) }
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("review-order").assertTextContains("模拟买入")
        compose.onNodeWithTag("order-quantity").performTextReplacement("wrong")
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-submit-order").assertIsNotEnabled()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-submit-order").assertContentDescriptionEquals("立即下单").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.positions.size == before + 1 }
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun compactArrowConfirmsTheCurrentDraftWithoutExpandingTheEditor() = phoneTest {
        render()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-submit-order").assertIsEnabled().performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        compose.onNodeWithText("${plugin.instruments.first().symbol} · 卖出限价 · 0.23 手").assertExists()
        assertEquals(before, session.state.value, "The arrow uses the existing confirmation flow")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("compact-sl").assertIsOn()
        compose.onNodeWithTag("compact-submit-order").performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.orders.size == before.orders.size + 1 }
        val sent = session.state.value.orders.last().request
        assertEquals(Side.SELL, sent.side)
        assertEquals(OrderKind.LIMIT, sent.kind)
        assertEquals(23L, sent.lotsHundredths)
        assertNotNull(sent.stopLoss)
        compose.onNodeWithTag("minimized-order").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun draggingTheDraftEntryBringsBackTheLegendWithItsEstimate() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        compose.onNodeWithTag("order-entry").performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("legend-toggle").performClick()
        val position = session.state.value.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit, entry) +
            session.state.value.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice }
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, size.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(size.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)) }
        compose.onNodeWithTag("chart-readout").assertTextContains("入场预览", substring = true)
        chart.performTouchInput { up() }
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
    }

    @Test fun minimizedDraftKeepsChartEditsAndCloseStartsAFreshOrder() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        compose.onNodeWithTag("order-entry").performScrollTo().performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("minimized-order").assertExists()
        compose.onNodeWithTag("mobile-navigation").assertDoesNotExist()
        capture("mobile-dock-order-minimized")
        // Turning protection off before the drag gives a deterministic price scale.
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        val position = before.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit, entry) +
            before.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice }
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, size.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(size.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)); up() }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("minimized-order").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("order-kind-MARKET").assertIsSelected()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOff()
        compose.onNodeWithTag("order-tp-enabled").assertIsOff()
        assertEquals(before, session.state.value, "Draft lifecycle and chart edits must never send orders")
    }

    @Test fun minimizingTheDockReturnsSpaceToTheChart() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        val before = bounds("trading-chart")
        compose.onNodeWithTag("minimize-order").performClick()
        assertTrue(bounds("trading-chart").height > before.height)
    }

    @Test fun openingMinimizingAndClosingKeepTheChartViewportAndLayers() = phoneTest {
        render()
        fun viewport() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription].substringBefore(";low:")
        val initial = viewport()
        compose.onNodeWithTag("trading-chart").performTouchInput {
            swipe(Offset(width * .25f, height - 8f), Offset(width * .65f, height - 8f))
        }
        val zoomed = viewport()
        assertNotEquals(initial, zoomed)
        compose.onNodeWithTag("open-order").performClick()
        assertEquals(zoomed, viewport())
        compose.onNodeWithTag("minimize-order").performClick()
        assertEquals(zoomed, viewport())
        compose.onNodeWithTag("close-order").performClick()
        assertEquals(zoomed, viewport())
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").performClick()
        // Layer choices keep the popup open; dismiss it before interacting with the dock.
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").assertDoesNotExist()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").performClick()
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").assertDoesNotExist()
        assertEquals(zoomed, viewport())
    }

    @Test fun switchingSideWhileMinimizedRebuildsTheRestingPriceForThatSide() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        val quote = plugin.market.value.quotes.getValue(instrument.symbol)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        // Expanded, the side buttons with their prices own the side; the minimized bar's copy appears only when minimized.
        compose.onNodeWithTag("order-peek-side-SELL").assertDoesNotExist()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("order-peek-side-SELL").performClick().assertIsSelected()
        compose.onNodeWithTag("restore-order").performClick()
        val entry = text("order-entry")
        assertTrue(instrument.parsePrice(entry)!! > quote.bid, "A sell limit rests above the market, got $entry")
    }

    @Test fun landscapeDocksTheOrderPanelBesideTheChartAndKeepsReviewVisible() = phoneTest(645, 300) {
        render(860.dp, 400.dp)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        val plot = bounds("trading-chart")
        val dock = bounds("order-dock")
        assertTrue(plot.right <= dock.left)
        assertTrue(plot.height >= dock.height * .70f)
        compose.onNodeWithTag("order-quantity").assertExists()
        compose.onNodeWithTag("order-sl-enabled").assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        capture("mobile-dock-landscape-order")
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun quantityButtonsAndSideQuotesOnlyEditTheDraft() = phoneTest {
        render()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("quantity-increase").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.11")
        compose.onNodeWithTag("quantity-decrease").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        compose.onNodeWithTag("reference-protection").performScrollTo().performClick()
        val sl = text("order-sl")
        compose.onNodeWithTag("order-side-BUY").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-kind-MARKET").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-side-SELL").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn()
        val sellStop = text("order-sl")
        assertNotEquals(sl, sellStop)
        compose.onNodeWithTag("order-side-SELL").assertIsSelected()
        compose.onNodeWithTag("order-quantity").performScrollTo().performTextReplacement("0.01")
        compose.onNodeWithTag("quantity-decrease").assertIsNotEnabled()
        compose.onNodeWithTag("order-quantity").performTextReplacement("wrong")
        compose.onNodeWithTag("quantity-increase").assertIsNotEnabled()
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.onNodeWithTag("order-error").assertIsDisplayed()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-submit-order").assertIsNotEnabled()
        assertEquals(before, session.state.value, "Opening, choosing a side and editing quantities must not submit an order")
    }

    @Test fun smallPhoneKeepsReviewFixedWhileDockDetailsScroll() = phoneTest(240, 480) {
        render(320.dp, 640.dp, fontScale = 1.3f)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-entry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        // Unfolded, the footer also carries the entry summary; it stays where it is while the details scroll.
        val footer = bounds("review-order")
        compose.onNodeWithTag("order-estimates-toggle").performScrollTo().performClick()
        compose.onNodeWithText("双边手续费").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        assertEquals(footer, bounds("review-order"))
        capture("mobile-dock-small-order-details")
    }

    @Test fun protectionTogglesKeepOnlyEnabledProtectionInTheConfirmedOrder() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").assertIsOn()
        val rememberedStop = text("order-sl")
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertDoesNotExist()
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(rememberedStop)
        capture("mobile-dock-protection-controls")
        compose.onNodeWithTag("order-sl-enabled").performClick()
        val target = text("order-tp")
        val before = session.state.value.orders.size
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.orders.size == before + 1 }
        val sent = session.state.value.orders.last().request
        assertNull(sent.stopLoss)
        assertEquals(instrument.parsePrice(target), sent.takeProfit)
    }

    @Test fun quantitySliderDragsOnContractStepsAndTracksManualQuantity() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-more").performScrollTo().performClick()
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .65f, centerY))
        }
        val quantity = instrument.parseQuantity(text("order-quantity"))!!
        assertNotEquals(10L, quantity)
        assertTrue(quantity in instrument.minLotsHundredths..instrument.maxLotsHundredths)
        assertEquals(0L, quantity % instrument.lotStepHundredths)
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        val progress = compose.onNodeWithTag("order-quantity-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(QuantitySliderScale.from(instrument)!!.fraction(10), progress, .00001f)
        assertEquals(before, session.state.value)
    }
}
