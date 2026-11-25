package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*
import kotlin.time.Instant

class ChartSessionTest {
    @Test fun priceSessionFollowsChicagoTimeAndWeekends() {
        fun active(value: String) = isRegularChartTime(Instant.parse(value).toEpochMilliseconds())
        assertFalse(active("2026-01-15T14:29:59Z"))
        assertTrue(active("2026-01-15T14:30:00Z"))
        assertFalse(active("2026-01-15T21:15:00Z"))
        assertTrue(active("2026-07-15T13:30:00Z"))
        assertFalse(active("2026-07-18T13:30:00Z"))
    }
    @Test fun offsetsUseTheSuppliedNetworkTimestamp() {
        val winter = Instant.parse("2026-01-15T12:00:00Z").toEpochMilliseconds()
        val summer = Instant.parse("2026-07-15T12:00:00Z").toEpochMilliseconds()
        assertEquals("(UTC-5) 纽约", com.unitrading.app.ChartTimeZone.NEW_YORK.labelAt(winter))
        assertEquals("(UTC-4) 纽约", com.unitrading.app.ChartTimeZone.NEW_YORK.labelAt(summer))
        assertEquals("(UTC-6) 芝加哥", com.unitrading.app.ChartTimeZone.CHICAGO.labelAt(winter))
        assertEquals("(UTC-5) 芝加哥", com.unitrading.app.ChartTimeZone.CHICAGO.labelAt(summer))
        assertEquals("(UTC+0) 伦敦", com.unitrading.app.ChartTimeZone.LONDON.labelAt(winter))
        assertEquals("(UTC+1) 伦敦", com.unitrading.app.ChartTimeZone.LONDON.labelAt(summer))
        assertEquals("纽约（等待行情时间）", com.unitrading.app.ChartTimeZone.NEW_YORK.labelAt(null))
    }
    private fun candle(time: String, open: Long = 10, high: Long = 12, low: Long = 9, close: Long = 11, volume: Long? = 2) =
        Candle(Instant.parse(time).toEpochMilliseconds(), open, high, low, close, volume)

    @Test fun regularSessionUsesChicagoDstAndExcludesOutsideBars() {
        val winter = listOf(candle("2026-01-15T14:29:00Z"), candle("2026-01-15T14:30:00Z"), candle("2026-01-15T21:14:00Z"), candle("2026-01-15T21:15:00Z"))
        val summer = listOf(candle("2026-07-15T13:29:00Z"), candle("2026-07-15T13:30:00Z"), candle("2026-07-15T20:14:00Z"), candle("2026-07-15T20:15:00Z"))
        assertEquals(winter.subList(1, 3), chartSessionCandles(winter, "@MES", Timeframe.M1, true).first)
        assertEquals(summer.subList(1, 3), chartSessionCandles(summer, "CLZ6", Timeframe.M1, true).first)
        assertEquals(winter, chartSessionCandles(winter, "@MES", Timeframe.M1, false).first)
    }
    @Test fun hourBarsAreRebuiltFromMinutesAtSessionOpenAndEnd() {
        val bars = listOf(
            candle("2026-01-15T14:29:00Z", high = 99),
            candle("2026-01-15T14:30:00Z", open = 10, high = 12, low = 8, close = 11, volume = 3),
            candle("2026-01-15T14:31:00Z", open = 11, high = 15, low = 7, close = 14, volume = 4),
            candle("2026-01-15T15:30:00Z", open = 14, high = 16, low = 13, close = 15, volume = null),
            candle("2026-01-15T21:14:00Z", open = 15, high = 17, low = 14, close = 16, volume = 2),
            candle("2026-01-15T21:15:00Z", high = 99))
        val result = chartSessionCandles(bars, "@ES", Timeframe.ofMinutes(60), true).first
        assertEquals(3, result.size)
        assertEquals(candle("2026-01-15T14:30:00Z", 10, 15, 7, 14, 7), result[0])
        assertEquals(candle("2026-01-15T15:30:00Z", 14, 16, 13, 15, null), result[1])
        assertEquals(candle("2026-01-15T20:30:00Z", 15, 17, 14, 16, 2), result[2])
    }
    @Test fun incompleteFirstSessionIsNotPresentedAsComplete() {
        val bars = listOf(candle("2026-01-15T15:00:00Z"), candle("2026-01-16T14:30:00Z"))
        assertEquals(listOf(bars[1]), chartSessionCandles(bars, "@ES", Timeframe.ofMinutes(60), true).first)
    }
    @Test fun multipleSessionsAcrossDaylightSavingAndUnorderedHistory() {
        val bars = listOf(candle("2026-03-06T14:30:00Z"), candle("2026-03-06T21:14:00Z"),
            candle("2026-03-09T13:30:00Z"), candle("2026-03-09T20:14:00Z"), candle("2026-03-09T20:15:00Z"))
        assertEquals(bars.dropLast(1), chartSessionCandles(bars.reversed(), "@MES", Timeframe.M1, true).first)
    }
    @Test fun dailyAndWeeklyUseUnmodifiedElectronicSessionBars() {
        val bars = listOf(candle("2026-01-15T00:00:00Z", open = 10, high = 30, low = 5, close = 25, volume = 100))
        for (symbol in listOf("@MES", "@OTHER")) for (frame in listOf(Timeframe.D1, Timeframe.ofMinutes(7 * 1440))) {
            assertEquals(frame, chartSessionSource(symbol, frame, true))
            assertEquals(bars to null, chartSessionCandles(bars, symbol, frame, true))
        }
    }
    @Test fun chooseOneMinuteSourceAndRejectUnknownSymbolsOrSeconds() {
        assertEquals(Timeframe.M1, chartSessionSource("@MES", Timeframe.ofMinutes(120), true))
        assertEquals(Timeframe.D1, chartSessionSource("@MES", Timeframe.D1, true))
        assertNotNull(chartSessionCandles(emptyList(), "@MISSING", Timeframe.M1, true).second)
        assertNotNull(chartSessionCandles(emptyList(), "@MES", Timeframe.ofSeconds(5), true).second)
        assertNull(chartSessionCandles(emptyList(), "@MES", Timeframe.D1, true).second)
    }
}
