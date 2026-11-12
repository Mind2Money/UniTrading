package com.unitrading.app

import com.unitrading.broker.*

/** Far above any real quantity. Quantities are capped here before they are added up, so no sum below can overflow. */
private const val LOT_BOUND = 1_000_000_000_000L

private fun Long.bounded() = coerceIn(0, LOT_BOUND)

/** Lots of a working order that have not filled yet, in hundredths. */
internal val PendingOrder.remainingLots: Long get() = (request.lotsHundredths - filledLotsHundredths).bounded()

/**
 * The most lots [orders], all on one side, can fill, each with the lots [remaining] gives it: an ordinary order in full,
 * and of the broker-paired protection the larger of all its stops and all its targets. A stop and the target of its
 * bracket cancel each other, so at most one of the two fills.
 */
private fun fillable(orders: List<PendingOrder>, remaining: (PendingOrder) -> Long = { it.remainingLots }): Long {
    var plain = 0L
    var stops = 0L
    var targets = 0L
    for (order in orders) when (order.role) {
        OrderRole.STOP_LOSS -> stops += remaining(order)
        OrderRole.TAKE_PROFIT -> targets += remaining(order)
        else -> plain += remaining(order)
    }
    return plain + maxOf(stops, targets)
}

/**
 * One symbol of a net account, in hundredths of a lot: the signed position, and the most lots the working orders on
 * each side can fill (see [fillable]). [worst] is the largest position either side can leave when all of its orders
 * fill, so it never falls below the position held.
 */
internal data class SymbolExposure(val net: Long, val buys: Long, val sells: Long) {
    val worst: Long get() = maxOf(net + buys, sells - net)
    /** The lots an order on [side] can take off the position: none when it is on the position's own side, or there is none. */
    fun closable(side: Side): Long = (if (side == Side.BUY) -net else net).coerceAtLeast(0)
    /** With a new order of [lots] on [side]: a new order is never broker-paired protection. */
    fun with(side: Side, lots: Long) = if (side == Side.BUY) copy(buys = buys + lots) else copy(sells = sells + lots)
}

/** [symbol] as [snapshot] has it, each working order with the lots [remaining] gives it: an edit passes what it leaves. */
internal fun symbolExposure(
    snapshot: AccountSnapshot, symbol: String, remaining: (PendingOrder) -> Long = { it.remainingLots },
): SymbolExposure {
    val net = snapshot.positions.filter { it.symbol == symbol }
        .sumOf { if (it.side == Side.BUY) it.lotsHundredths.bounded() else -it.lotsHundredths.bounded() }
    val working = snapshot.orders.filter { it.request.symbol == symbol }
    fun side(side: Side) = fillable(working.filter { it.request.side == side }, remaining)
    return SymbolExposure(net, side(Side.BUY), side(Side.SELL))
}

/**
 * Whether [order] may keep working while a flatten closes the positions, because it can only take the position of its
 * symbol towards flat. On a net account that is an order against the net position, whoever placed it (a stop added
 * after the entry is an ordinary order to the broker), and only while it fits in what the orders before it leave of
 * that position: stops come first, as what may still protect a position whose close fails, then the others in the order
 * of [snapshot], with a broker-paired stop and target as one pair. An order beyond that could reverse the position.
 * On a hedging account every order opens a position of its own, so only broker-paired protection reduces one.
 */
internal fun reducesPosition(snapshot: AccountSnapshot, hedging: Boolean, order: PendingOrder): Boolean {
    if (hedging) return order.isProtection
    val (symbol, side) = order.request.let { it.symbol to it.side }
    val held = symbolExposure(snapshot, symbol).closable(side)
    if (held <= 0) return false
    val kept = mutableListOf<PendingOrder>()
    for (candidate in snapshot.orders.filter { it.request.symbol == symbol && it.request.side == side }
        .sortedBy { it.request.kind != OrderKind.STOP }) {
        val fits = fillable(kept + candidate) <= held
        if (candidate.id == order.id) return fits
        if (fits) kept += candidate
    }
    return false
}

/**
 * What an action does to an account's exposure, in hundredths of a lot.
 *
 * On a net account the exposure is the sum over symbols of [SymbolExposure.worst]; on a hedging account it is every
 * position plus the unfilled part of every order that is not protection. [before] and [after] are those totals, which
 * the lot cap compares.
 *
 * [neutral] says the action is an exit: it cannot raise the worst case of its symbol, and the order it places or
 * changes only works against the position held, for no more lots than that position has. A stop, a target and a
 * partial close are neutral, also side by side; an order that would reverse the position is not, and neither is
 * anything on the position's own side or in a symbol without a position. A new order on a hedging account never is.
 */
internal data class ExposureEffect(val before: Long, val after: Long, val neutral: Boolean)

/** The effect of placing [request]. Its [ExposureEffect.neutral] is the test for "this order is an exit". */
internal fun newOrderExposure(snapshot: AccountSnapshot, hedging: Boolean, request: OrderRequest): ExposureEffect =
    exposureEffect(snapshot, hedging, request.symbol, request.side, request.lotsHundredths, null)

/**
 * The effect of leaving [order] working with [lotsHundredths] in total, its filled part included: a quantity edit, or
 * a price edit with the quantity it has. A broker-paired stop or target counts at its new size among the stops or the
 * targets of its side, so a leg raised past the position opens what exceeds it, also when it is the second one raised.
 */
internal fun editedOrderExposure(
    snapshot: AccountSnapshot, hedging: Boolean, order: PendingOrder, lotsHundredths: Long = order.request.lotsHundredths,
): ExposureEffect = exposureEffect(snapshot, hedging, order.request.symbol, order.request.side,
    lotsHundredths - order.filledLotsHundredths, order)

private fun exposureEffect(
    snapshot: AccountSnapshot, hedging: Boolean, symbol: String, side: Side, lots: Long, replaced: PendingOrder?,
): ExposureEffect {
    val now = lots.bounded()
    val old = replaced?.remainingLots ?: 0
    val raised = now > old
    if (hedging) {
        val protection = replaced?.isProtection == true
        val gross = snapshot.positions.sumOf { it.lotsHundredths.bounded() } +
            snapshot.orders.filterNot { it.isProtection }.sumOf { it.remainingLots }
        return ExposureEffect(gross, gross + if (protection) (now - old).coerceAtLeast(0) else now - old, protection && !raised)
    }
    val others = (snapshot.positions.map { it.symbol } + snapshot.orders.map { it.request.symbol }).toSet().minus(symbol)
        .sumOf { symbolExposure(snapshot, it).worst }
    val before = symbolExposure(snapshot, symbol)
    val after = if (replaced == null) before.with(side, now)
        else symbolExposure(snapshot, symbol) { if (it.id == replaced.id) now else it.remainingLots }
    val closable = before.closable(side)
    // Only a new order or a raised quantity can open something; a smaller or merely moved exit stays an exit.
    val exit = if (replaced == null) now in 1..closable else closable > 0 && (!raised || now <= closable)
    return ExposureEffect(others + before.worst, others + after.worst, exit && after.worst <= before.worst)
}

/**
 * The most lots a new order like [request] may have before [AccountOrderLimits.refusal] turns it down for a cap of
 * [cap], in hundredths; its own quantity does not matter. A quantity sized from a loss amount stays within it.
 */
internal fun lotsUnderCap(snapshot: AccountSnapshot, hedging: Boolean, request: OrderRequest, cap: Long): Long {
    // Lots that only offset working orders on the other side leave the total where it was; every lot past them adds
    // one to it. An order far larger than any real one therefore shows where the total would start from.
    val probe = newOrderExposure(snapshot, hedging, request.copy(lotsHundredths = LOT_BOUND))
    val start = probe.after - LOT_BOUND
    if (probe.before <= cap) return cap - start
    // Over the cap only an exit passes: no more than the position, and no lot that would raise the total.
    return if (hedging) 0 else minOf(symbolExposure(snapshot, request.symbol).closable(request.side), probe.before - start)
}

/**
 * Whether the stop loss of a holding on [side] (BUY for a long) gets looser or disappears when it moves from [old] to
 * [new]: the stop of a long may only rise and that of a short only fall. Adding a stop where there was none is fine.
 */
internal fun stopLoosened(side: Side, old: Long?, new: Long?): Boolean =
    old != null && (new == null || if (side == Side.BUY) new < old else new > old)

/** Whether moving this working order to [price] loosens a stop: a SELL stop guards a long, a BUY stop a short. A limit may move freely. */
internal fun PendingOrder.stopLoosenedAt(price: Long): Boolean = request.kind == OrderKind.STOP &&
    (request.entryPrice == null || stopLoosened(if (request.side == Side.SELL) Side.BUY else Side.SELL, request.entryPrice, price))
