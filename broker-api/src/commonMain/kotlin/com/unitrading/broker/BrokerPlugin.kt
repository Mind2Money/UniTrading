package com.unitrading.broker

import kotlinx.coroutines.flow.StateFlow

interface BrokerPlugin {
    val descriptor: BrokerDescriptor
    val capabilities: BrokerCapabilities
    val instruments: List<Instrument>
    val accounts: List<AccountInfo>
    val catalog: StateFlow<BrokerCatalog>
    val market: StateFlow<MarketSnapshot>
    suspend fun searchInstruments(query: String) {}
    fun watch(symbol: String, timeframe: Timeframe) {}
    fun disconnect() {}
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

class BrokerRegistry(plugins: List<BrokerPlugin>) {
    private val mutablePlugins = kotlinx.coroutines.flow.MutableStateFlow(plugins)
    val state: StateFlow<List<BrokerPlugin>> = mutablePlugins
    val plugins: List<BrokerPlugin> get() = state.value
    init {
        require(plugins.map { it.descriptor.id }.distinct().size == plugins.size) { "交易商 ID 重复" }
    }
    fun register(plugin: BrokerPlugin) {
        val previous = plugins.firstOrNull { it.descriptor.id == plugin.descriptor.id }
        if (previous === plugin) return
        previous?.disconnect()
        mutablePlugins.value = plugins.filterNot { it.descriptor.id == plugin.descriptor.id } + plugin
    }
    fun get(id: String): BrokerPlugin = plugins.first { it.descriptor.id == id }
    fun unregister(id: String) {
        val removed = plugins.firstOrNull { it.descriptor.id == id } ?: return
        removed.disconnect()
        mutablePlugins.value = plugins.filterNot { it.descriptor.id == id }
    }
}
