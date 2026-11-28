package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Shares a live chart feed but never forwards a trading command to that feed's account. */
internal class HyperpropChartBroker(
    private val source: BrokerPlugin,
    private val personal: HyperpropPersonalSession,
) : BrokerPlugin by source {
    internal val chartSourceId: String = source.descriptor.id
    override val descriptor = BrokerDescriptor("hyperprop-${source.descriptor.id}", "Hyperprop · 行情 ${source.descriptor.name}",
        "个人 API 的账户数据；行情来自 ${source.descriptor.name}。交易权限由个人密钥及 Hyperprop 账户授权决定。", simulated = false, environment = BrokerEnvironment.HYPERPROP)
    override val accounts = personal.check.accounts.map { AccountInfo(it.id, listOfNotNull(it.number, it.firm, it.accountType ?: "账户类型未提供").joinToString(" · "),
        readOnly = personal.check.scope != "trade") }
    override val instruments get() = source.instruments
    override val capabilities = BrokerCapabilities(orderKinds = OrderKind.entries.toSet(), modifyProtection = true, modifyPendingOrders = false,
        hedging = false, resynchronize = true, addProtection = false, removeProtection = false, atomicProtectionEdit = false,
        modifyPendingProtection = false)
    override val tradeFills: Flow<TradeFill> = emptyFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = MutableStateFlow(false)
    private val uncertain = MutableStateFlow(false)
    private val mutating = MutableStateFlow(false)
    private val status = MutableStateFlow("正在同步 Hyperprop 账户")
    private val catalogMutable = MutableStateFlow(BrokerCatalog(source.catalog.value.instruments, accounts))
    override val catalog: StateFlow<BrokerCatalog> = catalogMutable.asStateFlow()
    private val marketMutable = MutableStateFlow(source.market.value.copy(tradingReady = false, statusMessage = status.value))
    override val market: StateFlow<MarketSnapshot> = marketMutable.asStateFlow()
    private val snapshots = accounts.associate { account ->
        val balance = personal.check.accounts.first { it.id == account.id }.balance?.let { parseFixed(it, 6) }
        account.id to MutableStateFlow(AccountSnapshot(account.id, balance ?: 0, balanceKnown = balance != null, equityKnown = false, marginKnown = false))
    }
    private val syncLock = Mutex()
    private val writeLock = Mutex()
    private val results = mutableMapOf<String, TradeResult>()
    init {
        scope.launch { source.catalog.collect { catalogMutable.value = BrokerCatalog(it.instruments, accounts) } }
        scope.launch { combine(source.market, ready, status, uncertain, mutating) { feed, synced, text, unknown, busy ->
            feed.copy(connected = feed.connected && synced, tradingReady = feed.tradingReady && synced && !unknown && !busy,
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
            val accountData = personal.read("/trade/accounts")
            val positionData = personal.read("/trade/positions")
            val orderData = personal.read("/trade/orders/pending")
            val specs = source.catalog.value.instruments
            val next = accounts.associate { account ->
                val figures = hyperpropAccountFigures(accountData, account.id, Clock.System.now().toEpochMilliseconds())
                val positions = hyperpropPositions(positionData, account.id, specs)
                val orders = hyperpropWorkingOrders(orderData, account.id, specs)
                account.id to figures.copy(positions = hyperpropBindProtections(positions, orders), orders = orders)
            }
            next.forEach { (id, snapshot) -> snapshots.getValue(id).value = snapshot }
            status.value = if (uncertain.value) "交易结果待核对，请到 Hyperprop 核对后重新连接" else "Hyperprop 账户已同步 · 交易权限以密钥和账户授权为准"
            ready.value = true
        } finally { syncLock.unlock() }
    }
    override fun disconnect() { scope.cancel(); personal.close() }
    override fun openSession(accountId: String): AccountSession {
        val account = accounts.firstOrNull { it.id == accountId } ?: error("账户未授权")
        return object : AccountSession {
            override val account = account
            override val state = snapshots.getValue(accountId).asStateFlow()
            override fun preview(request: OrderRequest): OrderPreview {
                val error = entryError(account, request)
                if (error != null) return OrderPreview(null, errors = listOf(error), marginKnown = false, feesKnown = false)
                val quote = source.market.value.quotes[request.symbol]!!
                val entry = if (request.kind == OrderKind.MARKET) (if (request.side == Side.BUY) quote.ask else quote.bid) else request.entryPrice
                return OrderPreview(entry, marginKnown = false, feesKnown = false, quoteRevision = source.market.value.revision,
                    warnings = listOf("请核对 Hyperprop 到期合约；报价来自 ${source.descriptor.name}，成交以 Hyperprop 为准"))
            }
            override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(null, errors = listOf("挂单修改正在验证"))
            override suspend fun orderCapacity(request: OrderRequest, lastKnown: Long?) = OrderCapacity(message = "数量上限由 Hyperprop 在提交时校验")
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = refused()
            override suspend fun placeOrder(requestId: String, request: OrderRequest): TradeResult = write(requestId) {
                entryError(account, request)?.let { throw IllegalArgumentException(it) }
                val spec = source.catalog.value.instruments.first { it.symbol == (request.sourceSymbol ?: request.symbol) }
                val body = hyperpropOrderBody(accountId, request.symbol, if (request.side == Side.BUY) "buy" else "sell",
                    when (request.kind) { OrderKind.MARKET -> "market"; OrderKind.LIMIT -> "limit"; OrderKind.STOP -> "stop" },
                    (request.lotsHundredths / 100).toInt(), request.entryPrice?.let { formatFixed(it, spec.priceDecimals) } ?: "")
                val withBrackets = JsonObject(body + buildMap {
                    request.stopLoss?.let { put("stop_loss", Json.parseToJsonElement(formatFixed(it, spec.priceDecimals))) }
                    request.takeProfit?.let { put("take_profit", Json.parseToJsonElement(formatFixed(it, spec.priceDecimals))) }
                })
                "/trade/order" to withBrackets
            }
            override suspend fun closePosition(requestId: String, positionId: String): TradeResult = write(requestId) {
                checkMutable(account)
                val position = state.value.positions.firstOrNull { it.id == positionId } ?: throw IllegalArgumentException("持仓已结束或未同步")
                require(position.lotsHundredths > 0 && position.lotsHundredths % 100 == 0L) { "持仓数量无效" }
                val body = hyperpropOrderBody(accountId, position.symbol, if (position.side == Side.BUY) "sell" else "buy", "market", (position.lotsHundredths / 100).toInt(), "")
                "/trade/order" to JsonObject(body + ("reduce_only" to JsonPrimitive(true)))
            }
            override suspend fun cancelOrder(requestId: String, orderId: String): TradeResult = write(requestId) {
                checkMutable(account)
                require(state.value.orders.any { it.id == orderId }) { "订单已结束或未同步" }
                "/trade/order/cancel" to buildJsonObject { put("account_id", accountId); put("order_id", orderId) }
            }
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?): TradeResult = write(requestId) {
                checkMutable(account)
                val position = state.value.positions.firstOrNull { it.id == positionId } ?: throw IllegalArgumentException("持仓已结束或未同步")
                require(position.version == expectedVersion && position.protectionEditable && position.protectionRevision != null) { "持仓或保护单关系已变化" }
                val changedStop = stopLoss != position.stopLoss
                val changedTarget = takeProfit != position.takeProfit
                require(changedStop != changedTarget) { "每次只能修改一条止盈或止损" }
                val orderId = if (changedStop) position.stopLossOrderId else position.takeProfitOrderId
                val price = if (changedStop) stopLoss else takeProfit
                require(orderId != null && price != null) { "当前只能修改已确认的保护价，不能新增或移除" }
                val order = state.value.orders.firstOrNull { it.id == orderId && it.positionId == position.id } ?: throw IllegalArgumentException("保护单已变化")
                val spec = source.catalog.value.instruments.firstOrNull { it.executionSymbol == position.symbol } ?: throw IllegalArgumentException("合约规格未确认")
                val quote = source.market.value.quotes[position.symbol]
                val binding = bindHyperpropContract(source.descriptor.id, spec, position.symbol)
                hyperpropContractError(binding, source.descriptor.id, spec, position.symbol, quote, Clock.System.now().toEpochMilliseconds())?.let { throw IllegalArgumentException(it) }
                positionProtectionError(spec, position, quote, stopLoss, takeProfit)?.let { throw IllegalArgumentException(it) }
                require(order.version > 0) { "保护单版本未确认" }
                "/trade/order/modify" to buildJsonObject { put("account_id", accountId); put("order_id", orderId); put("price", Json.parseToJsonElement(formatFixed(price, spec.priceDecimals))) }
            }
            private fun refused() = TradeResult(false, "Hyperprop 主界面交易正在验证，当前只读")
        }
    }

    private fun checkMutable(account: AccountInfo) {
        require(!account.readOnly) { "当前密钥没有交易权限" }
        require(ready.value && !uncertain.value && !mutating.value) { "账户状态未确认，请在 Hyperprop 核对" }
    }

    private suspend fun write(requestId: String, prepare: () -> Pair<String, JsonObject>): TradeResult = writeLock.withLock {
        results[requestId]?.let { return@withLock it }
        if (requestId.isBlank() || requestId.length > 255 || requestId.any { it.code < 32 || it.code == 127 }) return@withLock TradeResult(false, "交易请求标识无效")
        try { resynchronize() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { ready.value = false; return@withLock TradeResult(false, "服务器状态同步失败，本次未发送交易请求") }
        val (path, body) = try { prepare() } catch (error: IllegalArgumentException) { return@withLock TradeResult(false, error.message ?: "交易参数无效") }
        mutating.value = true
        try {
            val outcome = personal.command(path, body, requestId)
            val result = when (outcome.succeeded) {
                true -> TradeResult(true, outcome.status, outcome.id)
                false -> TradeResult(false, outcome.status, outcome.id)
                null -> TradeResult(false, outcome.status, outcome.id, CommandOutcome.UNKNOWN)
            }
            if (outcome.succeeded == null) uncertain.value = true
            results[requestId] = result
            try { resynchronize() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { ready.value = false }
            result
        } catch (cancel: CancellationException) {
            uncertain.value = true
            results[requestId] = TradeResult(false, "请求中断，订单结果待核对", outcome = CommandOutcome.UNKNOWN)
            throw cancel
        } catch (_: Exception) {
            uncertain.value = true
            TradeResult(false, "订单结果待核对，请在 Hyperprop 确认后重新连接", outcome = CommandOutcome.UNKNOWN).also { results[requestId] = it }
        } finally { mutating.value = false }
    }

    private fun entryError(account: AccountInfo, request: OrderRequest): String? {
        if (account.readOnly) return "当前密钥没有交易权限"
        if (personal.check.accounts.firstOrNull { it.id == account.id }?.onlyPerpetualMarkets == true)
            return "当前账户没有期货市场权限，请选择 CME 期货账户"
        if (!ready.value || uncertain.value || mutating.value || !source.market.value.connected || !source.market.value.tradingReady)
            return "账户或行情尚未就绪，请先核对连接"
        val snapshot = snapshots.getValue(account.id).value
        if (snapshot.positions.isNotEmpty() || snapshot.orders.isNotEmpty()) return "测试版仅允许空仓且无挂单时下单；已有仓位请在 Hyperprop 管理"
        val spec = source.catalog.value.instruments.firstOrNull { it.symbol == (request.sourceSymbol ?: request.symbol) }
            ?: return "行情合约规格不可用"
        if (request.lotsHundredths < 100 || request.lotsHundredths % 100 != 0L || request.lotsHundredths > 100_000_000L)
            return "期货数量必须为正整数张"
        val binding = try { bindHyperpropContract(source.descriptor.id, spec, request.symbol) }
            catch (e: IllegalArgumentException) { return e.message ?: "合约未确认" }
        val quote = source.market.value.quotes[request.symbol]
        hyperpropContractError(binding, source.descriptor.id, spec, request.symbol, quote, Clock.System.now().toEpochMilliseconds())?.let { return it }
        if (request.kind != OrderKind.MARKET && request.entryPrice?.let { it > 0 && it % spec.tickSize == 0L } != true)
            return "请输入符合最小变动单位的价格"
        return entryProtectionError(spec, request, quote!!)
    }
}

internal val BrokerPlugin.chartSourceId: String
    get() = (this as? HyperpropChartBroker)?.chartSourceId ?: descriptor.id
