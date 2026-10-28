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
        val h4 = chartDescription(Timeframe(240))
        assertEquals("MinuteBar", h4.str("underlyingType"))
        assertEquals(240L, h4.long("elementSize"))
        val d1 = chartDescription(Timeframe(1440))
        assertEquals("DailyBar", d1.str("underlyingType"))
        assertEquals(1L, d1.long("elementSize"))
        assertEquals(3L, chartDescription(Timeframe(3 * 1440)).long("elementSize"))
        assertEquals("MinuteBar", chartDescription(Timeframe(1500)).str("underlyingType"))
    }
}
