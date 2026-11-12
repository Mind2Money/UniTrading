package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class OrderExposureTest {
    private fun position(side: Side, lots: Long, symbol: String = "MESZ6") = Position("p-$symbol", symbol, side, lots, 550_000, 0, marginMicros = 0)
    private fun order(id: String, side: Side, lots: Long, role: OrderRole = OrderRole.ENTRY, filled: Long = 0, symbol: String = "MESZ6", group: String? = null) =
        PendingOrder(id, OrderRequest(symbol, side, if (role == OrderRole.STOP_LOSS) OrderKind.STOP else OrderKind.LIMIT, lots, 550_000), 0,
            role = role, filledLotsHundredths = filled, ocoGroup = group)
    /** The stop [name] and the target T[name] of one broker bracket against a long: the broker cancels one when the other fills. */
    private fun bracket(name: String, stop: Long, target: Long = stop) = listOf(
        order(name, Side.SELL, stop, OrderRole.STOP_LOSS, group = name), order("T$name", Side.SELL, target, OrderRole.TAKE_PROFIT, group = name))
    private fun account(positions: List<Position> = emptyList(), orders: List<PendingOrder> = emptyList()) =
        AccountSnapshot("a", 0, positions = positions, orders = orders)
    private fun request(side: Side, lots: Long, symbol: String = "MESZ6") = OrderRequest(symbol, side, OrderKind.MARKET, lots)
    private fun place(snapshot: AccountSnapshot, side: Side, lots: Long, symbol: String = "MESZ6") =
        newOrderExposure(snapshot, hedging = false, request(side, lots, symbol))
    private fun raised(snapshot: AccountSnapshot, id: String, lots: Long) = snapshot.copy(orders = snapshot.orders.map {
        if (it.id == id) it.copy(request = it.request.copy(lotsHundredths = lots)) else it
    })

    @Test fun aSymbolCountsTheLargestPositionItsOrdersCanLeave() {
        val long = account(listOf(position(Side.BUY, 200)), listOf(
            order("add", Side.BUY, 300, filled = 100), order("exit", Side.SELL, 200), order("leg", Side.SELL, 200, OrderRole.STOP_LOSS)))
        // Long 2 with 2 more to fill is 4 at worst; the exit and the stop together could leave short 2.
        assertEquals(SymbolExposure(200, 200, 400), symbolExposure(long, "MESZ6"))
        assertEquals(400, symbolExposure(long, "MESZ6").worst)
        assertEquals(SymbolExposure(200, 0, 400), symbolExposure(long, "MESZ6") { if (it.id == "add") 0 else it.remainingLots })
        val short = account(listOf(position(Side.SELL, 300)), listOf(order("cover", Side.BUY, 100)))
        assertEquals(300, symbolExposure(short, "MESZ6").worst, "never below the position held")
        assertEquals(0, symbolExposure(short, "ESZ6").worst)
        assertEquals(300L to 0L, symbolExposure(short, "MESZ6").let { it.closable(Side.BUY) to it.closable(Side.SELL) })
    }

    @Test fun aNewOrderIsAnExitOnlyWhileWhatWorksAgainstThePositionStillFitsInIt() {
        val long = account(listOf(position(Side.BUY, 200), position(Side.SELL, 100, "ESZ6")))
        assertEquals(ExposureEffect(300, 300, true), place(long, Side.SELL, 200), "a stop or a full close")
        assertEquals(ExposureEffect(300, 300, true), place(long, Side.SELL, 100), "a partial close")
        assertEquals(ExposureEffect(300, 400, false), place(long, Side.BUY, 100), "an add")
        assertEquals(ExposureEffect(300, 400, false), place(long, Side.SELL, 100, "NQZ6"), "a symbol without a position")
        // Reversing leaves a worst case no larger than the position held, so a cap above it lets it through, but it is
        // more than the position: it reduces nothing and needs a stop like any entry.
        assertEquals(ExposureEffect(300, 300, false), place(long, Side.SELL, 300))
        assertEquals(ExposureEffect(300, 500, false), place(long, Side.SELL, 600))
        assertFalse(place(long, Side.SELL, 0).reduces)
        // A stop of 1 placed by hand leaves room for one more exit of 1. A stop of 2 leaves none: a partial close that filled
        // first would leave the stop to open a short. Those orders still reduce, and need no stop of their own.
        val stopOfOne = long.copy(orders = listOf(order("stop", Side.SELL, 100)))
        assertEquals(ExposureEffect(300, 300, true), place(stopOfOne, Side.SELL, 100))
        assertEquals(ExposureEffect(300, 300, false, reduces = true), place(stopOfOne, Side.SELL, 200))
        val stopOfTwo = long.copy(orders = listOf(order("stop", Side.SELL, 200)))
        assertEquals(ExposureEffect(300, 300, false, reduces = true), place(stopOfTwo, Side.SELL, 200))
        assertEquals(ExposureEffect(300, 300, false, reduces = true), place(stopOfTwo, Side.SELL, 100))
        assertFalse(place(stopOfTwo, Side.SELL, 100).harmless)
        // An oversized order on the other side does not make room to add on this one.
        val reversal = long.copy(orders = listOf(order("reverse", Side.SELL, 1_000)))
        assertEquals(ExposureEffect(900, 900, false), place(reversal, Side.BUY, 500))
        assertEquals(ExposureEffect(100, 100, true), place(long.copy(positions = long.positions.drop(1)), Side.BUY, 100, "ESZ6"), "a buy covers the short")
    }

    @Test fun anEditCountsWhatTheOrderLeavesWorkingAndAnOrderThatReducesMayMoveOrShrink() {
        val stop = order("stop", Side.SELL, 300, filled = 100)
        val entry = order("add", Side.BUY, 100)
        val leg = order("leg", Side.SELL, 100, OrderRole.STOP_LOSS)
        val long = account(listOf(position(Side.BUY, 200)), listOf(stop, entry, leg))
        // Long 2, 1 more to buy: 3 at worst. The stop has 2 of its 3 left, and with the leg beside it could leave short 1:
        // moved, it is no exit, but it reduces and takes no more than before, which a pause lets through.
        val moved = editedOrderExposure(long, false, stop)
        assertEquals(ExposureEffect(300, 300, false, reduces = true, grows = false), moved, "a price change")
        assertTrue(moved.harmless)
        assertEquals(ExposureEffect(300, 300, true, grows = false), editedOrderExposure(long, false, stop, 200), "one lot left fits beside the leg")
        assertEquals(ExposureEffect(300, 300, false), editedOrderExposure(long, false, stop, 400), "three left would reverse the position")
        assertEquals(ExposureEffect(300, 700, false), editedOrderExposure(long, false, stop, 900))
        assertEquals(ExposureEffect(300, 300, false, grows = false), editedOrderExposure(long, false, entry), "an entry reduces nothing, moved or not")
        assertFalse(editedOrderExposure(long, false, entry).harmless)
        assertEquals(ExposureEffect(300, 250, false, grows = false), editedOrderExposure(long, false, entry, 50), "smaller, and still an entry")
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long, false, entry, 200))
        // A stop leg counts at its new size too, and what exceeds the position opens one.
        assertEquals(ExposureEffect(300, 300, false, reduces = true, grows = false), editedOrderExposure(long, false, leg))
        assertEquals(ExposureEffect(300, 300, true), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 200))
        assertEquals(ExposureEffect(300, 400, false), editedOrderExposure(long.copy(orders = listOf(entry, leg)), false, leg, 600))
        assertFalse(editedOrderExposure(account(orders = listOf(leg)), false, leg).reduces, "nothing is left for it to protect")
    }

    @Test fun theLegsOfOneBrokerBracketCountOnceAndLegsWithoutAGroupInFull() {
        // Long 2 from two filled brackets of 1: A with TA, B with TB. A stop and the target of its bracket cancel each other.
        val legs = bracket("A", 100) + bracket("B", 100)
        val long = account(listOf(position(Side.BUY, 200)), legs)
        assertEquals(SymbolExposure(200, 0, 200), symbolExposure(long, "MESZ6"))
        val cap = AccountOrderLimits(maxLots = 200)
        fun edit(snapshot: AccountSnapshot, id: String, lots: Long) = snapshot.orders.single { it.id == id }.let { leg ->
            editedOrderExposure(snapshot, false, leg, lots).let { it to cap.refusal(leg.request.copy(lotsHundredths = lots), it) }
        }
        // A raised to 3: max(3, 1) + max(1, 1) = 4 sells, so all the stops filling leave short 2, which the cap allows.
        assertEquals(ExposureEffect(200, 200, false) to null, edit(long, "A", 300))
        // B raised to 3 after it: max(3, 1) + max(3, 1) = 6 against the long of 2 would leave short 4.
        val raisedA = raised(long, "A", 300)
        assertEquals(ExposureEffect(200, 400, false), edit(raisedA, "B", 300).first)
        assertNotNull(edit(raisedA, "B", 300).second)
        // So does TB raised to 3 after A, as A and TB belong to different brackets and can both fill: max(3, 1) + max(1, 3).
        assertEquals(ExposureEffect(200, 400, false), edit(raisedA, "TB", 300).first)
        assertNotNull(edit(raisedA, "TB", 300).second)
        // One leg raised to 6 at once: short 5.
        assertEquals(ExposureEffect(200, 500, false), edit(long, "A", 600).first)
        assertNotNull(edit(long, "A", 600).second)
        // Where the broker names no group, nothing says which legs cancel each other: all four count.
        assertEquals(SymbolExposure(200, 0, 400), symbolExposure(long.copy(orders = legs.map { it.copy(ocoGroup = null) }), "MESZ6"))
        // A partial close beside the two brackets reduces, and leaves the worst case at the cap, but the legs alone already
        // take the whole long: it is no exit.
        assertEquals(ExposureEffect(200, 200, false, reduces = true), place(long, Side.SELL, 100))
        assertNull(cap.refusal(request(Side.SELL, 100), place(long, Side.SELL, 100)))

        // Brackets of 5 and 1 on a long of 6 fill no more than 6. A sell of 3 beside them leaves the worst case at 6, so a cap
        // of 6 takes it, and it reduces; with the legs it could fill 9, so it is no exit.
        val six = account(listOf(position(Side.BUY, 600)), bracket("A", 500) + bracket("B", 100))
        assertEquals(SymbolExposure(600, 0, 600), symbolExposure(six, "MESZ6"))
        assertEquals(ExposureEffect(600, 600, false, reduces = true), place(six, Side.SELL, 300))
        assertNull(AccountOrderLimits(maxLots = 600).refusal(request(Side.SELL, 300), place(six, Side.SELL, 300)))
        // Brackets of 2 and 1 on a long of 3 alike: 3 sells, and a sell of 3 beside them leaves 3 at worst.
        val three = account(listOf(position(Side.BUY, 300)), bracket("A", 200) + bracket("B", 100))
        assertEquals(SymbolExposure(300, 0, 300), symbolExposure(three, "MESZ6"))
        assertEquals(ExposureEffect(300, 300, false, reduces = true), place(three, Side.SELL, 300))

        // A bracket of 1 beside a stop-only leg of 1 that no group links: 1 + 1. B raised to 3 leaves short 2 at worst, and
        // TA raised to 3 after it 4 more: A or TA, and B, can all fill.
        val stopOnly = account(listOf(position(Side.BUY, 200)), bracket("A", 100) + order("B", Side.SELL, 100, OrderRole.STOP_LOSS))
        assertEquals(SymbolExposure(200, 0, 200), symbolExposure(stopOnly, "MESZ6"))
        assertEquals(ExposureEffect(200, 200, false) to null, edit(stopOnly, "B", 300))
        assertEquals(ExposureEffect(200, 400, false), edit(raised(stopOnly, "B", 300), "TA", 300).first)
        assertNotNull(edit(raised(stopOnly, "B", 300), "TA", 300).second)
    }

    @Test fun aLegThatWaitsForItsEntryCountsForNothingUntilTheEntryFills() {
        // Flat, a buy stop of 2 working with its bracket: the legs cannot fill before it, and then only take back what it bought.
        val entry = order("entry", Side.BUY, 200).copy(stopLossOrderId = "sl", takeProfitOrderId = "tp")
        val legs = listOf(order("sl", Side.SELL, 200, OrderRole.STOP_LOSS, group = "b"), order("tp", Side.SELL, 200, OrderRole.TAKE_PROFIT, group = "b"))
        val waiting = account(orders = listOf(entry) + legs)
        assertEquals(SymbolExposure(0, 200, 0), symbolExposure(waiting, "MESZ6"))
        // The other side of the breakout, a sell stop of 2, can leave short 2 at most: a cap of 2 takes it, and leaves room for 2.
        assertEquals(ExposureEffect(200, 200, false), place(waiting, Side.SELL, 200))
        assertNull(AccountOrderLimits(maxLots = 200).refusal(request(Side.SELL, 200), place(waiting, Side.SELL, 200)))
        assertEquals(200, lotsUnderCap(waiting, false, request(Side.SELL, 100), 200))
        // The same breakout of 1 under a cap of 1, and another contract beside it that starts from 1.
        val one = account(orders = listOf(entry.copy(request = entry.request.copy(lotsHundredths = 100))) +
            legs.map { it.copy(request = it.request.copy(lotsHundredths = 100)) })
        assertEquals(ExposureEffect(100, 100, false), place(one, Side.SELL, 100))
        assertEquals(ExposureEffect(100, 200, false), place(one, Side.BUY, 100, "ESZ6"))
        // Once the entry has filled in part, its legs work against what it bought and count again.
        val partly = waiting.copy(positions = listOf(position(Side.BUY, 100)),
            orders = waiting.orders.map { if (it.id == "entry") it.copy(filledLotsHundredths = 100) else it })
        assertEquals(SymbolExposure(100, 100, 200), symbolExposure(partly, "MESZ6"))
        // So do legs no working entry names, and a leg being changed, at the size it is left with: an edit hides no lots.
        assertEquals(SymbolExposure(0, 0, 200), symbolExposure(waiting.copy(orders = legs), "MESZ6"))
        assertEquals(ExposureEffect(200, 300, false), editedOrderExposure(waiting, false, legs.first(), 300))
    }

    @Test fun aFlattenKeepsNoMoreOrdersAgainstAPositionThanItCanTake() {
        // Long 1 with a sell limit of 3 placed earlier: filled, it would leave the account short 2.
        val reversal = account(listOf(position(Side.BUY, 100)), listOf(order("reverse", Side.SELL, 300)))
        assertFalse(reducesPosition(reversal, false, reversal.orders.single()))
        // Long 2 with a target, a stop and a second stop placed by hand: the stops come first, and what is left of the
        // position after them keeps no more orders. The broker's bracket on the other contract counts once.
        val target = order("target", Side.SELL, 200)
        val stop = PendingOrder("stop", OrderRequest("MESZ6", Side.SELL, OrderKind.STOP, 100, 548_000), 0)
        val second = stop.copy(id = "second", request = stop.request.copy(lotsHundredths = 200))
        val bracketLegs = bracket("sl", 200).map { it.copy(request = it.request.copy(symbol = "ESZ6")) }
        val held = account(listOf(position(Side.BUY, 200), position(Side.BUY, 200, "ESZ6")), listOf(target, stop, second) + bracketLegs)
        assertEquals(listOf(true, false, false, true, true), (listOf(stop, second, target) + bracketLegs).map { reducesPosition(held, false, it) })
        // Without the group the stop and the target there could both fill: the target goes.
        val unpaired = held.copy(orders = held.orders.map { it.copy(ocoGroup = null) })
        assertEquals(listOf(true, false), bracketLegs.map { reducesPosition(unpaired, false, it) })
        // A filled part does not count, and a stop on the position's own side reduces nothing.
        val small = target.copy(request = target.request.copy(lotsHundredths = 100))
        assertFalse(reducesPosition(held.copy(orders = listOf(second, small)), false, small))
        assertTrue(reducesPosition(held.copy(orders = listOf(second.copy(filledLotsHundredths = 100), small)), false, small))
        assertFalse(reducesPosition(held, false, stop.copy(id = "add", request = stop.request.copy(side = Side.BUY))))
        // Brackets of 2 and 1 on a long of 3 all stay: each pair counts once.
        val brackets = account(listOf(position(Side.BUY, 300)), bracket("A", 200) + bracket("B", 100))
        assertTrue(brackets.orders.all { reducesPosition(brackets, false, it) })
        // A bracket of 1 whose target was raised to 2, beside a stop-only leg of 2: both stops fit, and the target would
        // make 4 against the long of 3.
        val stopOnly = account(listOf(position(Side.BUY, 300)), bracket("A", 100, target = 200) + order("B", Side.SELL, 200, OrderRole.STOP_LOSS))
        assertEquals(listOf(true, false, true), stopOnly.orders.map { reducesPosition(stopOnly, false, it) })
    }

    @Test fun aHedgingAccountAddsEverythingUpAndHasNoExitOrders() {
        val hedged = account(listOf(position(Side.BUY, 200), position(Side.SELL, 100, "ESZ6")), listOf(
            order("entry", Side.SELL, 300, filled = 100), order("paired", Side.SELL, 200, OrderRole.STOP_LOSS)))
        assertEquals(ExposureEffect(500, 600, false), newOrderExposure(hedged, hedging = true, request(Side.SELL, 100)))
        assertEquals(ExposureEffect(500, 500, false, grows = false), editedOrderExposure(hedged, true, hedged.orders[0]))
        assertEquals(ExposureEffect(500, 400, false, grows = false), editedOrderExposure(hedged, true, hedged.orders[0], 200))
        assertEquals(ExposureEffect(500, 500, true, grows = false), editedOrderExposure(hedged, true, hedged.orders[1]))
        assertEquals(ExposureEffect(500, 600, false), editedOrderExposure(hedged, true, hedged.orders[1], 300))
        assertFalse(reducesPosition(hedged, true, hedged.orders[0]))
        assertTrue(reducesPosition(hedged, true, hedged.orders[1]))
        // On a net account an order against the long reduces it, whatever its role, while it fits beside the stop that
        // comes first: alone the sell of 2 left does, beside the stop of 2 it does not.
        assertTrue(reducesPosition(hedged, false, hedged.orders[1]))
        assertFalse(reducesPosition(hedged, false, hedged.orders[0]))
        assertTrue(reducesPosition(hedged.copy(orders = hedged.orders.take(1)), false, hedged.orders[0]))
        assertFalse(reducesPosition(hedged, false, order("add", Side.BUY, 100)))
        assertFalse(reducesPosition(hedged, false, order("flat", Side.SELL, 100, symbol = "NQZ6")))
    }

    @Test fun absurdQuantitiesCannotOverflowIntoASmallTotal() {
        val long = account(listOf(position(Side.BUY, 200)))
        val huge = place(long, Side.BUY, Long.MAX_VALUE)
        assertTrue(huge.after > huge.before && huge.after > 999_999_999L && !huge.exit)
        assertNotNull(AccountOrderLimits(maxLots = 999_999_999L).refusal(request(Side.BUY, Long.MAX_VALUE), huge))
    }

    @Test fun overALoweredCapNothingGrowsUnlessItIsAnExitAndAnythingMayMoveOrShrink() {
        val cap = AccountOrderLimits(maxLots = 100)
        fun change(snapshot: AccountSnapshot, id: String, lots: Long) = snapshot.orders.single { it.id == id }.let {
            cap.refusal(it.request.copy(lotsHundredths = lots), editedOrderExposure(snapshot, false, it, lots))
        }
        // Long 3 under a cap of 1: a sell of 1 is an exit, also raised to 2. Raised to 6 it would leave short 3: the total
        // stays at 3, and it is refused all the same.
        val long = account(listOf(position(Side.BUY, 300)))
        assertNull(cap.refusal(request(Side.SELL, 100), place(long, Side.SELL, 100)))
        val exit = long.copy(orders = listOf(order("exit", Side.SELL, 100)))
        assertNull(change(exit, "exit", 200))
        assertNotNull(change(exit, "exit", 600))
        // Beside a stop of 3 placed by hand, a sell of 1 is no exit any more: the stop could fill after it.
        assertNotNull(cap.refusal(request(Side.SELL, 100), place(long.copy(orders = listOf(order("stop", Side.SELL, 300))), Side.SELL, 100)))
        // A leg of the broker's brackets raised past the position: long 2 from two brackets of 1.
        val legs = account(listOf(position(Side.BUY, 200)), bracket("A", 100) + bracket("B", 100))
        assertNotNull(change(legs, "A", 300))
        assertNull(change(legs, "A", 100), "moved")
        // An entry beside a larger order on the other side may be moved or made smaller, never larger.
        val dominated = long.copy(orders = listOf(order("reverse", Side.SELL, 1_000), order("add", Side.BUY, 100)))
        assertNotNull(change(dominated, "add", 400))
        assertNull(change(dominated, "add", 100))
        assertNull(change(dominated, "add", 50))
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
        // What already works against the long takes its share: a stop of 2 leaves 1, a bracket of 3 none.
        assertEquals(100, room(long.copy(orders = long.orders + order("stop", Side.SELL, 200)), false, Side.SELL, 200))
        assertEquals(0, room(long.copy(orders = long.orders + bracket("A", 300)), false, Side.SELL, 200))
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
