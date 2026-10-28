package com.unitrading.broker

import kotlinx.coroutines.flow.StateFlow

interface BrokerPlugin {
    val descriptor: BrokerDescriptor
    val capabilities: BrokerCapabilities
    val instruments: List<Instrument>
    val accounts: List<AccountInfo>
    val market: StateFlow<MarketSnapshot>
    fun openSession(accountId: String): AccountSession
    fun history(symbol: String, timeframe: Timeframe): List<Candle>
}

interface AccountSession {
    val account: AccountInfo
    val state: StateFlow<AccountSnapshot>
    fun preview(request: OrderRequest): OrderPreview
    fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview
    suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long): TradeResult
    suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult
    suspend fun closePosition(requestId: String, positionId: String): TradeResult
    suspend fun cancelOrder(requestId: String, orderId: String): TradeResult
    suspend fun modifyProtection(
        requestId: String,
        positionId: String,
        expectedVersion: Long,
        stopLoss: Long?,
        takeProfit: Long?,
    ): TradeResult
}

class BrokerRegistry(val plugins: List<BrokerPlugin>) {
    init {
        require(plugins.isNotEmpty()) { "至少注册一个交易商" }
        require(plugins.map { it.descriptor.id }.distinct().size == plugins.size) { "交易商 ID 重复" }
    }
    fun get(id: String): BrokerPlugin = plugins.first { it.descriptor.id == id }
}
