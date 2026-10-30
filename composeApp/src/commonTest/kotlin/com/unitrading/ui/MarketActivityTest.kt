package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class MarketActivityTest {
    @Test fun positionsAndAllOrderRolesRemainSeparateAndScopedToSymbol() {
        val positions = listOf(
            Position("long", "ES", Side.BUY, 100, 500000, 0, stopLoss = 499000, marginMicros = 0),
            Position("short", "ES", Side.SELL, 200, 500000, 0, marginMicros = 0),
            Position("flat", "ES", Side.BUY, 0, 500000, 0, marginMicros = 0),
        )
        val orders = OrderRole.entries.map { role ->
            PendingOrder(role.name, OrderRequest("ES", Side.BUY, OrderKind.STOP, 100, stopLoss = 499000, takeProfit = 501000), 0, role = role)
        } + PendingOrder("nq", OrderRequest("NQ", Side.SELL, OrderKind.LIMIT, 100), 0)
        val result = marketActivities(AccountSnapshot("A", 0, positions = positions, orders = orders))
        assertEquals(MarketActivity(1, 1, 1, 1, 1, 1), result["ES"])
        assertEquals(MarketActivity(entries = 1), result["NQ"])
        assertTrue(result.getValue("ES").description.contains("入场挂单 1 笔"))
        assertTrue(marketActivities(AccountSnapshot("B", 0)).isEmpty())
    }

    @Test fun cancellationAndClosingRemoveMarkersWithoutLeavingCachedState() {
        val account = AccountSnapshot("A", 0, orders = listOf(PendingOrder("order", OrderRequest("ES", Side.BUY, OrderKind.LIMIT, 100), 0)))
        assertTrue(marketActivities(account).getValue("ES").hasActivity)
        assertNull(marketActivities(account.copy(orders = emptyList()))["ES"])
        assertFalse(MarketActivity().hasActivity)
    }

    @Test fun timeAndSpreadUseActualQuoteAndContractTickSize() {
        val instrument = Instrument("ES", "ES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 12500000)
        assertEquals("00:00:00", quoteTime(0))
        assertEquals("20:51:22", quoteTime(86_400_000 + 75_082_000))
        assertEquals("2", quoteSpread(Quote(500000, 500050, 0), instrument))
        assertEquals("0.5", quoteSpread(Quote(500000, 500050, 0), instrument.copy(tickSize = 100)))
        assertEquals("0", quoteSpread(Quote(500000, 500000, 0), instrument))
        assertEquals("—", quoteSpread(null, instrument))
        assertEquals("—", quoteSpread(Quote(500050, 500000, 0), instrument))
    }
}
