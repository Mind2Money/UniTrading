package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*

class AccountAnalyticsTest {
    private fun record(id: String, pnl: Long?, time: String = "2026-11-10T02:00:00Z", currency: String = "USD", account: String = "a") =
        AnalyticsTrade(AccountInfo(account, account, currency), ClosedTrade(id, id, "MESZ6", Side.BUY, 100, 1, 2, 0,
            Instant.parse(time).toEpochMilliseconds(), pnl, "test"))

    @Test fun statisticsExcludeUnknownButIncludeBreakevenAndTrackDrawdownInOrder() {
        val records = listOf(record("a", 100_000_000), record("b", -60_000_000), record("c", 0), record("d", null), record("e", -20_000_000), record("f", 40_000_000))
        val s = tradeStatistics(records)
        assertEquals(60_000_000L, s.netMicros)
        assertEquals(40.0, s.winRate)
        assertEquals(1.75, s.profitFactor)
        assertEquals(1.75, s.averageWinLoss)
        assertEquals(80_000_000L, s.drawdownMicros)
        assertEquals(1, s.unknown); assertEquals(1, s.breakeven)
    }
    @Test fun emptyUnknownAndNoLossHaveHonestResults() {
        assertNull(tradeStatistics(emptyList()).winRate)
        assertNull(tradeStatistics(listOf(record("u", null))).netMicros)
        assertEquals(Double.POSITIVE_INFINITY, tradeStatistics(listOf(record("w", 5))).profitFactor)
        assertNull(tradeStatistics(listOf(record("b", 0))).profitFactor)
    }
    @Test fun currencyAndOverflowCannotSilentlyProduceWrongTotals() {
        assertFailsWith<IllegalArgumentException> { tradeStatistics(listOf(record("a", 1), record("b", 1, currency = "EUR"))) }
        assertNull(tradeStatistics(listOf(record("a", Long.MAX_VALUE), record("b", 1))).netMicros)
        assertNull(tradeStatistics(listOf(record("a", Long.MIN_VALUE))).drawdownMicros)
    }
    @Test fun localDatesRespectMidnightAndDaylightSaving() {
        val chicago = TimeZone.of("America/Chicago")
        assertEquals(LocalDate(2026, 11, 9), record("a", 0).date(chicago))
        assertEquals(LocalDate(2026, 11, 10), record("a", 0).date(TimeZone.of("Asia/Shanghai")))
        assertEquals(LocalDate(2026, 7, 1), record("a", 0, "2026-07-02T04:30:00Z").date(chicago))
        assertEquals(LocalDate(2026, 11, 1), record("a", 0, "2026-11-01T07:30:00Z").date(chicago))
    }
    @Test fun calendarsHandleLeapYearsAndYearBoundaries() {
        val feb = calendarDates(LocalDate(2024, 2, 1))
        assertEquals(42, feb.size); assertEquals(DayOfWeek.SUNDAY, feb.first().dayOfWeek)
        assertEquals(29, feb.count { it.month == Month.FEBRUARY })
        assertTrue(calendarDates(LocalDate(2026, 1, 1)).first().year == 2025)
    }
    @Test fun sameTradeIdInDifferentAccountsIsNotLost() {
        val record = record("same", 10)
        val accounts = listOf("a", "b").map { OverviewAccount(AccountInfo(it, it), AccountSnapshot(it, 0, trades = listOf(record.trade, record.trade))) }
        assertEquals(2, analyticsTrades(accounts).size)
        assertEquals(20L, tradeStatistics(analyticsTrades(accounts)).netMicros)
    }
}
