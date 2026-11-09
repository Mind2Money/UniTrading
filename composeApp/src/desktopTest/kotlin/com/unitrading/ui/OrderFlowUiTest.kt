package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun wideDesktopShowsChartBesideAnalysisAndReturnsToSavedPanels() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val spec = source.instruments.first()
        val broker = plugin(MutableStateFlow(snapshot(spec)))
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        val before = runtime.desktopLayout.preferences.value
        onNodeWithTag("open-order-flow").performClick()
        val panel = onNodeWithTag("order-flow-panel").fetchSemanticsNode().boundsInRoot
        val chart = onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        assertTrue(chart.right <= panel.left + 1f)
        assertTrue(panel.width >= 600f)
        capture("desktop-workspace")
        onNodeWithTag("close-order-flow").performClick()
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        assertEquals(before, runtime.desktopLayout.preferences.value)
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
