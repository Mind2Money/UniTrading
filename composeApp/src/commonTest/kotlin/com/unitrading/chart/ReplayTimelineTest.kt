package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*
import kotlin.time.Instant

class ReplayTimelineTest {
    @Test fun regularSessionFiltersMinuteBarsBeforeAggregation() {
        val before = Instant.parse("2026-11-23T14:29:00Z").toEpochMilliseconds()
        val bars = (0..2).map { Candle(before + it * 60_000L, 100L + it, 110L + it, 90L + it, 105L + it) }
        val visible = replayChartBars(bars, 1, Timeframe.M1, Timeframe.M1, "@MES", true)
        assertEquals(listOf(bars[1]), visible)
    }
    @Test fun brokerHistoryDoesNotRevealUnfinishedOrFutureCandles() {
        val bars = (0..3).map { Candle(it * 300_000L, 100, 110, 90, 100) }
        assertEquals(0, replayCompletedBrokerBars(bars, Timeframe.M5, 299_999).size)
        assertEquals(listOf(bars[0], bars[1]), replayCompletedBrokerBars(bars, Timeframe.M5, 600_000))
        val day = Candle(0, 100, 120, 90, 110)
        assertTrue(replayCompletedBrokerBars(listOf(day), Timeframe.D1, Timeframe.D1.millis - 1).isEmpty())
        assertEquals(listOf(day), replayCompletedBrokerBars(listOf(day), Timeframe.D1, Timeframe.D1.millis))
    }
    @Test fun largerCandleNeverContainsUnplayedMinutes() {
        val bars = (0..5).map { i -> Candle(i * 60_000L, 100L + i, 110L + i, 90L + i, 105L + i, 1) }
        val partial = replayChartBars(bars, 1, Timeframe.M1, Timeframe.M5, "@MES", false)
        assertEquals(listOf(Candle(0, 100, 111, 90, 106, 2)), partial)
        val complete = replayChartBars(bars, 4, Timeframe.M1, Timeframe.M5, "@MES", false)
        assertEquals(listOf(Candle(0, 100, 114, 90, 109, 5)), complete)
    }

    @Test fun rejectsFinerChartThanAvailableSource() {
        val bar = Candle(0, 100, 100, 100, 100)
        assertFailsWith<IllegalArgumentException> { replayChartBars(listOf(bar), 0, Timeframe.M5, Timeframe.M1, "@MES", false) }
    }

    @Test fun dailyDataIsUsedDirectlyAndIsNotSynthesizedFromMinuteData() {
        val daily = listOf(Candle(0, 100, 120, 90, 110), Candle(86_400_000, 110, 130, 100, 120))
        assertEquals(listOf(daily.first()), replayChartBars(daily, 0, Timeframe.D1, Timeframe.D1, "@MES", false))
        assertFailsWith<IllegalArgumentException> { replayChartBars(daily, 0, Timeframe.M1, Timeframe.D1, "@MES", false) }
    }
}
