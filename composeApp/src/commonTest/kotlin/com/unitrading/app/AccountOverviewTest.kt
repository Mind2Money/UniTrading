package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class AccountOverviewTest {
    private val trade = ClosedTrade("t", "position", "MES", Side.BUY, 100, 500000, 500100, 10, 20, 3_000_000, "止盈")

    @Test fun totalsComeFromTheSnapshot() {
        val totals = overviewTotals(AccountSnapshot("a", 50, equityMicros = 60, trades = listOf(trade, trade.copy(id = "loss", netPnlMicros = -1_000_000))))
        assertEquals(60L, totals.equityMicros)
        assertEquals(10L, totals.floatingMicros)
        assertEquals(2_000_000L, totals.realizedMicros)
        assertEquals(0, totals.unknownClosed)
    }

    @Test fun unknownValuesAndOverflowStayUnknownInsteadOfZero() {
        val unknown = overviewTotals(AccountSnapshot("a", 50, equityKnown = false, trades = listOf(trade.copy(netPnlMicros = null), trade.copy(id = "fees-missing", netPnlMicros = null))))
        assertNull(unknown.equityMicros)
        assertNull(unknown.floatingMicros)
        assertNull(unknown.realizedMicros, "with no result known there is no total, not a zero")
        assertEquals(2, unknown.unknownClosed)
        assertEquals(0, unknown.knownClosed, "nothing is counted, so the note names no others besides")
        assertNull(overviewTotals(AccountSnapshot("a", 0, balanceKnown = false)).floatingMicros)
        assertNull(overviewTotals(AccountSnapshot("a", 0, trades = listOf(trade.copy(netPnlMicros = Long.MAX_VALUE), trade))).realizedMicros)
    }

    /** Like the analytics page: a trade without a known result is left out of the total and counted apart, instead of blanking it. */
    @Test fun theTotalAddsUpTheKnownResultsAndCountsTheOthersApart() {
        val totals = overviewTotals(AccountSnapshot("a", 50, trades = listOf(trade, trade.copy(id = "loss", netPnlMicros = -1_000_000),
            trade.copy(id = "fees-missing", netPnlMicros = null))))
        assertEquals(2_000_000L, totals.realizedMicros)
        assertEquals(1, totals.unknownClosed)
        assertEquals(2, totals.knownClosed)
    }
}
