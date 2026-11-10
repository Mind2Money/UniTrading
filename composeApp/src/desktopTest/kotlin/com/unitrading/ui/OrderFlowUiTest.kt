package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

/** Synthetic market data only: screen fit, access to every tool, and subscription cleanup. */
@OptIn(ExperimentalTestApi::class)
class OrderFlowUiTest {
    private val mes = Instrument("MESZ6", "Micro E-mini S&P 500", "期货", 2, 25, 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0)
    private val source = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
    private val at = 1_798_589_000_000L

    private fun snapshot(spec: Instrument = mes): OrderFlowSnapshot {
        val base = if (spec == mes) 550_000L else source.market.value.quotes.getValue(spec.symbol).bid
        val trades = (0..149).map { index ->
            MarketTrade(at + index * 6_000L, base + (index % 9 - 4) * spec.tickSize,
                (index % 12 + 1).toLong(), when (index % 5) { 0 -> null; 1, 2 -> Side.SELL; else -> Side.BUY })
        }
        return OrderFlowSnapshot(spec.executionSymbol, trades,
            bids = (0..9).map { DepthLevel(base - it * spec.tickSize, (it * 13 + 7).toLong()) },
            asks = (1..10).map { DepthLevel(base + it * spec.tickSize, (it * 11 + 5).toLong()) },
            bestBid = DepthLevel(base, 7), bestAsk = DepthLevel(base + spec.tickSize, 16),
            depthStatus = DepthStatus.AVAILABLE, connected = true, startedAtMs = at, statusMessage = "实时行情")
    }

    private fun plugin(flow: MutableStateFlow<OrderFlowSnapshot>, onWatch: (String?) -> Unit = {}) = object : BrokerPlugin by source {
        override val orderFlow = flow
        override fun watchOrderFlow(symbol: String?) = onWatch(symbol)
    }

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val file = File("../artifacts/orderflow/$name.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertMaterialRailItems(items: List<Pair<String, String>>) {
        val rail = bounds("desktop-panel-rail")
        var previousBottom = rail.top
        items.forEach { (tag, label) ->
            onNodeWithTag("$tag-item").assertIsDisplayed()
            val item = bounds("$tag-item")
            assertTrue(item.height >= 80f, "$label has room for its native toggle and permanent label")
            assertTrue(item.top >= previousBottom, "Rail entries do not overlap")
            assertTrue(item.left >= rail.left && item.right <= rail.right + 1f)
            previousBottom = item.bottom
            onNode(hasText(label) and hasAnyAncestor(hasTestTag("$tag-item")), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(tag).assertIsDisplayed()
            val button = bounds(tag)
            assertTrue(button.width >= 48f && button.height >= 48f, "$label keeps a 48dp touch target")
        }
    }

    private fun panelTest(width: Int, height: Int, name: String) = runDesktopComposeUiTest(width = width, height = height) {
        val flow = MutableStateFlow(snapshot())
        val broker = plugin(flow)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            TradingTheme { Box(Modifier.requiredSize(width.dp, height.dp)) { OrderFlowPanel(broker, mes, Timeframe.M1, onClose = {}) } }
        } }
        onNodeWithTag("order-flow-panel").assertIsDisplayed()
        onNodeWithTag("close-order-flow").assertIsDisplayed()
        for (tool in listOf("FOOTPRINT", "PROFILE", "DELTA", "TAPE", "DOM")) {
            onNodeWithTag("flow-tab-$tool").performScrollTo().performClick()
            if (tool == "FOOTPRINT") {
                onNodeWithTag("orderflow-footprint-bars").performTouchInput { swipeLeft() }
                onNodeWithTag("orderflow-price-header").assertIsDisplayed()
                onNodeWithTag("orderflow-footprint-price-550100").assertIsDisplayed()
            }
            onNodeWithTag("close-order-flow").assertIsDisplayed()
            val panel = onNodeWithTag("order-flow-panel").fetchSemanticsNode().boundsInRoot
            val close = onNodeWithTag("close-order-flow").fetchSemanticsNode().boundsInRoot
            assertTrue(close.left >= panel.left && close.right <= panel.right + 1f)
            assertTrue(close.top >= panel.top && close.bottom <= panel.bottom + 1f)
            capture("$name-${tool.lowercase()}")
        }
        onNodeWithTag("flow-tab-TAPE").performScrollTo().performClick()
        onNodeWithTag("flow-tape-min-size").performTextReplacement("10")
        onNodeWithTag("flow-tape-pause").performClick()
        runOnIdle { flow.value = flow.value.copy(trades = flow.value.trades.drop(1) + MarketTrade(at + 1_000_000, 550_000, 99, Side.BUY),
            truncated = true, startedAtMs = at + 6_000) }
        onNodeWithTag("orderflow-tape-state").assertTextContains("显示已暂停", substring = true)
        onAllNodesWithText("99").assertCountEquals(0)
        capture("$name-tape-paused")
        onNodeWithTag("flow-tape-pause").performClick()
        onNodeWithText("99").assertIsDisplayed()
    }

    @Test fun narrowPhoneCanReachAllTools() = panelTest(320, 680, "phone")
    @Test fun landscapeCanReachAllTools() = panelTest(840, 400, "landscape")
    @Test fun tabletCanReachAllTools() = panelTest(768, 1024, "tablet")
    @Test fun desktopCanReachAllTools() = panelTest(1000, 800, "desktop")

    @Test fun closingAnalysisAndLeavingMobileChartStopTheSubscription() = runDesktopComposeUiTest(width = 412, height = 860) {
        val spec = source.instruments.first()
        val watched = mutableListOf<String?>()
        val broker = plugin(MutableStateFlow(snapshot(spec))) { watched += it }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(412.dp, 860.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("open-order-flow").performClick()
        onNodeWithTag("order-flow-panel").assertIsDisplayed()
        runOnIdle { assertEquals(spec.symbol, watched.last()) }
        onNodeWithTag("close-order-flow").performClick()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
        runOnIdle { assertEquals(null, watched.last()) }
        onNodeWithTag("open-order-flow").performClick()
        onNodeWithTag("nav-0").performClick()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
        runOnIdle { assertEquals(null, watched.last()) }
    }

    @Test fun unsupportedBrokersKeepTheCurrentWorkspace() = runDesktopComposeUiTest(width = 412, height = 860) {
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(source)).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(412.dp, 860.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("open-order-flow").assertDoesNotExist()
        onNodeWithTag("trading-chart").assertIsDisplayed()
    }

    @Test fun sidebarOrderFlowTakesHalfTheChartAndKeepsOtherPanels() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val spec = source.instruments.first()
        val watched = mutableListOf<String?>()
        val broker = plugin(MutableStateFlow(snapshot(spec))) { watched += it }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        val before = runtime.desktopLayout.preferences.value
        val originalChart = bounds("desktop-chart")
        val otherPanels = listOf("desktop-order", "desktop-sidebar", "desktop-terminal", "desktop-panel-rail")
            .associateWith { bounds(it) }
        onNodeWithTag("open-order-flow").assertDoesNotExist()
        val toggle = bounds("rail-order-flow")
        val rail = bounds("desktop-panel-rail")
        assertTrue(toggle.left >= rail.left && toggle.right <= rail.right + 1f, "Order flow opens from the right icon rail")
        assertMaterialRailItems(listOf("rail-market" to "行情", "rail-order" to "下单", "rail-terminal" to "交易", "rail-order-flow" to "订单流"))
        onNodeWithTag("rail-market").assertIsOn()
        onNodeWithTag("rail-order").assertIsOn()
        onNodeWithTag("rail-terminal").assertIsOn()
        onNodeWithTag("rail-order-flow").assertIsOff()
        onNodeWithTag("rail-order-flow").performClick()
        onNodeWithTag("rail-order-flow").assertIsOn()
        val panel = bounds("order-flow-panel")
        val chart = bounds("desktop-chart")
        val area = bounds("desktop-chart-area")
        assertTrue(chart.right <= panel.left + 1f)
        assertTrue(abs(chart.width - panel.width) <= 1f, "Order flow occupies half of the original chart area")
        assertEquals(originalChart, area)
        assertTrue(abs(chart.width + panel.width + 8f - originalChart.width) <= 1f)
        assertEquals(chart.top, panel.top)
        assertEquals(chart.bottom, panel.bottom)
        otherPanels.forEach { (tag, original) -> assertEquals(original, bounds(tag), "$tag stays in place") }
        runOnIdle { assertEquals(spec.symbol, watched.last()) }
        capture("desktop-workspace")
        onNodeWithTag("rail-order-flow").performClick()
        onNodeWithTag("rail-order-flow").assertIsOff()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
        assertEquals(originalChart, bounds("desktop-chart"))
        runOnIdle { assertEquals(null, watched.last()) }
        onNodeWithTag("rail-order-flow").performClick()
        onNodeWithTag("close-order-flow").performClick()
        onNodeWithTag("rail-order-flow").assertIsOff()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
        assertEquals(originalChart, bounds("desktop-chart"))
        otherPanels.forEach { (tag, original) -> assertEquals(original, bounds(tag)) }
        runOnIdle { assertEquals(null, watched.last()) }
        assertEquals(before, runtime.desktopLayout.preferences.value)
    }

    @Test fun draggingAndKeyboardResizeOnlyTheTwoHalvesAndSaveTheSplit() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val spec = source.instruments.first()
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, listOf(plugin(MutableStateFlow(snapshot(spec)))))
            .apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("rail-order-flow").performClick()
        val initialChart = bounds("desktop-chart")
        val initialFlow = bounds("order-flow-panel")
        val otherPanels = listOf("desktop-order", "desktop-sidebar", "desktop-terminal", "desktop-panel-rail")
            .associateWith { bounds(it) }
        onNodeWithTag("resize-order-flow").performTouchInput { swipe(center, center + Offset(-120f, 0f)) }
        assertTrue(bounds("order-flow-panel").width > initialFlow.width + 80f)
        assertTrue(bounds("desktop-chart").width < initialChart.width - 80f)
        otherPanels.forEach { (tag, original) -> assertEquals(original, bounds(tag), "$tag does not move when changing the chart split") }
        val draggedFraction = runtime.desktopLayout.preferences.value.orderFlowFraction
        assertTrue(abs(draggedFraction - .5f) > .05f)
        waitUntil(timeoutMillis = 5_000L) { DesktopLayoutController(store).preferences.value.orderFlowFraction == draggedFraction }
        capture("desktop-workspace-resized")
        onNodeWithTag("resize-order-flow").performTouchInput { click() }
        onNodeWithTag("resize-order-flow").performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        val keyboardFraction = runtime.desktopLayout.preferences.value.orderFlowFraction
        assertTrue(keyboardFraction != draggedFraction)
        waitUntil(timeoutMillis = 5_000L) { DesktopLayoutController(store).preferences.value.orderFlowFraction == keyboardFraction }
        onNodeWithTag("resize-order-flow").performTouchInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000L) { DesktopLayoutController(store).preferences.value.orderFlowFraction == .5f }
        assertEquals(.5f, runtime.desktopLayout.preferences.value.orderFlowFraction)
        assertTrue(abs(bounds("desktop-chart").width - bounds("order-flow-panel").width) <= 1f)
        otherPanels.forEach { (tag, original) -> assertEquals(original, bounds(tag)) }
    }

    @Test fun narrowDesktopKeepsTwoUsableHalvesAndTheMarketSidebar() = runDesktopComposeUiTest(width = 900, height = 650) {
        val spec = source.instruments.first()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin(MutableStateFlow(snapshot(spec)))))
            .apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(900.dp, 650.dp)) { TradingScreen(runtime) }
        } }
        val preferred = runtime.desktopLayout.preferences.value
        val originalSidebar = bounds("desktop-sidebar")
        val terminalHeight = bounds("desktop-terminal").height
        onNodeWithTag("rail-order-flow").performClick()
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        onNodeWithTag("desktop-sidebar").assertIsDisplayed()
        onNodeWithTag("desktop-order").assertDoesNotExist()
        assertMaterialRailItems(listOf("rail-market" to "行情", "rail-terminal" to "交易", "rail-order-flow" to "订单流"))
        onNodeWithTag("rail-order-flow").assertIsOn()
        val chart = bounds("desktop-chart")
        val panel = bounds("order-flow-panel")
        val area = bounds("desktop-chart-area")
        assertTrue(abs(chart.width - panel.width) <= 1f)
        assertTrue(chart.width >= 239f && panel.width >= 239f)
        assertEquals(originalSidebar, bounds("desktop-sidebar"))
        assertTrue(panel.right <= bounds("desktop-sidebar").left)
        assertEquals(area.width, bounds("desktop-terminal").width)
        assertEquals(terminalHeight, bounds("desktop-terminal").height)
        assertEquals(preferred, runtime.desktopLayout.preferences.value, "Window constraints must not overwrite saved panel sizes")
        onNodeWithTag("chart-toolbar").assertIsDisplayed()
        onNodeWithTag("interval-menu").performScrollTo().assertIsDisplayed()
        onNodeWithTag("chart-symbol").performScrollTo().assertIsDisplayed()
        onNodeWithTag("open-order-flow").assertDoesNotExist()
        onNodeWithTag("close-order-flow").assertIsDisplayed()
        assertTrue(bounds("orderflow-footprint-prices").height >= 36f, "The short analysis panel must show at least one complete footprint row: prices=${bounds("orderflow-footprint-prices")}, panel=${bounds("order-flow-panel")}")
        val firstPrice = source.market.value.quotes.getValue(spec.symbol).bid + 4 * spec.tickSize
        onNodeWithTag("orderflow-footprint-price-$firstPrice").assertIsDisplayed()
        capture("narrow-desktop-workspace")
    }

    @Test fun resizingAnOpenSidebarToPhoneKeepsAnalysisAndMobileNavigationUsable() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val spec = source.instruments.first()
        val watched = mutableListOf<String?>()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin(MutableStateFlow(snapshot(spec))) { watched += it }))
            .apply { paused.value = true }
        var width by mutableStateOf(1600.dp)
        var height by mutableStateOf(1000.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(width, height)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("rail-order-flow").performClick()
        // End any hover and allow TooltipBox's dismissal to finish before disposing the desktop tree.
        onNodeWithTag("desktop-workspace").performMouseInput { moveTo(Offset(1f, this.height - 1f)); exit() }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        runOnIdle { width = 412.dp; height = 860.dp }
        onNodeWithTag("desktop-workspace").assertDoesNotExist()
        onNodeWithTag("rail-order-flow").assertDoesNotExist()
        onNodeWithTag("order-flow-panel").assertIsDisplayed()
        onNodeWithTag("close-order-flow").assertIsDisplayed()
        onNodeWithTag("mobile-navigation").assertIsDisplayed()
        capture("phone-from-desktop-sidebar")
        onNodeWithTag("order-flow-panel").performMouseInput { moveTo(Offset(1f, this.height - 1f)); exit() }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithTag("close-order-flow").performClick()
        onNodeWithTag("open-order-flow").assertIsDisplayed()
        runOnIdle { assertEquals(null, watched.last()) }
        onNodeWithTag("open-order-flow").performClick()
        onNodeWithTag("nav-0").performClick()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
        runOnIdle { assertEquals(null, watched.last()) }
        onNodeWithTag("market-watch").performMouseInput { moveTo(Offset(1f, this.height - 1f)); exit() }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        runOnIdle { width = 1600.dp; height = 1000.dp }
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        onNodeWithTag("rail-order-flow").assertIsDisplayed()
        onNodeWithTag("open-order-flow").assertDoesNotExist()
        onNodeWithTag("order-flow-panel").assertDoesNotExist()
    }

    @Test fun unavailableDepthShowsOnlyRealBboAndDisconnectClearsTheTape() = runDesktopComposeUiTest(width = 412, height = 780) {
        val flow = MutableStateFlow(snapshot().copy(depthStatus = DepthStatus.UNAVAILABLE, bids = emptyList(), asks = emptyList(),
            statusMessage = "L2 深度不可用，请检查行情权限"))
        val broker = plugin(flow)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            TradingTheme { Box(Modifier.requiredSize(412.dp, 780.dp)) { OrderFlowPanel(broker, mes, Timeframe.M1, onClose = {}) } }
        } }
        onNodeWithTag("flow-tab-DOM").performScrollTo().performClick()
        onNodeWithTag("orderflow-dom-mode").assertTextContains("仅买一 / 卖一", substring = true)
        onNodeWithText("L2 深度不可用，请检查行情权限").assertIsDisplayed()
        capture("phone-dom-unavailable")
        runOnIdle { flow.value = OrderFlowSnapshot(statusMessage = "连接中断，逐笔窗口已清空") }
        onNodeWithTag("orderflow-status").assertTextContains("连接中断", substring = true)
        onNodeWithTag("flow-tab-TAPE").performScrollTo().performClick()
        onNodeWithText("等待实时逐笔成交").assertIsDisplayed()
        capture("phone-disconnected")
    }
}
