package com.unitrading.ui

import com.unitrading.broker.*

internal data class OrderDraft(
    val side: Side = Side.BUY,
    val kind: OrderKind = OrderKind.MARKET,
    val quantity: String = "0.10",
    val entry: String = "",
    val stopLoss: String = "",
    val takeProfit: String = "",
    val stopLossEnabled: Boolean = stopLoss.isNotBlank(),
    val takeProfitEnabled: Boolean = takeProfit.isNotBlank(),
) {
    /** Use the contract's integer quantity grid; never round a malformed manual entry into an order. */
    fun steppedQuantity(instrument: Instrument, increase: Boolean, maximum: Long? = instrument.maxLotsHundredths): String? {
        val current = instrument.parseQuantity(quantity) ?: return null
        val step = instrument.lotStepHundredths
        if (step <= 0 || current !in instrument.minLotsHundredths..instrument.maxLotsHundredths || current % step != 0L) return null
        if (increase && (maximum == null || maximum.coerceAtMost(instrument.maxLotsHundredths) - current < step)) return null
        if (!increase && current - instrument.minLotsHundredths < step) return null
        return instrument.formatQuantity(if (increase) current + step else current - step)
    }

    fun withKind(instrument: Instrument, quote: Quote?, value: OrderKind): OrderDraft {
        if (value == kind) return this
        return copy(kind = value, entry = if (value == OrderKind.MARKET) "" else quote?.let {
            val distance = maxOf(50L, instrument.minStopTicks * 4) * instrument.tickSize
            val current = if (side == Side.BUY) it.ask else it.bid
            val above = (side == Side.BUY) == (value == OrderKind.STOP)
            instrument.formatPrice(if (above) current + distance else maxOf(instrument.tickSize, current - distance))
        } ?: "", stopLoss = "", takeProfit = "").fillEnabledProtection(instrument, quote)
    }

    fun withSide(value: Side, instrument: Instrument, quote: Quote?): OrderDraft = if (value == side) this else
        copy(side = value, stopLoss = "", takeProfit = "").fillEnabledProtection(instrument, quote)

    fun withProtectionEnabled(instrument: Instrument, quote: Quote?, stop: Boolean, enabled: Boolean): OrderDraft {
        val changed = if (stop) copy(stopLossEnabled = enabled) else copy(takeProfitEnabled = enabled)
        if (!enabled || (if (stop) stopLoss else takeProfit).isNotBlank()) return changed
        val reference = withReferenceProtection(instrument, quote)
        return if (stop) changed.copy(stopLoss = reference.stopLoss) else changed.copy(takeProfit = reference.takeProfit)
    }

    fun withProtectionPrice(stop: Boolean, value: String): OrderDraft =
        if (stop) copy(stopLoss = value, stopLossEnabled = true) else copy(takeProfit = value, takeProfitEnabled = true)

    fun clearProtection() = copy(stopLoss = "", takeProfit = "", stopLossEnabled = false, takeProfitEnabled = false)

    private fun fillEnabledProtection(instrument: Instrument, quote: Quote?): OrderDraft {
        val reference = withReferenceProtection(instrument, quote)
        return copy(
            stopLoss = if (stopLossEnabled && stopLoss.isBlank()) reference.stopLoss else stopLoss,
            takeProfit = if (takeProfitEnabled && takeProfit.isBlank()) reference.takeProfit else takeProfit,
        )
    }

    fun request(instrument: Instrument, quantityOverride: Long? = null): OrderRequest? {
        val lots = quantityOverride ?: instrument.parseQuantity(quantity) ?: return null
        val price = if (kind == OrderKind.MARKET) null else instrument.parsePrice(entry) ?: return null
        val sl = if (!stopLossEnabled) null else instrument.parsePrice(stopLoss) ?: return null
        val tp = if (!takeProfitEnabled) null else instrument.parsePrice(takeProfit) ?: return null
        return OrderRequest(instrument.executionSymbol ?: return null, side, kind, lots, price, sl, tp,
            sourceSymbol = instrument.symbol.takeIf { instrument.isContinuous })
    }

    fun withReferenceProtection(instrument: Instrument, quote: Quote?): OrderDraft {
        val base = if (kind == OrderKind.MARKET) {
            quote ?: return this
            if (side == Side.BUY) quote.bid else quote.ask
        } else instrument.parsePrice(entry) ?: return this
        val distance = maxOf(instrument.minStopTicks * 8, 80L) * instrument.tickSize
        return copy(
            stopLossEnabled = true, takeProfitEnabled = true,
            stopLoss = instrument.formatPrice(if (side == Side.BUY) maxOf(instrument.tickSize, base - distance) else base + distance),
            takeProfit = instrument.formatPrice(if (side == Side.BUY) base + distance * 2 else maxOf(instrument.tickSize, base - distance * 2)),
        )
    }
}

/** What one chart window shows. [MAIN_CHART] is the chart of the main window; every other id is a window of its own. */
internal data class ChartSlot(val id: String, val symbol: String, val timeframe: Timeframe)
internal const val MAIN_CHART = "main"
/** The key a chart window's bounds are saved under. */
internal fun chartWindowKey(id: String) = "chart-$id"

internal data class PendingEntryEdit(val order: PendingOrder, val entry: String)
internal data class PendingProtectionEdit(val order: PendingOrder, val stopLoss: String, val takeProfit: String)
internal data class ProtectionEdit(val position: Position, val stopLoss: String, val takeProfit: String)
internal sealed interface Confirmation {
    /**
     * [fromChart] marks an order from the chart's context menu or trading keys: it is not the order panel's draft, so
     * placing it leaves the panel as it is. One asked for by a key ([fromKeys]) can also be confirmed by a key.
     */
    data class Place(val request: OrderRequest, val fromChart: Boolean = false, val fromKeys: Boolean = false) : Confirmation
    data class Close(val position: Position) : Confirmation
    data class Cancel(val order: PendingOrder) : Confirmation
    data object Reset : Confirmation
}

/** The readable tail of a broker id: internal ids may carry a namespace such as `atlas-demo:position:9`. */
internal fun shortId(id: String) = id.substringAfterLast(':').takeLast(7)

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
