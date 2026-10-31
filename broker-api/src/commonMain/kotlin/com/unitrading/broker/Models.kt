package com.unitrading.broker

import kotlinx.serialization.Serializable

@Serializable
enum class Side { BUY, SELL }
@Serializable
enum class OrderKind { MARKET, LIMIT, STOP }
/** Bar interval in minutes; whole days are multiples of 1440. Equality is by minutes, so custom intervals compare with presets. */
@Serializable
data class Timeframe(val minutes: Int) {
    init { require(minutes in 1..MAX_MINUTES) { "周期必须在 1 分钟到 ${MAX_MINUTES / 1440} 天之间" } }
    val label: String get() = when {
        minutes % 1440 == 0 -> "${minutes / 1440}天"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes}分钟"
    }
    companion object {
        const val MAX_MINUTES = 1440 * 366
        val M1 = Timeframe(1)
        val M5 = Timeframe(5)
        val M15 = Timeframe(15)
        val H1 = Timeframe(60)
        /** Dropdown presets grouped by unit like TradingView: minutes, hours, days. */
        val presets: List<Timeframe> = listOf(1, 2, 3, 5, 10, 15, 30, 45, 60, 120, 180, 240, 360, 480, 720, 1440).map(::Timeframe)
        val defaultFavorites: List<Timeframe> = listOf(M1, M5, M15, H1)
        /** [count] units of [unitMinutes] (1, 60 or 1440), or null when outside the supported range. */
        fun of(count: Int, unitMinutes: Int): Timeframe? {
            if (count <= 0 || unitMinutes <= 0 || count > MAX_MINUTES / unitMinutes) return null
            return Timeframe(count * unitMinutes)
        }
    }
}

@Serializable
data class Instrument(
    val symbol: String,
    val name: String,
    val category: String,
    val priceDecimals: Int,
    val tickSize: Long = 1,
    val tickValueMicrosPerLot: Long,
    val minLotsHundredths: Long = 1,
    val maxLotsHundredths: Long = 10_000,
    val lotStepHundredths: Long = 1,
    val leverage: Int = 100,
    val minStopTicks: Long = 10,
    val commissionMicrosPerLot: Long = 0,
    val currency: String = "USD",
    val quantityUnit: String = "手",
    val quantityDecimals: Int = 2,
    val displayPriceDecimals: Int = priceDecimals,
    val estimatesIncludeFees: Boolean = true,
    /** Continuous series are chart symbols; orders must name their resolved dated contract. */
    val isContinuous: Boolean = false,
    val tradeSymbol: String? = null,
    /** Per-symbol resolution progress/failure, independent of connection notifications. */
    val tradeSymbolStatus: String? = null,
) {
    val executionSymbol: String? get() = if (isContinuous) tradeSymbol else symbol
    val executionLabel: String get() = executionSymbol ?: tradeSymbolStatus ?: "主力待确认"
    fun formatPrice(price: Long): String {
        val full = formatFixed(price, priceDecimals)
        if (displayPriceDecimals >= priceDecimals) return full
        val trimmed = full.trimEnd('0').trimEnd('.')
        val parts = trimmed.split('.')
        return if (displayPriceDecimals == 0 && parts.size == 1) trimmed
            else parts[0] + "." + parts.getOrElse(1) { "" }.padEnd(displayPriceDecimals, '0')
    }
    fun parsePrice(text: String) = parseFixed(text, priceDecimals)
    fun formatQuantity(value: Long) = if (quantityDecimals == 0) (value / 100).toString() else formatFixed(value, 2)
    fun parseQuantity(text: String): Long? = if (quantityDecimals == 0) parseFixed(text, 0)?.let { runCatching { checkedMultiply(it, 100) }.getOrNull() } else parseFixed(text, 2)
}

@Serializable
data class Quote(val bid: Long, val ask: Long, val timeMs: Long)

data class OrderCapacity(val maximumLotsHundredths: Long? = null, val message: String? = null)
@Serializable
data class Candle(val timeMs: Long, val open: Long, val high: Long, val low: Long, val close: Long, val volume: Long? = null)
@Serializable
enum class BrokerEnvironment(val label: String) { LOCAL_DEMO("本地模拟"), REMOTE_DEMO("Tradovate Demo"), LIVE("Tradovate Live · 真实账户") }
@Serializable
data class BrokerDescriptor(val id: String, val name: String, val description: String, val simulated: Boolean = true,
    val environment: BrokerEnvironment = if (simulated) BrokerEnvironment.LOCAL_DEMO else BrokerEnvironment.LIVE)
data class BrokerCatalog(val instruments: List<Instrument>, val accounts: List<AccountInfo>)
@Serializable
data class BrokerCapabilities(
    val orderKinds: Set<OrderKind> = OrderKind.entries.toSet(),
    val modifyProtection: Boolean = true,
    val modifyPendingOrders: Boolean = true,
    val hedging: Boolean = true,
    val resynchronize: Boolean = false,
    val addProtection: Boolean = true,
    val removeProtection: Boolean = true,
    val atomicProtectionEdit: Boolean = true,
    /** A pending entry's stop loss and take profit can move before it fills; add/remove/atomic rules as for positions. */
    val modifyPendingProtection: Boolean = true,
)
@Serializable
data class AccountInfo(val id: String, val name: String, val currency: String = "USD", val readOnly: Boolean = false)
@Serializable
data class MarketSnapshot(
    val quotes: Map<String, Quote> = emptyMap(),
    val simulatedTimeMs: Long = 0,
    val revision: Long = 0,
    val connected: Boolean = true,
    val statusMessage: String = "",
    val tradingReady: Boolean = true,
)
@Serializable
data class OrderRequest(
    val symbol: String,
    val side: Side,
    val kind: OrderKind,
    val lotsHundredths: Long,
    val entryPrice: Long? = null,
    val stopLoss: Long? = null,
    val takeProfit: Long? = null,
    /** Pins the reviewed continuous-to-dated mapping; never sent to the broker's order endpoint. */
    val sourceSymbol: String? = null,
)
@Serializable
data class OrderPreview(
    val entryPrice: Long?,
    val marginMicros: Long = 0,
    val feesMicros: Long = 0,
    val stopLossPnlMicros: Long? = null,
    val takeProfitPnlMicros: Long? = null,
    val errors: List<String> = emptyList(),
    val quoteRevision: Long = 0,
    val marginKnown: Boolean = true,
    val feesKnown: Boolean = true,
    val warnings: List<String> = emptyList(),
) { val valid: Boolean get() = errors.isEmpty() && entryPrice != null }

@Serializable
data class Position(
    val id: String,
    val symbol: String,
    val side: Side,
    val lotsHundredths: Long,
    val entryPrice: Long,
    val openedAtMs: Long,
    val stopLoss: Long? = null,
    val takeProfit: Long? = null,
    val marginMicros: Long,
    val entryFeeMicros: Long = 0,
    val version: Long = 1,
    val protectionEditable: Boolean = true,
    val feesKnown: Boolean = true,
    val stopLossOrderId: String? = null,
    val takeProfitOrderId: String? = null,
    val protectionRevision: String? = null,
    val protectionNote: String? = null,
)
@Serializable
enum class OrderRole { ENTRY, STOP_LOSS, TAKE_PROFIT, UNRESOLVED }
@Serializable
data class PendingOrder(
    val id: String,
    val request: OrderRequest,
    val createdAtMs: Long,
    val version: Long = 1,
    val role: OrderRole = OrderRole.ENTRY,
    val positionId: String? = null,
    val editable: Boolean = true,
    val note: String? = null,
    val filledLotsHundredths: Long = 0,
    /** Separate bracket orders holding an entry's request.stopLoss / takeProfit, where the broker keeps them apart. */
    val stopLossOrderId: String? = null,
    val takeProfitOrderId: String? = null,
    /** Whether an entry's stop loss and take profit can be moved before it fills. */
    val protectionEditable: Boolean = true,
) {
    val isProtection: Boolean get() = role == OrderRole.STOP_LOSS || role == OrderRole.TAKE_PROFIT
}
@Serializable
data class ClosedTrade(
    val id: String,
    val positionId: String,
    val symbol: String,
    val side: Side,
    val lotsHundredths: Long,
    val entryPrice: Long,
    val exitPrice: Long,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val netPnlMicros: Long?,
    val reason: String,
    val grossPnlMicros: Long? = null,
    val feesMicros: Long? = null,
    val directionKnown: Boolean = true,
)
@Serializable
data class ActivityEvent(val timeMs: Long, val message: String)
@Serializable
data class Execution(val id: String, val symbol: String, val side: Side, val lotsHundredths: Long, val price: Long, val timeMs: Long, val orderId: String)
@Serializable
data class DailyPnl(
    val totalMicros: Long,
    val realizedMicros: Long? = null,
    val openMicros: Long? = null,
    /** Broker session date, never inferred from the device's calendar. */
    val tradingDay: String? = null,
    /** Time the authoritative snapshot was received. */
    val updatedAtMs: Long,
)
@Serializable
data class AccountSnapshot(
    val accountId: String,
    val balanceMicros: Long,
    val equityMicros: Long = balanceMicros,
    val marginMicros: Long = 0,
    val positions: List<Position> = emptyList(),
    val orders: List<PendingOrder> = emptyList(),
    val trades: List<ClosedTrade> = emptyList(),
    val events: List<ActivityEvent> = emptyList(),
    val executions: List<Execution> = emptyList(),
    val balanceKnown: Boolean = true,
    val equityKnown: Boolean = true,
    val marginKnown: Boolean = true,
    val equityEstimated: Boolean = false,
    val dailyPnl: DailyPnl? = null,
) {
    val freeMarginMicros: Long get() = equityMicros - marginMicros
    val floatingPnlMicros: Long get() = equityMicros - balanceMicros
}
@Serializable
enum class CommandOutcome { ACCEPTED, REJECTED, UNKNOWN }
@Serializable
data class TradeResult(val accepted: Boolean, val message: String, val entityId: String? = null,
    val outcome: CommandOutcome = if (accepted) CommandOutcome.ACCEPTED else CommandOutcome.REJECTED)
