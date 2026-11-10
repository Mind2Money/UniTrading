package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock

/** History depth per series: twice the largest indicator period, like the Tradovate adapter. */
const val RITHMIC_CHART_BARS = 2000
private const val QUOTE_FRESH_MS = 15_000L
/** Upper bound for the list loaded at login; anything beyond it stays reachable through search. */
private const val PRODUCT_LIST_LIMIT = 200

/**
 * R | Protocol API adapter. Four plant connections (ticker, order, PnL, history) share one login;
 * every number shown comes from the server, and nothing is ever re-sent after an unknown result.
 */
class RithmicBroker(
    private val credentials: RithmicCredentials,
    private val wire: RithmicWire = KtorRithmicWire(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    candleCacheStore: CandleCacheStore? = null,
) : BrokerPlugin {
    override val descriptor = BrokerDescriptor(credentials.brokerId,
        if (credentials.systemName.trim().startsWith("Rithmic", ignoreCase = true)) credentials.systemName.trim() else "Rithmic · ${credentials.systemName.trim()}",
        "R | Protocol 行情与净额期货账户", simulated = !credentials.environment.live,
        environment = when (credentials.environment) {
            RithmicEnvironment.TEST -> BrokerEnvironment.RITHMIC_TEST
            RithmicEnvironment.PAPER -> BrokerEnvironment.RITHMIC_PAPER
            RithmicEnvironment.LIVE -> BrokerEnvironment.RITHMIC_LIVE
        })
    override val capabilities = BrokerCapabilities(hedging = false, resynchronize = true,
        addProtection = false, removeProtection = false, atomicProtectionEdit = false)
    private val mutableCatalog = MutableStateFlow(BrokerCatalog(emptyList(), emptyList()))
    override val catalog: StateFlow<BrokerCatalog> = mutableCatalog.asStateFlow()
    override val instruments get() = catalog.value.instruments
    override val accounts get() = catalog.value.accounts
    private val mutableMarket = MutableStateFlow(MarketSnapshot(connected = false, tradingReady = false, statusMessage = "尚未连接"))
    override val market: StateFlow<MarketSnapshot> = mutableMarket.asStateFlow()
    private val tradeEvents = MutableSharedFlow<TradeFill>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val tradeFills = tradeEvents.asSharedFlow()
    private val flowWindow = RithmicOrderFlow()
    private val mutableOrderFlow = MutableStateFlow(OrderFlowSnapshot())
    override val orderFlow: StateFlow<OrderFlowSnapshot> = mutableOrderFlow.asStateFlow()
    private val orderFlowSyncLock = Mutex()
    private var requestedOrderFlow: String? = null
    private var requestedFlowRevision = 0L
    private var activeFlowRevision = -1L
    private var orderFlowExecution: String? = null
    private var subscribedDepth: String? = null
    private var flowPublishJob: Job? = null

    private val lock = Mutex()
    private val commandLock = Mutex()
    private val connectLock = Mutex()
    private val chartLock = Mutex()
    private val quoteSyncLock = Mutex()
    private val specLocks = mutableMapOf<String, Mutex>()
    private val specSlots = Semaphore(4)
    private val productListSlots = Semaphore(2)
    private var ticker: RithmicPlant? = null
    private var orderPlant: RithmicPlant? = null
    private var pnlPlant: RithmicPlant? = null
    private var historyPlant: RithmicPlant? = null
    private var fcmId = ""
    private var ibId = ""
    private var userType = RithmicEnums.USER_TYPE_TRADER
    private var accountInfos = emptyList<AccountInfo>()
    private var tradeRoutes = emptyMap<String, String>()
    private val specs = mutableMapOf<String, RithmicSpec>()
    /** [candidates] are the exchanges listing the product, routable ones first; the first that names a front month becomes [exchange]. */
    private data class ContinuousRoute(val productCode: String, val exchange: String, val tradeSymbol: String?, val checkedAt: Long?, val status: String?, val candidates: List<String> = listOf(exchange))
    private val continuousRoutes = mutableMapOf<String, ContinuousRoute>()
    private val orders = mutableMapOf<String, RithmicOrder>()
    private val fills = mutableMapOf<String, RithmicFill>()
    private val positions = mutableMapOf<Pair<String, String>, RithmicPosition>()
    private val financials = mutableMapOf<String, RithmicFinancials>()
    private var risks = emptyMap<String, RithmicRisk>()
    private val maxOrderQuantities = mutableMapOf<String, Long?>()
    private data class ProductRms(val orderLimit: Long?, val commissionMicros: Long?)
    private val productRms = mutableMapOf<Pair<String, String>, ProductRms>()
    private val brackets = mutableMapOf<String, BracketLevels>()
    private data class QuoteSides(val bid: Long?, val ask: Long?, val time: Long, val bidSize: Long? = null, val askSize: Long? = null)
    private val quoteSides = mutableMapOf<String, QuoteSides>()
    private var quotes = emptyMap<String, Quote>()
    private val subscribedQuotes = mutableSetOf<String>()
    private var history = emptyMap<Pair<String, Timeframe>, List<Candle>>()
    // v2: continuous series now hold the alias's history. Older entries hold one dated contract, and a cached series only ever grows at its end.
    private val candleCache = CandleCache(candleCacheStore, "rithmic-v2-${credentials.brokerId}", RITHMIC_CHART_BARS)
    /** Continuous series showing their dated contract alone because the alias had no bars. They stay out of the cache, so the next run asks the alias again. */
    private val singleContractSeries = mutableSetOf<Pair<String, Timeframe>>()
    private var barSubscription: Pair<String, Timeframe>? = null
    private var accountFlows = emptyMap<String, MutableStateFlow<AccountSnapshot>>()
    private val localEvents = mutableMapOf<String, List<ActivityEvent>>()
    private val results = mutableMapOf<String, Pair<String, TradeResult>>()
    private val unknownCommands = mutableSetOf<String>()
    private val attemptedRecovery = mutableSetOf<String>()
    private data class Awaiting(val account: String, val kind: String, val entity: String?, val tag: String, val started: Long,
        val expectedPrice: Long? = null, val baselineSequence: String? = null, val symbol: String? = null)
    private val awaiting = mutableMapOf<String, Awaiting>()
    private val notifiedFills = mutableSetOf<String>()
    private var connected = false
    private var marketReady = false
    private var connecting = false
    private var synchronizing = false
    private var stopped = false
    private var problem = "正在连接 Rithmic"
    private var watching: Pair<String, Timeframe>? = null
    private var quoteWatchSymbols = emptySet<String>()
    private var watchJob: Job? = null
    private var quoteWatchJob: Job? = null
    private var maintenance: Job? = null
    private var reconnectJob: Job? = null
    private var recoveryJob: Job? = null
    private var continuousJob: Job? = null
    private var productListJob: Job? = null
    private var liveSince = 0L

    // ───────────────────────────── connection ─────────────────────────────

    suspend fun connect(onProgress: (String) -> Unit = {}) = withContext(scope.coroutineContext.minusKey(Job)) {
        check(!stopped)
        connectLock.withLock {
            lock.withLock {
                connecting = true; connected = false; marketReady = false; problem = "正在连接 ${descriptor.name}"
                resetOrderFlow("连接已重置，等待重新订阅"); publish()
            }
            onProgress(problem)
            closePlants(logout = false)
            val gateway = credentials.normalizedGateway
            try {
                val systems = systemNames(gateway)
                if (systems.isNotEmpty() && systems.none { it.equals(credentials.systemName.trim(), ignoreCase = true) })
                    throw RithmicRemoteException("system", "系统名称“${credentials.systemName}”不在该网关提供的列表中：${systems.joinToString("、")}")
                onProgress("正在登录 Rithmic 订单服务…")
                val order = openPlant("order", gateway, ::orderEvent)
                order.login(credentials, RithmicEnums.INFRA_ORDER)
                loadLoginInfo(order)
                loadAccounts(order)
                onProgress("服务器已返回 ${accountInfos.size} 个账户，正在读取交易路由、订单和成交…")
                loadTradeRoutes(order)
                lock.withLock { synchronizing = true; orders.clear(); fills.clear(); brackets.clear() }
                for (account in accountInfos) subscribeAccount(order, account.id)
                lock.withLock { synchronizing = false }
                onProgress("正在登录盈亏服务…")
                val pnl = openPlant("pnl", gateway, ::pnlEvent)
                pnl.login(credentials, RithmicEnums.INFRA_PNL)
                lock.withLock { positions.clear(); financials.clear() }
                for (account in accountInfos) subscribePnl(pnl, account.id)
                onProgress("正在登录行情服务…")
                val tick = openPlant("ticker", gateway, ::tickerEvent)
                tick.login(credentials, RithmicEnums.INFRA_TICKER, aggregatedQuotes = false)
                lock.withLock { quoteSides.clear(); quotes = emptyMap(); subscribedQuotes.clear() }
                onProgress("正在登录历史数据服务…")
                val hist = openPlant("history", gateway, ::historyEvent)
                hist.login(credentials, RithmicEnums.INFRA_HISTORY)
                lock.withLock { barSubscription = null }
                onProgress("正在加载合约规格…")
                val wanted = lock.withLock { (orders.values.filter { it.active }.map { it.symbol to it.exchange } +
                    positions.values.filter { it.net != 0L }.map { it.symbol to it.exchange } + fills.values.map { it.symbol to "" }).distinct() }
                withTimeoutOrNull(30_000) { hydrateSpecs(wanted) }
                lock.withLock {
                    connected = true; marketReady = true; connecting = false; liveSince = now()
                    problem = if (accountInfos.isEmpty()) "当前登录没有可访问的交易账户" else "已连接 · ${descriptor.name} · 净额持仓"
                    rebuild()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                closePlants(logout = true)
                lock.withLock { connecting = false; connected = false; marketReady = false; problem = safeRithmicMessage(error.message ?: "连接失败"); publish() }
                throw error
            }
        }
        // Rithmic has no contract list to read at login, so start from the product the Tradovate adapter loads;
        // with an empty catalog the app would stop on its manual search page.
        if (lock.withLock { marketReady && specs.isEmpty() }) {
            onProgress("正在加载默认合约…")
            try { withTimeoutOrNull(15_000) { searchInstruments("MES") } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The manual search page stays available. */ }
        }
        onProgress(problem)
        watching?.let { watch(it.first, it.second) }
        refreshQuoteWatch()
        scope.launch { syncOrderFlow() }
        startMaintenance()
        productListJob?.cancel()
        productListJob = scope.launch { syncProductList() }
    }

    private suspend fun openPlant(name: String, gateway: String, onEvent: suspend (ProtoMessage) -> Unit): RithmicPlant {
        val plant = RithmicPlant(name, wire.socket(gateway), scope, now, onEvent) { reason ->
            scope.launch {
                lock.withLock {
                    if (stopped) return@withLock
                    connected = false; marketReady = false; problem = "$reason，正在等待重新连接"
                    resetOrderFlow("连接中断，逐笔窗口已清空"); publish()
                }
                scheduleReconnect()
            }
        }
        plant.start()
        when (name) { "order" -> orderPlant = plant; "pnl" -> pnlPlant = plant; "ticker" -> ticker = plant; "history" -> historyPlant = plant }
        return plant
    }

    /** Documented handshake: ask a fresh connection for the system names, then close it before logging in. */
    private suspend fun systemNames(gateway: String): List<String> = try {
        val probe = RithmicPlant("probe", wire.socket(gateway), scope, now, {}, {})
        probe.start()
        try { withTimeout(10_000) { probe.request(Rt.REQUEST_SYSTEM_INFO) {} }.flatMap { it.strings(Rf.SYSTEM_NAME) }.distinct() }
        finally { probe.close(logout = false) }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { emptyList() }

    private suspend fun loadLoginInfo(order: RithmicPlant) {
        // An FCM/IB-less reply still logs in; account-scoped requests then go out unscoped, as the public clients do.
        val info = order.request(Rt.REQUEST_LOGIN_INFO) {}.lastOrNull { it.has(Rf.FCM_ID) || it.has(Rf.IB_ID) || it.has(Rf.USER_TYPE) }
        fcmId = info?.str(Rf.FCM_ID)?.trim() ?: ""
        ibId = info?.str(Rf.IB_ID)?.trim() ?: ""
        userType = info?.int(Rf.USER_TYPE)?.takeIf { it in 1..3 } ?: RithmicEnums.USER_TYPE_TRADER
    }

    private suspend fun loadAccounts(order: RithmicPlant) {
        val rows = order.request(Rt.REQUEST_ACCOUNT_LIST) { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); int32(Rf.USER_TYPE, userType) }
        val mapped = rows.mapNotNull { row ->
            val id = row.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return@mapNotNull null
            AccountInfo(id, row.str(Rf.ACCOUNT_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: id, row.str(Rf.ACCOUNT_CURRENCY)?.trim()?.ifEmpty { null } ?: "USD")
        }.distinctBy { it.id }
        // A login reads the line afresh, so what a dropped connection left behind cannot stand in for it.
        val lines = rows.mapNotNull { row -> row.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { id -> mapped.any { it.id == id } }?.let { it to parseRiskUpdate(row, null) } }.toMap()
        lock.withLock {
            risks = lines
            accountInfos = mapped
            accountFlows = mapped.associate { it.id to (accountFlows[it.id] ?: MutableStateFlow(AccountSnapshot(it.id, 0, balanceKnown = false, equityKnown = false, marginKnown = false))) }
        }
    }

    private suspend fun loadTradeRoutes(order: RithmicPlant) {
        val rows = order.request(Rt.REQUEST_TRADE_ROUTES) { bool(Rf.SUBSCRIBE_FLAG, true) }
        lock.withLock { tradeRoutes = routesFrom(rows, tradeRoutes) }
    }

    private fun routesFrom(rows: List<ProtoMessage>, previous: Map<String, String>): Map<String, String> {
        val routes = previous.toMutableMap()
        val defaults = mutableSetOf<String>()
        for (row in rows) {
            val exchange = row.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val route = row.str(Rf.TRADE_ROUTE)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val status = row.str(Rf.ROUTE_STATUS)?.trim()?.lowercase()
            if (status != null && status.isNotEmpty() && status != "up" && status != "open" && status != "active") continue
            val isDefault = row.bool(Rf.IS_DEFAULT_ROUTE) == true
            if (isDefault || exchange !in defaults && exchange !in routes) routes[exchange] = route
            if (isDefault) defaults.add(exchange)
        }
        return routes
    }

    private suspend fun subscribeAccount(order: RithmicPlant, accountId: String) {
        fun ProtoWriter.account() { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId) }
        order.request(Rt.REQUEST_SUBSCRIBE_ORDER_UPDATES) { account() }
        runCatching { order.request(Rt.REQUEST_SUBSCRIBE_BRACKET_UPDATES) { account() } }
        // The open-order snapshot arrives as notifications with is_snapshot; the reply itself only closes the request.
        order.request(Rt.REQUEST_SHOW_ORDERS, 30_000) { account() }
        refreshBrackets(order, accountId)
        try {
            val rms = order.request(Rt.REQUEST_ACCOUNT_RMS_INFO) { account(); int32(Rf.USER_TYPE, userType) }
            val limits = rms.filter { it.str(Rf.ACCOUNT_ID)?.trim() == accountId || !it.has(Rf.ACCOUNT_ID) }
                .flatMap { listOfNotNull(it.long(Rf.MAX_ORDER_QUANTITY), it.long(Rf.BUY_LIMIT), it.long(Rf.SELL_LIMIT)) }.filter { it > 0 }
            lock.withLock {
                maxOrderQuantities[accountId] = limits.minOrNull()
                parseRiskSettings(rms, accountId, risks[accountId])?.let { risks = risks + (accountId to it) }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { lock.withLock { maxOrderQuantities[accountId] = null } }
        // Display data: a system without this stream costs the login nothing, so nothing waits for the reply.
        scope.launch {
            try { order.request(Rt.REQUEST_ACCOUNT_RMS_UPDATES) { account(); string(Rf.REQUEST, "subscribe"); int32(Rf.UPDATE_BITS, RithmicEnums.RMS_UPDATE_AUTO_LIQ_CURRENT_VALUE) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The line stays at what the login read. */ }
        }
        try {
            val rows = order.request(Rt.REQUEST_PRODUCT_RMS_INFO, 30_000) { account() }
            lock.withLock {
                productRms.keys.removeAll { it.first == accountId }
                for (row in rows) {
                    val product = row.str(Rf.PRODUCT_CODE)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    productRms[accountId to product] = ProductRms(
                        listOfNotNull(row.long(Rf.MAX_ORDER_QUANTITY), row.long(Rf.BUY_LIMIT), row.long(Rf.SELL_LIMIT)).filter { it > 0 }.minOrNull(),
                        row.double(Rf.COMMISSION_FILL_RATE)?.takeIf { it >= 0 && it.isFinite() }?.let { (it * MICROS_PER_USD).roundToLong() })
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The account-wide limit still applies, and the server checks every order. */ }
        try {
            val end = now() / 1_000
            val rows = order.request(Rt.REQUEST_SHOW_FILL_HISTORY, 30_000) {
                account(); string(Rf.INDEX_FORMAT, "ssboe"); int32(Rf.START_INDEX, (end - 7 * 86_400).toInt()); int32(Rf.FINISH_INDEX, end.toInt()); int32(Rf.USER_MAX_COUNT, 5_000)
            }
            lock.withLock { rows.mapNotNull { parseFill(it, now()) }.forEach { fills[it.id] = it }; notifiedFills.addAll(fills.keys) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Missing history leaves the execution list shorter, never wrong. */ }
    }

    private suspend fun refreshBrackets(order: RithmicPlant, accountId: String) {
        fun ProtoWriter.account() { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId) }
        try {
            val targets = order.request(Rt.REQUEST_SHOW_BRACKETS) { account() }
            val stops = order.request(Rt.REQUEST_SHOW_BRACKET_STOPS) { account() }
            lock.withLock {
                for (row in targets) {
                    val id = row.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val previous = brackets[id] ?: BracketLevels()
                    brackets[id] = previous.copy(targetTicks = parseTicks(row, Rf.TARGET_TICKS), targetQuantity = parseTicks(row, Rf.TARGET_QUANTITY))
                }
                for (row in stops) {
                    val id = row.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val previous = brackets[id] ?: BracketLevels()
                    brackets[id] = previous.copy(stopTicks = parseTicks(row, Rf.STOP_TICKS), stopQuantity = parseTicks(row, Rf.STOP_QUANTITY))
                }
                rebuild()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Bracket levels stay unknown; pending protection edits are then refused. */ }
    }

    private suspend fun subscribePnl(pnl: RithmicPlant, accountId: String) {
        fun ProtoWriter.account() { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId) }
        pnl.request(Rt.REQUEST_PNL_SNAPSHOT, 30_000) { account() }
        pnl.request(Rt.REQUEST_PNL_UPDATES) { account(); int32(Rf.REQUEST, RithmicEnums.REQUEST_SUBSCRIBE) }
    }

    private suspend fun closePlants(logout: Boolean) {
        val plants = listOfNotNull(ticker, orderPlant, pnlPlant, historyPlant)
        ticker = null; orderPlant = null; pnlPlant = null; historyPlant = null
        plants.forEach { runCatching { it.close(logout) } }
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true || connectLock.isLocked) return
        reconnectJob = scope.launch {
            for (attempt in 1..3) {
                delay(attempt * 3_000L)
                if (stopped) return@launch
                try { connect(); return@launch }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { lock.withLock { problem = "连接恢复失败（第 $attempt 次）；未重放任何交易指令"; publish() } }
            }
            lock.withLock { problem = "连接恢复失败，请重新登录；未重放任何交易指令"; publish() }
        }
    }

    private fun startMaintenance() {
        maintenance?.cancel()
        maintenance = scope.launch {
            while (isActive && !stopped) {
                delay(1_000)
                val recover = lock.withLock {
                    val expired = awaiting.filter { (key, wait) -> now() - wait.started >= 30_000 && key !in attemptedRecovery }.keys
                    attemptedRecovery.addAll(expired)
                    unknownCommands.addAll(expired)
                    if (expired.isNotEmpty()) publish()
                    expired.isNotEmpty()
                }
                if (recover && recoveryJob?.isActive != true) recoveryJob = scope.launch {
                    try { resynchronize() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { lock.withLock { problem = "重新同步失败，未重发交易指令；请重试同步或在 R | Trader 核对"; publish() } }
                }
                val selected = watching?.first
                if (selected != null && lock.withLock { continuousRoutes[selected]?.let { route -> route.checkedAt == null || now() - route.checkedAt >= if (route.tradeSymbol == null) 60_000L else 6 * 3_600_000L } == true } && continuousJob?.isActive != true) {
                    continuousJob = scope.launch { refreshContinuous(selected) }
                }
            }
        }
    }

    override suspend fun resynchronize() = commandLock.withLock {
        val order = orderPlant; val pnl = pnlPlant
        check(!stopped && connected && order != null && pnl != null) { "账户连接已断开，请先恢复连接" }
        lock.withLock { problem = "正在重新读取账户状态；不会重发交易指令"; publish() }
        for (account in accountInfos) {
            order.request(Rt.REQUEST_SHOW_ORDERS, 30_000) { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, account.id) }
            refreshBrackets(order, account.id)
            pnl.request(Rt.REQUEST_PNL_SNAPSHOT, 30_000) { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, account.id) }
        }
        val wanted = lock.withLock { (orders.values.filter { it.active }.map { it.symbol to it.exchange } + positions.values.filter { it.net != 0L }.map { it.symbol to it.exchange }).distinct() }
        hydrateSpecs(wanted)
        watching?.first?.takeIf { it in continuousRoutes }?.let { refreshContinuous(it) }
        lock.withLock { reconcile(fullSnapshot = true); problem = "账户状态已重新同步"; rebuild() }
    }

    override fun disconnect() {
        stopped = true
        scope.launch {
            lock.withLock { connected = false; marketReady = false; problem = "已断开"; resetOrderFlow("已断开，逐笔窗口已清空"); publish() }
            maintenance?.cancel(); reconnectJob?.cancel(); watchJob?.cancel(); quoteWatchJob?.cancel(); recoveryJob?.cancel(); continuousJob?.cancel(); productListJob?.cancel()
            closePlants(logout = true)
            saveCandleCache()
            wire.close(); scope.cancel()
        }
    }

    // ───────────────────────────── plant events ─────────────────────────────

    private suspend fun orderEvent(message: ProtoMessage) {
        when (message.templateId) {
            Rt.RITHMIC_ORDER_NOTIFICATION, Rt.EXCHANGE_ORDER_NOTIFICATION -> lock.withLock {
                val basket = message.str(Rf.BASKET_ID)?.trim() ?: return@withLock
                val previous = orders[basket]
                val next = parseOrderNotification(message, previous, now()) ?: return@withLock
                if (previous != null && previous.sequence != null && next.sequence != null && next.sequence < previous.sequence && next.timeMs < previous.timeMs) return@withLock
                orders[basket] = next
                val rejected = message.int(Rf.NOTIFY_TYPE) == RithmicEnums.NOTIFY_REJECT && message.templateId == Rt.EXCHANGE_ORDER_NOTIFICATION ||
                    next.status.trim().equals("rejected", ignoreCase = true) && !previous?.status.equals("rejected", ignoreCase = true)
                if (rejected && !synchronizing && message.bool(Rf.IS_SNAPSHOT) != true) {
                    val reason = listOfNotNull(message.str(Rf.REPORT_TEXT), message.str(Rf.TEXT)).firstOrNull { it.isNotBlank() } ?: "服务器拒绝"
                    localEvents[next.accountId] = ((localEvents[next.accountId] ?: emptyList()) +
                        ActivityEvent(now(), "订单 ${next.basketId}（${next.symbol}）被拒绝：${safeRithmicMessage(reason)}")).takeLast(200)
                }
                if (message.templateId == Rt.EXCHANGE_ORDER_NOTIFICATION && message.int(Rf.NOTIFY_TYPE) == RithmicEnums.NOTIFY_FILL) {
                    parseFill(message, now())?.let { fill ->
                        if (fill.id !in fills) {
                            fills[fill.id] = fill
                            if (!synchronizing && message.bool(Rf.IS_SNAPSHOT) != true && now() >= liveSince && fill.id !in notifiedFills) {
                                notifiedFills.add(fill.id)
                                val role = when {
                                    next.isBracketChild && next.isStop -> OrderRole.STOP_LOSS
                                    next.isBracketChild && RithmicEnums.PRICE_LIMIT in setOf(next.priceType, next.origPriceType) -> OrderRole.TAKE_PROFIT
                                    else -> OrderRole.ENTRY
                                }
                                tradeEvents.tryEmit(TradeFill(fill.accountId, fill.basketId, fill.id, role))
                            }
                        }
                    }
                }
                reconcile()
                rebuild()
            }
            Rt.BRACKET_UPDATES -> lock.withLock {
                val id = message.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return@withLock
                val previous = brackets[id] ?: BracketLevels()
                brackets[id] = BracketLevels(
                    message.long(Rf.STOP_TICKS) ?: previous.stopTicks, message.long(Rf.STOP_QUANTITY) ?: previous.stopQuantity,
                    message.long(Rf.TARGET_TICKS) ?: previous.targetTicks, message.long(Rf.TARGET_QUANTITY) ?: previous.targetQuantity)
                reconcile()
                rebuild()
            }
            Rt.TRADE_ROUTE -> lock.withLock { tradeRoutes = routesFrom(listOf(message), tradeRoutes) }
            Rt.ACCOUNT_RMS_UPDATES -> lock.withLock {
                val account = message.str(Rf.ACCOUNT_ID)?.trim() ?: return@withLock
                if (account !in risks) return@withLock
                risks = risks + (account to parseRiskUpdate(message, risks[account]))
                rebuild()
            }
            Rt.FORCED_LOGOUT -> lock.withLock { problem = "Rithmic 服务器强制登出了订单连接"; publish() }
            Rt.REJECT -> lock.withLock { problem = "Rithmic 拒绝了一个请求：${message.failureMessage()}"; publish() }
        }
        val unknown = lock.withLock { orders.values.filter { it.active && it.symbol !in specs }.map { it.symbol to it.exchange }.distinct() }
        if (unknown.isNotEmpty()) scope.launch { hydrateSpecs(unknown) }
    }

    private suspend fun pnlEvent(message: ProtoMessage) {
        when (message.templateId) {
            Rt.INSTRUMENT_PNL_UPDATE -> {
                val account = message.str(Rf.ACCOUNT_ID)?.trim() ?: return
                val symbol = message.str(Rf.SYMBOL)?.trim() ?: return
                val position = lock.withLock { parsePosition(message, positions[account to symbol], now()) } ?: return
                lock.withLock {
                    positions[position.accountId to position.symbol] = position
                    reconcile()
                    rebuild()
                }
                if (lock.withLock { position.net != 0L && position.symbol !in specs }) scope.launch { hydrateSpecs(listOf(position.symbol to position.exchange)) }
            }
            Rt.ACCOUNT_PNL_UPDATE -> lock.withLock {
                val account = message.str(Rf.ACCOUNT_ID)?.trim() ?: return@withLock
                financials[account] = parseFinancials(message, financials[account], now()) ?: return@withLock
                rebuild()
            }
            Rt.FORCED_LOGOUT -> lock.withLock { problem = "Rithmic 服务器强制登出了盈亏连接"; publish() }
        }
    }

    private suspend fun tickerEvent(message: ProtoMessage) = lock.withLock {
        when (message.templateId) {
            Rt.BEST_BID_OFFER -> {
                val symbol = message.str(Rf.SYMBOL)?.trim() ?: return@withLock
                val spec = specs[symbol] ?: return@withLock
                val presence = message.long(Rf.PRESENCE_BITS) ?: 0
                val cleared = message.long(Rf.CLEAR_BITS) ?: 0
                val time = message.timeMs() ?: now()
                val previous = quoteSides[symbol]
                if (previous != null && previous.time > time + 60_000) return@withLock
                val bidCleared = cleared and RithmicEnums.PRESENCE_BID.toLong() != 0L
                val askCleared = cleared and RithmicEnums.PRESENCE_ASK.toLong() != 0L
                val bid = if (bidCleared) null else if (presence and RithmicEnums.PRESENCE_BID.toLong() != 0L || message.has(Rf.BID_PRICE)) message.double(Rf.BID_PRICE)?.let(spec.scale::units) ?: previous?.bid else previous?.bid
                val ask = if (askCleared) null else if (presence and RithmicEnums.PRESENCE_ASK.toLong() != 0L || message.has(Rf.ASK_PRICE)) message.double(Rf.ASK_PRICE)?.let(spec.scale::units) ?: previous?.ask else previous?.ask
                if (listOfNotNull(bid, ask).any { it <= 0 || it % spec.instrument.tickSize != 0L }) return@withLock
                val bidSize = if (bidCleared || bid == null) null else message.size(Rf.BID_SIZE_64, Rf.BID_SIZE)?.takeIf { it >= 0 }
                    ?: previous?.bidSize?.takeIf { bid == previous?.bid }
                val askSize = if (askCleared || ask == null) null else message.size(Rf.ASK_SIZE_64, Rf.ASK_SIZE)?.takeIf { it >= 0 }
                    ?: previous?.askSize?.takeIf { ask == previous?.ask }
                val next = QuoteSides(bid, ask, time, bidSize, askSize)
                quoteSides[symbol] = next
                if (collectingOrderFlow(symbol)) {
                    if (bid != null && ask != null && ask < bid) flowWindow.bbo(null, null, null, null)
                    else flowWindow.bbo(bid, bidSize, ask, askSize)
                    publishOrderFlow()
                }
                val b = next.bid
                val a = next.ask
                // One side gone (cleared or never seen) means no tradable quote; stale prices never linger.
                if (b == null || a == null || a < b) { if (symbol in quotes) { quotes = quotes - symbol; publish() }; return@withLock }
                quotes = quotes + (symbol to Quote(b, a, time))
                publish()
            }
            Rt.LAST_TRADE -> {
                val symbol = message.str(Rf.SYMBOL)?.trim() ?: return@withLock
                val spec = specs[symbol] ?: return@withLock
                if (message.bool(Rf.IS_SNAPSHOT) == true) return@withLock
                if ((message.long(Rf.CLEAR_BITS) ?: 0L) and RithmicEnums.PRESENCE_LAST_TRADE.toLong() != 0L) return@withLock
                val price = message.double(Rf.TRADE_PRICE)?.let(spec.scale::units) ?: return@withLock
                val size = message.size(Rf.TRADE_SIZE_64, Rf.TRADE_SIZE)?.takeIf { it >= 0 } ?: 0
                val time = message.timeMs() ?: now()
                if (price <= 0 || price % spec.instrument.tickSize != 0L) return@withLock
                if (collectingOrderFlow(symbol) && size > 0) {
                    flowWindow.trade(price, size, time, message.int(Rf.TRANSACTION_TYPE))
                    publishOrderFlow()
                }
                val series = barSubscription ?: return@withLock
                // Daily bars follow the exchange trading date, not the UTC calendar day; only the server closes them.
                if (executionSymbol(series.first) != symbol || series.second.minutes % 1440 == 0) return@withLock
                applyTrade(series, price, size, time)
            }
            Rt.ORDER_BOOK -> {
                val symbol = message.str(Rf.SYMBOL)?.trim() ?: return@withLock
                if (!collectingOrderFlow(symbol) || subscribedDepth != symbol) return@withLock
                val spec = specs[symbol] ?: return@withLock
                if (flowWindow.book(message, spec)) publishOrderFlow(immediate = message.int(Rf.BOOK_UPDATE_TYPE) in listOf(RithmicEnums.BOOK_CLEAR, RithmicEnums.BOOK_NONE))
            }
            Rt.FORCED_LOGOUT -> { marketReady = false; resetOrderFlow("行情连接已断开，逐笔窗口已清空"); problem = "Rithmic 服务器强制登出了行情连接"; publish() }
        }
    }

    private suspend fun historyEvent(message: ProtoMessage) = lock.withLock {
        when (message.templateId) {
            Rt.TIME_BAR -> {
                val symbol = message.str(Rf.SYMBOL)?.trim() ?: return@withLock
                val series = barSubscription?.takeIf { executionSymbol(it.first) == symbol } ?: return@withLock
                val spec = specs[symbol] ?: return@withLock
                val bar = candleFrom(message, spec, series.second) ?: return@withLock
                history = history + (series to mergeBars(history[series] ?: emptyList(), listOf(bar)))
                publish()
                scheduleCandleCacheSave(series)
            }
            Rt.FORCED_LOGOUT -> { problem = "Rithmic 服务器强制登出了历史数据连接"; publish() }
        }
    }

    // ───────────────────────────── instruments ─────────────────────────────

    override suspend fun searchInstruments(query: String) {
        val plant = ticker ?: return
        if (!connected || query.trim().length !in 1..32) return
        val text = query.trim().uppercase().removePrefix("@").removeSuffix("1!")
        if (text.isBlank()) return
        // A CONTAINS search for "ES" also returns every contract whose name says FUTURES, so a product-code query goes first.
        val byProduct = if (Regex("^[A-Z0-9]{1,6}$").matches(text)) plant.request(Rt.REQUEST_SEARCH_SYMBOLS, 30_000) { productSearch(text, null) }.filter { it.str(Rf.PRODUCT_CODE)?.trim() == text } else emptyList()
        val rows = byProduct.ifEmpty { plant.request(Rt.REQUEST_SEARCH_SYMBOLS, 30_000) {
            string(Rf.SEARCH_TEXT, text); int32(Rf.INSTRUMENT_TYPE, RithmicEnums.SEARCH_FUTURE); int32(Rf.SEARCH_PATTERN, RithmicEnums.PATTERN_CONTAINS)
        } }
        // A product query spans every exchange, including ones this login is not entitled to, so listings it can route go first.
        val routable = lock.withLock { tradeRoutes.keys.toSet() }
        val matches = rows.mapNotNull { row ->
            val symbol = row.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: return@mapNotNull null
            val exchange = row.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val product = row.str(Rf.PRODUCT_CODE)?.trim()?.takeIf { it.isNotEmpty() }
            Triple(symbol, exchange, product)
        }.filter { (symbol, _, product) -> symbol != product && (symbol.startsWith(text) || product == text) } // A bare product row is the server's front-month alias.
            .sortedByDescending { it.second in routable }.distinctBy { it.first }
        val products = matches.mapNotNull { (_, exchange, product) -> product?.let { it to exchange } }.distinct()
            .filter { it.first == text || matches.size <= 3 }.groupBy({ it.first }, { it.second })
        lock.withLock {
            for ((product, exchanges) in products) {
                val key = "@$product"
                val current = continuousRoutes[key]
                if (current == null) continuousRoutes[key] = ContinuousRoute(product, exchanges.first(), null, null, "正在核实主力", exchanges)
                else if (current.tradeSymbol == null) continuousRoutes[key] = current.copy(candidates = exchanges)
            }
        }
        hydrateSpecs(matches.filter { it.second in routable }.ifEmpty { matches }.take(60).map { it.first to it.second })
        for (product in products.keys) refreshContinuous("@$product")
    }

    /**
     * Fills the instrument list after login. The account's product RMS rows name what it may trade, micros included;
     * the time-of-interest list is older and lacks them, so it only serves a login without such rows.
     */
    private suspend fun syncProductList() {
        val plant = ticker ?: return
        val accountProducts = lock.withLock { productRms.keys.map { it.second }.distinct() }
        val products = accountProducts.map { it to null }.ifEmpty { timeOfInterestProducts(plant) }
        coroutineScope {
            products.take(PRODUCT_LIST_LIMIT).map { (product, exchange) ->
                launch { productListSlots.withPermit { loadProduct(plant, product, exchange) } }
            }.joinAll()
        }
        refreshQuoteWatch()
    }

    private suspend fun timeOfInterestProducts(plant: RithmicPlant): List<Pair<String, String?>> {
        val products = mutableListOf<Pair<String, String?>>()
        for (exchange in lock.withLock { tradeRoutes.keys.sorted() }) {
            try {
                plant.request(Rt.REQUEST_PRODUCT_CODES, 30_000) { string(Rf.EXCHANGE, exchange); bool(Rf.GIVE_TOI_PRODUCTS_ONLY, true) }
                    .mapNotNullTo(products) { row -> row.str(Rf.PRODUCT_CODE)?.trim()?.takeIf { it.isNotEmpty() }?.let { it to exchange } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The other exchanges still load, and search reaches this one. */ }
        }
        return products.distinctBy { it.first }
    }

    /** Product RMS rows carry no exchange, so ask where a product is listed among the exchanges this login can route. */
    private suspend fun listingExchange(plant: RithmicPlant, product: String): String? {
        val routable = lock.withLock { tradeRoutes.keys.toSet() }
        return plant.request(Rt.REQUEST_SEARCH_SYMBOLS, 30_000) { productSearch(product, null) }
            .filter { it.str(Rf.PRODUCT_CODE)?.trim() == product }
            .mapNotNull { it.str(Rf.EXCHANGE)?.trim() }.firstOrNull { it in routable }
    }

    /** Adds one product of the login list; a product the server refuses is left out, without an error for the user. */
    private suspend fun loadProduct(plant: RithmicPlant, product: String, exchange: String?) {
        val key = "@$product"
        if (lock.withLock { continuousRoutes[key]?.tradeSymbol != null }) return
        val (contract, listing) = try {
            val listing = exchange ?: listingExchange(plant, product) ?: return
            frontMonthContract(plant, product, listing, quiet = true) to listing
        } catch (_: TimeoutCancellationException) { return }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return }
        lock.withLock {
            val route = continuousRoutes[key] ?: ContinuousRoute(product, listing, null, null, null)
            continuousRoutes[key] = route.copy(exchange = listing, tradeSymbol = contract, checkedAt = now(), status = null)
            rebuild()
        }
    }

    /** Loads reference data once per symbol; concurrent callers for the same symbol share the request. */
    private suspend fun hydrateSpecs(symbols: List<Pair<String, String>>, quiet: Boolean = false) {
        val plant = ticker ?: return
        coroutineScope {
            symbols.distinctBy { it.first }.map { (symbol, exchange) -> launch {
                val specLock = lock.withLock { specLocks.getOrPut(symbol) { Mutex() } }
                specLock.withLock {
                    if (lock.withLock { symbol in specs }) return@withLock
                    specSlots.withPermit {
                        try {
                            val candidates = if (exchange.isNotBlank()) listOf(exchange) else lock.withLock { tradeRoutes.keys.toList() }.ifEmpty { listOf("CME") }
                            for (candidate in candidates) {
                                val data = plant.request(Rt.REQUEST_REFERENCE_DATA) { string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, candidate) }
                                    .lastOrNull { it.has(Rf.SYMBOL) && it.has(Rf.MIN_FPRICE_CHANGE) } ?: continue
                                val spec = mapInstrument(data) ?: continue
                                lock.withLock { if (symbol !in specs) { specs[symbol] = spec; rebuild() } }
                                break
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { if (!quiet) lock.withLock { problem = "部分合约规格无法加载，缺失规格的品种不可交易" } }
                    }
                }
            } }.joinAll()
        }
        lock.withLock { rebuild() }
    }

    private fun ProtoWriter.productSearch(productCode: String, exchange: String?) {
        string(Rf.SEARCH_TEXT, productCode); string(Rf.PRODUCT_CODE, productCode)
        if (!exchange.isNullOrBlank()) string(Rf.EXCHANGE, exchange)
        int32(Rf.INSTRUMENT_TYPE, RithmicEnums.SEARCH_FUTURE); int32(Rf.SEARCH_PATTERN, RithmicEnums.PATTERN_CONTAINS)
    }

    /** Front-month resolution from the server; the continuous chart symbol trades the contract Rithmic names. */
    suspend fun refreshContinuous(symbol: String) {
        val plant = ticker ?: return
        val route = lock.withLock { continuousRoutes[symbol] } ?: return
        var found: Pair<String, String>? = null
        val failures = mutableListOf<String>()
        // The exchange that answered last goes first, so a refresh does not ask a refused one again.
        for (exchange in (listOf(route.exchange) + route.candidates).distinct()) {
            try { found = frontMonthContract(plant, route.productCode, exchange) to exchange; break }
            catch (_: TimeoutCancellationException) { failures += "$exchange：主力合约查询超时" }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { failures += "$exchange：${safeRithmicMessage(e.message ?: "主力合约不可用")}" }
        }
        val resolved = found
        val reason = failures.joinToString("；")
        lock.withLock {
            val current = continuousRoutes[symbol] ?: return@withLock
            continuousRoutes[symbol] = current.copy(exchange = resolved?.second ?: current.exchange, tradeSymbol = resolved?.first, checkedAt = now(), status = if (resolved == null) "主力无法确认：$reason" else null)
            if (resolved == null) problem = "$symbol 主力无法确认：$reason；连续合约下单已暂停"
            else if (problem.startsWith("$symbol 主力无法确认")) problem = "$symbol 主力已确认：${resolved.first}"
            rebuild()
        }
        if (requestedOrderFlow == symbol) syncOrderFlow()
        if (resolved != null) { refreshQuoteWatch(); watching?.takeIf { it.first == symbol }?.let { watch(it.first, it.second) } }
    }

    /** The dated contract the server names for a product on one exchange, with its specification loaded. */
    private suspend fun frontMonthContract(plant: RithmicPlant, productCode: String, exchange: String, quiet: Boolean = false): String {
        val reply = withTimeout(20_000) { plant.request(Rt.REQUEST_FRONT_MONTH) { string(Rf.SYMBOL, productCode); string(Rf.EXCHANGE, exchange); bool(Rf.NEED_UPDATES, false) } }
        val row = reply.lastOrNull { !it.str(Rf.TRADING_SYMBOL).isNullOrBlank() || !it.str(Rf.SYMBOL).isNullOrBlank() }
        var name = row?.str(Rf.TRADING_SYMBOL)?.trim()?.takeIf { it.isNotEmpty() } ?: row?.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() && it != productCode }
        var contractExchange = row?.str(Rf.TRADING_EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: exchange
        if (name == null) {
            // Rithmic Test answers the front-month request with "no data"; the nearest unexpired contract of the product stands in.
            val rows = plant.request(Rt.REQUEST_SEARCH_SYMBOLS, 30_000) { productSearch(productCode, exchange) }
            nearestContract(rows, productCode, exchange, yyyymmdd(now()))?.let { (contract, listed) -> name = contract; contractExchange = listed }
        }
        val contract = name ?: throw RithmicRemoteException("front-month", "服务器未返回主力合约")
        hydrateSpecs(listOf(contract to contractExchange), quiet)
        if (lock.withLock { contract !in specs }) throw RithmicRemoteException("front-month", "主力合约规格无法加载")
        return contract
    }

    private fun continuousInstrument(symbol: String, route: ContinuousRoute): Instrument? {
        val base = route.tradeSymbol?.let { specs[it] } ?: specs.values.firstOrNull { it.productCode == route.productCode } ?: return null
        return base.instrument.copy(symbol = symbol, name = "${base.instrument.name} 连续合约", category = "连续合约 · ${route.exchange}", isContinuous = true,
            tradeSymbol = route.tradeSymbol, tradeSymbolStatus = route.status, quoteSymbol = route.tradeSymbol)
    }

    /** Symbol a chart or order actually works with: the dated contract behind a continuous series. */
    private fun executionSymbol(symbol: String): String? = if (symbol.startsWith("@")) continuousRoutes[symbol]?.tradeSymbol else symbol

    // ───────────────────────────── quotes ─────────────────────────────

    override fun watchQuotes(symbols: Set<String>) {
        if (stopped || quoteWatchSymbols == symbols) return
        quoteWatchSymbols = symbols.toSet()
        refreshQuoteWatch()
    }

    private fun refreshQuoteWatch() {
        quoteWatchJob?.cancel()
        quoteWatchJob = scope.launch {
            if (!marketReady) return@launch
            for (symbol in quoteWatchSymbols) {
                if (lock.withLock { symbol !in specs && symbol !in continuousRoutes }) {
                    try { withTimeout(20_000) { searchInstruments(symbol) } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Other rows still subscribe. */ }
                }
            }
            syncQuoteSubscriptions()
        }
    }

    private suspend fun syncQuoteSubscriptions() = quoteSyncLock.withLock {
        val plant = ticker ?: return@withLock
        val wanted = lock.withLock {
            val fromAccounts = positions.values.filter { it.net != 0L }.map { it.symbol } + orders.values.filter { it.active }.map { it.symbol }
            val fromWatch = (quoteWatchSymbols + listOfNotNull(watching?.first, requestedOrderFlow)).mapNotNull { executionSymbol(it) }
            (fromAccounts + fromWatch).filter { it in specs }.toSet()
        }
        val (remove, add) = lock.withLock { (subscribedQuotes - wanted).toList() to (wanted - subscribedQuotes).toList() }
        var failure: Exception? = null
        for (symbol in remove) {
            val spec = lock.withLock { specs[symbol] } ?: continue
            runCatching { plant.request(Rt.REQUEST_MARKET_DATA_UPDATE) { marketData(symbol, spec.exchange, RithmicEnums.REQUEST_UNSUBSCRIBE) } }
            lock.withLock { subscribedQuotes.remove(symbol); quoteSides.remove(symbol); quotes = quotes - symbol }
        }
        for (symbol in add) {
            val spec = lock.withLock { specs[symbol] } ?: continue
            try {
                plant.request(Rt.REQUEST_MARKET_DATA_UPDATE) { marketData(symbol, spec.exchange, RithmicEnums.REQUEST_SUBSCRIBE) }
                lock.withLock { subscribedQuotes.add(symbol) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { lock.withLock { problem = "部分行情订阅失败：${safeRithmicMessage(it.message ?: "请检查行情权限")}"; publish() } }
        Unit
    }

    private fun ProtoWriter.marketData(symbol: String, exchange: String, request: Int) {
        string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); int32(Rf.REQUEST, request)
        uint32(Rf.UPDATE_BITS, (RithmicEnums.UPDATE_LAST_TRADE or RithmicEnums.UPDATE_BBO).toLong())
    }

    // ───────────────────────────── live order flow ─────────────────────────────

    override fun watchOrderFlow(symbol: String?) {
        if (stopped || requestedOrderFlow == symbol) return
        requestedOrderFlow = symbol
        requestedFlowRevision++
        scope.launch { syncOrderFlow() }
    }

    /** All callers hold [lock]. Disconnects invalidate both the book and the contiguous tape window. */
    private fun resetOrderFlow(message: String) {
        subscribedDepth = null; orderFlowExecution = null; activeFlowRevision = -1L
        flowWindow.reset(null, message)
        publishOrderFlow(immediate = true)
    }

    private fun collectingOrderFlow(symbol: String): Boolean =
        marketReady && activeFlowRevision == requestedFlowRevision && symbol == orderFlowExecution && requestedOrderFlow?.let(::executionSymbol) == symbol

    private fun publishOrderFlow(immediate: Boolean = false) {
        if (immediate) {
            flowPublishJob?.cancel(); flowPublishJob = null
            mutableOrderFlow.value = flowWindow.snapshot(marketReady && orderFlowExecution != null)
        } else if (flowPublishJob?.isActive != true) {
            flowPublishJob = scope.launch {
                delay(100)
                lock.withLock {
                    mutableOrderFlow.value = flowWindow.snapshot(marketReady && orderFlowExecution != null)
                }
            }
        }
    }

    /** Depth is an independent bits=4 subscription: a permission error never disables BBO or trading. */
    private suspend fun syncOrderFlow() = orderFlowSyncLock.withLock syncFlow@ {
        val requested = requestedOrderFlow
        val revision = requestedFlowRevision
        val plant = ticker
        val target = lock.withLock {
            requested?.let(::executionSymbol)?.takeIf { marketReady && it in specs }
        }
        val oldDepth = lock.withLock {
            if (requested != requestedOrderFlow || revision != requestedFlowRevision) return@syncFlow
            if (plant !== ticker || target != null && !marketReady) return@syncFlow
            if (target != null && orderFlowExecution == target && flowWindow.symbol == target && activeFlowRevision == revision) return@syncFlow
            val previous = subscribedDepth
            subscribedDepth = null; orderFlowExecution = target; activeFlowRevision = revision
            flowWindow.reset(target, when {
                requested == null -> "未订阅订单流"
                target == null -> "等待连接或主力合约确认"
                else -> "正在订阅实时逐笔，等待 L2 深度"
            })
            if (target != null) quoteSides[target]?.takeIf { it.bid == null || it.ask == null || it.ask >= it.bid }
                ?.let { flowWindow.bbo(it.bid, it.bidSize, it.ask, it.askSize) }
            publishOrderFlow(immediate = true)
            previous
        }
        if (oldDepth != null && plant != null) {
            val spec = lock.withLock { specs[oldDepth] }
            if (spec != null) try {
                plant.request(Rt.REQUEST_MARKET_DATA_UPDATE, 5_000) {
                    string(Rf.SYMBOL, oldDepth); string(Rf.EXCHANGE, spec.exchange)
                    int32(Rf.REQUEST, RithmicEnums.REQUEST_UNSUBSCRIBE); uint32(Rf.UPDATE_BITS, RithmicEnums.UPDATE_ORDER_BOOK.toLong())
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A stale depth subscription is ignored by collectingOrderFlow. */ }
        }
        if (plant == null || plant !== ticker || requested != requestedOrderFlow || revision != requestedFlowRevision || target == null) {
            if (marketReady && plant != null) syncQuoteSubscriptions()
            return@syncFlow
        }
        try { syncQuoteSubscriptions() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Existing quote errors already appear on the main connection. */ }
        if (plant !== ticker || requested != requestedOrderFlow || revision != requestedFlowRevision) return@syncFlow
        val spec = lock.withLock { specs[target] } ?: return@syncFlow
        lock.withLock {
            if (plant !== ticker || !marketReady || revision != requestedFlowRevision) return@syncFlow
            subscribedDepth = target
        }
        try {
            val reply = plant.request(Rt.REQUEST_MARKET_DATA_UPDATE, 10_000) {
                string(Rf.SYMBOL, target); string(Rf.EXCHANGE, spec.exchange)
                int32(Rf.REQUEST, RithmicEnums.REQUEST_SUBSCRIBE); uint32(Rf.UPDATE_BITS, RithmicEnums.UPDATE_ORDER_BOOK.toLong())
            }
            if (reply.lastOrNull()?.rpCode()?.firstOrNull() == "7") lock.withLock {
                if (plant === ticker && requested == requestedOrderFlow && revision == activeFlowRevision && target == orderFlowExecution) {
                    flowWindow.unavailable("服务器未提供 L2 深度；逐笔仍可使用")
                    publishOrderFlow(immediate = true)
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { lock.withLock {
            if (plant === ticker && requested == requestedOrderFlow && revision == activeFlowRevision && target == orderFlowExecution) {
                flowWindow.unavailable("L2 深度不可用：${safeRithmicMessage(error.message ?: "请检查行情权限")}；逐笔仍可使用")
                publishOrderFlow(immediate = true)
            }
        } }
    }

    // ───────────────────────────── history ─────────────────────────────

    override fun history(symbol: String, timeframe: Timeframe) = history[symbol to timeframe] ?: emptyList()

    override fun watch(symbol: String, timeframe: Timeframe) {
        require(timeframe.seconds == 0) { "Rithmic 秒周期未启用" }
        if (stopped) return
        watching = symbol to timeframe
        watchJob?.cancel()
        watchJob = scope.launch {
            chartLock.withLock {
                val series = symbol to timeframe
                val cached = history[series] ?: candleCache.read(series, now()).also { bars ->
                    lock.withLock { history = history + (series to bars); publish() }
                }
                if (!marketReady) return@launch
                if (symbol.startsWith("@") && lock.withLock { continuousRoutes[symbol]?.tradeSymbol == null }) {
                    if (continuousJob?.isActive != true) continuousJob = scope.launch { refreshContinuous(symbol) }
                    return@launch
                }
                val execution = lock.withLock { executionSymbol(symbol) } ?: return@launch
                val route = lock.withLock { continuousRoutes[symbol] }
                if (lock.withLock { execution !in specs }) hydrateSpecs(listOf(execution to ""))
                val spec = lock.withLock { specs[execution] } ?: run { lock.withLock { problem = "合约规格尚未加载，暂无法订阅 $symbol"; publish() }; return@launch }
                try { syncQuoteSubscriptions() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
                val plant = historyPlant ?: return@launch
                try {
                    withContext(NonCancellable) { switchBarSubscription(plant, series, spec) }
                    val bars = if (route == null) loadBars(plant, spec.instrument.symbol, spec.exchange, spec, timeframe, cached) else continuousBars(plant, series, route, spec, cached)
                    if (watching != series) return@launch
                    lock.withLock { history = history + (series to mergeBars(history[series] ?: emptyList(), bars)); publish() }
                    saveCandleCache(series)
                } catch (_: TimeoutCancellationException) { lock.withLock { problem = "历史数据请求超时，可重新选择品种或周期重试"; publish() } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { lock.withLock { problem = "历史数据加载失败：${safeRithmicMessage(error.message ?: "")}"; publish() } }
            }
        }
    }

    private suspend fun switchBarSubscription(plant: RithmicPlant, series: Pair<String, Timeframe>, spec: RithmicSpec) {
        val previous = lock.withLock { barSubscription }
        if (previous == series) return
        if (previous != null) {
            val old = lock.withLock { executionSymbol(previous.first)?.let { specs[it] } }
            if (old != null) runCatching { withTimeoutOrNull(5_000) { plant.request(Rt.REQUEST_TIME_BAR_UPDATE) { timeBar(old, previous.second, RithmicEnums.REQUEST_UNSUBSCRIBE) } } }
        }
        lock.withLock { barSubscription = series }
        plant.request(Rt.REQUEST_TIME_BAR_UPDATE) { timeBar(spec, series.second, RithmicEnums.REQUEST_SUBSCRIBE) }
    }

    private fun ProtoWriter.timeBar(spec: RithmicSpec, timeframe: Timeframe, request: Int) {
        string(Rf.SYMBOL, spec.instrument.symbol); string(Rf.EXCHANGE, spec.exchange); int32(Rf.REQUEST, request)
        val daily = timeframe.minutes % 1440 == 0
        int32(Rf.BAR_TYPE, if (daily) RithmicEnums.BAR_DAILY else RithmicEnums.BAR_MINUTE)
        int32(Rf.BAR_PERIOD, if (daily) timeframe.minutes / 1440 else timeframe.minutes)
    }

    /**
     * The server keeps no bars for expired months, so only the bare product code, its front-month alias, has history across rolls.
     * Without an alias (Rithmic Test has none) the chart falls back to the dated contract and says so.
     */
    private suspend fun continuousBars(plant: RithmicPlant, series: Pair<String, Timeframe>, route: ContinuousRoute, spec: RithmicSpec, cached: List<Candle>): List<Candle> {
        if (lock.withLock { series !in singleContractSeries }) {
            val stitched = loadBars(plant, route.productCode, route.exchange, spec, series.second, cached)
            if (stitched.isNotEmpty() || cached.isNotEmpty()) return stitched
            lock.withLock { singleContractSeries += series; problem = "服务器没有 ${series.first} 的连续历史，图表只显示 ${spec.instrument.symbol}"; publish() }
        }
        return loadBars(plant, spec.instrument.symbol, spec.exchange, spec, series.second, cached)
    }

    private suspend fun loadBars(plant: RithmicPlant, symbol: String, exchange: String, spec: RithmicSpec, timeframe: Timeframe, cached: List<Candle>): List<Candle> {
        val daily = timeframe.minutes % 1440 == 0
        val end = now()
        val span = timeframe.minutes * 60_000L
        // Four chart-widths of wall time cover weekends and holidays while staying under Rithmic's 10,000-bar reply cap.
        val start = cached.lastOrNull()?.timeMs?.takeIf { end - it < span * RITHMIC_CHART_BARS * 4 } ?: (end - span * RITHMIC_CHART_BARS * 4)
        val bars = mutableListOf<Candle>()
        var from = start
        for (page in 0 until 6) {
            val reply = plant.request(Rt.REQUEST_TIME_BAR_REPLAY, 60_000) {
                string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange)
                int32(Rf.BAR_TYPE, if (daily) RithmicEnums.BAR_DAILY else RithmicEnums.BAR_MINUTE)
                int32(Rf.BAR_PERIOD, if (daily) timeframe.minutes / 1440 else timeframe.minutes)
                if (daily) { int32(Rf.START_INDEX, yyyymmdd(from)); int32(Rf.FINISH_INDEX, yyyymmdd(end)) }
                else { int32(Rf.START_INDEX, (from / 1_000).toInt()); int32(Rf.FINISH_INDEX, (end / 1_000).toInt()) }
                int32(Rf.TIME_ORDER, RithmicEnums.TIME_ORDER_FORWARDS)
            }
            val received = reply.mapNotNull { candleFrom(it, spec, timeframe) }
            bars.addAll(received)
            val last = received.maxOfOrNull { it.timeMs } ?: break
            if (received.size < 1_000 || last + span >= end) break
            from = last + span
        }
        return bars.associateBy { it.timeMs }.values.sortedBy { it.timeMs }.takeLast(RITHMIC_CHART_BARS)
    }

    private fun candleFrom(bar: ProtoMessage, spec: RithmicSpec, timeframe: Timeframe): Candle? {
        val marker = bar.long(Rf.BAR_MARKER) ?: return null
        val time = barOpenTimeMs(marker, timeframe) ?: return null
        val o = bar.double(Rf.OPEN_PRICE)?.let(spec.scale::units) ?: return null
        val h = bar.double(Rf.HIGH_PRICE)?.let(spec.scale::units) ?: return null
        val l = bar.double(Rf.LOW_PRICE)?.let(spec.scale::units) ?: return null
        val c = bar.double(Rf.CLOSE_PRICE)?.let(spec.scale::units) ?: return null
        if (l <= 0 || h < maxOf(o, c) || l > minOf(o, c)) return null
        return Candle(time, o, h, l, c, bar.long(Rf.BAR_VOLUME)?.takeIf { it >= 0 })
    }

    private fun mergeBars(previous: List<Candle>, incoming: List<Candle>): List<Candle> =
        (previous + incoming).associateBy(Candle::timeMs).values.sortedBy(Candle::timeMs).takeLast(RITHMIC_CHART_BARS)

    /** Caller holds the lock. Trades extend the forming bar until the server's own bar replaces it. */
    private fun applyTrade(series: Pair<String, Timeframe>, price: Long, size: Long, time: Long) {
        val bars = history[series] ?: emptyList()
        val start = barStart(time, series.second)
        val last = bars.lastOrNull()
        val next = when {
            last == null || start > last.timeMs -> bars + Candle(start, price, price, price, price, size)
            start == last.timeMs -> bars.dropLast(1) + last.copy(high = maxOf(last.high, price), low = minOf(last.low, price), close = price, volume = (last.volume ?: 0) + size)
            else -> return
        }
        history = history + (series to next.takeLast(RITHMIC_CHART_BARS))
        publish()
    }

    private val cacheSaveJobs = mutableMapOf<Pair<String, Timeframe>, Job>()
    private fun scheduleCandleCacheSave(series: Pair<String, Timeframe>) {
        if (cacheSaveJobs[series]?.isActive == true) return
        cacheSaveJobs[series] = scope.launch { delay(2_000); saveCandleCache(series) }
    }
    private suspend fun saveCandleCache(series: Pair<String, Timeframe>? = null) {
        val targets = lock.withLock { (if (series == null) history.toMap() else history[series]?.let { mapOf(series to it) } ?: emptyMap()) - singleContractSeries }
        for ((key, bars) in targets) {
            try { if (bars.isNotEmpty()) candleCache.write(key, bars) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Disposable cache. */ }
        }
    }

    // ───────────────────────────── snapshot ─────────────────────────────

    /** Caller holds the lock. */
    private fun rebuild() {
        val nextFlows = accountFlows.toMutableMap()
        val activeOrders = orders.values.filter { it.active }
        for (account in accountInfos) {
            val aid = account.id
            val old = accountFlows[aid]?.value
            val accountOrders = activeOrders.filter { it.accountId == aid }
            val rawPositions = positions.values.filter { it.accountId == aid && it.net != 0L }
            val protectionByOrder = mutableMapOf<String, String>()
            val singledOut = mutableSetOf<String>()
            val mappedPositions = rawPositions.mapNotNull { p ->
                val spec = specs[p.symbol] ?: return@mapNotNull null
                val entry = p.avgPrice?.let(spec.scale::units)?.takeIf { it > 0 } ?: return@mapNotNull null
                val side = if (p.net > 0) Side.BUY else Side.SELL
                val children = accountOrders.filter { it.symbol == p.symbol && it.isBracketChild && it.side != null && it.side != side }
                val stops = children.filter { it.priceType == RithmicEnums.PRICE_STOP_MARKET || it.priceType == RithmicEnums.PRICE_STOP_LIMIT }
                val targets = children.filter { it.priceType == RithmicEnums.PRICE_LIMIT }
                val stop = stops.singleOrNull(); val target = targets.singleOrNull()
                val editable = stops.size <= 1 && targets.size <= 1
                (stops + targets).forEach { protectionByOrder[it.basketId] = "$aid:${p.symbol}" }
                listOfNotNull(stop, target).mapTo(singledOut) { it.basketId }
                val id = "$aid:${p.symbol}"
                val prior = old?.positions?.firstOrNull { it.id == id }
                // Every PnL update carries a new time; taking it as the opening time would bump the version and invalidate any open edit.
                val opened = prior?.takeIf { it.side == side }?.openedAtMs ?: p.timeMs
                val candidate = Position(id, p.symbol, side, checkedMultiply(abs(p.net), 100), entry, opened, stop?.triggerPrice?.let(spec.scale::units),
                    target?.price?.let(spec.scale::units), 0, version = 1, protectionEditable = editable, feesKnown = false,
                    stopLossOrderId = stop?.basketId, takeProfitOrderId = target?.basketId,
                    protectionRevision = listOf(stop?.basketId, stop?.triggerPrice, target?.basketId, target?.price).joinToString("/"),
                    protectionNote = if (editable) null else "该合约有多个括号保护单，请在 R | Trader 核对后再修改")
                candidate.copy(version = if (prior?.copy(version = 1) == candidate) prior.version else (prior?.version ?: 0) + 1)
            }
            val pending = accountOrders.mapNotNull { order ->
                val spec = specs[order.symbol] ?: return@mapNotNull null
                val side = order.side ?: return@mapNotNull null
                val kind = when (order.priceType) {
                    RithmicEnums.PRICE_LIMIT -> OrderKind.LIMIT
                    RithmicEnums.PRICE_STOP_MARKET, RithmicEnums.PRICE_STOP_LIMIT -> OrderKind.STOP
                    RithmicEnums.PRICE_MARKET -> OrderKind.MARKET
                    else -> return@mapNotNull null
                }
                val price = (if (kind == OrderKind.LIMIT) order.price else order.triggerPrice ?: order.price)?.let(spec.scale::units)
                if (kind != OrderKind.MARKET && (price == null || price <= 0)) return@mapNotNull null
                val role = when {
                    !order.isBracketChild -> OrderRole.ENTRY
                    kind == OrderKind.STOP -> OrderRole.STOP_LOSS
                    kind == OrderKind.LIMIT -> OrderRole.TAKE_PROFIT
                    else -> OrderRole.UNRESOLVED
                }
                val positionId = protectionByOrder[order.basketId]
                val levels = brackets[order.basketId]
                val entryPrice = price?.takeIf { it > 0 }
                val stopLoss = if (role == OrderRole.ENTRY && entryPrice != null) levels?.stopTicks?.takeIf { it > 0 }?.let { ticks ->
                    if (side == Side.BUY) entryPrice - ticks * spec.instrument.tickSize else entryPrice + ticks * spec.instrument.tickSize } else null
                val takeProfit = if (role == OrderRole.ENTRY && entryPrice != null) levels?.targetTicks?.takeIf { it > 0 }?.let { ticks ->
                    if (side == Side.BUY) entryPrice + ticks * spec.instrument.tickSize else entryPrice - ticks * spec.instrument.tickSize } else null
                val working = !order.commandInFlight
                val editable = when (role) {
                    OrderRole.ENTRY -> working && kind != OrderKind.MARKET && order.filled == 0L
                    OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT -> order.basketId in singledOut && working
                    else -> false
                }
                val note = when {
                    role == OrderRole.ENTRY && !editable -> if (order.filled > 0) "订单已部分成交，暂不可改价" else "订单状态为“${order.status}”，暂不可改价"
                    role != OrderRole.ENTRY && order.basketId !in singledOut -> "括号保护单尚未唯一关联到当前净仓，仅显示"
                    role == OrderRole.UNRESOLVED -> "无法识别的括号订单类型"
                    else -> null
                }
                val request = OrderRequest(order.symbol, side, kind, checkedMultiply(order.quantity, 100), entryPrice, stopLoss, takeProfit)
                val prior = old?.orders?.firstOrNull { it.id == order.basketId }
                val candidate = PendingOrder(order.basketId, request, order.timeMs, 1, role, positionId, editable, note,
                    checkedMultiply(order.filled, 100), protectionEditable = role == OrderRole.ENTRY && editable && entryPrice != null && kind != OrderKind.MARKET)
                candidate.copy(version = if (prior?.copy(version = 1) == candidate) prior.version else (prior?.version ?: 0) + 1)
            }
            val executions = fills.values.filter { it.accountId == aid }.mapNotNull { fill ->
                val spec = specs[fill.symbol] ?: return@mapNotNull null
                val price = spec.scale.units(fill.price)?.takeIf { it > 0 } ?: return@mapNotNull null
                Execution(fill.id, fill.symbol, fill.side, checkedMultiply(fill.quantity, 100), price, fill.timeMs, fill.basketId)
            }.sortedBy { it.timeMs }
            val money = financials[aid]
            // account_balance carries realized P&L and fees through the day; cash_on_hand stays at the session's opening value.
            val value = money?.let { runCatching { accountValueMicros(it) }.getOrNull() }
            val balance = value?.first
            val equity = value?.second
            val closedTrades = fills.values.filter { it.accountId == aid }.groupBy { it.symbol }.flatMap { (symbol, group) ->
                val spec = specs[symbol] ?: return@flatMap emptyList()
                val commission = spec.productCode?.let { productRms[aid to it]?.commissionMicros }
                runCatching { pairFills(group, orders, spec, commission, "$aid:$symbol", positions[aid to symbol]?.net ?: 0) }.getOrDefault(emptyList())
            }.sortedBy { it.closedAtMs }
            val margin = money?.usedBuyingPowerMicros
            val dailyPnl = money?.let { m ->
                val total = m.dayPnlMicros ?: if (m.dayOpenPnlMicros != null && m.dayClosedPnlMicros != null) runCatching { checkedAdd(m.dayOpenPnlMicros, m.dayClosedPnlMicros) }.getOrNull() else null
                total?.let { DailyPnl(it, m.dayClosedPnlMicros, m.dayOpenPnlMicros, null, m.timeMs) }
            }
            val snapshot = AccountSnapshot(aid, balance ?: 0, equity ?: balance ?: 0, margin ?: 0, mappedPositions, pending, trades = closedTrades,
                events = localEvents[aid] ?: emptyList(), executions = executions,
                balanceKnown = balance != null, equityKnown = equity != null, marginKnown = margin != null, equityEstimated = false, dailyPnl = dailyPnl,
                drawdown = accountDrawdown(rithmicDrawdownRule(risks[aid], money), rithmicAccountValue(money, rawPositions)))
            nextFlows.getOrPut(aid) { MutableStateFlow(snapshot) }.value = snapshot
        }
        accountFlows = nextFlows.toMap()
        val list = specs.values.map { it.instrument } + continuousRoutes.mapNotNull { (symbol, route) -> continuousInstrument(symbol, route) }
        mutableCatalog.value = BrokerCatalog(list.distinctBy { it.symbol }.sortedBy { it.symbol }, accountInfos)
        publish()
    }

    private fun publish() {
        val unresolved = unknownCommands.isNotEmpty()
        val pending = awaiting.isNotEmpty()
        val message = when {
            unresolved -> "有交易指令尚待核对，已暂停发送后续指令。请同步账户或在 R | Trader 核对"
            pending -> "指令已受理，等待服务器订单/仓位更新"
            else -> problem
        }
        mutableMarket.value = MarketSnapshot(quotes, now(), mutableMarket.value.revision + 1, connected, statusMessage = message,
            tradingReady = connected && marketReady && !connecting && !synchronizing && !unresolved && !pending)
    }

    private fun fresh(quote: Quote?): Boolean = quote != null && now() - quote.timeMs in -5_000..QUOTE_FRESH_MS

    // ───────────────────────────── trading ─────────────────────────────

    override fun openSession(accountId: String): AccountSession {
        val info = accounts.firstOrNull { it.id == accountId } ?: error("当前登录中没有该账户")
        val flow = accountFlows.getValue(accountId)
        return object : AccountSession {
            override val account = info
            override val state: StateFlow<AccountSnapshot> = flow.asStateFlow()
            override fun preview(request: OrderRequest) = estimate(accountId, request)
            override suspend fun orderCapacity(request: OrderRequest, lastKnown: Long?): OrderCapacity {
                if (!market.value.tradingReady) return OrderCapacity(message = "账户或行情尚未就绪，无法确认可下单数量")
                val limit = lock.withLock { orderLimit(accountId, request.symbol) }
                return if (limit != null) OrderCapacity(checkedMultiply(limit, 100), "服务器风控的单笔最大数量") else OrderCapacity(message = "服务器未提供数量上限，以下单校验为准")
            }
            override fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable) return OrderPreview(null, errors = listOf("该单不是可编辑入场单；括号保护单请通过仓位止损/止盈修改"))
                return estimate(accountId, order.request.copy(entryPrice = entryPrice), modifying = true)
            }
            override fun previewOrderProtection(orderId: String, stopLoss: Long?, takeProfit: Long?): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable || !order.protectionEditable) return OrderPreview(null, errors = listOf("该挂单的括号保护尚未确认或正在处理，暂不可修改"))
                return estimate(accountId, order.request.copy(stopLoss = stopLoss, takeProfit = takeProfit), modifying = true)
            }
            override suspend fun placeOrder(requestId: String, request: OrderRequest) = command(accountId, requestId, "place:$request") {
                request.sourceSymbol?.let { source -> if (lock.withLock { continuousRoutes[source]?.tradeSymbol } != request.symbol) errorArgument("主力合约已换月，请重新确认订单") }
                val estimate = estimate(accountId, request)
                require(estimate.valid) { estimate.errors.joinToString("；") }
                val spec = lock.withLock { specs[request.symbol] } ?: errorArgument("合约规格尚未加载")
                val route = lock.withLock { tradeRoutes[spec.exchange] } ?: errorArgument("${spec.exchange} 没有可用的交易路由，请在 R | Trader 核对账户权限")
                val entry = requireNotNull(estimate.entryPrice)
                val tick = spec.instrument.tickSize
                val stopTicks = request.stopLoss?.let { abs(entry - it) / tick }
                val targetTicks = request.takeProfit?.let { abs(it - entry) / tick }
                require((stopTicks ?: 1) in 1..1_000_000 && (targetTicks ?: 1) in 1..1_000_000) { "止损/止盈距离入场价至少一个最小变动单位" }
                val bracket = stopTicks != null || targetTicks != null
                val quantity = request.lotsHundredths / 100
                val template = if (bracket) Rt.REQUEST_BRACKET_ORDER else Rt.REQUEST_NEW_ORDER
                sendWrite(accountId, requestId, "place", null, template, request.symbol, null) {
                    string(Rf.USER_TAG, tag(requestId))
                    string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId)
                    string(Rf.SYMBOL, request.symbol); string(Rf.EXCHANGE, spec.exchange)
                    // Dev kit 0.90 deprecated the 4-byte quantity for quantity_64; both carry the same value.
                    int32(Rf.QUANTITY, quantity.toInt()); int64(Rf.QUANTITY_64, quantity)
                    when (request.kind) {
                        OrderKind.LIMIT -> { int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_LIMIT); double(Rf.PRICE, spec.scale.price(entry)) }
                        OrderKind.STOP -> { int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_STOP_MARKET); double(Rf.TRIGGER_PRICE, spec.scale.price(entry)) }
                        OrderKind.MARKET -> int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_MARKET)
                    }
                    int32(Rf.TRANSACTION_TYPE, if (request.side == Side.BUY) RithmicEnums.BUY else RithmicEnums.SELL)
                    int32(Rf.DURATION, RithmicEnums.DURATION_DAY)
                    string(Rf.TRADE_ROUTE, route)
                    int32(Rf.MANUAL_OR_AUTO, RithmicEnums.PLACEMENT_MANUAL)
                    if (bracket) {
                        int32(Rf.USER_TYPE, userType)
                        // Static brackets measure from the order price; a market entry has none, so Rithmic measures from the fill.
                        val static = request.kind != OrderKind.MARKET
                        int32(Rf.BRACKET_TYPE, when {
                            stopTicks != null && targetTicks != null -> if (static) RithmicEnums.BRACKET_TARGET_AND_STOP_STATIC else 3
                            stopTicks != null -> if (static) RithmicEnums.BRACKET_STOP_ONLY_STATIC else 1
                            else -> if (static) RithmicEnums.BRACKET_TARGET_ONLY_STATIC else 2
                        })
                        stopTicks?.let { int32(Rf.STOP_TICKS, it.toInt()); int32(Rf.STOP_QUANTITY, quantity.toInt()); int64(Rf.STOP_QUANTITY_64, quantity) }
                        targetTicks?.let { int32(Rf.TARGET_TICKS, it.toInt()); int32(Rf.TARGET_QUANTITY, quantity.toInt()); int64(Rf.TARGET_QUANTITY_64, quantity) }
                    }
                }
            }
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = command(accountId, requestId, "modify:$orderId:$expectedVersion:$entryPrice") {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: errorArgument("挂单已成交、撤销或不属于当前账户")
                require(order.version == expectedVersion) { "挂单版本已变化，请重新选择" }
                require(order.role == OrderRole.ENTRY && order.editable) { "括号保护单或处理中的订单不能按入场单改价" }
                val request = order.request.copy(entryPrice = entryPrice)
                val estimate = estimate(accountId, request, modifying = true)
                require(estimate.valid) { estimate.errors.joinToString("；") }
                val (spec, raw) = lock.withLock { specs[request.symbol] to orders[orderId] }
                require(spec != null && raw != null && raw.active) { "挂单正在处理，暂不可修改" }
                sendWrite(accountId, requestId, "modify", orderId, Rt.REQUEST_MODIFY_ORDER, request.symbol, entryPrice, raw.sequence) {
                    modifyBody(accountId, orderId, spec, raw.quantity, request.kind, entryPrice)
                }
            }
            override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
                command(accountId, requestId, "protect-order:$orderId:$expectedVersion:$stopLoss:$takeProfit") {
                    val order = state.value.orders.firstOrNull { it.id == orderId } ?: errorArgument("挂单已成交、撤销或不属于当前账户")
                    require(order.version == expectedVersion) { "挂单版本已变化，请重新选择" }
                    require(order.role == OrderRole.ENTRY && order.editable && order.protectionEditable) { "该挂单的括号保护尚未确认或正在处理，暂不可修改" }
                    val changes = listOf(order.request.stopLoss != stopLoss, order.request.takeProfit != takeProfit).count { it }
                    require(changes == 1) { "每次只能修改一条保护线，服务器不支持将两次修改作为一个操作执行" }
                    val stop = order.request.stopLoss != stopLoss
                    val price = if (stop) stopLoss else takeProfit
                    require(price != null && (if (stop) order.request.stopLoss else order.request.takeProfit) != null) { "这里只能修改已有括号保护，添加或删除保护请在 R | Trader 操作" }
                    val estimate = estimate(accountId, order.request.copy(stopLoss = stopLoss, takeProfit = takeProfit), modifying = true)
                    require(estimate.valid) { estimate.errors.joinToString("；") }
                    val spec = lock.withLock { specs[order.request.symbol] } ?: errorArgument("合约规格尚未加载")
                    val entry = requireNotNull(order.request.entryPrice)
                    val current = lock.withLock { brackets[orderId] }?.let { if (stop) it.stopTicks else it.targetTicks } ?: errorArgument("括号保护的当前档位未知，请先同步账户")
                    val ticks = abs(price - entry) / spec.instrument.tickSize
                    require(ticks in 1..1_000_000) { "止损/止盈距离入场价至少一个最小变动单位" }
                    // The level acknowledgement is the confirmation for this request type; the bracket update frame only echoes it.
                    sendWrite(accountId, requestId, if (stop) "bracket-stop" else "bracket-target", orderId,
                        if (stop) Rt.REQUEST_UPDATE_STOP_LEVEL else Rt.REQUEST_UPDATE_TARGET_LEVEL, order.request.symbol, ticks) {
                        string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId); string(Rf.BASKET_ID, orderId)
                        int32(Rf.LEVEL, current.toInt()); int32(if (stop) Rf.STOP_TICKS else Rf.TARGET_TICKS, ticks.toInt())
                    }.also { result -> if (result.accepted) lock.withLock {
                        val levels = brackets[orderId] ?: BracketLevels()
                        brackets[orderId] = if (stop) levels.copy(stopTicks = ticks) else levels.copy(targetTicks = ticks)
                        reconcile(); rebuild()
                    } }
                }
            override suspend fun cancelOrder(requestId: String, orderId: String) = command(accountId, requestId, "cancel:$orderId") {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: errorArgument("挂单已结束或不属于当前账户")
                sendWrite(accountId, requestId, "cancel", orderId, Rt.REQUEST_CANCEL_ORDER, order.request.symbol, null) {
                    string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId); string(Rf.BASKET_ID, orderId)
                    int32(Rf.MANUAL_OR_AUTO, RithmicEnums.PLACEMENT_MANUAL)
                }
            }
            override suspend fun closePosition(requestId: String, positionId: String) = command(accountId, requestId, "close:$positionId") {
                val p = state.value.positions.firstOrNull { it.id == positionId } ?: errorArgument("仓位已结束或不属于当前账户")
                val spec = lock.withLock { specs[p.symbol] } ?: errorArgument("合约规格尚未加载")
                sendWrite(accountId, requestId, "close", p.symbol, Rt.REQUEST_EXIT_POSITION, p.symbol, null) {
                    string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId)
                    string(Rf.SYMBOL, p.symbol); string(Rf.EXCHANGE, spec.exchange); int32(Rf.MANUAL_OR_AUTO, RithmicEnums.PLACEMENT_MANUAL)
                }
            }
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = command(accountId, requestId, "protect:$positionId:$expectedVersion:$stopLoss:$takeProfit") {
                val p = state.value.positions.firstOrNull { it.id == positionId } ?: errorArgument("仓位已结束或不属于当前账户")
                require(p.version == expectedVersion && p.protectionEditable) { "仓位或保护订单关系已变化，请在 R | Trader 核对" }
                val changes = listOf(p.stopLoss != stopLoss, p.takeProfit != takeProfit).count { it }
                require(changes == 1) { "每次只能修改一条保护线，服务器不支持将两次修改作为一个操作执行" }
                val stop = p.stopLoss != stopLoss
                val price = requireNotNull(if (stop) stopLoss else takeProfit) { "这里只能修改已有止损/止盈，添加或删除保护请在 R | Trader 操作" }
                val spec = lock.withLock { specs[p.symbol] } ?: errorArgument("合约规格尚未加载")
                require(fresh(quotes[p.symbol])) { "止损/止盈修改需要有效的实时报价" }
                positionProtectionError(spec.instrument, p, quotes[p.symbol], stopLoss, takeProfit)?.let { errorArgument(it) }
                val id = requireNotNull(if (stop) p.stopLossOrderId else p.takeProfitOrderId) { "没有唯一可修改的保护订单" }
                val raw = lock.withLock { orders[id] }
                require(raw != null && raw.active && raw.side != null) { "保护订单已被替换或不再有效" }
                sendWrite(accountId, requestId, "modify", id, Rt.REQUEST_MODIFY_ORDER, p.symbol, price, raw.sequence) {
                    modifyBody(accountId, id, spec, raw.quantity, if (stop) OrderKind.STOP else OrderKind.LIMIT, price)
                }
            }
        }
    }

    /** The tightest server limit for one order of [symbol]: account-wide and, where listed, for its product. */
    private fun orderLimit(accountId: String, symbol: String): Long? =
        listOfNotNull(maxOrderQuantities[accountId], specs[symbol]?.productCode?.let { productRms[accountId to it]?.orderLimit }).minOrNull()

    private fun ProtoWriter.modifyBody(accountId: String, basketId: String, spec: RithmicSpec, quantity: Long, kind: OrderKind, price: Long) {
        string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId); string(Rf.BASKET_ID, basketId)
        string(Rf.SYMBOL, spec.instrument.symbol); string(Rf.EXCHANGE, spec.exchange)
        int32(Rf.QUANTITY, quantity.toInt()); int64(Rf.QUANTITY_64, quantity)
        if (kind == OrderKind.LIMIT) { int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_LIMIT); double(Rf.PRICE, spec.scale.price(price)) }
        else { int32(Rf.PRICE_TYPE, RithmicEnums.PRICE_STOP_MARKET); double(Rf.TRIGGER_PRICE, spec.scale.price(price)) }
        int32(Rf.MANUAL_OR_AUTO, RithmicEnums.PLACEMENT_MANUAL)
    }

    private fun estimate(accountId: String, request: OrderRequest, modifying: Boolean = false): OrderPreview = try {
        require(!request.symbol.startsWith("@")) { "连续合约不能直接下单，请等待主力合约解析" }
        request.sourceSymbol?.let { source ->
            val route = continuousRoutes[source] ?: errorArgument("连续合约映射尚未加载")
            require(route.tradeSymbol == request.symbol) { "主力合约已换月，请检查新合约并重新确认订单" }
        }
        require(market.value.tradingReady) { market.value.statusMessage.ifBlank { "交易连接未就绪" } }
        require(accounts.any { it.id == accountId }) { "账户不在当前登录中" }
        val spec = specs[request.symbol] ?: errorArgument("合约规格尚未加载")
        require(request.lotsHundredths in spec.instrument.minLotsHundredths..spec.instrument.maxLotsHundredths && request.lotsHundredths % 100 == 0L) { "期货数量必须为正整数张" }
        val limit = orderLimit(accountId, request.symbol)
        require(limit == null || request.lotsHundredths / 100 <= limit) { "数量超过服务器风控的单笔上限 $limit 张" }
        val quote = quotes[request.symbol]
        require(request.symbol in subscribedQuotes && fresh(quote)) { "无可用实时 Bid/Ask 或报价超过 15 秒" }
        quote!!
        val tick = spec.instrument.tickSize
        val entry = if (request.kind == OrderKind.MARKET) if (request.side == Side.BUY) quote.ask else quote.bid else requireNotNull(request.entryPrice)
        require(entry > 0 && entry % tick == 0L) { "入场价不符合最小变动单位" }
        if (request.kind != OrderKind.MARKET) {
            val above = (request.side == Side.BUY) == (request.kind == OrderKind.STOP)
            val reference = if (request.side == Side.BUY) quote.ask else quote.bid
            require(if (above) entry >= reference + tick else entry <= reference - tick) { "Limit/Stop 入场方向或距离无效" }
        }
        entryProtectionError(spec.instrument, request, quote)?.let { errorArgument(it) }
        if (!modifying && (request.stopLoss != null || request.takeProfit != null)) require(accountFlows[accountId]?.value?.positions?.none { it.symbol == request.symbol } != false) { "该合约已有净仓，不能再叠加括号保护" }
        val warnings = mutableListOf("盈亏按合约价值估算，未扣手续费。手续费、保证金和最终成交以 Rithmic / FCM 为准")
        if (request.kind == OrderKind.MARKET && (request.stopLoss != null || request.takeProfit != null)) warnings.add("市价单的括号保护按实际成交价加减相同 tick 数生成，实际 SL/TP 价位可能与输入不同")
        // The rate is per fill, so one lot in and out pays it twice.
        val commission = spec.productCode?.let { productRms[accountId to it]?.commissionMicros }
        OrderPreview(entry, feesMicros = commission?.let { checkedMultiply(checkedMultiply(it, 2), request.lotsHundredths / 100) } ?: 0,
            stopLossPnlMicros = request.stopLoss?.let { grossPnl(spec.instrument, request.side, request.lotsHundredths, entry, it) },
            takeProfitPnlMicros = request.takeProfit?.let { grossPnl(spec.instrument, request.side, request.lotsHundredths, entry, it) },
            quoteRevision = market.value.revision, marginKnown = false, feesKnown = commission != null, warnings = warnings)
    } catch (e: IllegalArgumentException) { OrderPreview(null, errors = listOf(safeRithmicMessage(e.message ?: "订单无效")), marginKnown = false, feesKnown = false) }

    private suspend fun command(accountId: String, requestId: String, signature: String, action: suspend () -> TradeResult): TradeResult = commandLock.withLock {
        val key = "$accountId/$requestId"
        results[key]?.let { return@withLock if (it.first == signature) it.second else TradeResult(false, "同一请求 ID 不可用于不同指令") }
        val result = try {
            require(requestId.length in 1..50 && requestId.all { it.isLetterOrDigit() || it == '-' }) { "请求 ID 无效" }
            require(connected && !connecting && !synchronizing && unknownCommands.isEmpty() && awaiting.isEmpty()) { market.value.statusMessage }
            require(accounts.any { it.id == accountId }) { "账户不在当前登录中" }
            action()
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: IllegalArgumentException) { TradeResult(false, safeRithmicMessage(e.message ?: "请求无效")) }
        catch (_: Exception) { TradeResult(false, "操作未完成，请刷新账户状态后核对") }
        results[key] = signature to result
        lock.withLock {
            localEvents[accountId] = ((localEvents[accountId] ?: emptyList()) + ActivityEvent(now(), result.message)).takeLast(200)
            rebuild()
        }
        result
    }

    private suspend fun sendWrite(account: String, requestId: String, kind: String, entity: String?, template: Int, symbol: String, expectedPrice: Long?,
        baselineSequence: String? = null, build: ProtoWriter.() -> Unit): TradeResult {
        val plant = orderPlant ?: return TradeResult(false, "订单连接不可用")
        val key = "$account/$requestId"
        lock.withLock { awaiting[key] = Awaiting(account, kind, entity, tag(requestId), now(), expectedPrice, baselineSequence, symbol); publish() }
        return try {
            val reply = plant.request(template, 20_000, build)
            if (reply.isEmpty()) throw RithmicRemoteException("empty", "缺少服务器回执")
            // The server sends the basket id on a part ahead of the terminal one.
            val basket = reply.firstNotNullOfOrNull { part -> part.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } }
            lock.withLock {
                if (kind == "place") {
                    // The order notification can beat this reply and settle the wait by its client tag already.
                    if (basket == null && key in awaiting) throw RithmicRemoteException("basket", "缺少订单回执 ID，无法确认结果")
                    awaiting[key]?.let { awaiting[key] = it.copy(entity = basket) }
                }
                reconcile(); publish()
            }
            TradeResult(true, "Rithmic 已受理，最终结果以服务器订单/仓位更新为准", basket ?: entity)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { lock.withLock { if (key in awaiting) unknownCommands.add(key); publish() } }
            throw cancel
        } catch (error: RithmicRemoteException) {
            val certainRejection = error.code !in setOf("closed", "empty", "basket", "timeout")
            val confirmed = lock.withLock {
                if (certainRejection) awaiting.remove(key) else if (key in awaiting) unknownCommands.add(key)
                publish()
                !certainRejection && key !in awaiting
            }
            when {
                certainRejection -> TradeResult(false, "Rithmic 拒绝：${safeRithmicMessage(error.message ?: "请求被拒绝")}")
                confirmed -> TradeResult(true, "服务器已确认该指令；未重发", entity)
                else -> TradeResult(false, "交易结果未知，未自动重试。请在 R | Trader 核对，当前会话已暂停发送交易指令。", outcome = CommandOutcome.UNKNOWN)
            }
        } catch (_: Exception) {
            lock.withLock { if (key in awaiting) unknownCommands.add(key); publish() }
            TradeResult(false, "交易结果未知，未自动重试。请在 R | Trader 核对，当前会话已暂停发送交易指令。", outcome = CommandOutcome.UNKNOWN)
        }
    }

    /** Caller holds the lock. */
    private fun reconcile(fullSnapshot: Boolean = false) {
        val completed = awaiting.filter { (_, wait) ->
            when (wait.kind) {
                "place" -> orders.values.any { it.accountId == wait.account && (wait.entity != null && it.basketId == wait.entity || it.userTag == wait.tag) }
                "modify" -> orders[wait.entity]?.let { order ->
                    val price = order.price ?: order.triggerPrice
                    !order.active || (order.sequence != null && order.sequence != wait.baselineSequence) ||
                        (wait.expectedPrice != null && price != null && specs[order.symbol]?.scale?.units(price) == wait.expectedPrice)
                } ?: fullSnapshot
                "bracket-stop" -> fullSnapshot || orders[wait.entity]?.active == false || brackets[wait.entity]?.stopTicks == wait.expectedPrice
                "bracket-target" -> fullSnapshot || orders[wait.entity]?.active == false || brackets[wait.entity]?.targetTicks == wait.expectedPrice
                "cancel" -> orders[wait.entity]?.let { !it.active } ?: fullSnapshot
                "close" -> positions[wait.account to wait.entity]?.let { it.net == 0L } ?: fullSnapshot
                else -> false
            }
        }.keys
        completed.forEach { key ->
            val wait = awaiting.remove(key)!!
            unknownCommands.remove(key)
            attemptedRecovery.remove(key)
            results[key]?.let { (signature, result) ->
                if (result.outcome == CommandOutcome.UNKNOWN) results[key] = signature to TradeResult(true, "服务器状态已确认；未重发指令", wait.entity)
            }
        }
        // Nothing is left to confirm for a command the server has already settled.
        unknownCommands.retainAll(awaiting.keys)
    }

    private fun tag(id: String) = "UT-$id"
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
}
