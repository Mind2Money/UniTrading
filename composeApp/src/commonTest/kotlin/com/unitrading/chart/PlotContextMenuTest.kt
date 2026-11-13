package com.unitrading.chart

import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Quote
import com.unitrading.broker.Side
import kotlin.test.*

/** Which entry orders the plot's context menu offers at a price, and how it names them. */
class PlotContextMenuTest {
    private val quote = Quote(bid = 550_500, ask = 550_525, timeMs = 0)
    private fun orders(price: Long, kinds: Set<OrderKind> = OrderKind.entries.toSet(), at: Quote? = quote) =
        plotOrders(price, at, kinds).map { it.side to it.kind }

    @Test fun aPriceUnderTheMarketOffersABuyLimitAndASellStop() {
        assertEquals(listOf(Side.BUY to OrderKind.LIMIT, Side.SELL to OrderKind.STOP), orders(550_000))
    }

    @Test fun aPriceOverTheMarketOffersASellLimitAndABuyStop() {
        assertEquals(listOf(Side.SELL to OrderKind.LIMIT, Side.BUY to OrderKind.STOP), orders(551_000))
    }

    @Test fun aLimitMayRestOnItsOwnEdgeOfTheSpreadAndAStopHasToBeBeyondIt() {
        assertEquals(listOf(Side.BUY to OrderKind.LIMIT), orders(550_500), "at the bid")
        assertEquals(listOf(Side.SELL to OrderKind.LIMIT), orders(550_525), "at the ask")
        assertEquals(listOf(Side.BUY to OrderKind.LIMIT, Side.SELL to OrderKind.LIMIT), orders(550_550, at = Quote(550_500, 550_600, 0)), "inside a wide spread")
        assertTrue(orders(550_500, at = Quote(550_500, 550_500, 0)).isEmpty(), "on a quote without a spread nothing can rest")
    }

    @Test fun nothingIsOfferedWithoutAQuoteOrOfAKindTheBrokerLacks() {
        assertTrue(orders(550_000, at = null).isEmpty())
        assertEquals(listOf(Side.BUY to OrderKind.LIMIT), orders(550_000, setOf(OrderKind.MARKET, OrderKind.LIMIT)))
        assertEquals(listOf(Side.BUY to OrderKind.STOP), orders(551_000, setOf(OrderKind.STOP)))
        assertTrue(orders(550_000, setOf(OrderKind.MARKET)).isEmpty(), "the menu never offers a market order")
        assertTrue(orders(550_000, emptySet()).isEmpty())
    }

    @Test fun theLabelNamesSideKindQuantityAndPrice() {
        val futures = Instrument("TESTZ6", "Test", "Test", 2, 25, 1_250_000, quantityUnit = "张", quantityDecimals = 0)
        assertEquals("买入限价 2 张 @ 5500.00", plotOrderLabel(futures, PlotOrder(Side.BUY, OrderKind.LIMIT), 200, 550_000))
        assertEquals("卖出突破 @ 5500.00", plotOrderLabel(futures, PlotOrder(Side.SELL, OrderKind.STOP), null, 550_000), "no quantity that parses in the order panel")
        val lots = Instrument("EURUSD", "Euro", "Forex", 5, tickValueMicrosPerLot = 1_000_000)
        assertEquals("卖出限价 0.10 手 @ 1.08500", plotOrderLabel(lots, PlotOrder(Side.SELL, OrderKind.LIMIT), 10, 108_500))
    }

    @Test fun theLabelSaysWhatTheAccountsBracketsDoToTheOrder() {
        val futures = Instrument("TESTZ6", "Test", "Test", 2, 25, 1_250_000, quantityUnit = "张", quantityDecimals = 0)
        val buy = PlotOrder(Side.BUY, OrderKind.LIMIT)
        // With confirmations skipped this label is all that is shown before the order goes out with its protection.
        assertEquals("买入限价 2 张 @ 5500.00 · 开仓自动附加 SL/TP", plotOrderLabel(futures, buy, 200, 550_000, attached = "SL/TP"))
        assertEquals("买入限价 2 张 @ 5500.00 · 开仓自动附加 TP", plotOrderLabel(futures, buy, 200, 550_000, attached = "TP"))
        // An account that sizes from a stop sends nothing from the menu: the item opens the order panel, where the stop sets the quantity.
        assertEquals("买入限价 @ 5500.00 · 打开下单栏填写 SL", plotOrderLabel(futures, buy, 200, 550_000, sizing = true))
    }
}
