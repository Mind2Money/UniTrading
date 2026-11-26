package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Shares a live chart feed but never forwards a trading command to that feed's account. */
internal class HyperpropChartBroker(
    private val source: BrokerPlugin,
    private val personal: HyperpropPersonalSession,
) : BrokerPlugin by source {
    override val descriptor = BrokerDescriptor("hyperprop-${source.descriptor.id}", "Hyperprop · 行情 ${source.descriptor.name}",
        "个人 API 的账户数据；行情来自 ${source.descriptor.name}。主界面交易尚未开放。", simulated = false, environment = BrokerEnvironment.HYPERPROP)
    override val accounts = personal.check.accounts.map { AccountInfo(it.id, listOfNotNull(it.number, it.firm).joinToString(" · "), readOnly = true) }
    override val instruments get() = source.instruments
    override val capabilities = BrokerCapabilities(orderKinds = emptySet(), modifyProtection = false, modifyPendingOrders = false,
        hedging = false, resynchronize = true, addProtection = false, removeProtection = false, atomicProtectionEdit = false,
        modifyPendingProtection = false)
    override val tradeFills: Flow<TradeFill> = emptyFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = MutableStateFlow(false)
    private val status = MutableStateFlow("正在同步 Hyperprop 账户")
    private val catalogMutable = MutableStateFlow(BrokerCatalog(source.catalog.value.instruments, accounts))
    override val catalog: StateFlow<BrokerCatalog> = catalogMutable.asStateFlow()
    private val marketMutable = MutableStateFlow(source.market.value.copy(tradingReady = false, statusMessage = status.value))
    override val market: StateFlow<MarketSnapshot> = marketMutable.asStateFlow()
    private val snapshots = accounts.associate { account ->
        val balance = personal.check.accounts.first { it.id == account.id }.balance?.let { parseFixed(it, 6) }
        account.id to MutableStateFlow(AccountSnapshot(account.id, balance ?: 0, balanceKnown = balance != null, equityKnown = false, marginKnown = false))
    }
    private val syncLock = kotlinx.coroutines.sync.Mutex()
    init {
        scope.launch { source.catalog.collect { catalogMutable.value = BrokerCatalog(it.instruments, accounts) } }
        scope.launch { combine(source.market, ready, status) { feed, synced, text ->
            feed.copy(connected = feed.connected && synced, tradingReady = false,
                statusMessage = "$text · 行情 ${source.descriptor.name}")
        }.collect { marketMutable.value = it } }
        scope.launch {
            while (isActive) {
                try { resynchronize() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { ready.value = false; status.value = "Hyperprop 同步失败，持仓或订单状态未确认" }
                delay(4_000)
            }
        }
    }
    override suspend fun resynchronize() {
        syncLock.lock()
        try {
            val positionData = personal.read("/trade/positions")
            val orderData = personal.read("/trade/orders/pending")
            val specs = source.catalog.value.instruments
            val next = accounts.associate { account ->
                val prev = snapshots.getValue(account.id).value
                val positions = hyperpropPositions(positionData, account.id, specs)
                val orders = hyperpropWorkingOrders(orderData, account.id, specs)
                account.id to prev.copy(positions = hyperpropBindProtections(positions, orders), orders = orders)
            }
            next.forEach { (id, snapshot) -> snapshots.getValue(id).value = snapshot }
            status.value = "Hyperprop 账户已同步 · 主界面只读测试"
            ready.value = true
        } finally { syncLock.unlock() }
    }
    override fun disconnect() { scope.cancel(); personal.close() }
    override fun openSession(accountId: String): AccountSession {
        val account = accounts.firstOrNull { it.id == accountId } ?: error("账户未授权")
        return object : AccountSession {
            override val account = account
            override val state = snapshots.getValue(accountId).asStateFlow()
            override fun preview(request: OrderRequest) = OrderPreview(null, errors = listOf("Hyperprop 主界面交易正在验证，当前只读"))
            override fun previewOrderModification(orderId: String, entryPrice: Long) = preview(OrderRequest("", Side.BUY, OrderKind.MARKET, 100))
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = refused()
            override suspend fun placeOrder(requestId: String, request: OrderRequest) = refused()
            override suspend fun closePosition(requestId: String, positionId: String) = refused()
            override suspend fun cancelOrder(requestId: String, orderId: String) = refused()
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = refused()
            private fun refused() = TradeResult(false, "Hyperprop 主界面交易正在验证，当前只读")
        }
    }
}
