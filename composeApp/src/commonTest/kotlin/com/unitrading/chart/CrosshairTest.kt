package com.unitrading.chart

import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import kotlin.test.*
import kotlin.time.Instant

class CrosshairTest {
    private val es = Instrument("ESZ6", "E-Mini S&P 500", "期货 · 202612", 6, 250_000, 12_500_000, displayPriceDecimals = 2)
    private val time = Instant.parse("2026-10-29T01:20:00Z").toEpochMilliseconds()

    @Test fun crosshairTimeShowsDateAndMinuteIntradayAndOnlyTheDateForDailyBars() {
        assertEquals("2026-10-29 01:20", crosshairTimeLabel(time, Timeframe.M5))
        assertEquals("2026-10-29 01:20", crosshairTimeLabel(time, Timeframe(240)))
        assertEquals("2026-10-29", crosshairTimeLabel(time, Timeframe(1440)))
    }

    @Test fun paneValueFollowsThePaneScaleAndIgnoresRowsOutsideTheValueAreas() {
        val rsi = ComputedIndicator(IndicatorConfig("r", IndicatorKind.RSI), listOf(IndicatorSeries(IndicatorLine("RSI"), doubleArrayOf(50.0, 50.0, 50.0), "#FFFFFF")))
        val panes = listOf(rsi, rsi)
        // Panes start at row 100 and are 100 tall; with a 10 px label and 2 px padding, values occupy rows 116–196.
        // The RSI pane range is 0–100 padded by 6 %, so the bottom row reads -6 and the top row 106.
        assertEquals(116f to 196f, paneValueArea(100f, 100f, 10f, 2f))
        assertEquals("-6.00", paneValueLabel(panes, 196f, 100f, 100f, 0..2, es, 10f, 2f))
        assertEquals("106.00", paneValueLabel(panes, 116f, 100f, 100f, 0..2, es, 10f, 2f))
        assertEquals("50.00", paneValueLabel(panes, 156f, 100f, 100f, 0..2, es, 10f, 2f))
        assertEquals("50.00", paneValueLabel(panes, 256f, 100f, 100f, 0..2, es, 10f, 2f))
        assertNull(paneValueLabel(panes, 105f, 100f, 100f, 0..2, es, 10f, 2f), "title row of the first pane")
        assertNull(paneValueLabel(panes, 99f, 100f, 100f, 0..2, es, 10f, 2f), "above the panes")
        assertNull(paneValueLabel(panes, 320f, 100f, 100f, 0..2, es, 10f, 2f), "below the last pane")
        assertNull(paneValueLabel(emptyList(), 150f, 100f, 0f, 0..2, es, 10f, 2f), "no panes")
    }
}
