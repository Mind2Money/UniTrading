package com.unitrading.tradovate

import com.unitrading.broker.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.time.Clock

/** Bars requested per symbol and timeframe and kept in memory: twice the largest indicator period (1000), so that period still yields 1000 values. */
const val CHART_BARS = 2000
private const val PENDING_BRACKETS_LOCKED = "该挂单的止损/止盈关联未确认、属于 ATM 或正在处理，暂不可修改"

/** Server-authoritative adapter. No remote order is executed by the local demo engine. */
class TradovateBroker(
    private var credentials: TradovateSession,
    private val wire: TradovateWire = KtorTradovateWire(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onSessionRenewed: suspend (TradovateSession) -> Unit = {},
) : BrokerPlugin {
    override val descriptor = BrokerDescriptor("tradovate-${credentials.environment.wire}-${credentials.userId}",
        "Tradovate ${credentials.environment.wire.replaceFirstChar(Char::uppercaseChar)}", "服务器行情与净额期货账户", credentials.environment == TradovateEnvironment.DEMO,
        if (credentials.environment == TradovateEnvironment.DEMO) BrokerEnvironment.REMOTE_DEMO else BrokerEnvironment.LIVE)
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
    private val tradeFillTracker = TradeFillTracker()
    private var accountFlows = emptyMap<String, MutableStateFlow<AccountSnapshot>>()
    private var incompleteAccounts = emptySet<String>()
    private val book = EntityBook()
    private var authorizedAccounts = emptySet<Long>()
    private val lock = Mutex()
    private val metadataLock = Mutex()
    private val commandLock = Mutex()
    private val capacityLock = Mutex()
    private val capacityChecks = mutableListOf<Long>()
    private val synchronizationLock = Mutex()
    private val requestPace = Mutex()
    private val requestSlots = Semaphore(4)
    private var lastRequestMs: Long? = null
    private data class FinancialSnapshot(val values: JsonObject, val dailyPnl: DailyPnl?)
    private val financials = mutableMapOf<Long, FinancialSnapshot>()
    private var financialRevision = 0L
    private val fillFees = mutableMapOf<Long, Long?>()
    private var historyDetailsJob: Job? = null
    private var historyDetailsRevision = 0L
    private val contractSpecs = mutableMapOf<Long, Instrument>()
    private val localEvents = mutableMapOf<String, List<ActivityEvent>>()
    private var quotes = emptyMap<String, Quote>()
    private data class QuoteSides(val bid: Long?, val ask: Long?, val time: Long)
    private val quoteSides = mutableMapOf<String, QuoteSides>()
    private var history = emptyMap<Pair<String, Timeframe>, List<Candle>>()
    private data class ChartChannel(val wireId: JsonPrimitive, val series: Pair<String, Timeframe>)
    private val charts = mutableMapOf<String, ChartChannel>()
    private val earlyCharts = mutableListOf<JsonObject>()
    private val subscribedQuotes = mutableSetOf<String>()
    private var accountSocket: TradovateSocket? = null
    private var marketSocket: TradovateSocket? = null
    private var connected = false
    private var marketReady = false
    private var synchronizing = true
    private var connecting = false
    private var stopped = false
    private var problem = "正在连接 Tradovate"
    private val queuedEvents = mutableListOf<JsonObject>()
    private var maintenance: Job? = null
    private var watchJob: Job? = null
    private var reconnectJob: Job? = null
    private var metadataJob: Job? = null
    private var balanceJob: Job? = null
    private var recoveryJob: Job? = null
    private var watching: Pair<String, Timeframe>? = null
    private val results = mutableMapOf<String, Pair<String, TradeResult>>()
    private val unknownCommands = mutableSetOf<String>()
    private val awaiting = mutableMapOf<String, Awaiting>()
    private val attemptedRecovery = mutableSetOf<String>()
    private data class Awaiting(val account: String, val kind: String, val entity: Long?, val tag: String, val started: Long,
        val baselineVersion: Long = 0, val priceKey: String? = null, val expectedPrice: Long? = null,
        val baselinePositionId: Long? = null)

    suspend fun connect(onProgress: (String) -> Unit = {}) = withContext(scope.coroutineContext.minusKey(Job)) {
        check(!stopped)
        lock.withLock { connecting = true; publish() }
        if (credentials.expiresAtMs?.let { it - now() < 120_000 } == true) renewSession()
        connected = false
        marketReady = false
        problem = "登录会话已交接，正在读取 ${credentials.environment.wire} 授权账户列表"
        onProgress(problem)
        publish()
        val discovery = rest("GET", "account/list") as? JsonArray
            ?: throw TradovateRemoteException(0, "服务器账户列表格式无效")
        val discoveredIds = discovery.map { row ->
            (row as? JsonObject)?.long("id")?.takeIf { it > 0 }
                ?: throw TradovateRemoteException(0, "服务器账户标识无效")
        }.toSet()
        authorizedAccounts = discoveredIds
        onProgress("服务器已返回 ${discoveredIds.size} 个授权账户，正在连接账户流…")
        val account = TradovateSocket(wire.socket(credentials.environment.socketUrl), scope, ::accountEvent) { message ->
            connected = false
            problem = message
            publish()
            scheduleReconnect()
        }
        accountSocket = account
        onProgress("正在验证账户访问令牌…")
        account.authorize(credentials.accessToken)
        onProgress("账户授权成功，正在读取完整账户快照…")
        synchronizeAccount(account)
        onProgress("已同步 ${accounts.size} 个账户，正在并行加载合约规格、资金快照和行情…")
        val metadataLoaded = coroutineScope {
            val metadata = async {
                val loaded = withTimeoutOrNull(30_000) { hydrateContracts(); true } == true
                if (instruments.isEmpty()) withTimeoutOrNull(15_000) { searchInstruments("MES") }
                loaded
            }
            launch { withTimeoutOrNull(20_000) { refreshBalances() } }
            launch { connectMarket() }
            metadata.await()
        }
        val status = lock.withLock {
            if (!metadataLoaded && marketReady) problem = "账户已连接，部分合约规格加载超时；缺失规格的品种不可交易"
            if (accounts.isEmpty()) problem = "当前会话没有可访问的交易账户"
            connecting = false
            publish()
            problem
        }
        onProgress(status)
        watching?.let { watch(it.first, it.second) }
        maintenance?.cancel()
        maintenance = scope.launch {
            while (isActive && !stopped) {
                delay(1_000)
                val recover = lock.withLock {
                    val expired = awaiting.filter { (key, wait) -> now() - wait.started >= 30_000 && key !in attemptedRecovery }.keys
                    attemptedRecovery.addAll(expired)
                    unknownCommands.addAll(expired)
                    publish()
                    expired.isNotEmpty()
                }
                if (recover && recoveryJob?.isActive != true) recoveryJob = scope.launch {
                    try { resynchronize() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { lock.withLock { problem = "重新同步失败，未重发交易指令；请重试同步或在官网核对"; publish() } }
                }
                val expiry = credentials.expiresAtMs
                if (expiry != null && expiry - now() < 120_000) {
                    try { renewSession() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        connected = false; marketReady = false; problem = "登录会话已失效，请重新打开官方登录页"; publish(); return@launch
                    }
                }
            }
        }
    }

    override suspend fun resynchronize() = commandLock.withLock {
        check(!stopped && connected) { "账户连接已断开，请先恢复连接" }
        val socket = checkNotNull(accountSocket)
        problem = "正在重新读取账户状态；不会重发交易指令"
        publish()
        synchronizeAccount(socket)
        hydrateContracts()
        refreshBalances()
        problem = "账户状态已重新同步"
        publish()
    }

    private suspend fun synchronizeAccount(account: TradovateSocket) = synchronizationLock.withLock {
        lock.withLock { synchronizing = true; queuedEvents.clear() }
        try {
        var snapshot = account.request("user/syncrequest", buildJsonObject {
            put("splitResponses", false)
            putJsonArray("entityTypes") {
                listOf("currency", "account", "cashBalance", "order", "orderVersion", "position", "fill", "fillPair", "fillFee", "contract", "orderStrategy", "orderStrategyLink").forEach { add(it) }
            }
        })
        if (listOf("accounts", "orders", "orderVersions", "positions", "fills").any { snapshot[it] !is JsonArray }) {
            throw TradovateRemoteException(0, "账户快照不完整，未将缺失的订单或仓位当作空账户；请重新同步")
        }
        if (authorizedAccounts.isNotEmpty() && snapshot.array("accounts").none { it.long("id") in authorizedAccounts }) {
            throw TradovateRemoteException(0, "已发现授权账户，但账户流未返回对应记录；未将缺失状态当作空仓")
        }
        if (snapshot["fillPairs"] !is JsonArray) {
            // Keep queuing incremental events until REST fallback and snapshot can be applied together.
            try {
                val pairs = rest("GET", "fillPair/list") as? JsonArray
                if (pairs != null) snapshot = JsonObject(snapshot + ("fillPairs" to pairs))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { problem = "成交配对暂时无法读取，可重试同步" }
        }
        lock.withLock {
            book.snapshot(snapshot)
            financials.clear()
            financialRevision++
            queuedEvents.forEach(book::event)
            queuedEvents.clear()
            tradeFillTracker.baseline(book, authorizedAccounts, now())
            synchronizing = false
            connected = true
            reconcile(fullSnapshot = true)
            rebuild()
        }
        lock.withLock { scheduleHistoryDetails(invalidate = true) }
        } finally {
            withContext(NonCancellable) { lock.withLock {
                if (synchronizing) {
                    queuedEvents.forEach(book::event)
                    queuedEvents.clear()
                    tradeFillTracker.baseline(book, authorizedAccounts, now())
                    synchronizing = false
                    reconcile()
                    rebuild()
                }
            } }
        }
    }

    private suspend fun renewSession() {
        val renewed = rest("GET", "auth/renewaccesstoken").jsonObject
        val token = renewed.str("accessToken") ?: error("renewal denied")
        val expiry = renewed.str("expirationTime")?.let(::timestamp)
            ?: jwtClaims(token)?.long("exp")?.takeIf { it in 1..253_402_300_799L }?.times(1_000)
        check(expiry != null && expiry > now()) { "renewal expiry missing or invalid" }
        credentials = TradovateSession(credentials.environment, token, renewed.str("mdAccessToken") ?: credentials.mdAccessToken,
            credentials.userId, credentials.userName, expiry, credentials.marketUrl)
        onSessionRenewed(credentials)
    }

    private suspend fun connectMarket() {
        // Web sessions use their account token on the selected market-data gateway too.
        val token = credentials.accessToken
        lock.withLock { quoteSides.clear(); quotes = emptyMap() }
        try {
            val socket = TradovateSocket(wire.socket(credentials.environment.marketUrl), scope, ::marketEvent) {
                marketReady = false; problem = "行情已断开，交易已暂停"; publish(); scheduleReconnect()
            }
            marketSocket = socket
            socket.authorize(token)
            lock.withLock { marketReady = true; problem = "已连接 · 服务器行情 · 净额持仓"; publish() }
        } catch (_: TimeoutCancellationException) { lock.withLock { marketReady = false; problem = "账户已连接；行情授权超时，账户登录状态仍保留"; publish() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { lock.withLock { marketReady = false; problem = "账户已连接；行情授权/连接失败：${safeMessage(e.message ?: "请检查行情订阅")}"; publish() } }
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            for (attempt in 1..3) {
                delay(attempt * 3_000L)
                if (stopped) return@launch
                try {
                    accountSocket?.close(); marketSocket?.close(); marketReady = false
                    charts.clear(); subscribedQuotes.clear()
                    connect()
                    return@launch
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { problem = "连接恢复失败，请重新登录；未重放任何交易指令"; publish() }
            }
        }
    }

    override fun disconnect() {
        stopped = true
        connected = false; marketReady = false; problem = "已断开"
        publish()
        maintenance?.cancel(); reconnectJob?.cancel(); watchJob?.cancel(); metadataJob?.cancel(); balanceJob?.cancel(); recoveryJob?.cancel(); historyDetailsJob?.cancel()
        scope.launch { runCatching { accountSocket?.close() }; runCatching { marketSocket?.close() }; wire.close(); scope.cancel() }
    }

    override suspend fun searchInstruments(query: String) {
        if (!connected || query.trim().length !in 1..32) return
        val data = rest("GET", "contract/suggest?t=${query.trim().encodeURLParameter()}&l=15") as? JsonArray ?: return
        val contracts = data.mapNotNull { it as? JsonObject }
        lock.withLock { contracts.forEach { book.apply("contract", it) } }
        hydrateContracts(contracts.mapNotNull { it.long("id") })
    }

    private suspend fun hydrateContracts(priority: List<Long> = emptyList()) = metadataLock.withLock {
        val ids = lock.withLock {
            (priority + book.list("position").filter { (it.long("netPos") ?: 0) != 0L }.mapNotNull { it.long("contractId") } +
                book.list("order").filter(::activeOrder).mapNotNull { it.long("contractId") } +
                book.list("contract").mapNotNull { it.long("id") }).distinct().filter { it !in contractSpecs }.take(100)
        }
        // Different contracts can share maturity/product/currency rows. Serialize only those
        // cache misses, never the entire network load, so each shared row is fetched once.
        val entityLocks = mutableMapOf<Pair<String, Long>, Mutex>()
        suspend fun entity(type: String, entityId: Long): JsonObject {
            val entityLock = lock.withLock { entityLocks.getOrPut(type to entityId) { Mutex() } }
            return entityLock.withLock {
                    lock.withLock { book.item(type, entityId) }?.let { return it }
                    val item = rest("GET", "$type/item?id=$entityId").jsonObject
                    lock.withLock { book.apply(type, item) }
                    item
            }
        }
        val slots = Semaphore(4)
        coroutineScope { ids.map { id -> launch { slots.withPermit {
            try {
                val contract = entity("contract", id)
                val maturity = entity("contractMaturity", contract.long("contractMaturityId") ?: return@withPermit)
                val product = entity("product", maturity.long("productId") ?: return@withPermit)
                val currency = entity("currency", product.long("currencyId") ?: return@withPermit).str("name") ?: return@withPermit
                val spec = mapInstrument(contract, maturity, product, currency) ?: return@withPermit
                lock.withLock { contractSpecs[id] = spec; rebuild() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { lock.withLock { problem = "部分合约规格无法加载，缺失规格的品种不可交易" } }
        } } }.joinAll() }
        lock.withLock { rebuild() }
    }

    private suspend fun refreshBalances() {
        val slots = Semaphore(4)
        coroutineScope { accounts.map { account -> launch { slots.withPermit {
            val id = account.id.toLongOrNull() ?: return@withPermit
            try {
                val revision = lock.withLock { financialRevision }
                val snapshot = rest("POST", "cashBalance/getcashbalancesnapshot", buildJsonObject { put("accountId", id) }).jsonObject
                lock.withLock {
                    if (revision == financialRevision) {
                        financials[id] = FinancialSnapshot(snapshot, mapDailyPnl(snapshot, latestUsdCashBalance(id), now()))
                        rebuild()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Missing values stay unknown instead of becoming zero. */ }
        } } }.joinAll() }
    }

    /** Caller holds lock; one worker coalesces entity updates and preserves known fees for unchanged fills. */
    private fun scheduleHistoryDetails(invalidate: Boolean = false) {
        historyDetailsRevision++
        if (invalidate) { fillFees.clear(); rebuild() }
        if (historyDetailsJob?.isActive == true) return
        historyDetailsJob = scope.launch {
            var again = true
            while (again) {
                val revision = lock.withLock { historyDetailsRevision }
                hydrateTradeEntities(revision)
                val ids = lock.withLock {
                    val accountOrders = book.list("order").filter { it.long("accountId") in authorizedAccounts }.mapNotNull { it.long("id") }.toSet()
                    val paired = book.list("fillPair").filter { it.bool("active") == true }.flatMap { listOfNotNull(it.long("buyFillId"), it.long("sellFillId")) }.toSet()
                    book.list("fill").filter { it.long("id") in paired && it.long("orderId") in accountOrders && it.bool("active") == true }
                        .sortedByDescending { it.str("timestamp") }.mapNotNull { it.long("id") }.filter { it !in fillFees }
                }
                for (id in ids) {
                    ensureActive()
                    val response = try { rest("GET", "fillFee/deps?masterid=$id") as? JsonArray }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                    lock.withLock {
                        if (revision == historyDetailsRevision) {
                            fillFees[id] = response?.filterIsInstance<JsonObject>()?.let { usdFillFees(it, book) }
                            rebuild()
                        }
                    }
                    if (lock.withLock { revision != historyDetailsRevision }) break
                }
                again = lock.withLock {
                    if (revision == historyDetailsRevision) { historyDetailsJob = null; false } else true
                }
            }
        }
    }

    private suspend fun hydrateTradeEntities(revision: Long) {
        for (type in listOf("fill", "order")) {
            val missing = lock.withLock {
                val paired = book.list("fillPair").filter { it.bool("active") == true }
                    .flatMap { listOfNotNull(it.long("buyFillId"), it.long("sellFillId")) }.distinct()
                val ids = if (type == "fill") paired else paired.mapNotNull { book.item("fill", it)?.long("orderId") }.distinct()
                ids.filter { it > 0 && book.item(type, it) == null }
            }
            for (ids in missing.chunked(50)) {
                if (lock.withLock { revision != historyDetailsRevision }) return
                val rows = try { rest("GET", "$type/items?ids=${ids.joinToString(",")}") as? JsonArray }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                lock.withLock {
                    if (revision == historyDetailsRevision) rows?.filterIsInstance<JsonObject>()?.filter { it.long("id") in ids }
                        ?.filter { type != "order" || it.long("accountId") in authorizedAccounts }?.forEach { book.apply(type, it) }
                }
            }
        }
        val contracts = lock.withLock {
            val ownOrders = book.list("order").filter { it.long("accountId") in authorizedAccounts }.mapNotNull { it.long("id") }.toSet()
            book.list("fill").filter { it.long("orderId") in ownOrders }.mapNotNull { it.long("contractId") }.distinct()
        }
        if (lock.withLock { revision == historyDetailsRevision }) hydrateContracts(contracts)
    }

    private suspend fun accountEvent(frame: JsonObject) {
        if (frame.str("e") != "props") return
        val data = frame["d"] as? JsonObject ?: return
        lock.withLock {
            if (synchronizing) {
                if (queuedEvents.size >= 10_000) throw TradovateRemoteException(0, "账户同步事件过多")
                queuedEvents.add(data)
            } else {
                book.event(data)
                if (data.str("entityType") in setOf("fill", "fillPair", "fillFee")) {
                    if (data.str("entityType") == "fill") (data["entity"] as? JsonObject)?.long("id")?.let(fillFees::remove)
                    scheduleHistoryDetails(invalidate = data.str("entityType") == "fillFee")
                }
                if (data.str("entityType") in setOf("position", "cashBalance", "fill")) {
                    financials.clear()
                    financialRevision++
                    if (balanceJob?.isActive != true) balanceJob = scope.launch {
                        do {
                            delay(2_000)
                            val revision = lock.withLock { financialRevision }
                            refreshBalances()
                            val changed = lock.withLock { revision != financialRevision }
                        } while (changed && !stopped)
                    }
                }
                val deletedId = if (data.str("eventType").equals("Deleted", true)) (data["entity"] as? JsonObject)?.long("id") else null
                reconcile(deletedPositionId = deletedId.takeIf { data.str("entityType") == "position" },
                    deletedOrderId = deletedId.takeIf { data.str("entityType") == "order" })
                rebuild()
                if (!stopped) tradeFillTracker.event(book, authorizedAccounts, data).forEach { tradeEvents.tryEmit(it) }
            }
        }
        if (metadataJob?.isActive != true) metadataJob = scope.launch { hydrateContracts() }
    }

    override fun history(symbol: String, timeframe: Timeframe) = history[symbol to timeframe] ?: emptyList()
    override fun watch(symbol: String, timeframe: Timeframe) {
        watching = symbol to timeframe
        watchJob?.cancel()
        watchJob = scope.launch {
            val socket = marketSocket ?: return@launch
            if (!marketReady) return@launch
            try {
                val wanted = (accountFlows.values.flatMap { it.value.positions.map(Position::symbol) } + symbol).toSet()
                for (old in subscribedQuotes.toList() - wanted) {
                    socket.request("md/unsubscribequote", buildJsonObject { put("symbol", old) })
                    subscribedQuotes.remove(old)
                }
                for (name in wanted - subscribedQuotes) {
                    val response = socket.request("md/subscribequote", buildJsonObject { put("symbol", name) })
                    val mode = response.str("mode")
                    if (mode != null && mode != "RealTime") throw TradovateRemoteException(403, "行情模式 $mode，不允许使用延迟报价下单")
                    subscribedQuotes.add(name)
                }
                val oldCharts = lock.withLock { charts.values.map { it.wireId }.also { charts.clear() } }
                for (id in oldCharts) socket.request("md/cancelChart", buildJsonObject { put("subscriptionId", id) })
                val response = socket.request("md/getChart", buildJsonObject {
                    put("symbol", symbol)
                    put("chartDescription", chartDescription(timeframe))
                    putJsonObject("timeRange") { put("asMuchAsElements", CHART_BARS) }
                })
                lock.withLock {
                    for (field in listOf("historicalId", "realtimeId")) {
                        val id = response[field] as? JsonPrimitive ?: continue
                        val key = chartSubscriptionKey(id) ?: continue
                        charts[key] = ChartChannel(id, symbol to timeframe)
                    }
                    earlyCharts.toList().forEach(::chartEvent); earlyCharts.clear()
                    publish()
                }
            } catch (_: TimeoutCancellationException) {
                problem = "账户已连接；行情/历史订阅请求超时，可重新选择品种或周期重试"
                publish()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { problem = safeMessage(error.message ?: "行情/历史订阅失败"); publish() }
        }
    }

    private suspend fun marketEvent(frame: JsonObject) = lock.withLock {
        val data = frame["d"] as? JsonObject ?: return@withLock
        if (frame.str("e") == "md") {
            for (q in data.array("quotes")) {
                val spec = contractSpecs[q.long("contractId")] ?: continue
                val entries = q["entries"] as? JsonObject ?: continue
                val incomingBid = (entries["Bid"] as? JsonObject)?.priceValue("price")
                val incomingAsk = (entries["Offer"] as? JsonObject)?.priceValue("price")
                if (incomingBid == null && incomingAsk == null) continue
                val time = q.str("timestamp")?.let(::timestamp) ?: continue
                val previous = quoteSides[spec.symbol]
                if (previous != null && previous.time > time) continue
                if (listOfNotNull(incomingBid, incomingAsk).any { it <= 0 || it % spec.tickSize != 0L }) continue
                val next = QuoteSides(incomingBid ?: previous?.bid, incomingAsk ?: previous?.ask, time)
                quoteSides[spec.symbol] = next
                val bid = next.bid ?: continue
                val ask = next.ask ?: continue
                if (ask < bid) continue
                quotes = quotes + (spec.symbol to Quote(bid, ask, time))
            }
        }
        if (frame.str("e") == "chart") data.array("charts").forEach(::chartEvent)
        rebuild()
    }

    private fun chartEvent(chart: JsonObject) {
        val key = chartSubscriptionKey(chart["id"])?.let { charts[it]?.series }
        if (key == null) { if (earlyCharts.size < 256) earlyCharts.add(chart); return }
        val incoming = chart.array("bars").mapNotNull { bar ->
            val time = bar.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null
            val o = bar.priceValue("open") ?: return@mapNotNull null
            val h = bar.priceValue("high") ?: return@mapNotNull null
            val l = bar.priceValue("low") ?: return@mapNotNull null
            val c = bar.priceValue("close") ?: return@mapNotNull null
            if (l <= 0 || h < maxOf(o, c) || l > minOf(o, c)) return@mapNotNull null
            val volume = listOfNotNull(bar.long("upVolume"), bar.long("downVolume")).takeIf { it.isNotEmpty() }?.sum()?.takeIf { it >= 0 }
            Candle(time, o, h, l, c, volume)
        }
        history = history + (key to ((history[key] ?: emptyList()) + incoming).associateBy { it.timeMs }.values.sortedBy { it.timeMs }.takeLast(CHART_BARS))
    }

    private fun rebuild() {
        val mappedAccounts = book.list("account").mapNotNull { a ->
            val id = a.long("id") ?: return@mapNotNull null
            if (id !in authorizedAccounts || a.bool("closed") == true) return@mapNotNull null
            AccountInfo(id.toString(), a.str("name") ?: id.toString(), readOnly = a.bool("readonly") == true || a.bool("restricted") == true || a.bool("futuresDisabled") == true)
        }
        val nextFlows = accountFlows.toMutableMap()
        val incomplete = mutableSetOf<String>()
        for (account in mappedAccounts) {
            val aid = account.id.toLong()
            val orders = book.list("order").filter { it.long("accountId") == aid }
            val active = orders.filter(::activeOrder)
            val classifier = ProtectionClassifier(book, aid)
            val protectionByContract = mutableMapOf<Long, PositionProtection>()
            val old = accountFlows[account.id]?.value
            val rawPositions = book.list("position").filter { it.long("accountId") == aid && (it.long("netPos") ?: 0) != 0L }
            val positions = rawPositions.mapNotNull { p ->
                val cid = p.long("contractId") ?: return@mapNotNull null
                val spec = contractSpecs[cid] ?: return@mapNotNull null
                val net = p.long("netPos") ?: return@mapNotNull null
                val entry = p.priceValue("netPrice") ?: return@mapNotNull null
                if (entry <= 0) return@mapNotNull null
                val side = if (net > 0) Side.BUY else Side.SELL
                val protection = classifier.forPosition(cid, side, abs(net))
                protectionByContract[cid] = protection
                val sl = protection.stopId?.let { book.orderVersion(it)?.priceValue("stopPrice") }
                val tp = protection.targetId?.let { book.orderVersion(it)?.priceValue("price") }
                val id = "$aid:$cid"
                val candidate = Position(id, spec.symbol, side, checkedMultiply(abs(net), 100), entry, p.str("timestamp")?.let(::timestamp) ?: now(),
                    sl, tp, 0, version = 1, protectionEditable = protection.editable, feesKnown = false,
                    stopLossOrderId = protection.stopId?.toString(), takeProfitOrderId = protection.targetId?.toString(),
                    protectionRevision = protection.revision, protectionNote = protection.note)
                val prior = old?.positions?.firstOrNull { it.id == id }
                candidate.copy(version = if (prior?.copy(version = 1) == candidate) prior.version else (prior?.version ?: 0) + 1)
            }
            if (positions.size != rawPositions.size) incomplete.add(account.id)
            val pending = active.mapNotNull { order ->
                val id = order.long("id") ?: return@mapNotNull null
                val version = book.orderVersion(id) ?: return@mapNotNull null
                val spec = contractSpecs[order.long("contractId")] ?: return@mapNotNull null
                val kind = when (version.str("orderType")) { "Limit" -> OrderKind.LIMIT; "Stop" -> OrderKind.STOP; else -> return@mapNotNull null }
                val side = if (order.str("action") == "Buy") Side.BUY else Side.SELL
                val price = version.priceValue(if (kind == OrderKind.LIMIT) "price" else "stopPrice") ?: return@mapNotNull null
                val relation = classifier.relation(id)
                val stop = classifier.children(id, OrderRole.STOP_LOSS).singleOrNull()
                val target = classifier.children(id, OrderRole.TAKE_PROFIT).singleOrNull()
                val contract = order.long("contractId")!!
                val protection = protectionByContract[contract]
                val associated = relation.role == OrderRole.STOP_LOSS && protection?.stopId == id ||
                    relation.role == OrderRole.TAKE_PROFIT && protection?.targetId == id
                val editable = if (relation.role == OrderRole.ENTRY) classifier.entryEditable(id) else associated && protection?.editable == true
                val note = relation.note ?: when {
                    relation.role == OrderRole.ENTRY && !editable -> "订单正在处理或已部分成交，暂不可改价"
                    relation.role in setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT) && !editable -> protection?.note ?: "保护关联尚未匹配当前净仓，仅显示"
                    else -> null
                }
                PendingOrder(id.toString(), OrderRequest(spec.symbol, side, kind, (version.long("orderQty") ?: return@mapNotNull null) * 100, price,
                    stop?.let { book.orderVersion(it)?.priceValue("stopPrice") }, target?.let { book.orderVersion(it)?.priceValue("price") }),
                    order.str("timestamp")?.let(::timestamp) ?: 0, version.long("id") ?: 1,
                    role = relation.role, positionId = if (associated) "$aid:$contract" else null, editable = editable, note = note,
                    filledLotsHundredths = checkedMultiply(classifier.filledQuantity(id), 100),
                    stopLossOrderId = stop?.toString(), takeProfitOrderId = target?.toString(),
                    protectionEditable = relation.role == OrderRole.ENTRY && classifier.pendingBracketsEditable(id))
            }
            val fills = book.list("fill").filter { f -> f.bool("active") != false && orders.any { it.long("id") == f.long("orderId") } }.mapNotNull { f ->
                val spec = contractSpecs[f.long("contractId")] ?: return@mapNotNull null
                Execution(f.long("id")?.toString() ?: return@mapNotNull null, spec.symbol, if (f.str("action") == "Buy") Side.BUY else Side.SELL,
                    (f.long("qty") ?: return@mapNotNull null) * 100, f.priceValue("price") ?: return@mapNotNull null,
                    f.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null, f.long("orderId").toString())
            }.sortedBy { it.timeMs }
            val finance = financials[aid]?.values
            val cash = latestUsdCashBalance(aid)
            val balance = finance?.moneyValue("totalCashValue") ?: cash?.moneyValue("amount")
            val pnlKnown = positions.size == rawPositions.size && positions.all { fresh(quotes[it.symbol]) }
            val floating = if (pnlKnown) runCatching { positions.sumOf { p -> positionPnl(contractSpecs.values.first { it.symbol == p.symbol }, p, quotes.getValue(p.symbol)) } }.getOrNull() else null
            val estimatedEquity = if (balance != null && floating != null) checkedAdd(balance, floating) else null
            val equity = estimatedEquity ?: finance?.moneyValue("netLiq")
            val margin = finance?.moneyValue("initialMargin")
            val snapshot = AccountSnapshot(account.id, balance ?: 0, equity ?: 0, margin ?: 0, positions, pending,
                trades = pairedTrades(book, aid, contractSpecs, fillFees), events = localEvents[account.id] ?: emptyList(), executions = fills,
                balanceKnown = balance != null, equityKnown = equity != null, marginKnown = margin != null, equityEstimated = estimatedEquity != null && positions.isNotEmpty(),
                dailyPnl = financials[aid]?.dailyPnl)
            nextFlows.getOrPut(account.id) { MutableStateFlow(snapshot) }.value = snapshot
        }
        incompleteAccounts = incomplete.toSet()
        accountFlows = nextFlows.toMap()
        mutableCatalog.value = BrokerCatalog(contractSpecs.values.distinctBy { it.symbol }.sortedBy { it.symbol }, mappedAccounts)
        publish()
    }

    private fun latestUsdCashBalance(accountId: Long): JsonObject? = book.list("cashBalance")
        .filter { it.long("accountId") == accountId && book.item("currency", it.long("currencyId") ?: 0)?.str("name") == "USD" }
        .maxByOrNull { it.str("timestamp") ?: "" }

    private fun publish() {
        val unresolved = unknownCommands.isNotEmpty()
        val pending = awaiting.isNotEmpty()
        val message = when {
            unresolved -> "有交易指令尚待核对，已暂停发送后续指令。请同步账户或到 Tradovate 官网核对"
            pending -> "指令已受理，等待服务端订单/仓位更新"
            else -> problem
        }
        mutableMarket.value = MarketSnapshot(quotes, now(), mutableMarket.value.revision + 1, connected,
            statusMessage = message, tradingReady = connected && marketReady && !connecting && !synchronizing && !unresolved && !pending)
    }
    private fun fresh(quote: Quote?): Boolean = quote != null && now() - quote.timeMs in -5_000..15_000
    private suspend fun rest(method: String, path: String, body: JsonObject? = null): JsonElement = requestSlots.withPermit {
        requestPace.withLock {
            lastRequestMs?.let { previous ->
                val elapsed = Clock.System.now().toEpochMilliseconds() - previous
                if (elapsed < 750) delay(750 - elapsed.coerceAtLeast(0))
            }
            lastRequestMs = Clock.System.now().toEpochMilliseconds()
        }
        wire.request(credentials.environment, credentials.accessToken, method, path, body)
    }

    override fun openSession(accountId: String): AccountSession {
        val info = accounts.firstOrNull { it.id == accountId } ?: error("当前授权中没有该账户")
        val flow = accountFlows.getValue(accountId)
        return object : AccountSession {
            override val account = info
            override val state: StateFlow<AccountSnapshot> = flow.asStateFlow()
            override fun preview(request: OrderRequest) = estimate(accountId, request)
            override suspend fun orderCapacity(request: OrderRequest): OrderCapacity = capacityLock.withLock {
                if (!market.value.tradingReady || account.readOnly || accountId in incompleteAccounts)
                    return@withLock OrderCapacity(message = "账户或行情尚未就绪，无法确认可下单数量")
                val contractId = lock.withLock { contractSpecs.entries.firstOrNull { it.value.symbol == request.symbol }?.key }
                    ?: return@withLock OrderCapacity(message = "合约规格尚未加载")
                if (request.kind != OrderKind.MARKET && request.entryPrice?.let { it > 0 && it % contractSpecs.getValue(contractId).tickSize == 0L } != true)
                    return@withLock OrderCapacity(message = "请先输入有效的订单价格")
                val revision = lock.withLock { book.revision }
                val result = discoverOrderCapacity { quantity ->
                    // Keep this endpoint below its documented 500/hour budget, with >=1s between calls.
                    val time = now()
                    capacityChecks.removeAll { time - it >= 3_600_000 }
                    check(capacityChecks.size < 450) { "数量校验频率受限" }
                    capacityChecks.lastOrNull()?.let { delay((1_000 - (time - it)).coerceIn(0, 1_000)) }
                    check(!stopped && market.value.tradingReady && fresh(quotes[request.symbol]) && lock.withLock { book.revision == revision }) { "账户状态已变化" }
                    capacityChecks.add(now())
                    rest("POST", "order/dryrun", capacityBody(accountId.toLong(), contractId, request, quantity)).jsonObject
                }
                if (!market.value.tradingReady || lock.withLock { book.revision != revision })
                    OrderCapacity(message = "账户状态已变化，请刷新数量上限")
                else result
            }
            override fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable) return OrderPreview(null, errors = listOf("该单不是可编辑入场单；保护单请使用仓位保护价入口，关联待确认的订单不可改价"))
                return estimate(accountId, order.request.copy(entryPrice = entryPrice), modifying = true)
            }
            override fun previewOrderProtection(orderId: String, stopLoss: Long?, takeProfit: Long?): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable || !order.protectionEditable) return OrderPreview(null, errors = listOf(PENDING_BRACKETS_LOCKED))
                return estimate(accountId, order.request.copy(stopLoss = stopLoss, takeProfit = takeProfit), modifying = true)
            }
            override suspend fun placeOrder(requestId: String, request: OrderRequest) = command(accountId, requestId, "place:$request") {
                val estimate = estimate(accountId, request)
                require(estimate.valid) { estimate.errors.joinToString("；") }
                val spec = instruments.first { it.symbol == request.symbol }
                val hasProtection = request.stopLoss != null || request.takeProfit != null
                val existingNet = lock.withLock { book.list("position").any { p -> p.long("accountId")?.toString() == accountId &&
                    contractSpecs[p.long("contractId")]?.symbol == request.symbol && (p.long("netPos") ?: 0) != 0L } }
                require(!hasProtection || !existingNet) { "已有净仓时不能再添加括号单，请先在官网核对保护关系" }
                val body = orderBody(accountId, requestId, request, spec)
                sendWrite(accountId, requestId, "place", null, if (hasProtection) "order/placeoso" else "order/placeorder", body)
            }
            override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = command(accountId, requestId, "modify:$orderId:$expectedVersion:$entryPrice") {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: errorArgument("挂单已成交、撤销或不属于当前账户")
                require(order.version == expectedVersion) { "挂单版本已变化，请重新选择" }
                require(order.role == OrderRole.ENTRY && order.editable) { "保护单或关联待确认订单不能按入场单改价" }
                val request = order.request.copy(entryPrice = entryPrice)
                val estimate = estimate(accountId, request, modifying = true)
                require(estimate.valid) { estimate.errors.joinToString("；") }
                val spec = instruments.first { it.symbol == request.symbol }
                val raw = lock.withLock { book.item("order", orderId.toLong()) }
                require(raw?.str("ordStatus") in setOf("Working", "Suspended")) { "挂单正在处理，暂不可修改" }
                sendWrite(accountId, requestId, "modify", orderId.toLong(), "order/modifyorder", buildJsonObject {
                    put("orderId", orderId.toLong()); put("orderQty", request.lotsHundredths / 100)
                    put("orderType", if (request.kind == OrderKind.LIMIT) "Limit" else "Stop")
                    put(if (request.kind == OrderKind.LIMIT) "price" else "stopPrice", jsonNumber(entryPrice, spec.priceDecimals))
                    put("clOrdId", tag(requestId)); put("isAutomated", false)
                })
            }
            override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
                command(accountId, requestId, "protect-order:$orderId:$expectedVersion:$stopLoss:$takeProfit") {
                    val order = state.value.orders.firstOrNull { it.id == orderId } ?: errorArgument("挂单已成交、撤销或不属于当前账户")
                    require(order.version == expectedVersion) { "挂单版本已变化，请重新选择" }
                    require(order.role == OrderRole.ENTRY && order.editable && order.protectionEditable) { PENDING_BRACKETS_LOCKED }
                    val changes = listOf(order.request.stopLoss != stopLoss, order.request.takeProfit != takeProfit).count { it }
                    require(changes == 1) { "每次只能修改一条保护线，服务器不支持将两次修改作为一个操作执行" }
                    val stop = order.request.stopLoss != stopLoss
                    val price = if (stop) stopLoss else takeProfit
                    require(price != null && (if (stop) order.request.stopLoss else order.request.takeProfit) != null) { "这里只能修改已有保护价，添加或删除保护请到官网操作" }
                    val estimate = estimate(accountId, order.request.copy(stopLoss = stopLoss, takeProfit = takeProfit), modifying = true)
                    require(estimate.valid) { estimate.errors.joinToString("；") }
                    val spec = instruments.first { it.symbol == order.request.symbol }
                    val binding = lock.withLock {
                        val classifier = ProtectionClassifier(book, accountId.toLong())
                        require(classifier.pendingBracketsEditable(orderId.toLong())) { PENDING_BRACKETS_LOCKED }
                        val id = requireNotNull(classifier.children(orderId.toLong(), if (stop) OrderRole.STOP_LOSS else OrderRole.TAKE_PROFIT).singleOrNull()) { "没有唯一可修改的保护订单" }
                        require(id.toString() == if (stop) order.stopLossOrderId else order.takeProfitOrderId) { "保护订单已被替换" }
                        id to requireNotNull(book.orderVersion(id)?.long("orderQty"))
                    }
                    sendWrite(accountId, requestId, "modify", binding.first, "order/modifyorder", buildJsonObject {
                        put("orderId", binding.first); put("orderQty", binding.second); put("orderType", if (stop) "Stop" else "Limit")
                        put(if (stop) "stopPrice" else "price", jsonNumber(price, spec.priceDecimals)); put("clOrdId", tag(requestId)); put("isAutomated", false)
                    })
                }
            override suspend fun cancelOrder(requestId: String, orderId: String) = command(accountId, requestId, "cancel:$orderId") {
                require(state.value.orders.any { it.id == orderId }) { "挂单已结束或不属于当前账户" }
                sendWrite(accountId, requestId, "cancel", orderId.toLong(), "order/cancelorder", buildJsonObject { put("orderId", orderId.toLong()); put("clOrdId", tag(requestId)); put("isAutomated", false) })
            }
            override suspend fun closePosition(requestId: String, positionId: String) = command(accountId, requestId, "close:$positionId") {
                val p = state.value.positions.firstOrNull { it.id == positionId } ?: errorArgument("仓位已结束或不属于当前账户")
                val cid = positionId.substringAfter(':').toLong()
                sendWrite(accountId, requestId, "close", cid, "order/liquidateposition", buildJsonObject { put("accountId", accountId.toLong()); put("contractId", cid); put("admin", false); put("isAutomated", false) })
            }
            override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = command(accountId, requestId, "protect:$positionId:$expectedVersion:$stopLoss:$takeProfit") {
                val p = state.value.positions.firstOrNull { it.id == positionId } ?: errorArgument("仓位已结束或不属于当前账户")
                require(p.version == expectedVersion && p.protectionEditable) { "仓位或保护订单关系已变化，请在官网核对" }
                val changes = listOf(p.stopLoss != stopLoss, p.takeProfit != takeProfit).count { it }
                require(changes == 1) { "每次只能修改一条保护线，服务器不支持将两次修改作为一个操作执行" }
                val stop = p.stopLoss != stopLoss
                val price = requireNotNull(if (stop) stopLoss else takeProfit) { "这里只能修改已有保护价，添加或删除保护请到官网操作" }
                val spec = instruments.first { it.symbol == p.symbol }
                validateProtection(spec, p.side, quotes[p.symbol], stopLoss, takeProfit)
                val binding = lock.withLock {
                    val resolution = ProtectionClassifier(book, accountId.toLong()).forPosition(positionId.substringAfter(':').toLong(), p.side, p.lotsHundredths / 100)
                    require(resolution.editable && resolution.revision == p.protectionRevision) { "保护订单关联或版本已变化，请重新选择" }
                    val id = requireNotNull(if (stop) resolution.stopId else resolution.targetId) { "没有唯一可修改的保护订单" }
                    require(id.toString() == if (stop) p.stopLossOrderId else p.takeProfitOrderId) { "保护订单已被替换" }
                    val quantity = requireNotNull(book.orderVersion(id)?.long("orderQty"))
                    id to quantity
                }
                val id = binding.first
                sendWrite(accountId, requestId, "modify", id, "order/modifyorder", buildJsonObject {
                    put("orderId", id); put("orderQty", binding.second); put("orderType", if (stop) "Stop" else "Limit")
                    put(if (stop) "stopPrice" else "price", jsonNumber(price, spec.priceDecimals)); put("clOrdId", tag(requestId)); put("isAutomated", false)
                })
            }
        }
    }

    private fun estimate(accountId: String, request: OrderRequest, modifying: Boolean = false): OrderPreview = try {
        require(market.value.tradingReady) { market.value.statusMessage.ifBlank { "交易连接未就绪" } }
        require(accounts.first { it.id == accountId }.readOnly.not()) { "该账户只有读取权限" }
        require(accountId !in incompleteAccounts) { "该账户仍有未加载规格或均价的净仓，暂不允许新订单" }
        val spec = instruments.firstOrNull { it.symbol == request.symbol } ?: errorArgument("合约规格尚未加载")
        require(request.lotsHundredths in spec.minLotsHundredths..spec.maxLotsHundredths && request.lotsHundredths % 100 == 0L) { "期货数量必须为正整数张" }
        val quote = quotes[request.symbol]
        require(request.symbol in subscribedQuotes && fresh(quote)) { "无可用实时 Bid/Ask 或报价超过 15 秒" }
        quote!!
        val entry = if (request.kind == OrderKind.MARKET) if (request.side == Side.BUY) quote.ask else quote.bid else requireNotNull(request.entryPrice)
        require(entry > 0 && entry % spec.tickSize == 0L) { "入场价不符合最小变动单位" }
        if (request.kind != OrderKind.MARKET) {
            val above = (request.side == Side.BUY) == (request.kind == OrderKind.STOP)
            val reference = if (request.side == Side.BUY) quote.ask else quote.bid
            require(if (above) entry >= reference + spec.tickSize else entry <= reference - spec.tickSize) { "Limit/Stop 入场方向或距离无效" }
        }
        val reference = if (request.kind == OrderKind.MARKET) if (request.side == Side.BUY) quote.bid else quote.ask else entry
        validateProtection(spec, request.side, Quote(reference, reference, quote.timeMs), request.stopLoss, request.takeProfit)
        if (!modifying && (request.stopLoss != null || request.takeProfit != null)) require(accountFlows.getValue(accountId).value.positions.none { it.symbol == request.symbol }) { "该合约已有净仓，不能再叠加括号保护" }
        OrderPreview(entry, stopLossPnlMicros = request.stopLoss?.let { grossPnl(spec, request.side, request.lotsHundredths, entry, it) },
            takeProfitPnlMicros = request.takeProfit?.let { grossPnl(spec, request.side, request.lotsHundredths, entry, it) },
            quoteRevision = market.value.revision, marginKnown = false, feesKnown = false,
            warnings = listOf("盈亏按合约价值估算，未扣手续费。手续费、保证金和最终成交以 Tradovate 为准"))
    } catch (e: IllegalArgumentException) { OrderPreview(null, errors = listOf(safeMessage(e.message ?: "订单无效")), marginKnown = false, feesKnown = false) }

    private fun validateProtection(spec: Instrument, side: Side, quote: Quote?, sl: Long?, tp: Long?) {
        require(fresh(quote)) { "保护价修改需要有效的实时报价" }
        val reference = if (side == Side.BUY) quote!!.bid else quote!!.ask
        for (price in listOfNotNull(sl, tp)) require(price > 0 && price % spec.tickSize == 0L) { "保护价不符合最小变动单位" }
        sl?.let { require(if (side == Side.BUY) it <= reference - spec.tickSize else it >= reference + spec.tickSize) { "止损方向或距离无效" } }
        tp?.let { require(if (side == Side.BUY) it >= reference + spec.tickSize else it <= reference - spec.tickSize) { "止盈方向或距离无效" } }
    }

    private fun orderBody(accountId: String, requestId: String, request: OrderRequest, spec: Instrument): JsonObject = buildJsonObject {
        put("accountId", accountId.toLong()); put("action", if (request.side == Side.BUY) "Buy" else "Sell")
        put("symbol", spec.symbol); put("orderQty", request.lotsHundredths / 100)
        put("orderType", when (request.kind) { OrderKind.MARKET -> "Market"; OrderKind.LIMIT -> "Limit"; OrderKind.STOP -> "Stop" })
        if (request.kind != OrderKind.MARKET) put(if (request.kind == OrderKind.LIMIT) "price" else "stopPrice", jsonNumber(request.entryPrice!!, spec.priceDecimals))
        put("clOrdId", tag(requestId)); put("timeInForce", "Day"); put("isAutomated", false)
        val protections = listOfNotNull(request.stopLoss?.let { true to it }, request.takeProfit?.let { false to it })
        protections.forEachIndexed { index, (stop, price) ->
            putJsonObject("bracket${index + 1}") {
                put("action", if (request.side == Side.BUY) "Sell" else "Buy"); put("orderType", if (stop) "Stop" else "Limit")
                put(if (stop) "stopPrice" else "price", jsonNumber(price, spec.priceDecimals)); put("timeInForce", "Day")
            }
        }
    }

    private suspend fun command(accountId: String, requestId: String, signature: String, action: suspend () -> TradeResult): TradeResult = commandLock.withLock {
        val key = "$accountId/$requestId"
        results[key]?.let { return@withLock if (it.first == signature) it.second else TradeResult(false, "同一请求 ID 不可用于不同指令") }
        val result = try {
            require(requestId.length in 1..50 && requestId.all { it.isLetterOrDigit() || it == '-' }) { "请求 ID 无效" }
            require(connected && !connecting && !synchronizing && unknownCommands.isEmpty() && awaiting.isEmpty()) { market.value.statusMessage }
            require(accounts.first { it.id == accountId }.readOnly.not()) { "账户为只读" }
            action()
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: IllegalArgumentException) { TradeResult(false, safeMessage(e.message ?: "请求无效")) }
        catch (_: Exception) { TradeResult(false, "操作未完成，请刷新账户状态后核对") }
        results[key] = signature to result
        lock.withLock {
            localEvents[accountId] = ((localEvents[accountId] ?: emptyList()) + ActivityEvent(now(), result.message)).takeLast(200)
            rebuild()
        }
        result
    }

    private suspend fun sendWrite(account: String, requestId: String, kind: String, entity: Long?, route: String, body: JsonObject): TradeResult {
        val key = "$account/$requestId"
        val wait = lock.withLock { Awaiting(account, kind, entity, tag(requestId), now(),
            entity?.let { book.orderVersion(it)?.long("id") } ?: 0,
            if (body["stopPrice"] != null) "stopPrice" else if (body["price"] != null) "price" else null,
            body.priceValue("stopPrice") ?: body.priceValue("price"),
            if (kind == "close") book.list("position").firstOrNull { it.long("accountId")?.toString() == account && it.long("contractId") == entity }?.long("id") else null) }
        lock.withLock {
            awaiting[key] = wait
            if (kind == "place") {
                val contract = contractSpecs.entries.firstOrNull { it.value.symbol == body.str("symbol") }?.key
                if (contract != null) tradeFillTracker.prepareEntry(book, account.toLong(), contract,
                    body.str("action") ?: "", body.long("orderQty") ?: 0, wait.tag)
            }
            publish()
        }
        return try {
            val response = rest("POST", route, body).jsonObject
            val failure = response.str("failureReason")
            if (failure != null && failure != "Success") {
                lock.withLock {
                    awaiting.remove(key)
                    if (kind == "place") tradeFillTracker.rejectEntry(account.toLong(), wait.tag)
                }
                TradeResult(false, "Tradovate 拒绝：${safeMessage(response.str("failureText") ?: failure)}")
            } else {
                if (kind == "place" && response.long("orderId") == null) throw TradovateRemoteException(0, "缺少订单回执 ID，无法确认结果")
                lock.withLock {
                    if (kind == "place") {
                        awaiting[key] = wait.copy(entity = response.long("orderId"))
                        tradeFillTracker.acceptEntry(account.toLong(), wait.tag, response.long("orderId")!!)
                        tradeFillTracker.drain(book, authorizedAccounts).forEach { tradeEvents.tryEmit(it) }
                    }
                    reconcile(); publish()
                }
                TradeResult(true, "Tradovate 已受理，最终结果以服务器订单/成交更新为准", response.long("orderId")?.toString() ?: entity?.toString())
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { lock.withLock { unknownCommands.add(key); publish() } }
            throw cancel
        } catch (error: Exception) {
            val certainRejection = error is TradovateRemoteException && error.status in 400..499
            lock.withLock {
                if (certainRejection) {
                    awaiting.remove(key)
                    if (kind == "place") tradeFillTracker.rejectEntry(account.toLong(), wait.tag)
                } else unknownCommands.add(key)
                publish()
            }
            if (certainRejection) TradeResult(false, safeMessage(error.message ?: "请求被拒绝"))
            else TradeResult(false, "交易结果未知，未自动重试。请到 Tradovate 官网核对，当前会话已暂停发送交易指令。", outcome = CommandOutcome.UNKNOWN)
        }
    }

    private fun reconcile(fullSnapshot: Boolean = false, deletedPositionId: Long? = null, deletedOrderId: Long? = null) {
        val completed = awaiting.filter { (_, wait) ->
            if (wait.account.toLongOrNull() !in authorizedAccounts) return@filter false
            val completeAccount = fullSnapshot && book.list("account").any { it.long("id")?.toString() == wait.account }
            when (wait.kind) {
                "place" -> book.list("order").any { it.long("accountId")?.toString() == wait.account &&
                    (wait.entity != null && it.long("id") == wait.entity || it.str("clOrdId") == wait.tag) }
                "modify" -> book.item("order", wait.entity ?: 0)?.let { order ->
                    val version = book.orderVersion(wait.entity ?: 0)
                    !activeOrder(order) || order.str("clOrdId") == wait.tag || version != null &&
                        (version.long("id") ?: 0) > wait.baselineVersion && wait.priceKey != null && version.priceValue(wait.priceKey) == wait.expectedPrice
                } == true
                "cancel" -> book.item("order", wait.entity ?: 0)?.let { !activeOrder(it) }
                    ?: (wait.entity != null && (deletedOrderId == wait.entity || completeAccount))
                "close" -> {
                    val rows = book.list("position").filter { it.long("accountId")?.toString() == wait.account && it.long("contractId") == wait.entity }
                    rows.any { it.long("netPos") == 0L } || rows.isEmpty() && wait.baselinePositionId != null &&
                        (wait.baselinePositionId == deletedPositionId || completeAccount)
                }
                else -> false
            }
        }.keys
        completed.forEach { key ->
            val wait = awaiting.remove(key)!!
            unknownCommands.remove(key)
            attemptedRecovery.remove(key)
            results[key]?.let { (signature, result) ->
                if (result.outcome == CommandOutcome.UNKNOWN) results[key] = signature to TradeResult(true, "服务器状态已确认；未重发指令", wait.entity?.toString())
            }
        }
    }
    private fun tag(id: String) = "UT-$id"
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
}
