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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.chart.ChartViewMemory
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** A chart opens a series as it was left: after a change of symbol, interval or page, and after a restart. */
@OptIn(ExperimentalTestApi::class)
class ChartViewMemoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val broker = OfflineOrderBroker()
    private var runtime by mutableStateOf(newRuntime())

    private fun newRuntime() = TradingRuntime(store, listOf(broker)).apply { paused.value = true }
    private fun render(width: Dp = 1400.dp, height: Dp = 900.dp) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) {
            Box(Modifier.requiredSize(width, height)) { key(runtime) { TradingScreen(runtime) } }
        } }
        compose.waitForIdle()
    }
    private fun restart() {
        compose.runOnIdle { runtime = newRuntime() }
        compose.waitForIdle()
    }
    private fun state(): Map<String, String> = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun view() = state().let { Triple(it.getValue("bars").toDouble(), it.getValue("offset").toDouble(), it.getValue("auto").toBoolean()) }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    private fun symbol(value: String) { tap("chart-symbol"); tap("symbol-option-$value") }
    /** Waits until the store holds the view of [key] as the chart shows it now. */
    private fun awaitSaved(key: String) {
        val (bars, _, _) = view()
        compose.waitUntil(5_000) { ChartViewMemory(store).view(key)?.count == bars }
    }

    @Test fun aSeriesOpensAsItWasLeftAndAnUnseenOneKeepsTheBarCount() {
        render()
        assertEquals(Triple(80.0, 0.0, true), view(), "nothing saved: the default view")
        tap("chart-zoom-in"); tap("chart-zoom-in"); tap("chart-earlier")
        val five = view()
        assertTrue(five.first < 80.0 && five.second > 0.0, "$five")
        // The price axis set by hand comes back with the rest.
        val plot = compose.onNodeWithTag("trading-chart")
        val size = plot.fetchSemanticsNode().size
        plot.performMouseInput { moveTo(Offset(size.width - 20f, size.height * .4f)); scroll(2f) }
        compose.waitForIdle()
        val axis = state().let { it.getValue("low") to it.getValue("high") }
        assertEquals(false, view().third)

        tap("interval-inline-15")
        assertEquals(Triple(five.first, 0.0, true), view(), "a series not seen yet: the same bar count, on the newest bar, the axis fitted")
        tap("chart-zoom-out")
        val fifteen = view()
        tap("interval-inline-5")
        assertEquals(Triple(five.first, five.second, false), view())
        assertEquals(axis, state().let { it.getValue("low") to it.getValue("high") })
        symbol("OTHRZ6")
        assertEquals(Triple(five.first, 0.0, true), view())
        awaitSaved("OTHRZ6|5")

        // After a restart each series opens as it was left, the chart on the symbol and interval it showed last.
        restart()
        assertEquals(Triple(five.first, 0.0, true), view())
        symbol("TESTZ6")
        assertEquals(Triple(five.first, five.second, false), view())
        tap("interval-inline-15")
        assertEquals(fifteen, view())
        // The reset button still brings the default view back, and that is kept too.
        tap("chart-reset")
        assertEquals(Triple(80.0, 0.0, true), view())
        tap("interval-inline-5"); tap("interval-inline-15")
        assertEquals(Triple(80.0, 0.0, true), view())
    }

    @Test fun aPhoneKeepsTheViewWhileAnotherPageIsShown() {
        render(400.dp, 800.dp)
        tap("chart-zoom-in"); tap("chart-zoom-in")
        val zoomed = view()
        assertTrue(zoomed.first < 80.0)
        tap("nav-0")
        compose.onNodeWithTag("trading-chart").assertDoesNotExist()
        tap("nav-1")
        assertEquals(zoomed, view())
    }
}
