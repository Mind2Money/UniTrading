package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class OrderExposureTest {
    private fun position(side: Side, lots: Long, symbol: String = "MESZ6") = Position("p-$symbol", symbol, side, lots, 550_000, 0, marginMicros = 0)
    private fun order(id: String, side: Side, lots: Long, role: OrderRole = OrderRole.ENTRY, filled: Long = 0, symbol: String = "MESZ6") =
        PendingOrder(id, OrderRequest(symbol, side, OrderKind.LIMIT, lots, 550_000), 0, role = role, filledLotsHundredths = filled)
    private fun account(positions: List<Position> = emptyList(), orders: List<PendingOrder> = emptyList()) =
        AccountSnapshot("a", 0, positions = positions, orders = orders)
    private fun request(side: Side, lots: Long, symbol: String = "MESZ6") = OrderRequest(symbol, side, OrderKind.MARKET, lots)
    private fun place(snapshot: AccountSnapshot, side: Side, lots: Long, symbol: String = "MESZ6") =
        newOrderExposure(snapshot, hedging = false, request(side, lots, symbol))

    @Test fun aSymbolCountsTheLargestPositionItsOrdersCanLeave() {
        val long = account(listOf(position(Side.BUY, 200)), listOf(
            order("add", Side.BUY, 300, filled = 100), order("exit", Side.SELL, 200), order("paired", Side.SELL, 200, OrderRole.STOP_LOSS)))
        // Long 2 with 2 more to fill is 4 at worst; the exit could only take the position to zero, and paired protection is not counted.
        assertEquals(SymbolExposure(200, 200, 200), symbolExposure(long, "MESZ6"))
        assertEquals(400, symbolExposure(long, "MESZ6").worst)
        assertEquals(SymbolExposure(200, 0, 200), symbolExposure(long, "MESZ6", except = long.orders.first()))
        val short = account(listOf(position(Side.SELL, 300)), listOf(order("cover", Side.BUY, 100)))
        assertEquals(300, symbolExposure(short, "MESZ6").worst, "never below the position held")
        assertEquals(0, symbolExposure(short, "ESZ6").worst)
        assertEquals(300L to 0L, symbolExposure(short, "MESZ6").let { it.closable(Side.BUY) to it.closable(Side.SELL) })
    }

    @Test fun aNewOrderIsNeutralOnlyWhileItCanDoNothingButReduceThePosition() {
        val long = account(listOf(position(Side.BUY, 200), position(Side.SELL, 100, "ESZ6")))
        assertEquals(ExposureEffect(300, 300, true), place(long, Side.SELL, 200), "a stop or a full close")
        assertEquals(ExposureEffect(300, 300, true), place(long, Side.SELL, 100), "a partial close")
        assertEquals(ExposureEffect(300, 400, false), place(long, Side.BUY, 100), "an add")
        assertEquals(ExposureEffect(300, 400, false), place(long, Side.SELL, 100, "NQZ6"), "a symbol without a position")
        // Reversing leaves a worst case no larger than the position held, so the cap lets it through, but it opens the other side.
        assertEquals(ExposureEffect(300, 300, false), place(long, Side.SELL, 300))
        assertEquals(ExposureEffect(300, 500, false), place(long, Side.SELL, 600))
        assertFalse(place(long, Side.SELL, 0).neutral)
        // A stop and a target rest side by side: together they cannot leave more than the position either way. A third exit could.
        val exits = long.copy(orders = listOf(order("stop", Side.SELL, 200)))
        assertEquals(ExposureEffect(300, 300, true), place(exits, Side.SELL, 200))
        val both = exits.copy(orders = exits.orders + order("target", Side.SELL, 200))
        assertEquals(ExposureEffect(300, 400, false), place(both, Side.SELL, 100))
        // An oversized order on the other side does not make room to add on this one.
        val reversal = long.copy(orders = listOf(order("reverse", Side.SELL, 1_000)))
        assertEquals(ExposureEffect(900, 900, false), place(reversal, Side.BUY, 500))
        assertEquals(ExposureEffect(100, 100, true), place(long.copy(positions = long.positions.drop(1)), Side.BUY, 100, "ESZ6"), "a buy covers the short")
    }

    @Test fun anEditCountsWhatTheOrderLeavesWorking() {
        val stop = order("stop", Side.SELL, 300, filled = 100)
        val entry = order("add", Side.BUY, 100)
        val leg = order("leg", Side.SELL, 100, OrderRole.STOP_LOSS)
        val long = account(listOf(position(Side.BUY, 200)), listOf(stop, entry, leg))
        // Long 2, 1 more to buy: 3 at worst. The stop has 2 of its 3 left.
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, stop), "a price change")
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, stop, 200), "one lot left")
        assertEquals(ExposureEffect(300, 300, false), editedOrderExposure(long, false, stop, 400), "three left would reverse the position")
        assertEquals(ExposureEffect(300, 600, false), editedOrderExposure(long, false, stop, 900))
        assertEquals(ExposureEffect(300, 300, false), editedOrderExposure(long, false, entry), "an entry is no exit, moved or not")
        assertEquals(ExposureEffect(300, 250, false), editedOrderExposure(long, false, entry, 50), "smaller, and still an entry")
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long, false, entry, 200))
        // Paired protection is left out until it grows; then it counts like any order, and what exceeds the position opens one.
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, leg))
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 200))
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 600))
        assertFalse(editedOrderExposure(account(orders = listOf(leg)), false, leg).neutral, "nothing is left for it to protect")
    }

    @Test fun aHedgingAccountAddsEverythingUpAndHasNoExitOrders() {
        val hedged = account(listOf(position(Side.BUY, 200), position(Side.SELL, 100, "ESZ6")), listOf(
            order("entry", Side.SELL, 300, filled = 100), order("paired", Side.SELL, 200, OrderRole.STOP_LOSS)))
        assertEquals(ExposureEffect(500, 600, false), newOrderExposure(hedged, hedging = true, request(Side.SELL, 100)))
        assertEquals(ExposureEffect(500, 500, false), editedOrderExposure(hedged, true, hedged.orders[0]))
        assertEquals(ExposureEffect(500, 400, false), editedOrderExposure(hedged, true, hedged.orders[0], 200))
        assertEquals(ExposureEffect(500, 500, true), editedOrderExposure(hedged, true, hedged.orders[1]))
        assertEquals(ExposureEffect(500, 600, false), editedOrderExposure(hedged, true, hedged.orders[1], 300))
        assertFalse(reducesPosition(hedged, true, hedged.orders[0]))
        assertTrue(reducesPosition(hedged, true, hedged.orders[1]))
        // On a net account the same order works against the long, whatever its role.
        assertTrue(reducesPosition(hedged, false, hedged.orders[0]))
        assertFalse(reducesPosition(hedged, false, order("add", Side.BUY, 100)))
        assertFalse(reducesPosition(hedged, false, order("flat", Side.SELL, 100, symbol = "NQZ6")))
    }

    @Test fun absurdQuantitiesCannotOverflowIntoASmallTotal() {
        val long = account(listOf(position(Side.BUY, 200)))
        val huge = place(long, Side.BUY, Long.MAX_VALUE)
        assertTrue(huge.after > huge.before && huge.after > 999_999_999L && !huge.neutral)
        assertNotNull(AccountOrderLimits(maxLots = 999_999_999L).refusal(request(Side.BUY, Long.MAX_VALUE), huge))
    }

    @Test fun aStopLoosensWhenItMovesAwayFromTheMarketOrGoes() {
        assertFalse(stopLoosened(Side.BUY, 548_000, 548_000))
        assertFalse(stopLoosened(Side.BUY, 548_000, 549_000))
        assertTrue(stopLoosened(Side.BUY, 548_000, 547_975))
        assertTrue(stopLoosened(Side.BUY, 548_000, null))
        assertFalse(stopLoosened(Side.BUY, null, 547_000), "a first stop")
        assertFalse(stopLoosened(Side.BUY, null, null))
        assertFalse(stopLoosened(Side.SELL, 552_000, 551_000))
        assertTrue(stopLoosened(Side.SELL, 552_000, 552_025))
        // A sell stop guards a long, a buy stop a short; a limit order is not a stop at all.
        val sellStop = PendingOrder("s", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 548_000), 0)
        assertFalse(sellStop.stopLoosenedAt(549_000))
        assertTrue(sellStop.stopLoosenedAt(547_000))
        val buyStop = PendingOrder("b", OrderRequest("MESZ6", Side.BUY, OrderKind.STOP, 100, 552_000), 0)
        assertFalse(buyStop.stopLoosenedAt(551_000))
        assertTrue(buyStop.stopLoosenedAt(553_000))
        assertFalse(PendingOrder("l", OrderRequest("MESZ6", Side.SELL, OrderKind.LIMIT, 100, 552_000), 0).stopLoosenedAt(540_000))
    }
}
