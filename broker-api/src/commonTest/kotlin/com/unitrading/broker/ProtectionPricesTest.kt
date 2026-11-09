package com.unitrading.broker

import kotlin.test.*

class ProtectionPricesTest {
    private val mes = Instrument("MESZ6", "MES", "期货", 2, 25, 1_250_000)
    /** Two ticks wide, so a line can sit strictly inside the spread. */
    private val quote = Quote(550000, 550050, 0)
    private val long = Position("p", "MESZ6", Side.BUY, 100, 549000, 0, 548000, 552000, 0)
    private val short = Position("p", "MESZ6", Side.SELL, 100, 551000, 0, 552000, 549000, 0)
    private fun market(side: Side, sl: Long? = null, tp: Long? = null) =
        entryProtectionError(mes, OrderRequest("MESZ6", side, OrderKind.MARKET, 100, stopLoss = sl, takeProfit = tp), quote)
    private fun pending(side: Side, sl: Long? = null, tp: Long? = null) =
        entryProtectionError(mes, OrderRequest("MESZ6", side, OrderKind.LIMIT, 100, 540000, sl, tp), quote)

    @Test fun aMarketOrderLineClearsTheWholeSpreadOnItsOwnSide() {
        assertNull(market(Side.BUY, 549975, 550075))
        assertEquals("买入单的止损须低于当前 Bid 5500.00", market(Side.BUY, sl = 550000))
        assertEquals("买入单的止盈须高于当前 Ask 5500.50", market(Side.BUY, tp = 550050), "Exactly at the buy fill")
        assertNotNull(market(Side.BUY, tp = 550025), "Inside the spread")
        assertNull(market(Side.SELL, 550075, 549975))
        assertEquals("卖出单的止损须高于当前 Ask 5500.50", market(Side.SELL, sl = 550050))
        assertEquals("卖出单的止盈须低于当前 Bid 5500.00", market(Side.SELL, tp = 550000), "Exactly at the sell fill")
        assertNotNull(market(Side.SELL, tp = 550025), "Inside the spread")
    }

    @Test fun aPendingOrderIsMeasuredFromItsEntryNotTheMarket() {
        assertNull(pending(Side.BUY, 539975, 540025), "Both lines are far under the market, each on its side of the entry")
        assertNull(pending(Side.SELL, 540025, 539975))
        assertEquals("买入单的止损须低于入场价 5400.00", pending(Side.BUY, sl = 540000))
        assertEquals("买入单的止盈须高于入场价 5400.00", pending(Side.BUY, tp = 540000))
        assertEquals("卖出单的止损须高于入场价 5400.00", pending(Side.SELL, sl = 540000))
        assertEquals("卖出单的止盈须低于入场价 5400.00", pending(Side.SELL, tp = 540000))
    }

    @Test fun anOrderPriceOffTheTickGridIsRefused() {
        assertNotNull(market(Side.BUY, sl = 549990))
        assertNotNull(pending(Side.BUY, tp = 0))
    }

    @Test fun aPositionStopMayGoToBreakEvenOrPastIt() {
        assertNull(positionProtectionError(mes, long, quote, 549000, 552000), "Break-even")
        assertNull(positionProtectionError(mes, long, quote, 549975, 552000), "Locks in profit, one tick under the bid")
        val falling = Quote(549950, 550000, 0)
        assertNull(positionProtectionError(mes, short, falling, 551000, 549000), "Break-even")
        assertNull(positionProtectionError(mes, short, falling, 550025, 549000), "Locks in profit, one tick over the ask")
        assertNull(positionProtectionError(mes, long.copy(entryPrice = 551000), quote, 548000, 550500), "A target under a losing entry cuts the loss")
    }

    @Test fun aPositionLineCannotBeThroughTheMarket() {
        assertEquals("买入仓位的止损须低于当前 Bid 5500.00", positionProtectionError(mes, long, quote, 550000, 552000))
        assertEquals("买入仓位的止盈须高于当前 Bid 5500.00", positionProtectionError(mes, long, quote, 548000, 550000))
        assertEquals("卖出仓位的止损须高于当前 Ask 5500.50", positionProtectionError(mes, short, quote, 550050, 549000))
        assertEquals("卖出仓位的止盈须低于当前 Ask 5500.50", positionProtectionError(mes, short, quote, 552000, 550050))
    }

    @Test fun onlyTheLineThatMovesIsCheckedAgainstTheMarket() {
        assertNull(positionProtectionError(mes, long.copy(takeProfit = 550000), quote, 549000, 550000), "A target the market reached does not hold up the stop")
        assertNull(positionProtectionError(mes, long.copy(stopLoss = 550000), quote, 550000, 552500), "Nor does a stop the market reached hold up the target")
        assertNull(positionProtectionError(mes, long, quote, null, 552000), "Removing a line has no price to check")
        assertNull(positionProtectionError(mes, long, quote, 548000, 552000), "Nothing moved")
    }

    @Test fun thePricesThemselvesAreCheckedEvenWithoutAQuote() {
        assertNull(positionProtectionError(mes, long, null, 549000, 552000))
        assertNotNull(positionProtectionError(mes, long, null, 0, 552000))
        assertNotNull(positionProtectionError(mes, long, null, 549010, 552000), "Off the tick grid")
        assertEquals("止损价须低于止盈价", positionProtectionError(mes, long, null, 552000, 552000))
        assertEquals("止损价须高于止盈价", positionProtectionError(mes, short, null, 549000, 549000))
    }
}
