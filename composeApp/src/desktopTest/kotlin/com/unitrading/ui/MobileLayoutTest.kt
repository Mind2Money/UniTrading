package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.unitrading.app.ChartPreferencesController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import androidx.compose.ui.geometry.Offset
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import com.unitrading.broker.Timeframe
import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Phone layouts: the chart takes the screen, trading waits in the collapsible order panel, account figures sit on the trade and account tabs. */
class MobileLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val session = plugin.openSession(plugin.accounts.first().id)

    private fun render(width: Dp = 400.dp, height: Dp = 800.dp) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(width, height).testTag("test-host")) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test fun portraitChartFillsTheScreenAndTradingWaitsInTheCollapsedPanel() {
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

        compose.onNodeWithTag("quick-sell").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(hasText(Side.SELL.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("mobile-navigation").assertDoesNotExist()
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        assertTrue(height("trading-chart") > host * .2f, "The chart stays visible above the open ticket")
        compose.onNodeWithTag("review-order").performScrollTo().assertIsDisplayed()
        capture("mobile-order-panel")

        compose.onNodeWithTag("collapse-order").performClick()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        compose.onNodeWithTag("panel-positions").performClick()
        compose.onNodeWithTag("nav-2").assertIsSelected()
        compose.onNodeWithText("余额 USD").assertExists()
        compose.onNodeWithTag("trade-terminal").assertExists()
    }

    @Test fun draggingTheDraftEntryBringsBackTheLegendWithItsEstimate() {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithText(OrderKind.LIMIT.label()).performClick()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        compose.onNodeWithTag("order-entry").performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
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
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
    }

    @Test fun legendFoldWithItsIndicatorListIsRememberedAcrossTabsAndRestarts() {
        runBlocking { assertTrue(runtime.indicators.apply(IndicatorSettings(listOf(IndicatorConfig("m", IndicatorKind.MA))))) }
        render()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        compose.waitUntil(5_000) { runtime.chartPreferences.preferences.value.legendCollapsed == true }
        // Leaving the chart tab drops the chart from composition; it comes back folded.
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        assertEquals(true, ChartPreferencesController(store).preferences.value.legendCollapsed, "a restart reads the saved fold")
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.waitUntil(5_000) { ChartPreferencesController(store).preferences.value.legendCollapsed == false }
    }

    @Test fun handleSwipesAndTapsToggleThePanel() {
        render()
        compose.onNodeWithTag("order-panel-handle").performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("order-panel-handle").performTouchInput { swipeDown(startY = centerY, endY = centerY + 120f) }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("order-panel-handle").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("order-panel-handle").performClick()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun systemBackClosesTheOpenPanelOnly() {
        var fallbacks = 0
        val dispatcher = NavigationEventDispatcher(onBackCompletedFallback = { fallbacks++ })
        val back = DirectNavigationEventInput().also(dispatcher::addInput)
        val owner = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = dispatcher }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f), LocalNavigationEventDispatcherOwner provides owner) {
            Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) }
        } }
        compose.onNodeWithTag("open-order").performClick()
        compose.runOnIdle { back.backCompleted() }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        assertEquals(0, fallbacks)
        // With the panel closed, Back goes to the platform (leaving the app) instead of being swallowed.
        compose.runOnIdle { back.backCompleted() }
        assertEquals(1, fallbacks)
    }

    @Test fun confirmedOrderClosesThePanel() {
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5_000) { session.state.value.positions.size == before + 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("order-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mobile-navigation").assertExists()
    }

    @Test fun toolbarRowKeepsIntervalsIndicatorsAndLayers() {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("interval-current").assertTextContains("5分钟").performClick()
        compose.onNodeWithTag("interval-option-240").performClick()
        compose.onNodeWithText("${instrument.name} · 4小时 · ${instrument.category}").assertExists()
        compose.onNodeWithTag("chart-indicators").performClick()
        compose.onNodeWithTag("indicator-settings").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("chart-layers").performClick()
        compose.onNodeWithTag("layer-3").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("chart-toolbar").assertExists()
    }

    @Test fun landscapeDocksTheOpenTicketBesideTheChart() {
        render(860.dp, 400.dp)
        compose.onNodeWithTag("open-order").performClick()
        val chart = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("order-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.left >= chart.right, "The ticket docks to the right of the chart")
        assertTrue(chart.width > panel.width, "The chart keeps the wider side")
        compose.onNodeWithTag("order-panel-handle").assertDoesNotExist()
        compose.onNodeWithTag("order-quantity").assertExists()
        capture("mobile-landscape-order")
        compose.onNodeWithTag("collapse-order").performClick()
        compose.onNodeWithTag("trade-bar").assertExists()
    }
}
