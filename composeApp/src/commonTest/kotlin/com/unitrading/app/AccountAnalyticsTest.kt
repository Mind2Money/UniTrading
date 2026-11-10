package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*

class AccountAnalyticsTest {
    private fun trade(id: String, pnl: Long?, time: String = "2026-11-10T02:00:00Z") =
        ClosedTrade(id, id, "MESZ6", Side.BUY, 100, 1, 2, 0, Instant.parse(time).toEpochMilliseconds(), pnl, "test")

    @Test fun statisticsExcludeUnknownButIncludeBreakevenAndTrackDrawdownInOrder() {
        val trades = listOf(trade("a", 100_000_000), trade("b", -60_000_000), trade("c", 0), trade("d", null), trade("e", -20_000_000), trade("f", 40_000_000))
        val s = tradeStatistics(trades)
        assertEquals(60_000_000L, s.netMicros)
        assertEquals(40.0, s.winRate)
        assertEquals(1.75, s.profitFactor)
        assertEquals(1.75, s.averageWinLoss)
        assertEquals(80_000_000L, s.drawdownMicros)
        assertEquals(1, s.unknown); assertEquals(1, s.breakeven)
    }
    @Test fun emptyUnknownAndNoLossHaveHonestResults() {
        assertNull(tradeStatistics(emptyList()).winRate)
        assertNull(tradeStatistics(listOf(trade("u", null))).netMicros)
        assertEquals(Double.POSITIVE_INFINITY, tradeStatistics(listOf(trade("w", 5))).profitFactor)
        assertNull(tradeStatistics(listOf(trade("b", 0))).profitFactor)
    }
    @Test fun overflowCannotSilentlyProduceWrongTotals() {
        assertNull(tradeStatistics(listOf(trade("a", Long.MAX_VALUE), trade("b", 1))).netMicros)
        assertNull(tradeStatistics(listOf(trade("a", Long.MIN_VALUE))).drawdownMicros)
    }
    @Test fun localDatesRespectMidnightAndDaylightSaving() {
        val chicago = TimeZone.of("America/Chicago")
        assertEquals(LocalDate(2026, 11, 9), trade("a", 0).closeDate(chicago))
        assertEquals(LocalDate(2026, 11, 10), trade("a", 0).closeDate(TimeZone.of("Asia/Shanghai")))
        assertEquals(LocalDate(2026, 7, 1), trade("a", 0, "2026-07-02T04:30:00Z").closeDate(chicago))
        assertEquals(LocalDate(2026, 11, 1), trade("a", 0, "2026-11-01T07:30:00Z").closeDate(chicago))
    }
    @Test fun calendarsHandleLeapYearsAndYearBoundaries() {
        val feb = calendarDates(LocalDate(2024, 2, 1))
        assertEquals(42, feb.size); assertEquals(DayOfWeek.SUNDAY, feb.first().dayOfWeek)
        assertEquals(29, feb.count { it.month == Month.FEBRUARY })
        assertTrue(calendarDates(LocalDate(2026, 1, 1)).first().year == 2025)
    }
}
