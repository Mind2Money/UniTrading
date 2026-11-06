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
    private fun render(first: List<Candle>) {
        candles = first
        compose.setContent {
            TradingChart("account", instrument, null, candles, Timeframe.H1, {}, emptyList(), emptyList(), null, null, null, null,
                emptyList(), actions, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
    }
    private fun offset() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').first { it.startsWith("offset:") }.substringAfter(':').toDouble()
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
}
