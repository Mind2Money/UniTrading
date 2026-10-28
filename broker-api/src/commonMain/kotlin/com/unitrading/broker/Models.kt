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
) {
    fun formatPrice(price: Long) = formatFixed(price, priceDecimals)
    fun parsePrice(text: String) = parseFixed(text, priceDecimals)
}

@Serializable
data class Quote(val bid: Long, val ask: Long, val timeMs: Long)
@Serializable
data class Candle(val timeMs: Long, val open: Long, val high: Long, val low: Long, val close: Long)
@Serializable
data class BrokerDescriptor(val id: String, val name: String, val description: String, val simulated: Boolean = true)
@Serializable
data class BrokerCapabilities(
    val orderKinds: Set<OrderKind> = OrderKind.entries.toSet(),
    val modifyProtection: Boolean = true,
    val modifyPendingOrders: Boolean = true,
    val hedging: Boolean = true,
)
@Serializable
data class AccountInfo(val id: String, val name: String, val currency: String = "USD")
@Serializable
data class MarketSnapshot(
    val quotes: Map<String, Quote> = emptyMap(),
    val simulatedTimeMs: Long = 0,
    val revision: Long = 0,
    val connected: Boolean = true,
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
)
@Serializable
data class PendingOrder(val id: String, val request: OrderRequest, val createdAtMs: Long, val version: Long = 1)
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
data class AccountSnapshot(
    val accountId: String,
    val balanceMicros: Long,
    val equityMicros: Long = balanceMicros,
    val marginMicros: Long = 0,
    val positions: List<Position> = emptyList(),
    val orders: List<PendingOrder> = emptyList(),
    val trades: List<ClosedTrade> = emptyList(),
    val events: List<ActivityEvent> = emptyList(),
) {
    val freeMarginMicros: Long get() = equityMicros - marginMicros
    val floatingPnlMicros: Long get() = equityMicros - balanceMicros
}
@Serializable
data class TradeResult(val accepted: Boolean, val message: String, val entityId: String? = null)
