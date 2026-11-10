package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class AccountOverviewTest {
    private val instrument = Instrument("MES", "Micro S&P", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000)
    private val position = Position("same", "MES", Side.BUY, 100, 500000, 100, marginMicros = 0)
    private val trade = ClosedTrade("same", "position", "MES", Side.BUY, 100, 500000, 500100, 10, 20, 3_000_000, "止盈")
    private fun account(id: String, currency: String = "USD", snapshot: AccountSnapshot = AccountSnapshot(id, 50_000_000)) =
        OverviewAccount(AccountInfo(id, "Account $id", currency), snapshot)

    @Test fun accountAndEntityKindsHaveIndependentKeysAndUseEveryChild() {
        val order = PendingOrder("same", OrderRequest("MES", Side.BUY, OrderKind.LIMIT, 100, 500000), 100)
        val accounts = listOf("a", "b").map { id -> account(id, snapshot = AccountSnapshot(id, 0,
            positions = listOf(position), orders = listOf(order), trades = listOf(trade))) }
        val rows = overviewRows(accounts, listOf(instrument), MarketSnapshot(mapOf("MES" to Quote(500100, 500125, 100))))
        assertEquals(6, rows.size)
        assertEquals(6, rows.map { it.key }.distinct().size)
        assertEquals(setOf("a", "b"), rows.map { it.account.id }.toSet())
        assertEquals(2, rows.count { OverviewFilter.FLOATING_PROFIT.matches(it) })
        assertEquals(2, rows.count { OverviewFilter.REALIZED_PROFIT.matches(it) })
        assertEquals(2, rows.count { OverviewFilter.PENDING.matches(it) })
        assertTrue(rows.filter { it.kind == OverviewKind.POSITION }.all { it.pnlMicros == 5_000_000L && it.gross })
    }

    @Test fun missingQuoteOrSpecNeverShowsZeroProfit() {
        val accounts = listOf(account("a", snapshot = AccountSnapshot("a", 0, positions = listOf(position))))
        for ((specs, market) in listOf(listOf(instrument) to MarketSnapshot(), emptyList<Instrument>() to MarketSnapshot(mapOf("MES" to Quote(1, 2, 1))))) {
            val row = overviewRows(accounts, specs, market).single()
            assertNull(row.pnlMicros)
            assertEquals(OverviewStatus.UNKNOWN, row.status)
        }
    }

    @Test fun sellsUseAskAndFlatsAreNotLosses() {
        val snapshot = AccountSnapshot("a", 0, positions = listOf(position.copy(side = Side.SELL)))
        val row = overviewRows(listOf(account("a", snapshot = snapshot)), listOf(instrument), MarketSnapshot(mapOf("MES" to Quote(499975, 500000, 100)))).single()
        assertEquals(0L, row.pnlMicros)
        assertEquals(OverviewStatus.FLOATING_FLAT, row.status)
        assertFalse(OverviewFilter.FLOATING_LOSS.matches(row))
    }

    @Test fun closeReasonIsIndependentOfNetOutcomeAndUnknownNetStaysUnknown() {
        val snapshot = AccountSnapshot("a", 0, trades = listOf(
            trade.copy(id = "stopped-green", reason = "止损", netPnlMicros = 1_000_000),
            trade.copy(id = "target-red", reason = "止盈", netPnlMicros = -1_000_000),
            trade.copy(id = "fees-missing", netPnlMicros = null, grossPnlMicros = 2_000_000),
            trade.copy(id = "flat", netPnlMicros = 0)))
        val rows = overviewRows(listOf(account("a", snapshot = snapshot)), listOf(instrument), MarketSnapshot()).associateBy { it.id }
        assertEquals(OverviewStatus.REALIZED_PROFIT, rows.getValue("stopped-green").status)
        assertEquals("止损", rows.getValue("stopped-green").detail)
        assertEquals(OverviewStatus.REALIZED_LOSS, rows.getValue("target-red").status)
        assertEquals("止盈", rows.getValue("target-red").detail)
        assertEquals(OverviewStatus.UNKNOWN, rows.getValue("fees-missing").status)
        assertTrue(rows.getValue("fees-missing").gross)
        assertEquals(OverviewStatus.REALIZED_FLAT, rows.getValue("flat").status)
    }

    @Test fun totalsSeparateCurrenciesAndPropagateUnknownAndOverflow() {
        val totals = overviewTotals(listOf(
            account("a", snapshot = AccountSnapshot("a", 50, equityMicros = 60, trades = listOf(trade))),
            account("b", snapshot = AccountSnapshot("b", 80, equityKnown = false, trades = listOf(trade.copy(netPnlMicros = null)))),
            account("c", "EUR", AccountSnapshot("c", 30, equityMicros = 40))))
        val usd = totals.single { it.currency == "USD" }
        assertNull(usd.equityMicros); assertNull(usd.floatingMicros); assertNull(usd.realizedMicros)
        assertEquals(1, usd.unknownClosed)
        val eur = totals.single { it.currency == "EUR" }
        assertEquals(40L, eur.equityMicros); assertEquals(10L, eur.floatingMicros)
        val overflow = overviewTotals(listOf(account("a", snapshot = AccountSnapshot("a", Long.MAX_VALUE)), account("b"))).single()
        assertNull(overflow.equityMicros)
    }

    @Test fun individualFloatingOverflowIsUnknownButExtremeValidDifferencesRemainValid() {
        for ((equity, balance) in listOf(Long.MAX_VALUE to -1L, Long.MIN_VALUE to 1L)) {
            val total = overviewTotals(listOf(account("a", snapshot =
                AccountSnapshot("a", balance, equityMicros = equity)))).single()
            assertNull(total.floatingMicros)
        }
        val valid = overviewTotals(listOf(account("a", snapshot =
            AccountSnapshot("a", Long.MIN_VALUE, equityMicros = -1L)))).single()
        assertEquals(Long.MAX_VALUE, valid.floatingMicros)
    }

    @Test fun searchIncludesAccountAndOrderIdentityAndClearsWhitespace() {
        val row = overviewRows(listOf(account("alpha", snapshot = AccountSnapshot("alpha", 0, trades = listOf(trade)))), listOf(instrument), MarketSnapshot()).single()
        assertTrue(row.matchesQuery(" mes ")); assertTrue(row.matchesQuery("ALPHA"))
        assertTrue(row.matchesQuery("same")); assertTrue(row.matchesQuery("止盈")); assertTrue(row.matchesQuery("   "))
        assertFalse(row.matchesQuery("NQ"))
    }

    @Test fun snapshotsMustMatchTheirAccount() {
        assertFailsWith<IllegalArgumentException> { account("a", snapshot = AccountSnapshot("b", 0)) }
    }
}
