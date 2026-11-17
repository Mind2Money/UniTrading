package com.unitrading.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.ChartOrderActions
import com.unitrading.chart.ChartChrome
import com.unitrading.chart.PriceAxisSettings
import com.unitrading.chart.TradingChart
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** History pages land left of the view in batches, newest day first; `offset` counts bars from the newest one. */
class ChartHistoryLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val hour = 3_600_000L
    private val instrument = Instrument("@MNQ", "微型纳指", "指数", priceDecimals = 2, tickValueMicrosPerLot = 500_000)
    private val actions = ChartOrderActions({ error("unused") }, {}, { _, _ -> }, false, { _, _ -> error("unused") }, { _, _ -> },
        false, { _, _, _ -> error("unused") }, { _, _, _ -> }, { _, _, _ -> })
    private var candles by mutableStateOf(emptyList<Candle>())

    private fun bars(range: IntRange) = range.map { Candle(it * hour, 10_000L + it, 10_010L + it, 9_990L + it, 10_005L + it) }
    private fun render(first: List<Candle>, autoFit: Boolean = false) {
        candles = first
        compose.setContent {
            TradingChart("account", instrument, null, candles, Timeframe.H1, {}, emptyList(), emptyList(), null, null, null, null,
                emptyList(), actions, Modifier.fillMaxSize(), chrome = ChartChrome(priceAxis = PriceAxisSettings(fitAllBars = autoFit)))
        }
        compose.waitForIdle()
    }
    private fun state() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun offset() = state().getValue("offset").toDouble()
    private fun moveChart() {
        compose.onNodeWithTag("trading-chart").performTouchInput { swipe(Offset(width * .3f, height * .5f), Offset(width * .4f, height * .5f)) }
        compose.waitForIdle()
    }
    private fun loadOlderPages(pages: Int, assertion: () -> Unit) {
        var oldest = candles.first().timeMs / hour
        repeat(pages) {
            oldest -= 6
            candles = bars(oldest.toInt() until oldest.toInt() + 6) + candles
            compose.waitForIdle()
            assertion()
        }
    }

    @Test fun olderPagesLeaveTheNewestBarsOnScreen() {
        render(bars(2_000..2_129))
        loadOlderPages(20) { assertEquals(0.0, offset()) }
    }

    @Test fun olderPagesLeaveAMovedChartOnItsBars() {
        render(bars(2_000..2_129))
        moveChart()
        val moved = offset()
        assertNotEquals(0.0, moved)
        loadOlderPages(20) { assertEquals(moved, offset()) }
    }

    @Test fun newBarsStillLeaveAMovedChartOnItsBars() {
        render(bars(2_000..2_129))
        moveChart()
        val moved = offset()
        candles = candles + bars(2_130..2_132)
        compose.waitForIdle()
        assertEquals(moved + 3, offset())
    }

    @Test fun autoFitFollowsVisibleBarsAfterFingerAndMousePan() {
        render(bars(2_000..2_129), autoFit = true)
        val chart = compose.onNodeWithTag("trading-chart")
        val before = state()
        chart.performTouchInput { swipe(Offset(width * .3f, height * .45f), Offset(width * .55f, height * .53f)) }
        compose.waitForIdle()
        val afterTouch = state()
        assertEquals("true", afterTouch["auto"])
        assertTrue(offset() > 0.0, "the finger moved the chart through time")
        assertTrue(afterTouch.getValue("low").toDouble() < before.getValue("low").toDouble(), "axis refits older, lower bars")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * .35f, size.height * .45f)); press(); moveBy(Offset(size.width * .1f, size.height * .04f)); release() }
        compose.waitForIdle()
        val afterMouse = state()
        assertEquals("true", afterMouse["auto"])
        assertTrue(afterMouse.getValue("offset").toDouble() > afterTouch.getValue("offset").toDouble())
        assertTrue(afterMouse.getValue("low").toDouble() < afterTouch.getValue("low").toDouble())
    }

    @Test fun twoFingerZoomStaysAutomaticAndManualAxisStillWorks() {
        render(bars(2_000..2_129), autoFit = true)
        val chart = compose.onNodeWithTag("trading-chart")
        val before = state()
        chart.performTouchInput {
            down(0, Offset(width * .32f, height * .40f)); down(1, Offset(width * .58f, height * .60f))
            repeat(10) { i ->
                updatePointerTo(0, Offset(width * (.32f - .012f * (i + 1)), height * (.40f - .008f * (i + 1))))
                updatePointerTo(1, Offset(width * (.58f + .012f * (i + 1)), height * (.60f + .008f * (i + 1))))
                move()
            }
            up(0); up(1)
        }
        compose.waitForIdle()
        val zoomed = state()
        assertEquals("true", zoomed["auto"])
        assertTrue(zoomed.getValue("bars").toDouble() < before.getValue("bars").toDouble())
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width - 10f, size.height * .45f)); scroll(2f) }
        compose.waitForIdle()
        assertEquals("false", state()["auto"], "manual price-axis scaling exits auto")
        val manual = state()
        chart.performTouchInput { swipe(Offset(width * .3f, height * .45f), Offset(width * .5f, height * .52f)) }
        compose.waitForIdle()
        assertEquals("false", state()["auto"])
        assertNotEquals(manual.getValue("low"), state().getValue("low"), "manual price axis can still be panned vertically")
    }

    @Test fun zoomWidensCandlesAndKeepsAGapBetweenThem() {
        render(bars(2_000..2_129), autoFit = true)
        fun measurements() = state().let { it.getValue("barStep").toFloat() to it.getValue("barBody").toFloat() }
        val initial = measurements()
        repeat(7) { compose.onNodeWithTag("chart-zoom-in").performClick() }
        compose.waitForIdle()
        val zoomed = measurements()
        assertTrue(zoomed.first > initial.first)
        assertTrue(zoomed.second > initial.second, "the bodies, not only gaps, grow with zoom")
        assertTrue(zoomed.second > 12f, "bodies are no longer capped at 12 pixels")
        assertTrue(zoomed.second < zoomed.first, "neighbouring candles retain a gap")
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * .6f, size.height * .45f)); scroll(100f) }
        compose.waitForIdle()
        val out = measurements()
        assertTrue(out.first < zoomed.first)
        assertTrue(out.first >= 2f, "even the most zoomed-out view has distinct candle slots")
        assertTrue(out.second < out.first, "zooming out cannot make bodies overlap")
        assertTrue(out.first - out.second >= .999f, "leave at least one device pixel between candles")
        assertEquals("true", state()["auto"])
    }
}
