package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import com.unitrading.broker.checkedAdd
import com.unitrading.broker.commission
import com.unitrading.broker.grossPnl

internal enum class ReplayProfitKind { TARGET, CONSISTENCY }
internal data class ReplayProfitTrigger(val kind: ReplayProfitKind, val price: Long)

/** Closing equity includes all realised trades, open-position P&L, and both sides of commission. */
internal fun ReplayState.closingEquity(spec: Instrument, config: ReplayRiskConfig, price: Long): Long {
    val realized = trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) }
    val floating = positions.fold(0L) { sum, position ->
        checkedAdd(sum, checkedAdd(grossPnl(spec, position.side, position.lotsHundredths, position.entryPrice, price),
            -checkedAdd(position.entryFeeMicros, commission(spec, position.lotsHundredths))))
    }
    return checkedAdd(config.initialMicros, checkedAdd(realized, floating))
}

/**
 * The first reached ceiling wins. Only use a candle's extremes if the same positions existed for the whole candle and
 * no order or protective exit changed the ledger. Otherwise only the known closing snapshot is safe to use: an extreme
 * may have occurred before a pending entry. Stops and loss limits are evaluated conservatively before this function.
 */
internal fun replayProfitTrigger(spec: Instrument, config: ReplayRiskConfig, risk: ReplayRiskState,
    before: ReplayState, after: ReplayState, bar: Candle): ReplayProfitTrigger? {
    if (risk.failed || risk.targetReached) return null
    val target = checkedAdd(config.initialMicros, config.targetMicros)
    val daily = config.consistencyLimitMicros?.takeUnless { risk.consistencyHit }?.let {
        if (risk.dayOpeningBalance > Long.MAX_VALUE - it) Long.MAX_VALUE else checkedAdd(risk.dayOpeningBalance, it)
    }
    val threshold = daily?.let { minOf(target, it) } ?: target
    fun equity(price: Long) = after.closingEquity(spec, config, price)
    fun result(price: Long) = ReplayProfitTrigger(if (equity(price) >= target) ReplayProfitKind.TARGET else ReplayProfitKind.CONSISTENCY, price)
    if (after.positions.isEmpty()) return if (equity(bar.close) >= threshold) result(bar.close) else null
    val newAtOpen = after.positions.singleOrNull()?.let { position ->
        before.positions.isEmpty() && before.trades == after.trades && position.openedAtMs == bar.timeMs && before.pending.any { order ->
            order.side == position.side && order.quantity == position.lotsHundredths && when (order.kind) {
                OrderKind.LIMIT -> position.entryPrice == order.price &&
                    if (order.side == Side.BUY) bar.open <= order.price else bar.open >= order.price
                OrderKind.STOP -> position.entryPrice ==
                    (if (order.side == Side.BUY) maxOf(bar.open, order.price) else minOf(bar.open, order.price)) &&
                    if (order.side == Side.BUY) bar.open >= order.price else bar.open <= order.price
                OrderKind.MARKET -> false
            }
        }
    } == true
    val unchanged = before.positions.isNotEmpty() && before.positions == after.positions &&
        before.trades == after.trades && before.pending == after.pending
    val sameForWholeBar = after.index == before.index + 1 && (unchanged || newAtOpen) &&
        after.positions.all { it.side == after.positions.first().side }
    if (!sameForWholeBar) return if (equity(bar.close) >= threshold) result(bar.close) else null
    if (equity(bar.open) >= threshold) return result(bar.open)
    val side = after.positions.first().side
    val best = if (side == Side.BUY) bar.high else bar.low
    if (equity(best) < threshold) return null
    val tick = spec.tickSize
    require(tick > 0 && bar.open > 0 && best > 0)
    fun ceilTicks(price: Long) = price / tick + if (price % tick == 0L) 0 else 1
    var low = ceilTicks(minOf(bar.open, best))
    var high = maxOf(bar.open, best) / tick
    require(low <= high) { "K 线内没有可用的最小价格变动" }
    if (side == Side.BUY) {
        while (low < high) {
            val middle = low + (high - low) / 2
            if (equity(middle * tick) >= threshold) high = middle else low = middle + 1
        }
    } else {
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (equity(middle * tick) >= threshold) low = middle else high = middle - 1
        }
    }
    val price = low * tick
    return if (equity(price) >= threshold) result(price) else null
}

/** An account-wide threshold before a later TP must not be skipped by the candle's TP processing. */
internal fun replayProfitPrecedesTakeProfit(spec: Instrument, config: ReplayRiskConfig, risk: ReplayRiskState,
    before: ReplayState, bar: Candle): Boolean {
    if (before.positions.isEmpty() || before.pending.isNotEmpty()) return false
    val side = before.positions.first().side
    if (before.positions.any { it.side != side }) return false
    fun hitStop(p: com.unitrading.broker.Position) = p.stopLoss?.let { if (side == Side.BUY) bar.low <= it else bar.high >= it } == true
    fun hitTarget(p: com.unitrading.broker.Position) = p.takeProfit?.let { if (side == Side.BUY) bar.high >= it else bar.low <= it } == true
    fun openTarget(p: com.unitrading.broker.Position) = p.takeProfit?.let { if (side == Side.BUY) bar.open >= it else bar.open <= it } == true
    if (before.positions.any(::hitStop) || before.positions.none(::hitTarget) || before.positions.any(::openTarget)) return false
    val after = before.copy(index = before.index + 1, lastNotice = null)
    val balance = checkedAdd(config.initialMicros, after.trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) })
    val closing = after.closingEquity(spec, config, bar.close)
    val worst = after.closingEquity(spec, config, if (side == Side.BUY) bar.low else bar.high)
    val observed = risk.observe(config, bar.timeMs, balance, closing).observe(config, bar.timeMs, balance, worst)
    val trigger = replayProfitTrigger(spec, config, observed, before, after, bar) ?: return false
    return before.positions.filter(::hitTarget).all { position ->
        val takeProfit = position.takeProfit!!
        if (side == Side.BUY) trigger.price <= takeProfit else trigger.price >= takeProfit
    }
}

/** The opening price is known to occur before later intrabar extremes. */
internal fun replayProfitAtOpen(spec: Instrument, config: ReplayRiskConfig, risk: ReplayRiskState,
    before: ReplayState, bar: Candle): Boolean {
    if (before.positions.isEmpty() || before.pending.isNotEmpty()) return false
    // An existing protection order already marketable at the open takes precedence.
    if (before.positions.any { p ->
        p.stopLoss?.let { if (p.side == Side.BUY) bar.open <= it else bar.open >= it } == true ||
            p.takeProfit?.let { if (p.side == Side.BUY) bar.open >= it else bar.open <= it } == true
    }) return false
    val balance = checkedAdd(config.initialMicros, before.trades.fold(0L) { sum, trade -> checkedAdd(sum, trade.pnlMicros) })
    val equity = before.closingEquity(spec, config, bar.open)
    val observed = risk.observe(config, bar.timeMs, balance, equity)
    val openingBar = bar.copy(high = bar.open, low = bar.open, close = bar.open)
    return replayProfitTrigger(spec, config, observed, before, before.copy(index = before.index + 1), openingBar) != null
}
