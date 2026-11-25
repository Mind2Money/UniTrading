package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.chart.pine.PinePlot
import com.unitrading.chart.pine.PinePlotStyle
import com.unitrading.chart.pine.PineResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartPineTest {
    private fun bar(time: Long) = Candle(time, 10_000, 10_100, 9_900, 10_000)
    private val candles = (1L..8).map { bar(it * 60_000) }

    @Test fun alignsPrependAndRolloverButRejectsInteriorChanges() {
        val replay = candles.subList(2, 7)
        assertEquals(2, pineSampleOffset(replay, candles))
        assertEquals(-1, pineSampleOffset(replay, candles.drop(3)))
        assertEquals(2, pineSampleOffset(replay, candles.mapIndexed { index, bar -> if (index == 4) bar.copy(high = 10_300) else bar }))
        assertNull(pineSampleOffset(replay, candles.filterIndexed { index, _ -> index != 4 }))
        assertNull(pineSampleOffset(replay, listOf(bar(900_000))))
    }

    @Test fun pricesCanExpandTheAxisButArbitraryOutputsCannotFlattenIt() {
        fun pane(vararg values: Double): ChartPinePanel {
            val plot = PinePlot(1, "Line", values, Array(values.size) { 0xFF000000.toInt() }, PinePlotStyle.LINE, 1f, 0, 0.0, true)
            val result = PineResult("Overlay", true, listOf(plot), emptyList(), emptyList(), emptyList(), emptyList(), emptyArray(), emptyArray())
            return ChartPinePanel(result, 0)
        }
        val range = candles.indices
        val near = pane(98.0, 102.0)
        val far = pane(1e10, 9e16)
        val negative = pane(-1e10, 0.0)
        assertEquals(listOf(9_800L, 10_200L), pineOverlayScaleValues(listOf(near, far, negative), range, range, candles, 60_000, 100.0))
        assertEquals(emptyList(), pineOverlayScaleValues(listOf(far), range, range, candles, 60_000, 100.0))
    }
}
