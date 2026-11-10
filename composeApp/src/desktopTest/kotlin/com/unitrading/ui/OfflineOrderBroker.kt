package com.unitrading.ui

import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A remote-style broker for chart order tests: bars between 5480 and 5520 around a 5500.00 / 5500.25 quote, an empty
 * account, and a record of every order sent. Nothing leaves the process.
 */
internal class OfflineOrderBroker : BrokerPlugin {
    val spec = Instrument("TESTZ6", "Offline futures", "离线测试", 2, 25, 1_250_000, minLotsHundredths = 100,
        lotStepHundredths = 100, quantityUnit = "张", quantityDecimals = 0, estimatesIncludeFees = false)
    val time = 1_798_589_000_000L
    override val descriptor = BrokerDescriptor("offline-orders", "离线下单测试", "Only synthetic fixtures", true, BrokerEnvironment.REMOTE_DEMO)
    override val capabilities = BrokerCapabilities(hedging = false)
    override val instruments = listOf(spec)
    override val accounts = listOf(AccountInfo("100", "离线测试账户"))
    override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
    override val market = MutableStateFlow(MarketSnapshot(mapOf(spec.symbol to Quote(550000, 550025, time)), time, connected = true))
    val placed = mutableListOf<OrderRequest>()
    /** What the broker's own check answers; an error stands for any order it would refuse. */
    var refusal: String? = null
    override fun history(symbol: String, timeframe: Timeframe) = (0..100).map { i ->
        val close = 550000L + (i % 9 - 4) * 500
        Candle(time - (100 - i) * timeframe.minutes * 60000L, close, close + 50, close - 50, close)
    }
    override fun openSession(accountId: String) = object : AccountSession {
        override val account = accounts.single()
        override val state = MutableStateFlow(AccountSnapshot("100", 25_000_000_000))
        override fun preview(request: OrderRequest) =
            refusal?.let { OrderPreview(null, errors = listOf(it), marginKnown = false, feesKnown = false) } ?: OrderPreview(request.entryPrice ?: 550025, marginKnown = false, feesKnown = false)
        override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(null, errors = listOf("unused"))
        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
        override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult { placed += request; return TradeResult(true, "离线订单已接受") }
        override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
        override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
    }
}
