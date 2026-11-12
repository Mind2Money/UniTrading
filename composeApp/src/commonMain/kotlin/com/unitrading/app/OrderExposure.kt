package com.unitrading.app

import com.unitrading.broker.*

/** Far above any real quantity. Quantities are capped here before they are added up, so no sum below can overflow. */
private const val LOT_BOUND = 1_000_000_000_000L

private fun Long.bounded() = coerceIn(0, LOT_BOUND)

/** Lots of a working order that have not filled yet, in hundredths. */
internal val PendingOrder.remainingLots: Long get() = (request.lotsHundredths - filledLotsHundredths).bounded()

/**
 * Why a paused account or a blocked symbol refuses an order that reduces the position but is no exit: the orders already
 * working against the position could take all of it, and with this one as well the account could end up on the other side.
 */
internal const val EXITS_COVER_POSITION = "已挂的止损、止盈等减仓单已覆盖持仓，再加这笔可能反手。请先撤销或减少已挂的减仓单，或直接平仓。"

/**
 * The stops and targets of entries that have not filled, as the entries name them in [PendingOrder.stopLossOrderId] and
 * [PendingOrder.takeProfitOrderId]. Such a leg cannot fill before its entry, and then only takes back what the entry
 * added, so until then it counts for nothing.
 */
private fun AccountSnapshot.waitingLegs(): Set<String> = orders.filter { it.role == OrderRole.ENTRY && it.filledLotsHundredths == 0L }
    .flatMapTo(mutableSetOf()) { listOfNotNull(it.stopLossOrderId, it.takeProfitOrderId) }

/**
 * The most lots [orders], all on one side, can fill, each with the lots [remaining] gives it. An ordinary order counts in
 * full, and so does a stop or target the broker gives no [PendingOrder.ocoGroup]: nothing says it cancels another one.
 * The legs of one group cancel each other as they fill, so a group fills no more than the larger of its stops' lots and
 * its targets' lots.
 */
private fun fillable(orders: List<PendingOrder>, remaining: (PendingOrder) -> Long): Long {
    val (legs, plain) = orders.partition { it.isProtection && it.ocoGroup != null }
    return plain.sumOf(remaining) + legs.groupBy { it.ocoGroup }.values.sumOf { group ->
        val (stops, targets) = group.partition { it.role == OrderRole.STOP_LOSS }
        maxOf(stops.sumOf(remaining), targets.sumOf(remaining))
    }
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
    /** With a new order of [lots] on [side]: a new order is an ordinary one, in no group and waiting for nothing. */
    fun with(side: Side, lots: Long) = if (side == Side.BUY) copy(buys = buys + lots) else copy(sells = sells + lots)
}

/**
 * [symbol] as [snapshot] has it, each working order with the lots [remaining] gives it: an edit passes what it leaves.
 * A leg that waits for its entry counts for nothing, except the order [edited], which counts as it is left: an edit
 * cannot hide lots that way.
 */
internal fun symbolExposure(
    snapshot: AccountSnapshot, symbol: String, edited: String? = null, remaining: (PendingOrder) -> Long = { it.remainingLots },
): SymbolExposure {
    val net = snapshot.positions.filter { it.symbol == symbol }
        .sumOf { if (it.side == Side.BUY) it.lotsHundredths.bounded() else -it.lotsHundredths.bounded() }
    val waiting = snapshot.waitingLegs() - setOfNotNull(edited)
    val working = snapshot.orders.filter { it.request.symbol == symbol }
    fun side(side: Side) = fillable(working.filter { it.request.side == side }) { if (it.id in waiting) 0 else remaining(it) }
    return SymbolExposure(net, side(Side.BUY), side(Side.SELL))
}

/**
 * Whether [order] may keep working while a flatten closes the positions, because it can only take the position of its
 * symbol towards flat. On a net account that is an order against the net position, whoever placed it (a stop added
 * after the entry is an ordinary order to the broker), and only while it fits in what the orders before it leave of
 * that position: stops come first, as what may still protect a position whose close fails, then the others in the order
 * of [snapshot], counted as [fillable] counts them. An order beyond that could reverse the position.
 * On a hedging account every order opens a position of its own, so only broker-paired protection reduces one.
 */
internal fun reducesPosition(snapshot: AccountSnapshot, hedging: Boolean, order: PendingOrder): Boolean {
    if (hedging) return order.isProtection
    val (symbol, side) = order.request.let { it.symbol to it.side }
    val held = symbolExposure(snapshot, symbol).closable(side)
    if (held <= 0) return false
    val waiting = snapshot.waitingLegs()
    val kept = mutableListOf<PendingOrder>()
    for (candidate in snapshot.orders.filter { it.request.symbol == symbol && it.request.side == side }
        .sortedBy { it.request.kind != OrderKind.STOP }) {
        val fits = fillable(kept + candidate) { if (it.id in waiting) 0 else it.remainingLots } <= held
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
 * [reduces] says the order placed or left by an edit works against the position held, for no more lots than that
 * position has: it needs no stop or target of its own. [exit] says that, on top of that, it fits in what the orders
 * already working against the position leave of it: whatever of them fills, this one included, the account cannot end
 * up on the other side. A stop, a target and a partial close are exits, also side by side, until together they could
 * fill more than the position; a stop and the target of one broker bracket count once. [grows] says the action places
 * an order or raises the lots of one. On a hedging account no new order reduces, as each opens a position of its own,
 * and only the protection of a position moved or made smaller is an exit.
 */
internal data class ExposureEffect(
    val before: Long, val after: Long, val exit: Boolean, val reduces: Boolean = exit, val grows: Boolean = true,
) {
    /**
     * What a pause or a blocked symbol lets through: an exit, or an order that reduces moved or made smaller, which
     * leaves the orders against the position no larger than they were.
     */
    val harmless: Boolean get() = exit || (reduces && !grows)
}

/** The effect of placing [request]. Its [ExposureEffect.exit] is the test for "this order may go while nothing else may". */
internal fun newOrderExposure(snapshot: AccountSnapshot, hedging: Boolean, request: OrderRequest): ExposureEffect =
    exposureEffect(snapshot, hedging, request.symbol, request.side, request.lotsHundredths, null)

/**
 * The effect of leaving [order] working with [lotsHundredths] in total, its filled part included: a quantity edit, or
 * a price edit with the quantity it has. A stop or target of a broker bracket counts at its new size within its group,
 * so a leg raised past the position opens what exceeds it, also when it is the second one raised.
 */
internal fun editedOrderExposure(
    snapshot: AccountSnapshot, hedging: Boolean, order: PendingOrder, lotsHundredths: Long = order.request.lotsHundredths,
): ExposureEffect = exposureEffect(snapshot, hedging, order.request.symbol, order.request.side,
    lotsHundredths - order.filledLotsHundredths, order)

private fun exposureEffect(
    snapshot: AccountSnapshot, hedging: Boolean, symbol: String, side: Side, lots: Long, replaced: PendingOrder?,
): ExposureEffect {
    val now = lots.bounded()
    val grows = replaced == null || now > replaced.remainingLots
    if (hedging) {
        val old = replaced?.remainingLots ?: 0
        val protection = replaced?.isProtection == true
        val gross = snapshot.positions.sumOf { it.lotsHundredths.bounded() } +
            snapshot.orders.filterNot { it.isProtection }.sumOf { it.remainingLots }
        val exit = protection && !grows
        return ExposureEffect(gross, gross + if (protection) (now - old).coerceAtLeast(0) else now - old, exit, exit, grows)
    }
    val others = (snapshot.positions.map { it.symbol } + snapshot.orders.map { it.request.symbol }).toSet().minus(symbol)
        .sumOf { symbolExposure(snapshot, it).worst }
    val before = symbolExposure(snapshot, symbol, replaced?.id)
    val after = if (replaced == null) before.with(side, now)
        else symbolExposure(snapshot, symbol, replaced.id) { if (it.id == replaced.id) now else it.remainingLots }
    val held = before.closable(side)
    val reduces = held > 0 && now <= held && (now > 0 || replaced != null)
    val exit = reduces && (if (side == Side.BUY) after.buys else after.sells) <= held
    return ExposureEffect(others + before.worst, others + after.worst, exit, reduces, grows)
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
    // Over the cap only an exit passes: no more than what the orders already on its side leave of the position, and no
    // lot that would raise the total.
    if (hedging) return 0
    val exposure = symbolExposure(snapshot, request.symbol)
    val left = exposure.closable(request.side) - if (request.side == Side.BUY) exposure.buys else exposure.sells
    return minOf(left, probe.before - start).coerceAtLeast(0)
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
