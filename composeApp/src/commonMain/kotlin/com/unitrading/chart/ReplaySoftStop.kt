package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side

/** First executable price at which the current position reaches the daily soft-loss limit. */
internal fun replaySoftStopPrice(spec: Instrument, config: ReplayRiskConfig, risk: ReplayRiskState,
    before: ReplayState, after: ReplayState, bar: Candle): Long? {
    val loss = config.maxDailyLossMicros ?: return null
    if (config.dailyLossMode != ReplayDailyLoss.SOFT || after.positions.isEmpty()) return null
    // Roll the trading day using the last known account snapshot, not the end of this candle.
    val day = risk.observe(config, bar.timeMs, risk.lastBalance, risk.lastEquity)
    if (day.failed || day.targetReached) return null
    val line = if (day.dayOpeningBalance < Long.MIN_VALUE + loss) Long.MIN_VALUE else day.dayOpeningBalance - loss
    fun equity(price: Long) = after.closingEquity(spec, config, price)
    val samePositions = before.positions == after.positions && before.trades == after.trades
    val newPositions = after.positions.all { it.openedAtMs == bar.timeMs && it.entryPrice == after.positions.first().entryPrice }
    val oldIds = before.positions.map { it.id }.toSet()
    val added = after.positions.filter { it.id !in oldIds }
    val addedToExisting = before.positions.isNotEmpty() && before.trades == after.trades &&
        after.positions.filter { it.id in oldIds } == before.positions && added.isNotEmpty() &&
        added.all { it.openedAtMs == bar.timeMs && it.entryPrice == added.first().entryPrice && it.side == before.positions.first().side }
    val isNextBar = after.index == before.index + 1
    val start = when {
        !isNextBar -> bar.close
        samePositions -> bar.open
        newPositions -> after.positions.first().entryPrice
        addedToExisting -> added.first().entryPrice
        else -> bar.close
    }
    if (day.dailyLossHit || equity(start) <= line) return start
    // If fills changed the ledger and their order is unknown, do not apply an earlier bar extreme to them.
    if (!isNextBar || !samePositions && !newPositions && !addedToExisting) return if (equity(bar.close) <= line) bar.close else null
    val side = after.positions.first().side
    if (after.positions.any { it.side != side }) return if (equity(bar.close) <= line) bar.close else null
    val worst = if (side == Side.BUY) bar.low else bar.high
    if (start !in bar.low..bar.high || equity(worst) > line) return null
    val tick = spec.tickSize
    require(tick > 0 && start > 0 && worst > 0)
    fun ceilTicks(price: Long) = price / tick + if (price % tick == 0L) 0 else 1
    var low = ceilTicks(minOf(start, worst))
    var high = maxOf(start, worst) / tick
    if (low > high) return if (equity(bar.close) <= line) bar.close else null
    if (side == Side.BUY) {
        // A long loses as price falls: choose the highest tick already at or below the limit.
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (equity(middle * tick) <= line) low = middle else high = middle - 1
        }
    } else {
        // A short loses as price rises: choose the lowest tick already at or below the limit.
        while (low < high) {
            val middle = low + (high - low) / 2
            if (equity(middle * tick) <= line) high = middle else low = middle + 1
        }
    }
    return (low * tick).takeIf { equity(it) <= line }
}

/** Account soft stop can precede a later TP or pending fill inside the same OHLC candle. */
internal fun replaySoftStopBeforeAdvance(spec: Instrument, config: ReplayRiskConfig, risk: ReplayRiskState,
    before: ReplayState, bar: Candle): Long? {
    val candidate = replaySoftStopPrice(spec, config, risk, before, before.copy(index = before.index + 1), bar) ?: return null
    // A gap across the account line is acted on immediately, before any later candle extremes.
    if (candidate == bar.open) return candidate
    for (position in before.positions) {
        val stopAtOpen = position.stopLoss?.let { if (position.side == Side.BUY) bar.open <= it else bar.open >= it } == true
        val targetAtOpen = position.takeProfit?.let { if (position.side == Side.BUY) bar.open >= it else bar.open <= it } == true
        if (stopAtOpen || targetAtOpen) return null
        val earlierStop = position.stopLoss?.let {
            if (position.side == Side.BUY) it > candidate && it <= bar.open else it >= bar.open && it < candidate
        } == true
        if (earlierStop) return null
    }
    // Orders reached at the open or on the adverse path before the account limit must be processed first.
    fun hit(order: ReplayPending, price: Long) = when (order.kind) {
        OrderKind.LIMIT -> if (order.side == Side.BUY) price <= order.price else price >= order.price
        OrderKind.STOP -> if (order.side == Side.BUY) price >= order.price else price <= order.price
        OrderKind.MARKET -> false
    }
    if (before.pending.any { hit(it, bar.open) || hit(it, candidate) && it.price != candidate }) return null
    return candidate
}

/** An isolated pending entry that is known to have filled at the opening price of this candle. */
internal fun replaySinglePendingAtOpen(spec: Instrument, before: ReplayState, bar: Candle): ReplayState? {
    if (before.positions.isNotEmpty() || before.pending.size != 1) return null
    val order = before.pending.single()
    val atOpen = when (order.kind) {
        OrderKind.LIMIT -> if (order.side == Side.BUY) bar.open <= order.price else bar.open >= order.price
        OrderKind.STOP -> if (order.side == Side.BUY) bar.open >= order.price else bar.open <= order.price
        OrderKind.MARKET -> false
    }
    if (!atOpen) return null
    val fill = when (order.kind) {
        OrderKind.LIMIT -> order.price
        OrderKind.STOP -> if (order.side == Side.BUY) maxOf(bar.open, order.price) else minOf(bar.open, order.price)
        OrderKind.MARKET -> return null
    }
    return runCatching {
        before.copy(pending = emptyList(), lastNotice = null).open(spec, bar.copy(close = fill), order.side, order.quantity, order.stop, order.target)
    }.getOrNull()
}
