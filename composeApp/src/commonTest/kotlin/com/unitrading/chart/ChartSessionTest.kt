package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*
import kotlin.time.Instant

class ChartSessionTest {
    private fun candle(time: String) = Candle(Instant.parse(time).toEpochMilliseconds(), 1, 2, 0, 1, 3)
    @Test fun regularSessionUsesChicagoDstAndWholeBars() {
        val winter = listOf(candle("2026-01-15T14:29:00Z"), candle("2026-01-15T14:30:00Z"), candle("2026-01-15T21:14:00Z"), candle("2026-01-15T21:15:00Z"))
        val summer = listOf(candle("2026-07-15T13:29:00Z"), candle("2026-07-15T13:30:00Z"), candle("2026-07-15T20:14:00Z"), candle("2026-07-15T20:15:00Z"))
        assertEquals(winter.subList(1, 3), chartSessionCandles(winter, "@MES", Timeframe.M1, true).first)
        assertEquals(summer.subList(1, 3), chartSessionCandles(summer, "CLZ6", Timeframe.M1, true).first)
        assertEquals(winter, chartSessionCandles(winter, "@MES", Timeframe.M1, false).first)
    }
    @Test fun rejectsUnknownSymbolsAndUnsafeBarBoundaries() {
        assertNotNull(chartSessionCandles(emptyList(), "@MISSING", Timeframe.M1, true).second)
        assertNotNull(chartSessionCandles(emptyList(), "@MES", Timeframe.ofMinutes(60), true).second)
        assertNull(chartSessionCandles(emptyList(), "@MES", Timeframe.D1, true).second)
    }
}
