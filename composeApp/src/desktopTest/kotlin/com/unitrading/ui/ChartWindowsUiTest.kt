package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.ChartPreferences
import com.unitrading.app.ChartPreferencesController
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.SavedChart
import com.unitrading.app.TradingRuntime
import com.unitrading.app.WindowBounds
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import com.unitrading.broker.Timeframe
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Charts beside the main one, each in a window with a symbol and an interval of its own. The chart pressed last is the
 * one the order panel works for. The fake windows stand under the screen.
 */
@OptIn(ExperimentalTestApi::class)
class ChartWindowsUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val broker = OfflineOrderBroker()
    private var runtime by mutableStateOf(newRuntime())
    private var windows by mutableStateOf(FakePanelWindows())
    private val id = broker.descriptor.id
    private val m5 = Timeframe.M5
    private val m15 = Timeframe.M15

    private fun newRuntime() = TradingRuntime(store, listOf(broker), includeDemoBrokers = false).apply { paused.value = true }

    private fun render(windowed: Boolean = true) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) {
            val current = runtime
            val fake = windows
            key(current, fake) {
                CompositionLocalProvider(LocalPanelWindowHost provides fake.takeIf { windowed }) {
                    // The strip under the screen is where the fake windows stand.
                    Column(Modifier.fillMaxSize()) { Box(Modifier.fillMaxWidth().height(760.dp)) { TradingScreen(current) } }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun restart() {
        compose.runOnIdle { windows = FakePanelWindows(); runtime = newRuntime() }
        compose.waitForIdle()
    }
    private fun saved() = ChartPreferencesController(store).preferences.value
    private fun awaitSaved(what: (ChartPreferences) -> Boolean) = compose.waitUntil(5_000) { what(saved()) }
    private fun newChart() {
        compose.onNodeWithTag("rail-windows").performClick()
        compose.onNodeWithTag("window-menu-new-chart").performClick()
        compose.waitForIdle()
    }
    /** A node of the chart window [chart]; the main chart is the one in the workspace. */
    private fun inChart(chart: String, tag: String) =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(if (chart == MAIN_CHART) "desktop-chart" else "fake-window-chart-$chart")))
    private fun title(chart: String) = windows.open.getValue("chart-$chart").title
    private fun pickSymbol(chart: String, symbol: String) {
        inChart(chart, "chart-symbol").performClick()
        compose.onNodeWithTag("symbol-option-$symbol").performClick()
        compose.waitForIdle()
    }
    /** Presses an empty spot of the plot, low on the right, where no line or button is. */
    private fun press(chart: String) {
        val plot = inChart(chart, "trading-chart")
        val size = plot.fetchSemanticsNode().size
        plot.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .8f)); press(); release() }
        compose.waitForIdle()
    }
    /** Confirms the order asked for and waits until it is sent and its dialog is gone. */
    private fun confirmOrder(count: Int) {
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5_000) { broker.placed.size == count }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("confirm-action").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    @Test fun aNewChartWindowOpensOnTheWorkingChartAndKeepsASymbolAndIntervalOfItsOwn() {
        render()
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(1)
        assertEquals(listOf(broker.spec.symbol to m5), broker.watched.last())
        newChart()
        assertEquals(setOf("chart-c1"), windows.open.keys.toSet())
        assertEquals("UniTrading · 图表 · TESTZ6 · 5分钟", title("c1"))
        compose.onNodeWithTag("window-menu-new-chart").assertDoesNotExist() // the menu closes behind its choice
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(2)
        inChart("c1", "trading-chart").assertExists()
        assertEquals(listOf(broker.spec.symbol to m5), broker.watched.last(), "two charts of one series are one subscription")

        // Its interval is its own.
        inChart("c1", "interval-inline-15").performClick()
        compose.waitForIdle()
        assertEquals("UniTrading · 图表 · TESTZ6 · 15分钟", title("c1"))
        assertEquals(listOf(broker.spec.symbol to m15, broker.spec.symbol to m5), broker.watched.last(), "both stay live, the one being worked on first")
        awaitSaved { it.extraCharts == listOf(SavedChart("c1", mapOf(id to "TESTZ6"), m15.seconds)) && it.lastTimeframe == m5 }

        // So is its symbol.
        pickSymbol("c1", "OTHRZ6")
        assertEquals("UniTrading · 图表 · OTHRZ6 · 15分钟", title("c1"))
        inChart("c1", "chart-symbol").assertTextContains("OTHRZ6", substring = true)
        inChart(MAIN_CHART, "chart-symbol").assertTextContains("TESTZ6", substring = true)
        assertEquals(listOf("OTHRZ6" to m15, "TESTZ6" to m5), broker.watched.last())
        awaitSaved { it.extraCharts == listOf(SavedChart("c1", mapOf(id to "OTHRZ6"), m15.seconds)) && it.lastSymbols[id] == "TESTZ6" && it.lastTimeframe == m5 }

        // The main chart changes on its own as well, and a second window starts from the chart being worked on.
        inChart(MAIN_CHART, "interval-inline-60").performClick()
        compose.waitForIdle()
        assertEquals(listOf("TESTZ6" to Timeframe.H1, "OTHRZ6" to m15), broker.watched.last())
        assertEquals("UniTrading · 图表 · OTHRZ6 · 15分钟", title("c1"))
        newChart()
        assertEquals("UniTrading · 图表 · TESTZ6 · 1小时", title("c2"))
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(3)
        val folder = File("../artifacts").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "desktop-chart-windows.png"))
    }

    @Test fun theOrderPanelAndChartOrdersWorkForTheChartPressedLast() {
        render()
        newChart()
        pickSymbol("c1", "OTHRZ6")
        // The order panel in the main window now works for the window's chart.
        compose.onNodeWithTag("review-order").performClick()
        compose.waitForIdle()
        confirmOrder(1)
        assertEquals("OTHRZ6", broker.placed.last().symbol)

        // A press in the main chart brings the panel back to it.
        press(MAIN_CHART)
        assertEquals(listOf("TESTZ6" to m5, "OTHRZ6" to m5), broker.watched.last())
        compose.onNodeWithTag("review-order").performClick()
        compose.waitForIdle()
        confirmOrder(2)
        assertEquals("TESTZ6", broker.placed.last().symbol)

        // An order asked for on a chart that is not the working one: the press that opens the menu makes it the working chart.
        val plot = inChart("c1", "trading-chart")
        val size = plot.fetchSemanticsNode().size
        plot.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .8f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.waitForIdle()
        compose.onNodeWithTag("plot-menu-BUY-LIMIT").performClick()
        compose.waitForIdle()
        confirmOrder(3)
        val order = broker.placed.last()
        assertEquals(Triple("OTHRZ6", Side.BUY, OrderKind.LIMIT), Triple(order.symbol, order.side, order.kind))
        assertEquals(listOf("OTHRZ6" to m5, "TESTZ6" to m5), broker.watched.last())
    }

    @Test fun chartWindowsComeBackAfterARestartWithTheirSymbolIntervalAndPlace() {
        render()
        newChart()
        inChart("c1", "interval-inline-15").performClick()
        compose.waitForIdle()
        pickSymbol("c1", "OTHRZ6")
        val moved = WindowBounds(1500f, 80f, 1100f, 700f)
        compose.runOnIdle { windows.open.getValue("chart-c1").onBounds(moved) }
        compose.waitUntil(5_000) { DesktopLayoutController(store).preferences.value.windows["chart-c1"] == moved }
        awaitSaved { it.extraCharts == listOf(SavedChart("c1", mapOf(id to "OTHRZ6"), m15.seconds)) }

        // The window's chart was the working one when the app closed; the main chart still reopens with its own symbol and interval.
        restart()
        assertEquals("UniTrading · 图表 · OTHRZ6 · 15分钟", title("c1"))
        assertEquals(moved, windows.open.getValue("chart-c1").bounds, "the window opens where it was")
        inChart(MAIN_CHART, "chart-symbol").assertTextContains("TESTZ6", substring = true)
        inChart("c1", "chart-symbol").assertTextContains("OTHRZ6", substring = true)
        assertEquals(listOf("TESTZ6" to m5, "OTHRZ6" to m15), broker.watched.last())
        compose.onNodeWithTag("review-order").performClick()
        compose.waitForIdle()
        confirmOrder(1)
        assertEquals("TESTZ6", broker.placed.last().symbol, "after a restart the main chart is the working one")
    }

    @Test fun closingAChartWindowEndsItsChartAndTheMainChartTakesOver() {
        render()
        newChart()
        pickSymbol("c1", "OTHRZ6")
        compose.runOnIdle { windows.open.getValue("chart-c1").onBounds(WindowBounds(10f, 10f, 800f, 600f)) }
        compose.waitUntil(5_000) { DesktopLayoutController(store).preferences.value.windows.containsKey("chart-c1") }
        compose.runOnIdle { windows.open.getValue("chart-c1").onClose() }
        compose.waitForIdle()
        assertTrue(windows.open.isEmpty())
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(1)
        inChart(MAIN_CHART, "chart-symbol").assertTextContains("TESTZ6", substring = true)
        assertEquals(listOf("TESTZ6" to m5), broker.watched.last(), "the closed chart's subscription ends")
        awaitSaved { it.extraCharts.isEmpty() }
        compose.waitUntil(5_000) { DesktopLayoutController(store).preferences.value.windows.isEmpty() }
        compose.onNodeWithTag("review-order").performClick()
        compose.waitForIdle()
        confirmOrder(1)
        assertEquals("TESTZ6", broker.placed.last().symbol)

        // A new window takes the freed name and does not inherit the closed one's place.
        newChart()
        assertEquals(setOf("chart-c1"), windows.open.keys.toSet())
        assertNull(windows.open.getValue("chart-c1").bounds)
    }

    @Test fun noMoreChartWindowsOpenThanTheBrokerCanKeepLive() {
        render()
        repeat(ChartPreferences.MAX_EXTRA_CHARTS) { newChart() }
        assertEquals(ChartPreferences.MAX_EXTRA_CHARTS, windows.open.size)
        compose.onNodeWithTag("rail-windows").performClick()
        compose.onNodeWithTag("window-menu-new-chart").assertIsNotEnabled()
    }

    @Test fun aPlatformWithoutWindowsShowsOnlyTheMainChart() {
        compose.runOnIdle { kotlinx.coroutines.runBlocking { runtime.chartPreferences.setExtraCharts(id, listOf(Triple("c1", "OTHRZ6", m15))) } }
        render(windowed = false)
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(1)
        compose.onNodeWithTag("chart-symbol").assertTextContains("TESTZ6", substring = true)
        compose.onNodeWithTag("rail-windows").assertDoesNotExist()
        assertTrue(windows.open.isEmpty())
    }
}
