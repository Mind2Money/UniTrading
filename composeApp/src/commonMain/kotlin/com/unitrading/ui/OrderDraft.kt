package com.unitrading.ui

import com.unitrading.broker.*

internal data class OrderDraft(
    val side: Side = Side.BUY,
    val kind: OrderKind = OrderKind.MARKET,
    val quantity: String = "0.10",
    val entry: String = "",
    val stopLoss: String = "",
    val takeProfit: String = "",
) {
    fun request(instrument: Instrument): OrderRequest? {
        val lots = instrument.parseQuantity(quantity) ?: return null
        val price = if (kind == OrderKind.MARKET) null else instrument.parsePrice(entry) ?: return null
        val sl = if (stopLoss.isBlank()) null else instrument.parsePrice(stopLoss) ?: return null
        val tp = if (takeProfit.isBlank()) null else instrument.parsePrice(takeProfit) ?: return null
        return OrderRequest(instrument.symbol, side, kind, lots, price, sl, tp)
    }

    fun withReferenceProtection(instrument: Instrument, quote: Quote): OrderDraft {
        val base = if (kind == OrderKind.MARKET) {
            if (side == Side.BUY) quote.bid else quote.ask
        } else instrument.parsePrice(entry) ?: return this
        val distance = maxOf(instrument.minStopTicks * 8, 80L) * instrument.tickSize
        return copy(
            stopLoss = instrument.formatPrice(if (side == Side.BUY) maxOf(instrument.tickSize, base - distance) else base + distance),
            takeProfit = instrument.formatPrice(if (side == Side.BUY) base + distance * 2 else maxOf(instrument.tickSize, base - distance * 2)),
        )
    }
}

internal data class PendingEntryEdit(val order: PendingOrder, val entry: String)
internal data class ProtectionEdit(val position: Position, val stopLoss: String, val takeProfit: String)
internal sealed interface Confirmation {
    data class Place(val request: OrderRequest) : Confirmation
    data class Close(val position: Position) : Confirmation
    data class Cancel(val order: PendingOrder) : Confirmation
    data object Reset : Confirmation
}

internal fun Side.label() = if (this == Side.BUY) "买入" else "卖出"
internal fun OrderKind.label() = when (this) { OrderKind.MARKET -> "市价"; OrderKind.LIMIT -> "限价"; OrderKind.STOP -> "突破" }
internal fun OrderRole.label() = when (this) {
    OrderRole.ENTRY -> "入场挂单"
    OrderRole.STOP_LOSS -> "SL 止损"
    OrderRole.TAKE_PROFIT -> "TP 止盈"
    OrderRole.UNRESOLVED -> "关联待确认"
}
internal fun OrderRole.editLabel() = when (this) {
    OrderRole.ENTRY -> "入场价"
    OrderRole.STOP_LOSS -> "止损价"
    OrderRole.TAKE_PROFIT -> "止盈价"
    OrderRole.UNRESOLVED -> "待确认"
}
