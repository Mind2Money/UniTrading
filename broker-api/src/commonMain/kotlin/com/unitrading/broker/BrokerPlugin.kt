package com.unitrading.broker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface BrokerPlugin {
    val descriptor: BrokerDescriptor
    val capabilities: BrokerCapabilities
    val instruments: List<Instrument>
    val accounts: List<AccountInfo>
    val catalog: StateFlow<BrokerCatalog>
    val market: StateFlow<MarketSnapshot>
    /** Best-effort live entry/protection notifications; never replays historical executions. */
    val tradeFills: Flow<TradeFill> get() = emptyFlow()
    /** Optional, read-only market tape and price-level depth for one explicitly watched contract. */
    val orderFlow: StateFlow<OrderFlowSnapshot>? get() = null
    fun watchOrderFlow(symbol: String?) {}
    suspend fun searchInstruments(query: String) {}
    /** Subscribe an independent quote list without changing the chart or sending trade commands. */
    fun watchQuotes(symbols: Set<String>) {}
    fun watch(symbol: String, timeframe: Timeframe) {}
    /**
     * Keeps every chart series in [series] live at once; each call replaces the list. The first is the chart the user is
     * working on. A broker that serves one live chart at a time keeps only that one, and the others show what
     * [history] already has.
     */
    fun watchAll(series: List<Pair<String, Timeframe>>) { series.firstOrNull()?.let { watch(it.first, it.second) } }
    /**
     * Series to read whole and cache in the background, most wanted first; each call replaces the list. A chart
     * that opens one of them then only fetches the bars since. Never changes the chart or sends trade commands.
     */
    fun preloadHistory(series: List<Pair<String, Timeframe>>) {}
    suspend fun resynchronize() {}
    fun disconnect() {}
    fun openSession(accountId: String): AccountSession
    fun history(symbol: String, timeframe: Timeframe): List<Candle>
}

interface AccountSession {
    val account: AccountInfo
    val state: StateFlow<AccountSnapshot>
    fun preview(request: OrderRequest): OrderPreview
    /**
     * Read-only server risk check. Null maximum means unavailable, never unlimited. Quantity in the request is ignored.
     * [lastKnown] is the limit the caller last got, which a broker may use to confirm an unchanged limit cheaply.
     */
    suspend fun orderCapacity(request: OrderRequest, lastKnown: Long? = null): OrderCapacity = OrderCapacity()
    fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview
    suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long): TradeResult
    /** Estimate for a pending entry whose stop loss and take profit become [stopLoss] and [takeProfit]. */
    fun previewOrderProtection(orderId: String, stopLoss: Long?, takeProfit: Long?): OrderPreview =
        OrderPreview(null, errors = listOf("当前交易商不支持修改挂单止损/止盈"))
    suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?): TradeResult =
        TradeResult(false, "当前交易商不支持修改挂单止损/止盈")
    /** Estimate for a [PendingOrder.standaloneEditable] order changed on its own to [price] and [lotsHundredths]. */
    fun previewOrderChange(orderId: String, price: Long, lotsHundredths: Long): OrderPreview =
        OrderPreview(null, errors = listOf(STANDALONE_UNSUPPORTED))
    /** Changes a [PendingOrder.standaloneEditable] order on its own, by its id. */
    suspend fun changeOrder(requestId: String, orderId: String, expectedVersion: Long, price: Long, lotsHundredths: Long): TradeResult =
        TradeResult(false, STANDALONE_UNSUPPORTED)
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

private const val STANDALONE_UNSUPPORTED = "当前交易商不支持单独修改这张订单"

/** The message for a stop or limit price that has already crossed the market, said of an order that may close a position. */
const val CROSSED_ORDER_PRICE = "价格已越过当前报价，改后会立即触发或成交"

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
