package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unitrading.app.ChartPreferencesController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.Side
import com.unitrading.broker.Timeframe
import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Phone layouts and the Material order sheet, using only synthetic broker data. */
@OptIn(ExperimentalTestApi::class)
class MobileLayoutTest {
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

    private fun render(width: Dp = 400.dp, height: Dp = 800.dp, fontScale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f, fontScale)) {
            Box(Modifier.requiredSize(width, height).testTag("test-host")) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test fun portraitChartFillsTheScreenAndTradingWaitsInTheCollapsedPanel() = phoneTest {
        render()
        val host = height("test-host")
        assertTrue(height("trading-chart") >= host * .66f, "chart ${height("trading-chart")} px of $host px")
        compose.onNodeWithTag("chart-toolbar").assertExists()
        compose.onNodeWithText("UniTrading").assertDoesNotExist()
        compose.onNodeWithText("余额 USD").assertDoesNotExist()
        compose.onNodeWithTag("legend-sell").assertDoesNotExist()
        compose.onNodeWithTag("order-quantity").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        capture("mobile-chart-first")

        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(hasText(Side.SELL.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("mobile-navigation").assertDoesNotExist()
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        assertTrue(height("trading-chart") >= host * .66f, "The sheet must overlay the chart without shrinking it")
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity").assertIsDisplayed()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().assertIsDisplayed()
        capture("mobile-order-panel")

        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        compose.onNodeWithTag("panel-positions").performClick()
        compose.onNodeWithTag("nav-4").assertIsSelected()
        compose.onNodeWithText("余额 USD").assertDoesNotExist()
        compose.onNodeWithTag("trade-terminal").assertExists()
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
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, bounds.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(bounds.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)) }
        compose.onNodeWithTag("chart-readout").assertTextContains("入场预览", substring = true)
        chart.performTouchInput { up() }
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
    }

    @Test fun legendFoldWithItsIndicatorListIsRememberedAcrossTabsAndRestarts() = phoneTest {
        runBlocking { assertTrue(runtime.indicators.apply(IndicatorSettings(listOf(IndicatorConfig("m", IndicatorKind.MA))))) }
        render()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) { runtime.chartPreferences.preferences.value.legendCollapsed == true }
        // Leaving the chart tab drops the chart from composition; it comes back folded.
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("account-tab-1").performClick()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        assertEquals(true, ChartPreferencesController(store).preferences.value.legendCollapsed, "a restart reads the saved fold")
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) { ChartPreferencesController(store).preferences.value.legendCollapsed == false }
    }

    @Test fun nativeSwipeAndAccessibilityCollapsePreserveTheDraft() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("order-sheet-handle", useUnmergedTree = true).performTouchInput { swipeDown(endY = 300f, durationMillis = 300) }
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("order-type-strip").assertIsDisplayed()
        compose.onNodeWithTag("order-sheet-handle", useUnmergedTree = true).performTouchInput { swipeUp(endY = -300f, durationMillis = 300) }
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            .performSemanticsAction(SemanticsActions.Collapse) { it() }
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        // Even a second fast downward fling cannot discard the compact ticket.
        compose.onNodeWithTag("order-sheet-handle", useUnmergedTree = true).performTouchInput { swipeDown(endY = 100f, durationMillis = 100) }
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
    }

    @Test fun chartTapDoesNotDismissThePersistentSheetAndCloseDiscardsTheDraft() = phoneTest {
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

    @Test fun confirmedOrderClosesThePanel() = phoneTest {
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

    /**
     * The compact bar's arrow sends the draft as it is when pressed. Its click used to keep the order of the moment the
     * bar was first shown, so protection switched on while minimized was left out of the order that went out.
     */
    @Test fun compactArrowSubmitsTheProtectionSwitchedOnWhileMinimized() = phoneTest {
        // An empty account that records what it is sent: only the order placed here can carry the prices asserted below.
        val broker = OfflineOrderBroker()
        val offline = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        runBlocking { assertTrue(offline.tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(offline) }
        } }
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("compact-sl").performClick().assertIsOn()
        compose.onNodeWithTag("compact-tp").performClick().assertIsOn()
        compose.onNodeWithTag("compact-submit-order").performClick()
        // The reference protection of a short at an ask of 5500.25: 80 ticks above it and 160 below.
        compose.onNodeWithText("5520.25").assertExists()
        compose.onNodeWithText("5460.25").assertExists()
        assertTrue(broker.placed.isEmpty())
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { broker.placed.isNotEmpty() }
        assertEquals(OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100, stopLoss = 552_025, takeProfit = 546_025), broker.placed.single())
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
        capture("mobile-order-minimized")
        // Turning protection off before the drag gives a deterministic price scale.
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        val position = before.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit, entry) +
            before.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice }
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, bounds.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(bounds.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)); up() }
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

    @Test fun expandingAndMinimizingTheSheetDoesNotResizeTheChart() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        val before = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("minimize-order").performClick()
        assertEquals(before, compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun openingMinimizingAndClosingKeepTheChartViewportAndLayers() = phoneTest {
        render()
        fun viewport() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].substringBefore(";low:")
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
        // Layer choices keep the popup open; dismiss it before interacting with the sheet.
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
        // Layer choices keep the popup open; dismiss it before interacting with the sheet.
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").assertDoesNotExist()
        assertEquals(zoomed, viewport())
    }

    @Test fun toolbarRowKeepsIntervalsIndicatorsAndLayers() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("interval-inline-5").assertTextContains("5m")
        compose.onNodeWithTag("interval-menu").performScrollTo().performClick()
        // The second intervals come first, so on a phone the hour intervals start below the fold of the menu.
        compose.onNodeWithTag("interval-option-240").performScrollTo().performClick()
        compose.onNodeWithText("${instrument.name} · 4小时 · ${instrument.category}").assertExists()
        compose.onNodeWithTag("chart-indicators").performScrollTo().performClick()
        compose.onNodeWithTag("indicator-settings").assertExists()
        compose.onNodeWithTag("indicators-cancel").performClick()
        compose.onNodeWithTag("chart-layers").performScrollTo().performClick()
        compose.onNodeWithTag("layer-3").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("chart-toolbar").assertExists()
    }

    @Test fun wideCompactWindowsNavigateFromASideRail() = phoneTest(645, 300) {
        render(860.dp, 400.dp)
        val rail = compose.onNodeWithTag("mobile-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(rail.height > rail.width, "Navigation sits at the side once the window is wide")
        compose.onNodeWithTag("trade-bar").assertExists()
        compose.onNodeWithTag("nav-4").performClick().assertIsSelected()
        compose.onNodeWithTag("account-tab-1").performClick()
        compose.onNodeWithTag("trade-terminal").assertIsDisplayed()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun switchingSideWhileMinimizedRebuildsTheRestingPriceForThatSide() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        val quote = plugin.market.value.quotes.getValue(instrument.symbol)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        // Expanded, the side buttons with their prices own the side; the peek's copy appears only when minimized.
        compose.onNodeWithTag("order-peek-side-SELL").assertDoesNotExist()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("order-peek-side-SELL").performClick().assertIsSelected()
        compose.onNodeWithTag("restore-order").performClick()
        val entry = compose.onNodeWithTag("order-entry").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertTrue(instrument.parsePrice(entry)!! > quote.bid, "A sell limit rests above the market, got $entry")
    }

    @Test fun landscapeUsesTheSamePersistentSheetAndKeepsReviewVisible() = phoneTest(645, 300) {
        render(860.dp, 400.dp)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)).assertExists()
        compose.onNodeWithTag("order-quantity").assertExists()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        capture("mobile-landscape-order")
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
        compose.onNodeWithTag("reference-protection").performScrollTo().performClick()
        val sl = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        compose.onNodeWithTag("order-side-BUY").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-kind-MARKET").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-side-SELL").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn()
        val sellStop = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
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

    @Test fun smallPhoneKeepsReviewFixedWhileSheetDetailsScroll() = phoneTest(240, 480) {
        render(320.dp, 640.dp, fontScale = 1.3f)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-entry").performScrollTo().assertIsDisplayed()
        val footer = compose.onNodeWithTag("review-order").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("order-estimates-toggle").performScrollTo().performClick()
        compose.onNodeWithText("双边手续费").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        assertEquals(footer, compose.onNodeWithTag("review-order").fetchSemanticsNode().boundsInRoot)
        capture("mobile-small-order-details")
        compose.onNodeWithTag("review-order").assertIsDisplayed()
    }

    @Test fun protectionTogglesKeepOnlyEnabledProtectionInTheConfirmedOrder() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").assertIsOn()
        val rememberedStop = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertDoesNotExist()
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(rememberedStop)
        capture("mobile-protection-controls")
        compose.onNodeWithTag("order-sl-enabled").performClick()
        val target = compose.onNodeWithTag("order-tp").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
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
        compose.onNodeWithTag("order-quantity-slider").performScrollTo().performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .65f, centerY))
        }
        val text = compose.onNodeWithTag("order-quantity").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        val quantity = instrument.parseQuantity(text)!!
        assertNotEquals(10L, quantity)
        assertTrue(quantity in instrument.minLotsHundredths..instrument.maxLotsHundredths)
        assertEquals(0L, quantity % instrument.lotStepHundredths)
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        val progress = compose.onNodeWithTag("order-quantity-slider").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(QuantitySliderScale.from(instrument)!!.fraction(10), progress, .00001f)
        assertEquals(before, session.state.value)
    }
}
