package com.unitrading.broker

import kotlinx.serialization.Serializable

@Serializable
enum class Side { BUY, SELL }
@Serializable
enum class OrderKind { MARKET, LIMIT, STOP }
@Serializable
enum class Timeframe(val minutes: Int, val label: String) {
    M1(1, "1分"), M5(5, "5分"), M15(15, "15分"), H1(60, "1时")
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
) {
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
@Serializable
data class Candle(val timeMs: Long, val open: Long, val high: Long, val low: Long, val close: Long)
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
    val netPnlMicros: Long,
    val reason: String,
)
@Serializable
data class ActivityEvent(val timeMs: Long, val message: String)
@Serializable
data class Execution(val id: String, val symbol: String, val side: Side, val lotsHundredths: Long, val price: Long, val timeMs: Long, val orderId: String)
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
) {
    val freeMarginMicros: Long get() = equityMicros - marginMicros
    val floatingPnlMicros: Long get() = equityMicros - balanceMicros
}
@Serializable
enum class CommandOutcome { ACCEPTED, REJECTED, UNKNOWN }
@Serializable
data class TradeResult(val accepted: Boolean, val message: String, val entityId: String? = null,
    val outcome: CommandOutcome = if (accepted) CommandOutcome.ACCEPTED else CommandOutcome.REJECTED)
