package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Position
import com.unitrading.broker.Side
import com.unitrading.broker.OrderKind
import com.unitrading.broker.checkedAdd
import com.unitrading.broker.commission
import com.unitrading.broker.grossPnl

/** The replay ledger is deliberately independent of every broker account and order route. */
internal data class ReplayTrade(val position: Position, val exit: Long, val exitTimeMs: Long, val pnlMicros: Long, val reason: String)
internal data class ReplayPending(val id: Long, val side: Side, val kind: OrderKind, val quantity: Long,
    val price: Long, val stop: Long?, val target: Long?)
internal data class ReplayState(
    val index: Int, val positions: List<Position> = emptyList(), val trades: List<ReplayTrade> = emptyList(), val nextId: Long = 1,
    val pending: List<ReplayPending> = emptyList(), val lastNotice: String? = null,
) {
    fun enqueue(spec: Instrument, side: Side, kind: OrderKind, quantity: Long, price: Long, stop: Long?, target: Long?, current: Long): ReplayState {
        require(kind != OrderKind.MARKET) { "市价单应立即成交" }
        require(spec.tickSize > 0 && price > 0 && price % spec.tickSize == 0L) { "挂单价格无效" }
        require(if (kind == OrderKind.LIMIT) { if (side == Side.BUY) price < current else price > current }
            else { if (side == Side.BUY) price > current else price < current }) { "挂单价格方向不正确" }
        // Reuse the same contract, quantity and protection checks at the intended entry price.
        open(spec, Candle(0, price, price, price, price), side, quantity, stop, target)
        return copy(pending = pending + ReplayPending(nextId, side, kind, quantity, price, stop, target), nextId = nextId + 1)
    }
    fun modifyPending(spec: Instrument, id: Long, price: Long, stop: Long?, target: Long?, current: Long): ReplayState {
        val existing = pending.firstOrNull { it.id == id } ?: error("模拟挂单不存在")
        enqueue(spec, existing.side, existing.kind, existing.quantity, price, stop, target, current)
        return copy(pending = pending.map { if (it.id == id) it.copy(price = price, stop = stop, target = target) else it })
    }
    fun open(spec: Instrument, candle: Candle, side: Side, quantity: Long, stop: Long?, target: Long?): ReplayState {
        require(spec.lotStepHundredths > 0 && quantity > 0 && quantity >= spec.minLotsHundredths && quantity <= spec.maxLotsHundredths && quantity % spec.lotStepHundredths == 0L) { "数量不符合合约手数限制" }
        val entry = candle.close
        require(entry > 0 && spec.tickSize > 0 && entry % spec.tickSize == 0L) { "当前 K 线价格无效" }
        var remaining = quantity
        var state = this
        // Futures accounts are netted: opposite orders reduce existing lots in opening order before reversing.
        for (position in positions.filter { it.side != side }) {
            if (remaining == 0L) break
            val closedLots = minOf(remaining, position.lotsHundredths)
            val entryFee = if (closedLots == position.lotsHundredths) position.entryFeeMicros
                else com.unitrading.broker.multiplyDivide(position.entryFeeMicros, closedLots, 1, position.lotsHundredths)
            val part = position.copy(lotsHundredths = closedLots, entryFeeMicros = entryFee)
            val gross = grossPnl(spec, part.side, closedLots, part.entryPrice, entry)
            val net = checkedAdd(gross, -checkedAdd(entryFee, commission(spec, closedLots)))
            val rest = position.lotsHundredths - closedLots
            state = state.copy(positions = state.positions.mapNotNull {
                if (it.id != position.id) it else if (rest == 0L) null
                else it.copy(lotsHundredths = rest, entryFeeMicros = it.entryFeeMicros - entryFee)
            }, trades = state.trades + ReplayTrade(part, entry, candle.timeMs, net, "反向减仓"))
            remaining -= closedLots
        }
        if (remaining == 0L) return state
        require(stop == null || stop > 0 && stop % spec.tickSize == 0L && (if (side == Side.BUY) stop < entry else stop > entry)) { "止损价格无效或方向不正确" }
        require(target == null || target > 0 && target % spec.tickSize == 0L && (if (side == Side.BUY) target > entry else target < entry)) { "止盈价格无效或方向不正确" }
        val fee = commission(spec, remaining)
        val position = Position("replay-${state.nextId}", spec.executionSymbol ?: spec.symbol, side, remaining, entry, candle.timeMs,
            stopLoss = stop, takeProfit = target, marginMicros = 0, entryFeeMicros = fee)
        return state.copy(positions = state.positions + position, nextId = state.nextId + 1)
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
        var state = copy(index = index + 1, lastNotice = null)
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
        // Within a source candle the entry/exit path is unknown. Conservatively assume a touched stop
        // may occur after the entry; only take profit in this candle when the entry happened at its open.
        for (order in pending) {
            val hit = when (order.kind) {
                OrderKind.LIMIT -> if (order.side == Side.BUY) next.low <= order.price else next.high >= order.price
                OrderKind.STOP -> if (order.side == Side.BUY) next.high >= order.price else next.low <= order.price
                OrderKind.MARKET -> false
            }
            if (!hit) continue
            val fill = when (order.kind) {
                OrderKind.STOP -> if (order.side == Side.BUY) maxOf(next.open, order.price) else minOf(next.open, order.price)
                else -> order.price
            }
            state = state.copy(pending = state.pending.filterNot { it.id == order.id })
            val beforeIds = state.positions.map { it.id }.toSet()
            val opened = runCatching { state.open(spec, next.copy(close = fill), order.side, order.quantity, order.stop, order.target) }
            state = opened.getOrElse { state.copy(lastNotice = "挂单触发时保护价无效，已取消该模拟挂单") }
            val newPosition = state.positions.firstOrNull { it.id !in beforeIds }
            if (newPosition != null) {
                val openedAtBarOpen = when (order.kind) {
                    OrderKind.LIMIT -> if (order.side == Side.BUY) next.open <= order.price else next.open >= order.price
                    OrderKind.STOP -> if (order.side == Side.BUY) next.open >= order.price else next.open <= order.price
                    OrderKind.MARKET -> false
                }
                val hitStop = newPosition.stopLoss?.let { if (newPosition.side == Side.BUY) next.low <= it else next.high >= it } == true
                val hitTarget = newPosition.takeProfit?.let { if (newPosition.side == Side.BUY) next.high >= it else next.low <= it } == true
                if (hitStop) {
                    val stop = newPosition.stopLoss!!
                    val exit = if (!openedAtBarOpen) stop else if (newPosition.side == Side.BUY) minOf(next.open, stop) else maxOf(next.open, stop)
                    state = state.close(spec, newPosition, exit, next.timeMs, "止损")
                } else if (openedAtBarOpen && hitTarget) state = state.close(spec, newPosition, newPosition.takeProfit!!, next.timeMs, "止盈")
            }
        }
        return state
    }
}
