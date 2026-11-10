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
        assertEquals("2026-10-29 01:20", crosshairTimeLabel(time, Timeframe.ofMinutes(240)))
        assertEquals("2026-10-29", crosshairTimeLabel(time, Timeframe.ofMinutes(1440)))
        assertEquals("2026-10-29 01:20:00", crosshairTimeLabel(time, Timeframe.ofSeconds(5)), "second intervals show the second")
        assertEquals("2026-10-29 01:20:45", crosshairTimeLabel(time + 45_250, Timeframe.ofSeconds(15)))
        assertEquals("2026-10-29 01:20", dateTime(time + 45_250))
        assertEquals("2026-10-29 01:20:45", dateTime(time + 45_250, seconds = true))
    }

    @Test fun paneValueFollowsThePaneScaleAndIgnoresRowsOutsideTheValueAreas() {
        val rsi = ComputedIndicator(IndicatorConfig("r", IndicatorKind.RSI), listOf(IndicatorSeries(IndicatorLine("RSI"), doubleArrayOf(50.0, 50.0, 50.0), "#FFFFFF")))
        val panes = listOf(rsi, rsi)
        val layout = listOf(IndicatorPaneBounds("r", 100f, 100f), IndicatorPaneBounds("r2", 200f, 100f))
        // Panes start at row 100 and are 100 tall; with a 10 px label and 2 px padding, values occupy rows 116–196.
        // The RSI pane range is 0–100 padded by 6 %, so the bottom row reads -6 and the top row 106.
        assertEquals(116f to 196f, paneValueArea(100f, 100f, 10f, 2f))
        assertEquals("-6.00", paneValueLabel(panes, 196f, layout, 0..2, es, 10f, 2f))
        assertEquals("106.00", paneValueLabel(panes, 116f, layout, 0..2, es, 10f, 2f))
        assertEquals("50.00", paneValueLabel(panes, 156f, layout, 0..2, es, 10f, 2f))
        assertEquals("50.00", paneValueLabel(panes, 256f, layout, 0..2, es, 10f, 2f))
        assertNull(paneValueLabel(panes, 105f, layout, 0..2, es, 10f, 2f), "title row of the first pane")
        assertNull(paneValueLabel(panes, 99f, layout, 0..2, es, 10f, 2f), "above the panes")
        assertNull(paneValueLabel(panes, 320f, layout, 0..2, es, 10f, 2f), "below the last pane")
        assertNull(paneValueLabel(emptyList(), 150f, emptyList(), 0..2, es, 10f, 2f), "no panes")
        val resized = listOf(IndicatorPaneBounds("r", 100f, 150f), IndicatorPaneBounds("r2", 250f, 50f))
        assertEquals("50.00", paneValueLabel(panes, 181f, resized, 0..2, es, 10f, 2f))
        assertEquals("50.00", paneValueLabel(panes, 281f, resized, 0..2, es, 10f, 2f))
    }
}
