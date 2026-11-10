package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartRequestsTest {
    @Test fun intradayIntervalsRequestMinuteBarsAndWholeDaysRequestDailyBars() {
        val m5 = chartDescription(Timeframe.M5)
        assertEquals("MinuteBar", m5.str("underlyingType"))
        assertEquals(5L, m5.long("elementSize"))
        assertEquals("UnderlyingUnits", m5.str("elementSizeUnit"))
        assertEquals(false, m5.bool("withHistogram"))
        val h4 = chartDescription(Timeframe.ofMinutes(240))
        assertEquals("MinuteBar", h4.str("underlyingType"))
        assertEquals(240L, h4.long("elementSize"))
        val d1 = chartDescription(Timeframe.ofMinutes(1440))
        assertEquals("DailyBar", d1.str("underlyingType"))
        assertEquals(1L, d1.long("elementSize"))
        assertEquals(3L, chartDescription(Timeframe.ofMinutes(3 * 1440)).long("elementSize"))
        assertEquals("MinuteBar", chartDescription(Timeframe.ofMinutes(1500)).str("underlyingType"))
    }

    @Test fun secondIntervalsRequestTheTickSeriesGroupedBySeconds() {
        val s30 = chartDescription(Timeframe.ofSeconds(30))
        assertEquals("Tick", s30.str("underlyingType"))
        assertEquals(30L, s30.long("elementSize"))
        assertEquals("Seconds", s30.str("elementSizeUnit"))
        assertEquals(false, s30.bool("withHistogram"))
        assertEquals(1L, chartDescription(Timeframe.ofSeconds(1)).long("elementSize"))
        assertEquals(90L, chartDescription(Timeframe.ofSeconds(90)).long("elementSize"), "not a whole number of minutes, so still seconds")
        assertEquals("MinuteBar", chartDescription(Timeframe.ofSeconds(120)).str("underlyingType"), "two minutes however they were entered")
    }
}
