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
        // Long 2 with 2 more to fill is 4 at worst; the exit and the paired stop together could only leave short 2.
        assertEquals(SymbolExposure(200, 200, 400), symbolExposure(long, "MESZ6"))
        assertEquals(400, symbolExposure(long, "MESZ6").worst)
        assertEquals(SymbolExposure(200, 0, 400), symbolExposure(long, "MESZ6") { if (it.id == "add") 0 else it.remainingLots })
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
        // Long 2, 1 more to buy: 3 at worst. The stop has 2 of its 3 left, and with the paired leg it could leave short 1.
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, stop), "a price change")
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, stop, 200), "one lot left")
        assertEquals(ExposureEffect(300, 300, false), editedOrderExposure(long, false, stop, 400), "three left would reverse the position")
        assertEquals(ExposureEffect(300, 700, false), editedOrderExposure(long, false, stop, 900))
        assertEquals(ExposureEffect(300, 300, false), editedOrderExposure(long, false, entry), "an entry is no exit, moved or not")
        assertEquals(ExposureEffect(300, 250, false), editedOrderExposure(long, false, entry, 50), "smaller, and still an entry")
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long, false, entry, 200))
        // Paired protection counts at its new size too, and what exceeds the position opens one.
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long, false, leg))
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 200))
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 600))
        assertFalse(editedOrderExposure(account(orders = listOf(leg)), false, leg).neutral, "nothing is left for it to protect")
    }

    @Test fun theLegsOfTheBrokersBracketsCountAsPairsOfWhichOneSideFills() {
        // Long 2 from two filled brackets of 1: stops A and B, targets TA and TB. A stop and its target cancel each other.
        val a = order("A", Side.SELL, 100, OrderRole.STOP_LOSS)
        val legs = listOf(a, order("B", Side.SELL, 100, OrderRole.STOP_LOSS),
            order("TA", Side.SELL, 100, OrderRole.TAKE_PROFIT), order("TB", Side.SELL, 100, OrderRole.TAKE_PROFIT))
        val long = account(listOf(position(Side.BUY, 200)), legs)
        assertEquals(SymbolExposure(200, 0, 200), symbolExposure(long, "MESZ6"))
        val cap = AccountOrderLimits(maxLots = 200)
        fun edit(snapshot: AccountSnapshot, id: String, lots: Long) = snapshot.orders.single { it.id == id }.let { leg ->
            editedOrderExposure(snapshot, false, leg, lots) to cap.refusal(leg.request, editedOrderExposure(snapshot, false, leg, lots), edit = true)
        }
        // A raised to 3: all the stops filling leave short 2, which the cap allows.
        assertEquals(ExposureEffect(200, 200, false) to null, edit(long, "A", 300))
        // B raised as well, after A: six lots of stops against the long of two would leave short 4.
        val raisedA = long.copy(orders = listOf(a.copy(request = a.request.copy(lotsHundredths = 300))) + legs.drop(1))
        assertEquals(ExposureEffect(200, 400, false), edit(raisedA, "B", 300).first)
        assertNotNull(edit(raisedA, "B", 300).second)
        // Or the target of the other bracket raised after A: nothing says which stop goes with which target, and A with
        // TB can both fill, so the legs pair the way that fills the most (A with TA, B with TB): six lots, short 4.
        assertEquals(ExposureEffect(200, 400, false), edit(raisedA, "TB", 300).first)
        assertNotNull(edit(raisedA, "TB", 300).second)
        // One leg raised to 6 at once: short 5.
        assertEquals(ExposureEffect(200, 500, false), edit(long, "A", 600).first)
        assertNotNull(edit(long, "A", 600).second)
        // A partial close beside the two brackets changes nothing: it is an exit.
        assertEquals(ExposureEffect(200, 200, true), place(long, Side.SELL, 100))
        assertNull(cap.refusal(request(Side.SELL, 100), place(long, Side.SELL, 100)))
    }

    @Test fun aFlattenKeepsNoMoreOrdersAgainstAPositionThanItCanTake() {
        // Long 1 with a sell limit of 3 placed earlier: filled, it would leave the account short 2.
        val reversal = account(listOf(position(Side.BUY, 100)), listOf(order("reverse", Side.SELL, 300)))
        assertFalse(reducesPosition(reversal, false, reversal.orders.single()))
        // Long 2 with a target, a stop and a second stop placed by hand: the stops come first, and what is left of the
        // position after them keeps no more orders. The broker's bracket on the other contract counts as one pair.
        val target = order("target", Side.SELL, 200)
        val stop = PendingOrder("stop", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 548_000), 0)
        val second = stop.copy(id = "second", request = stop.request.copy(lotsHundredths = 200))
        val bracketStop = PendingOrder("sl", OrderRequest("ESZ6", Side.SELL, OrderKind.STOP, 200, 548_000), 0, role = OrderRole.STOP_LOSS)
        val bracketTarget = order("tp", Side.SELL, 200, OrderRole.TAKE_PROFIT, symbol = "ESZ6")
        val held = account(listOf(position(Side.BUY, 200), position(Side.BUY, 200, "ESZ6")),
            listOf(target, stop, second, bracketStop, bracketTarget))
        assertEquals(listOf(true, false, false, true, true), listOf(stop, second, target, bracketStop, bracketTarget).map { reducesPosition(held, false, it) })
        // A filled part does not count, and a stop on the position's own side reduces nothing.
        val small = target.copy(request = target.request.copy(lotsHundredths = 100))
        assertFalse(reducesPosition(held.copy(orders = listOf(second, small)), false, small))
        assertTrue(reducesPosition(held.copy(orders = listOf(second.copy(filledLotsHundredths = 100), small)), false, small))
        assertFalse(reducesPosition(held, false, stop.copy(id = "add", request = stop.request.copy(side = Side.BUY))))
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

    @Test fun theRoomUnderACapIsTheLargestOrderTheCapLetsThrough() {
        fun room(snapshot: AccountSnapshot, hedging: Boolean, side: Side, cap: Long, symbol: String = "MESZ6"): Long {
            val lots = lotsUnderCap(snapshot, hedging, request(side, 1, symbol), cap)
            fun refusal(size: Long) = request(side, size, symbol).let { AccountOrderLimits(cap).refusal(it, newOrderExposure(snapshot, hedging, it)) }
            if (lots > 0) assertNull(refusal(lots), "$lots $side under $cap")
            assertNotNull(refusal(lots + 1), "${lots + 1} $side under $cap")
            return lots
        }
        assertEquals(500, room(account(), false, Side.BUY, 500))
        // Long 3 with 1 more to fill, and short 1 elsewhere: 5 at worst.
        val long = account(listOf(position(Side.BUY, 300), position(Side.SELL, 100, "ESZ6")), listOf(order("add", Side.BUY, 100)))
        assertEquals(200, room(long, false, Side.BUY, 700))
        assertEquals(200, room(long, false, Side.BUY, 700, "NQZ6"), "another symbol adds to the same total")
        assertEquals(900, room(long, false, Side.SELL, 700), "three to close the long, and six make a short as large as the cap leaves")
        // Over a lowered cap only an exit passes: nothing may add, and the other side may close the long but not reverse
        // it, although a short of up to 4 would leave the total where it is.
        assertEquals(0, room(long, false, Side.BUY, 200))
        assertEquals(300, room(long, false, Side.SELL, 200))
        assertEquals(0, room(long, false, Side.SELL, 200, "NQZ6"), "nothing is held there")
        assertEquals(100, room(long, false, Side.BUY, 200, "ESZ6"), "the short of 1 there")
        // A hedging account adds everything up: 4 held and 1 working.
        assertEquals(200, room(long, true, Side.SELL, 700))
        assertEquals(0, room(long, true, Side.BUY, 400))
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
