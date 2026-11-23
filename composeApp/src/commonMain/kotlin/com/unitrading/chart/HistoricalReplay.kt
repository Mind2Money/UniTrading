package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Position
import com.unitrading.broker.Side
import com.unitrading.broker.checkedAdd
import com.unitrading.broker.commission
import com.unitrading.broker.grossPnl

/** The replay ledger is deliberately independent of every broker account and order route. */
internal data class ReplayTrade(val position: Position, val exit: Long, val exitTimeMs: Long, val pnlMicros: Long, val reason: String)
internal data class ReplayState(
    val index: Int, val positions: List<Position> = emptyList(), val trades: List<ReplayTrade> = emptyList(), val nextId: Long = 1,
) {
    fun open(spec: Instrument, candle: Candle, side: Side, quantity: Long, stop: Long?, target: Long?): ReplayState {
        require(spec.lotStepHundredths > 0 && quantity > 0 && quantity >= spec.minLotsHundredths && quantity <= spec.maxLotsHundredths && quantity % spec.lotStepHundredths == 0L) { "数量不符合合约手数限制" }
        val entry = candle.close
        require(entry > 0 && spec.tickSize > 0 && entry % spec.tickSize == 0L) { "当前 K 线价格无效" }
        require(stop == null || stop > 0 && stop % spec.tickSize == 0L && (if (side == Side.BUY) stop < entry else stop > entry)) { "止损价格无效或方向不正确" }
        require(target == null || target > 0 && target % spec.tickSize == 0L && (if (side == Side.BUY) target > entry else target < entry)) { "止盈价格无效或方向不正确" }
        val fee = commission(spec, quantity)
        val position = Position("replay-$nextId", spec.executionSymbol ?: spec.symbol, side, quantity, entry, candle.timeMs,
            stopLoss = stop, takeProfit = target, marginMicros = 0, entryFeeMicros = fee)
        return copy(positions = positions + position, nextId = nextId + 1)
    }

    fun protect(spec: Instrument, position: Position, current: Long, stop: Long?, target: Long?): ReplayState {
        require(positions.any { it.id == position.id }) { "模拟仓位已平仓" }
        require(spec.tickSize > 0)
        require(stop == null || stop > 0 && stop % spec.tickSize == 0L && (if (position.side == Side.BUY) stop < current else stop > current)) { "止损价格无效或方向不正确" }
        require(target == null || target > 0 && target % spec.tickSize == 0L && (if (position.side == Side.BUY) target > current else target < current)) { "止盈价格无效或方向不正确" }
        return copy(positions = positions.map { if (it.id == position.id) it.copy(stopLoss = stop, takeProfit = target) else it })
    }

    fun close(spec: Instrument, position: Position, price: Long, timeMs: Long, reason: String): ReplayState {
        require(positions.any { it.id == position.id }) { "模拟仓位已平仓" }
        val gross = grossPnl(spec, position.side, position.lotsHundredths, position.entryPrice, price)
        val fees = checkedAdd(position.entryFeeMicros, commission(spec, position.lotsHundredths))
        val trade = ReplayTrade(position, price, timeMs, checkedAdd(gross, -fees), reason)
        return copy(positions = positions.filterNot { it.id == position.id }, trades = trades + trade)
    }

    /** OHLC does not reveal the intrabar path: when both exits touch, assume the stop happens first. */
    fun advance(spec: Instrument, next: Candle): ReplayState {
        var state = copy(index = index + 1)
        for (p in positions) {
            val stopHit = p.stopLoss?.let { if (p.side == Side.BUY) next.low <= it else next.high >= it } == true
            val targetHit = p.takeProfit?.let { if (p.side == Side.BUY) next.high >= it else next.low <= it } == true
            val openStop = p.stopLoss?.let { if (p.side == Side.BUY) next.open <= it else next.open >= it } == true
            val openTarget = p.takeProfit?.let { if (p.side == Side.BUY) next.open >= it else next.open <= it } == true
            if (openStop || (!openTarget && stopHit)) {
                val price = if (p.side == Side.BUY) minOf(next.open, p.stopLoss!!) else maxOf(next.open, p.stopLoss!!)
                state = state.close(spec, p, price, next.timeMs, "止损")
            } else if (openTarget || targetHit) state = state.close(spec, p, p.takeProfit!!, next.timeMs, "止盈")
        }
        return state
    }
}
