package com.unitrading.tradovate

import com.unitrading.broker.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.time.Clock

/** Initial history depth and cache window: twice the largest indicator period (1000). */
const val CHART_BARS = 2000
private const val PENDING_BRACKETS_LOCKED = "该挂单的止损/止盈关联未确认、属于 ATM 或正在处理，暂不可修改"

/** Server-authoritative adapter. No remote order is executed by the local demo engine. */
class TradovateBroker(
    private var credentials: TradovateSession,
    private val wire: TradovateWire = KtorTradovateWire(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onSessionRenewed: suspend (TradovateSession) -> Unit = {},
    candleCacheStore: CandleCacheStore? = null,
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
    private val metadataEntityLocks = mutableMapOf<Pair<String, Long>, Mutex>()
    private val metadataContractLocks = mutableMapOf<Long, Mutex>()
    private data class InstrumentSearch(val fetchedAt: Long, val ids: List<Long>)
    private val instrumentSearchLocks = mutableMapOf<String, Mutex>()
    private val instrumentSearches = mutableMapOf<String, InstrumentSearch>()
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
    private data class ContinuousRoute(val productId: Long, val spec: Instrument, val checkedAt: Long? = null, val validUntil: Long = 0)
    private var continuousRoutes = emptyMap<String, ContinuousRoute>()
    private val continuousLocks = mutableMapOf<String, Mutex>()
    private val continuousResolutionSlots = Semaphore(2)
    private val volumeSlots = Semaphore(3)
    private val continuousEvidenceSaveLock = Mutex()
    private var continuousJob: Job? = null
    private val volumeCharts = mutableMapOf<String, DailyVolumeHistory>()
    private val continuousEvidence = ContinuousEvidence(candleCacheStore, "continuous-v1-${credentials.environment.wire}-${credentials.userId}")
    private val discardedCharts = mutableSetOf<String>()
    private val localEvents = mutableMapOf<String, List<ActivityEvent>>()
    private var quotes = emptyMap<String, Quote>()
    private data class QuoteSides(val bid: Long?, val ask: Long?, val time: Long)
    private val quoteSides = mutableMapOf<String, QuoteSides>()
    private var history = emptyMap<Pair<String, Timeframe>, List<Candle>>()
    // v2: older entries stopped at the first page, about a month of intraday bars, and a cached series only ever grows at its end.
    private val candleCache = CandleCache(candleCacheStore, "v2-${credentials.environment.wire}-${credentials.userId}")
    private val cachedHistory = mutableMapOf<Pair<String, Timeframe>, List<Candle>>()
    private val dirtyHistory = mutableMapOf<Pair<String, Timeframe>, List<Candle>>()
    private var cacheSaveJob: Job? = null
    private var cacheRevision = 0L
    private val chartWatchLock = Mutex()
    private val cancelledChartIds = mutableSetOf<String>()
    private val pendingChartCancellations = mutableMapOf<String, JsonPrimitive>()
    private val chartBackfillJobs = mutableMapOf<Pair<String, Timeframe>, Job>()
    private class ChartPage(val series: Pair<String, Timeframe>, val load: ChartLoad, val startedAt: Long,
        val liveOnly: Boolean, val historyOnly: Boolean, var ended: Boolean = false)
    private data class ChartChannel(val wireId: JsonPrimitive, val page: ChartPage, val historical: Boolean, val shared: Boolean)
    private val charts = mutableMapOf<String, ChartChannel>()
    /** Series read whole in the background so that their charts open from the cache, most wanted first. */
    private val preloadTargets = MutableStateFlow<List<Pair<String, Timeframe>>>(emptyList())
    private val preloadWake = Channel<Unit>(Channel.CONFLATED)
    private var preloadJob: Job? = null
    private var preloading: Pair<String, Timeframe>? = null
    private var preloadSeriesJob: Job? = null
    private val preloadRetryAt = mutableMapOf<Pair<String, Timeframe>, Long>()
    private val preloadFailures = mutableMapOf<Pair<String, Timeframe>, Int>()
    private var preloadFailureStreak = 0
    private var preloadPausedUntil = 0L
    private class PreloadPage(val load: ChartLoad, val historyOnly: Boolean) {
        val ended = CompletableDeferred<Unit>()
        var historicalKey: String? = null
        var shared = false
    }
    private val preloadCharts = mutableMapOf<String, PreloadPage>()
    private val earlyCharts = mutableListOf<JsonObject>()
    private var earlyChartOverflow = 0L
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
    private val watchJobs = mutableMapOf<Pair<String, Timeframe>, Job>()
    private var reconnectJob: Job? = null
    private var metadataJob: Job? = null
    private var balanceJob: Job? = null
    private var recoveryJob: Job? = null
    /** Every chart series kept live. The first is the chart the user works on: it is served first and owns the leader check. */
    private var watched: List<Pair<String, Timeframe>> = emptyList()
    private val watching: Pair<String, Timeframe>? get() = watched.firstOrNull()
    private var quoteWatchSymbols = emptySet<String>()
    private var quoteWatchJob: Job? = null
    private val quoteWatchJobs = mutableMapOf<String, Job>()
    private val quoteDiscoverySlots = Semaphore(3)
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
        continuousEvidence.load(now())
        if (credentials.expiresAtMs?.let { it - now() < 120_000 } == true) renewSession(followHosts = true)
        connected = false
        marketReady = false
        continuousRoutes = continuousRoutes.mapValues { (_, route) -> route.copy(checkedAt = null, validUntil = 0) }
        problem = "登录会话已交接，正在读取 ${credentials.environment.wire} 授权账户列表"
        onProgress(problem)
        publish()
        val discovery = discoverAccounts() as? JsonArray
            ?: throw TradovateRemoteException(0, "服务器账户列表格式无效")
        val discoveredIds = discovery.map { row ->
            (row as? JsonObject)?.long("id")?.takeIf { it > 0 }
                ?: throw TradovateRemoteException(0, "服务器账户标识无效")
        }.toSet()
        authorizedAccounts = discoveredIds
        onProgress("服务器已返回 ${discoveredIds.size} 个授权账户，正在连接账户流…")
        val account = TradovateSocket(wire.socket(credentials.hosts.socketUrl), scope, ::accountEvent) { message ->
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
        watched.forEach(::startWatch)
        refreshQuoteWatch()
        startPreload()
        maintenance?.cancel()
        maintenance = scope.launch {
            while (isActive && !stopped) {
                delay(1_000)
                if (quoteWatchJob?.isActive != true && lock.withLock { quoteWatchSymbols.any { symbol ->
                    quoteWatchJobs[symbol]?.isActive != true && continuousRoutes[symbol]?.let {
                        now() >= it.validUntil && (it.checkedAt == null || now() - it.checkedAt >= 60_000)
                    } == true
                } }) refreshQuoteWatch()
                val selected = watching
                val route = selected?.first?.let(continuousRoutes::get)
                if (selected != null && route != null && now() >= route.validUntil && (route.checkedAt == null || now() - route.checkedAt >= 60_000) && continuousJob?.isActive != true) {
                    continuousJob = scope.launch {
                        val before = continuousRoutes[selected.first]?.spec?.tradeSymbol
                        refreshContinuous(selected.first)
                        if (watching == selected && before != continuousRoutes[selected.first]?.spec?.tradeSymbol) startWatch(selected)
                    }
                }
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
        watching?.takeIf { it.first in continuousRoutes }?.let { refreshContinuous(it.first, force = true); startWatch(it) }
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
                listOf("currency", "account", "cashBalance", "order", "orderVersion", "position", "fill", "fillPair", "fillFee", "contract", "orderStrategy", "orderStrategyLink", "accountRiskStatus", "userAccountAutoLiq").forEach { add(it) }
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

    /**
     * A login saved before its organisation moved gets a redirect here. Only the authenticated reply from the fixed
     * authentication host may route the session; a redirect's target never does.
     */
    private suspend fun discoverAccounts(): JsonElement = try { rest("GET", "account/list") }
    catch (moved: TradovateRemoteException) {
        if (moved.status !in 300..399) throw moved
        val previous = credentials.hosts
        try { renewSession(followHosts = true) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw moved }
        if (credentials.hosts == previous) throw moved
        rest("GET", "account/list")
    }

    /** [followHosts] only before a connection starts: a running session stays on the hosts its sockets use. */
    private suspend fun renewSession(followHosts: Boolean = false) {
        val renewed = rest("GET", "auth/renewaccesstoken").jsonObject
        val token = renewed.str("accessToken") ?: error("renewal denied")
        val expiry = renewed.str("expirationTime")?.let(::timestamp)
            ?: jwtClaims(token)?.long("exp")?.takeIf { it in 1..253_402_300_799L }?.times(1_000)
        check(expiry != null && expiry > now()) { "renewal expiry missing or invalid" }
        credentials = TradovateSession(credentials.environment, token, renewed.str("mdAccessToken") ?: credentials.mdAccessToken,
            credentials.userId, credentials.userName, expiry, credentials.marketUrl, if (followHosts) renewalHosts(renewed) else credentials.hosts)
        onSessionRenewed(credentials)
    }

    /** A renewal that names no usable hosts keeps the session's own, and its new token. */
    private fun renewalHosts(reply: JsonObject): TradovateHosts {
        val named = reply["apiHosts"] as? JsonObject
        if (named.isNullOrEmpty()) return credentials.hosts
        return try { selectTradovateHosts(credentials.environment, named) } catch (_: TradovateRemoteException) { credentials.hosts }
    }

    private suspend fun connectMarket() {
        // Web sessions use their account token on the selected market-data gateway too.
        val token = credentials.accessToken
        lock.withLock { quoteSides.clear(); quotes = emptyMap() }
        try {
            val socket = TradovateSocket(wire.socket(credentials.hosts.marketUrl), scope, ::marketEvent) {
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
                    marketReady = false
                    maintenance?.cancelAndJoin()
                    quoteWatchJob?.cancelAndJoin()
                    val pendingQuotes = lock.withLock { quoteWatchJobs.values.toList().also { quoteWatchJobs.clear() } }
                    pendingQuotes.forEach { it.cancel() }
                    pendingQuotes.joinAll()
                    continuousJob?.cancelAndJoin()
                    (watchJobs.values + chartBackfillJobs.values).toList().forEach { it.cancelAndJoin() }
                    lock.withLock { preloadJob }?.cancelAndJoin()
                    cancelledChartIds.clear()
                    pendingChartCancellations.clear()
                    accountSocket?.close(); marketSocket?.close(); marketReady = false
                    charts.clear(); preloadCharts.clear(); subscribedQuotes.clear(); discardedCharts.clear(); earlyCharts.clear()
                    volumeCharts.values.forEach { it.result.completeExceptionally(IllegalStateException("行情已重连")) }; volumeCharts.clear()
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
        maintenance?.cancel(); quoteWatchJob?.cancel(); continuousJob?.cancel(); reconnectJob?.cancel(); watchJobs.values.toList().forEach { it.cancel() }; metadataJob?.cancel(); balanceJob?.cancel(); recoveryJob?.cancel(); historyDetailsJob?.cancel()
        scope.launch {
            val pendingQuotes = lock.withLock { quoteWatchJobs.values.toList().also { quoteWatchJobs.clear() } }
            pendingQuotes.forEach { it.cancel() }
            pendingQuotes.joinAll()
            watchJobs.values.toList().joinAll()
            chartBackfillJobs.values.toList().forEach { it.cancelAndJoin() }
            lock.withLock { preloadJob }?.cancelAndJoin()
            runCatching { accountSocket?.close() }; runCatching { marketSocket?.close() }
            cacheSaveJob?.cancelAndJoin()
            saveCandleCache()
            wire.close(); scope.cancel()
        }
    }

    override suspend fun searchInstruments(query: String) {
        if (!connected || query.trim().length !in 1..32) return
        val text = query.trim().uppercase().removePrefix("@").removeSuffix("1!")
        if (text.isBlank()) return
        // Discovery must not wait for daily-volume ranking. Aliases become visible as soon
        // as their product metadata is hydrated; watch/chart selection resolves execution.
        val searchLock = lock.withLock { instrumentSearchLocks.getOrPut(text) { Mutex() } }
        val ids = searchLock.withLock {
            lock.withLock { instrumentSearches[text]?.takeIf { now() - it.fetchedAt in 0 until 30_000 } }
                ?.let { return@withLock it.ids }
            val data = rest("GET", "contract/suggest?t=${text.encodeURLParameter()}&l=100") as? JsonArray
                ?: error("合约搜索返回无效")
            val contracts = data.map { it as? JsonObject ?: error("合约搜索返回无效") }
            require(contracts.all { (it.long("id") ?: 0) > 0 && (it.long("contractMaturityId") ?: 0) > 0 && !it.str("name").isNullOrBlank() }) {
                "合约搜索返回无效"
            }
            val found = contracts.map { requireNotNull(it.long("id")) }.distinct()
            lock.withLock {
                contracts.forEach { book.apply("contract", it) }
                instrumentSearches[text] = InstrumentSearch(now(), found)
            }
            found
        }
        // Cached discovery still retries any specification that failed to hydrate.
        hydrateContracts(ids)
    }

    override fun watchQuotes(symbols: Set<String>) {
        if (stopped || quoteWatchSymbols == symbols) return
        quoteWatchSymbols = symbols.toSet()
        refreshQuoteWatch()
    }

    private fun refreshQuoteWatch() {
        quoteWatchJob?.cancel()
        quoteWatchJob = scope.launch {
            // Reconcile ownership, keeping retained symbols' in-flight discovery/ranking.
            // Adding or reordering a row must not throw away the other rows' progress.
            lock.withLock {
                (quoteWatchJobs.keys - quoteWatchSymbols).forEach { quoteWatchJobs.remove(it)?.cancel() }
            }
            if (!marketReady) return@launch
            lock.withLock {
                if (!marketReady || stopped) return@launch
                for (symbol in quoteWatchSymbols) if (quoteWatchJobs[symbol]?.isActive != true) {
                    // Each row advances as soon as its own metadata is ready. Slow searches
                    // do not prevent already-loaded symbols from confirming their leader.
                    quoteWatchJobs[symbol] = scope.launch {
                        if (instruments.none { it.symbol == symbol }) {
                            try { quoteDiscoverySlots.withPermit { withTimeout(20_000) { searchInstruments(symbol) } } }
                            catch (_: TimeoutCancellationException) { /* Keep the saved entry available for retry. */ }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { /* Other rows still load and subscribe independently. */ }
                        }
                        syncWatchQuotes()
                        if (symbol in continuousRoutes) refreshContinuous(symbol)
                        syncWatchQuotes()
                        preloadWake.trySend(Unit)
                    }
                }
            }
            syncWatchQuotes()
        }
    }

    private suspend fun syncWatchQuotes() {
        try { chartWatchLock.withLock { marketSocket?.let { syncQuoteSubscriptions(it) } } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { lock.withLock { problem = "部分自选行情订阅失败，请检查行情权限"; publish() } }
    }

    /** Called under chartWatchLock; chart and watch-list subscriptions share one owner. */
    private suspend fun syncQuoteSubscriptions(socket: TradovateSocket) {
        val requested = quoteWatchSymbols + watched.map { it.first }
        // Quotes only map to loaded specs; an unconfirmed series' last contract waits for its spec.
        val loaded = instruments.filterNot { it.isContinuous }.mapTo(HashSet()) { it.symbol }
        val wanted = (accountFlows.values.flatMap { flow ->
            flow.value.positions.map(Position::symbol) + flow.value.orders.map { it.request.symbol }
        } + instruments.filter { it.symbol in requested }.mapNotNull { it.priceSymbol }.filter { it in loaded }).toSet()
        for (old in subscribedQuotes.toList() - wanted) {
            socket.request("md/unsubscribequote", buildJsonObject { put("symbol", old) })
            subscribedQuotes.remove(old)
        }
        // One refused symbol must not leave the rest of the watch list without quotes.
        var failure: Exception? = null
        for (name in wanted - subscribedQuotes) {
            try {
                val response = socket.request("md/subscribequote", buildJsonObject { put("symbol", name) })
                val mode = response.str("mode")
                if (mode != null && mode != "RealTime") throw TradovateRemoteException(403, "行情模式 $mode，不允许使用延迟报价下单")
                subscribedQuotes.add(name)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }

    /** Read-only resolution entry point; valid routes and cached completed-day volumes are reused. */
    suspend fun refreshContinuous(symbol: String, force: Boolean = false) {
        val resolutionLock = lock.withLock { continuousLocks.getOrPut(symbol) { Mutex() } }
        resolutionLock.withLock {
            // Cached reads do not wait for unrelated products. Admission to the bounded
            // ranking pipeline happens before the per-product network timeout starts.
            if (!lock.withLock { needsContinuousCheck(symbol, force) }) return@withLock
            continuousResolutionSlots.withPermit { resolveContinuous(symbol, force) }
        }
    }

    private fun needsContinuousCheck(symbol: String, force: Boolean): Boolean {
        if (!connected || !marketReady || stopped) return false
        val route = continuousRoutes[symbol] ?: return false
        val time = now()
        if (!force && time < route.validUntil && route.checkedAt?.let { time >= it } == true) return false
        if (!force && route.validUntil == 0L && route.checkedAt?.let { time - it in 0 until 60_000 } == true) return false
        return true
    }

    /** Only callers for the same symbol wait on each other; daily chart capacity is shared globally. */
    private suspend fun resolveContinuous(symbol: String, force: Boolean) {
        if (!needsContinuousCheck(symbol, force)) return
        val resolutionSocket = marketSocket ?: return
        val route = continuousRoutes[symbol] ?: return
        // The last confirmed contract keeps its quotes on screen; orders still wait for this check.
        val shown = route.spec.tradeSymbol ?: route.spec.quoteSymbol
        suspend fun checking(status: String) = lock.withLock {
            check(marketReady && marketSocket === resolutionSocket) { "行情连接已变化，请重新核实" }
            continuousRoutes = continuousRoutes + (symbol to route.copy(spec = route.spec.copy(tradeSymbol = null, quoteSymbol = shown, tradeSymbolStatus = status)))
            rebuild()
        }
        var target: Instrument? = null
        var validUntil = 0L
        var reason = ""
        try {
            checking("正在核实主力")
            val leader = withTimeout(180_000) { rankContinuous(route.productId, symbol) { done, total -> checking("正在核实主力 $done/$total") } }
            require(now() < leader.session.validUntil) { "查询期间交易日已切换，请重新查询" }
            lock.withLock { book.apply("contractMaturity", leader.maturity); book.apply("contract", leader.contract) }
            val id = requireNotNull(leader.contract.long("id"))
            hydrateContracts(listOf(id))
            target = lock.withLock { contractSpecs[id] }
            require(target != null) { "主力合约规格尚未加载" }
            validUntil = if (leader.session.refreshCompletedVolume) minOf(leader.session.validUntil, now() + CALENDAR_TAIL_REFRESH_MS)
                else leader.session.validUntil
        } catch (_: TimeoutCancellationException) { reason = "日成交量查询超时" }
        catch (cancel: CancellationException) {
            // Nothing was learned: keep the previous mapping and validity instead of a stuck "checking" state.
            withContext(NonCancellable) { lock.withLock {
                if (marketSocket === resolutionSocket) { continuousRoutes = continuousRoutes + (symbol to route); rebuild() }
            } }
            throw cancel
        }
        catch (e: Exception) { reason = safeMessage(e.message ?: "日成交量不可用") }
        lock.withLock {
            if (stopped || !marketReady || marketSocket !== resolutionSocket) return
            if (target != null && now() >= validUntil) {
                target = null; validUntil = 0
                reason = "查询期间交易日已切换，请重新查询"
            }
            val confirmed = target?.symbol
            val spec = (target ?: route.spec).copy(symbol = symbol, category = "连续合约", isContinuous = true, tradeSymbol = confirmed,
                tradeSymbolStatus = if (confirmed == null) "主力无法确认：$reason" else null, quoteSymbol = confirmed ?: shown)
            continuousRoutes = continuousRoutes + (symbol to route.copy(spec = spec, checkedAt = now(), validUntil = validUntil))
            if (confirmed == null) problem = "$symbol 主力无法确认：$reason；连续合约下单已暂停"
            else {
                if (problem.startsWith("$symbol 主力无法确认")) problem = "$symbol 成交量主力已确认：$confirmed"
                continuousEvidence.lead(symbol, confirmed)
            }
            rebuild()
        }
        // Concurrent symbols must not persist an older snapshot over a newer result.
        continuousEvidenceSaveLock.withLock { continuousEvidence.save(lock.withLock { continuousEvidence.snapshot() }) }
    }

    /**
     * Queries only months without a cached volume for the trade date. Cached values cannot show a
     * volume correction, so a calendar tail freshly rechecks the leader on that same completed date.
     */
    private suspend fun rankContinuous(productId: Long, symbol: String, progress: suspend (Int, Int) -> Unit): VolumeLeader {
        val root = symbol.removePrefix("@")
        val completedChecked = mutableSetOf<String>()
        suspend fun rank(cached: Boolean) = selectVolumeLeader(productId, root, now(),
            read = { path -> rest("GET", path) as? JsonArray ?: error("合约或交易时段返回无效") },
            volume = { name, session ->
                (if (cached) lock.withLock { continuousEvidence.volume(name, session.day) } else null)
                    ?: completedDailyVolume(name, session).also { count -> lock.withLock {
                        continuousEvidence.remember(name, session.day, count)
                        if (session.refreshCompletedVolume) completedChecked.add(name)
                    } }
            }, progress = progress)
        val leader = rank(cached = true)
        val name = requireNotNull(leader.contract.str("name"))
        if (!leader.session.refreshCompletedVolume || lock.withLock { name in completedChecked }) return leader
        if (completedDailyVolume(name, leader.session) == leader.volume) return leader
        // A corrected completed volume makes this product's cached values for the date suspect.
        lock.withLock { continuousEvidence.forget(root, leader.session.day) }
        return rank(cached = false)
    }

    /** One trade date per request, always cancelled afterwards: rankings never hold server chart slots. */
    private suspend fun completedDailyVolume(symbol: String, session: CompletedSession): Long = volumeSlots.withPermit {
        val socket = checkNotNull(marketSocket) { "行情连接不可用" }
        check(marketReady) { "行情尚未就绪" }
        val overflow = lock.withLock { earlyChartOverflow }
        val body = buildJsonObject {
            put("symbol", symbol)
            put("chartDescription", chartDescription(Timeframe.D1))
            put("timeRange", dailyVolumeRange(session))
        }
        val response = requestMarketChart(socket, body)
        val ids = listOfNotNull(response["historicalId"] as? JsonPrimitive, response["realtimeId"] as? JsonPrimitive).distinct()
        try {
            require(response.str("mode") == null || response.str("mode") == "RealTime") { "日成交量行情权限或模式不可用" }
            require(!session.refreshCompletedVolume || response.str("mode") == "RealTime") { "核实交易日历末尾需要实时日线权限" }
            val collector = DailyVolumeHistory(session.day, allowEmpty = response.str("mode") == "RealTime")
            val key = requireNotNull(chartSubscriptionKey(response["historicalId"])) { "日成交量历史通道缺失" }
            lock.withLock {
                require(overflow == earlyChartOverflow) { "日成交量历史包丢失，请重试" }
                // Background reads retire many subscriptions; an identifier the server hands out again is a new one.
                ids.mapNotNull(::chartSubscriptionKey).forEach { discardedCharts.remove(it); cancelledChartIds.remove(it); pendingChartCancellations.remove(it) }
                volumeCharts[key] = collector
                // Also observe target-date corrections before cancellation; newer dates do not enter the ranking.
                chartSubscriptionKey(response["realtimeId"])?.takeIf { it != key }?.let {
                    if (session.refreshCompletedVolume) volumeCharts[it] = collector else discardedCharts.add(it)
                }
                if (session.refreshCompletedVolume) requireNotNull(chartSubscriptionKey(response["realtimeId"])) { "核实交易日历末尾需要实时日线通道" }
                drainEarlyCharts()
            }
            val count = withTimeout(15_000) { collector.result.await() }
            require(!collector.invalidated) { "日线交易日或成交量已更新，请重新查询" }
            return@withPermit count
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    ids.mapNotNull(::chartSubscriptionKey).forEach { volumeCharts.remove(it); discardedCharts.add(it) }
                    if (discardedCharts.size > 1024) discardedCharts.clear()
                }
                for (id in ids) withTimeoutOrNull(2_000) { runCatching { socket.request("md/cancelChart", buildJsonObject { put("subscriptionId", id) }) } }
            }
        }
    }

    private fun validateContinuous(request: OrderRequest) {
        require(!request.symbol.startsWith("@")) { "连续合约不能直接下单，请等待主力合约解析" }
        val source = request.sourceSymbol ?: return
        val route = continuousRoutes[source] ?: errorArgument("连续合约映射尚未加载")
        require(route.checkedAt?.let { now() >= it && now() < route.validUntil } == true && route.spec.tradeSymbol != null) { "主力成交量依据已过期或无法确认，请重新选择连续合约" }
        require(route.spec.tradeSymbol == request.symbol) { "主力合约已换月，请检查新合约并重新确认订单" }
    }

    private suspend fun hydrateContracts(priority: List<Long>? = null) {
        val ids = lock.withLock {
            (priority ?: (book.list("position").filter { (it.long("netPos") ?: 0) != 0L }.mapNotNull { it.long("contractId") } +
                book.list("order").filter(::activeOrder).mapNotNull { it.long("contractId") } +
                book.list("contract").mapNotNull { it.long("id") })).distinct().filter { it !in contractSpecs }.take(100)
        }
        // A root search returns many dated contracts. Fetch their missing maturities
        // in batches instead of spending one paced REST round trip on each month.
        val missingMaturities = lock.withLock {
            ids.mapNotNull { book.item("contract", it)?.long("contractMaturityId") }
                .distinct().filter { book.item("contractMaturity", it) == null }
        }
        if (missingMaturities.size > 1) for (chunk in missingMaturities.chunked(50)) {
            try {
                // Batch and single-item requests share locks, including across concurrent searches.
                // Consistent acquisition order prevents overlapping batches from deadlocking.
                val batchLocks = lock.withLock { chunk.sorted().map { metadataEntityLocks.getOrPut("contractMaturity" to it) { Mutex() } } }
                suspend fun loadBatch(index: Int) {
                    if (index < batchLocks.size) return batchLocks[index].withLock { loadBatch(index + 1) }
                    val missing = lock.withLock { chunk.filter { book.item("contractMaturity", it) == null } }
                    if (missing.isEmpty()) return
                    val rows = (rest("GET", "contractMaturity/items?ids=${missing.joinToString(",")}") as? JsonArray)
                        ?.map { it.jsonObject } ?: error("合约月份批量查询返回无效")
                    require(rows.all { it.long("id") in missing && (it.long("productId") ?: 0) > 0 } &&
                        rows.map { it.long("id") }.distinct().size == rows.size) { "合约月份批量查询不匹配" }
                    lock.withLock { rows.forEach { book.apply("contractMaturity", it) } }
                }
                loadBatch(0)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Missing rows still use the validated per-item path below. */ }
        }
        // Different contracts can share maturity/product/currency rows. Serialize only those
        // cache misses, never the entire network load, so each shared row is fetched once.
        suspend fun entity(type: String, entityId: Long): JsonObject {
            val entityLock = lock.withLock { metadataEntityLocks.getOrPut(type to entityId) { Mutex() } }
            return entityLock.withLock {
                lock.withLock { book.item(type, entityId) }?.let { return it }
                val item = rest("GET", "$type/item?id=$entityId").jsonObject
                require(item.long("id") == entityId) { "合约元数据标识不匹配" }
                lock.withLock { book.apply(type, item) }
                item
            }
        }
        val slots = Semaphore(4)
        coroutineScope { ids.map { id -> launch {
            val contractLock = lock.withLock { metadataContractLocks.getOrPut(id) { Mutex() } }
            contractLock.withLock {
                if (lock.withLock { id in contractSpecs }) return@withLock
                slots.withPermit {
                    try {
                        val contract = entity("contract", id)
                        val maturity = entity("contractMaturity", contract.long("contractMaturityId") ?: return@withPermit)
                        val product = entity("product", maturity.long("productId") ?: return@withPermit)
                        val currency = entity("currency", product.long("currencyId") ?: return@withPermit).str("name") ?: return@withPermit
                        val spec = mapInstrument(contract, maturity, product, currency) ?: return@withPermit
                        lock.withLock {
                            contractSpecs[id] = spec
                            val root = product.str("name")
                            if (root != null && Regex("[A-Z0-9]{1,16}").matches(root)) {
                                val symbol = "@$root"
                                // A remembered leader the server still lists shows quotes before this session's check.
                                val remembered = continuousEvidence.leader(symbol)?.takeIf { name -> book.list("contract").any { it.str("name") == name } }
                                if (symbol !in continuousRoutes) continuousRoutes = continuousRoutes + (symbol to ContinuousRoute(
                                    requireNotNull(product.long("id")), spec.copy(symbol = symbol, category = "连续合约", isContinuous = true, quoteSymbol = remembered)))
                            }
                            rebuild()
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { lock.withLock { problem = "部分合约规格无法加载，缺失规格的品种不可交易" } }
                }
            }
        } }.joinAll() }
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
                // Display data: it moves the drawdown figures and nothing about orders or positions.
                if (data.str("entityType") in DRAWDOWN_ENTITY_TYPES) { rebuild(); return }
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
    override fun watch(symbol: String, timeframe: Timeframe) = watchAll(listOf(symbol to timeframe))

    override fun watchAll(series: List<Pair<String, Timeframe>>) {
        if (stopped) return
        val next = series.distinct()
        val previous = watched
        if (previous.firstOrNull()?.first != next.firstOrNull()?.first) { continuousJob?.cancel(); continuousJob = null }
        watched = next
        (previous - next.toSet()).forEach(::stopWatch)
        // The chart the user works on is asked for again on every call, as before; the others only when they are new.
        next.forEachIndexed { index, one -> if (index == 0 || one !in previous) startWatch(one) }
    }

    /** Ends the live chart of a series no window shows any more. */
    private fun stopWatch(series: Pair<String, Timeframe>) {
        watchJobs.remove(series)?.cancel()
        chartBackfillJobs.remove(series)?.cancel()
        scope.launch {
            chartWatchLock.withLock {
                val socket = marketSocket ?: return@launch
                // Asked for again while this waited: the new watch owns the series' charts.
                if (stopped || series in watched) return@launch
                withContext(NonCancellable) { cancelCharts(socket, series = series) }
            }
        }
    }

    private fun startWatch(series: Pair<String, Timeframe>) {
        val (symbol, timeframe) = series
        watchJobs[series]?.cancel()
        watchJobs[series] = scope.launch {
            chartWatchLock.withLock {
                // The chart reads the series it shows itself; the background read of it would repeat every request.
                lock.withLock { if (preloading == series) preloadSeriesJob?.cancel() }
                preloadWake.trySend(Unit)
                val cached = lock.withLock { cachedHistory[series] } ?: candleCache.read(series, now()).also { bars ->
                    lock.withLock {
                        cachedHistory[series] = bars
                        history = history + (series to bars)
                        publish()
                    }
                }
                val socket = marketSocket ?: return@launch
                if (!marketReady) return@launch
                try {
                    // The leader of the chart being worked on is checked here; the other charts' symbols are checked with the watch list.
                    if (series == watching && symbol in continuousRoutes && continuousJob?.isActive != true) continuousJob = scope.launch {
                        val before = continuousRoutes[symbol]?.spec?.tradeSymbol
                        refreshContinuous(symbol)
                        if (watching == series && before != continuousRoutes[symbol]?.spec?.tradeSymbol) startWatch(series)
                    }
                    syncQuoteSubscriptions(socket)
                    if (lock.withLock { charts.values.any { it.page.let { page -> page.series == series &&
                            !page.load.failed && (page.load.complete || now() - page.startedAt < 30_000) } } }) return@launch
                    // Serialize both cancellation and binding so rapid switches cannot orphan IDs.
                    withContext(NonCancellable) {
                        // This series' own stale charts, and those of series that no chart shows any more.
                        cancelCharts(socket, keep = watched.filter { it != series })
                        if (series !in watched || stopped || !marketReady) return@withContext
                        val load = ChartLoad(cached, chartHistoryFloor(timeframe, now()))
                        requestChart(socket, ChartPage(series, load, now(), liveOnly = cached.isNotEmpty(), historyOnly = false), chartTimeRange(cached))
                        if (cached.isNotEmpty() && series in watched && !stopped) {
                            lock.withLock { load.beginPage() }
                            requestChart(socket, ChartPage(series, load, now(), liveOnly = false, historyOnly = true),
                                chartBackfillRange(cached.last().timeMs, now()))
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    problem = "账户已连接；行情/历史订阅请求超时，可重新选择品种或周期重试"
                    publish()
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { problem = safeMessage(error.message ?: "行情/历史订阅失败"); publish() }
            }
        }
    }

    /** Both continuous history and volume history share the server's chart request budget. */
    private suspend fun requestMarketChart(socket: TradovateSocket, body: JsonObject): JsonObject {
        suspend fun request(value: JsonObject): JsonObject {
            // A rejected request creates no subscription; slots free up as other charts are cancelled.
            for (wait in listOf(2_000L, 4_000L)) {
                try { return socket.request("md/getChart", value) }
                catch (error: TradovateRemoteException) {
                    if (error.message?.contains("simultaneous chart", ignoreCase = true) != true) throw error
                }
                delay(wait)
            }
            return socket.request("md/getChart", value)
        }
        var response = request(body)
        response.str("p-ticket")?.let { ticket ->
            require(response.bool("p-captcha") != true) { "历史行情查询限流，需要稍后在官方应用重试" }
            val seconds = requireNotNull(response.long("p-time")?.takeIf { it in 0..30 }) { "历史行情查询限流，等待服务端恢复" }
            delay(seconds * 1_000)
            response = request(JsonObject(body + ("p-ticket" to JsonPrimitive(ticket))))
        }
        require(response.str("p-ticket") == null) { "历史行情查询仍受限，请稍后重试" }
        require(chartSubscriptionKey(response["historicalId"]) != null || chartSubscriptionKey(response["realtimeId"]) != null) { "历史行情通道缺失" }
        return response
    }

    /** Called under chartWatchLock and NonCancellable, including response-to-ID registration. */
    private suspend fun requestChart(socket: TradovateSocket, page: ChartPage, range: JsonObject) {
        val overflow = lock.withLock { earlyChartOverflow }
        val response = try {
            requestMarketChart(socket, buildJsonObject {
                put("symbol", page.series.first)
                put("chartDescription", chartDescription(page.series.second))
                put("timeRange", range)
            })
        } catch (error: Exception) {
            lock.withLock { page.load.accept(emptyList(), historical = true, endOfHistory = false, validPacket = false) }
            // An ACK arriving after request timeout cannot be correlated. Closing the socket
            // releases every server subscription, including IDs that never reached this client.
            if (error is TimeoutCancellationException || (error is TradovateRemoteException && error.status == 0)) resetChartConnection(socket)
            throw error
        }
        lock.withLock {
            if (overflow != earlyChartOverflow) page.load.accept(emptyList(), historical = true, endOfHistory = false, validPacket = false)
            for (field in listOf("historicalId", "realtimeId")) {
                val id = response[field] as? JsonPrimitive ?: continue
                val key = chartSubscriptionKey(id) ?: continue
                charts[key] = ChartChannel(id, page, field == "historicalId",
                    chartSubscriptionKey(response["historicalId"]) == chartSubscriptionKey(response["realtimeId"]))
                cancelledChartIds.remove(key)
                pendingChartCancellations.remove(key)
            }
            val pending = earlyCharts.toList()
            earlyCharts.clear()
            pending.forEach(::chartEvent)
            publish()
        }
    }

    /**
     * Cancels chart subscriptions: those of [page] alone, those of [series] alone, or otherwise every one whose series is not
     * in [keep].
     */
    private suspend fun cancelCharts(socket: TradovateSocket, page: ChartPage? = null, series: Pair<String, Timeframe>? = null, keep: List<Pair<String, Timeframe>> = emptyList()) {
        val pending = lock.withLock {
            val retired = charts.filterValues { when {
                page != null -> it.page === page
                series != null -> it.page.series == series
                else -> it.page.series !in keep
            } }
            cancelledChartIds.addAll(retired.keys)
            retired.forEach { (key, channel) -> pendingChartCancellations[key] = channel.wireId; charts.remove(key) }
            earlyCharts.removeAll { chartSubscriptionKey(it["id"]) in cancelledChartIds }
            pendingChartCancellations.toMap()
        }
        try {
            withTimeout(5_000) {
                for ((key, id) in pending) {
                    try {
                        socket.request("md/cancelChart", buildJsonObject { put("subscriptionId", id) })
                        lock.withLock { pendingChartCancellations.remove(key) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Keep failed IDs for the next cleanup; attempt the other IDs too. */ }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Bound the whole cleanup, rather than waiting 15 seconds for each old ID.
            resetChartConnection(socket)
        }
    }

    private suspend fun resetChartConnection(socket: TradovateSocket) {
        if (marketSocket !== socket) return
        lock.withLock { marketReady = false; problem = "历史订阅超时，正在重建行情连接"; publish() }
        runCatching { socket.close() }
        scheduleReconnect()
    }

    private fun continueChartHistory(page: ChartPage) {
        chartBackfillJobs[page.series] = scope.launch {
            chartWatchLock.withLock {
                val socket = marketSocket ?: return@launch
                if (stopped || !marketReady || page.series !in watched || lock.withLock { charts.values.none { it.page === page } }) return@launch
                withContext(NonCancellable) {
                    // A fresh chart's first page also carries the live stream.
                    if (page.historyOnly) cancelCharts(socket, page)
                    if (!marketReady) return@withContext
                    val before = lock.withLock { page.load.nextPageBefore }
                    if (before != null && !page.load.failed && page.series in watched && !stopped) {
                        lock.withLock { page.load.beginPage() }
                        try {
                            requestChart(socket, ChartPage(page.series, page.load, page.startedAt, liveOnly = false, historyOnly = true),
                                chartBackfillRange(requireNotNull(page.load.floor), before))
                        } catch (_: Exception) {
                            lock.withLock {
                                page.load.accept(emptyList(), historical = true, endOfHistory = false, validPacket = false)
                                problem = if (page.load.anchor != null) "历史补取失败，已保留原缓存；可重新选择品种或周期重试" else "更早的历史读取失败，可重新选择品种或周期重试"
                                publish()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun preloadHistory(series: List<Pair<String, Timeframe>>) {
        if (stopped) return
        preloadTargets.value = series.distinct()
        startPreload()
    }

    /** One reader per connection: a reconnect ends the old one before the new connection starts its own. */
    private fun startPreload() {
        if (stopped || preloadTargets.value.isEmpty()) return
        scope.launch {
            lock.withLock { if (!stopped && preloadJob?.isActive != true) preloadJob = scope.launch { preloadLoop() } }
            preloadWake.trySend(Unit)
        }
    }

    /** The disk first, so that every listed chart opens from memory; then the series the cache lacks, one at a time. */
    private suspend fun preloadLoop() {
        while (!stopped) {
            val targets = preloadTargets.value
            for (series in targets) {
                if (lock.withLock { cachedHistory[series] } != null) continue
                val bars = candleCache.read(series, now())
                lock.withLock {
                    if (cachedHistory[series] == null) {
                        cachedHistory[series] = bars
                        if (bars.isNotEmpty() && series !in history) history = history + (series to bars)
                    }
                }
            }
            val next = lock.withLock {
                val time = now()
                // Leader checks share the server's chart budget and gate orders, so they go first.
                val verifying = quoteWatchJobs.values.any { it.isActive } || continuousJob?.isActive == true
                if (!marketReady || marketSocket == null || time < preloadPausedUntil || verifying) null
                else targets.firstOrNull { it !in watched && cachedHistory[it]?.isEmpty() == true && (preloadRetryAt[it] ?: 0) <= time }
            }
            if (next == null) {
                withTimeoutOrNull(PRELOAD_IDLE_MS) { preloadWake.receive() }
                continue
            }
            try {
                coroutineScope {
                    val reader = launch(start = CoroutineStart.LAZY) { preloadSeries(next) }
                    // The chart reads the series it shows; watch() ends this reader when the chart opens its series.
                    val owned = lock.withLock { (next !in watched).also { if (it) { preloading = next; preloadSeriesJob = reader } } }
                    if (owned) reader.join() else reader.cancel()
                }
            } finally {
                withContext(NonCancellable) { lock.withLock { preloading = null; preloadSeriesJob = null } }
            }
        }
    }

    private suspend fun preloadSeries(series: Pair<String, Timeframe>) {
        val bars = try { readSeries(series) }
            catch (_: TimeoutCancellationException) { null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        lock.withLock {
            val time = now()
            when {
                bars == null -> {
                    // The other series go first. What refused this one (a rate limit, a lost connection) would refuse
                    // them too, so the whole reader also waits, longer after each failure in a row.
                    val failures = (preloadFailures[series] ?: 0) + 1
                    preloadFailures[series] = failures
                    preloadRetryAt[series] = time + PRELOAD_RETRY_MS * minOf(failures, 6)
                    preloadPausedUntil = maxOf(preloadPausedUntil, time + minOf(PRELOAD_PAUSE_MS shl minOf(preloadFailureStreak++, 5), 2 * PRELOAD_RETRY_MS))
                }
                // The server has no bars for it; asking again at once would never end.
                bars.isEmpty() -> { preloadFailureStreak = 0; preloadRetryAt[series] = time + PRELOAD_EMPTY_RETRY_MS }
                else -> {
                    preloadFailureStreak = 0
                    preloadFailures.remove(series)
                    preloadRetryAt.remove(series)
                    // Opened meanwhile: the chart has its own load of this series, and shows that.
                    if (series in watched) return
                    cachedHistory[series] = bars
                    history = history + (series to bars)
                    dirtyHistory[series] = bars
                    cacheRevision++
                    scheduleCandleCacheSave()
                }
            }
        }
    }

    /** The requests a fresh chart makes, without the chart. Null when the series could not be read whole. */
    private suspend fun readSeries(series: Pair<String, Timeframe>): List<Candle>? {
        val load = ChartLoad(emptyList(), chartHistoryFloor(series.second, now()))
        var range = chartTimeRange(emptyList())
        var historyOnly = false
        while (true) {
            lock.withLock { load.beginPage() }
            preloadPage(series, load, range, historyOnly)
            val before = lock.withLock {
                if (load.failed) return null
                if (load.complete) return load.candles
                load.nextPageBefore ?: return null
            }
            range = chartBackfillRange(requireNotNull(load.floor), before)
            historyOnly = true
        }
    }

    /** One page of a background read. Whatever ends the page, its subscription does not outlive it. */
    private suspend fun preloadPage(series: Pair<String, Timeframe>, load: ChartLoad, range: JsonObject, historyOnly: Boolean) {
        val socket = checkNotNull(marketSocket) { "行情连接不可用" }
        val page = PreloadPage(load, historyOnly)
        var ids = emptyList<JsonPrimitive>()
        var retired = true
        try {
            // An acknowledgement arriving after a cancellation would leave a subscription this client never learns of.
            withContext(NonCancellable) {
                check(marketReady && !stopped) { "行情尚未就绪" }
                val overflow = lock.withLock { earlyChartOverflow }
                val response = try {
                    requestMarketChart(socket, buildJsonObject {
                        put("symbol", series.first)
                        put("chartDescription", chartDescription(series.second))
                        put("timeRange", range)
                    })
                } catch (error: Exception) {
                    // As for a chart: only closing the socket releases a subscription whose identifiers never arrived.
                    // The quotes and orders use that connection too, so the reader then stays away from it for a while.
                    if (error is TimeoutCancellationException) lock.withLock { preloadPausedUntil = now() + PRELOAD_RESET_PAUSE_MS }
                    if (error is TimeoutCancellationException || (error is TradovateRemoteException && error.status == 0)) resetChartConnection(socket)
                    throw error
                }
                ids = listOfNotNull(response["historicalId"] as? JsonPrimitive, response["realtimeId"] as? JsonPrimitive).distinct()
                lock.withLock {
                    page.historicalKey = chartSubscriptionKey(response["historicalId"])
                    page.shared = page.historicalKey == chartSubscriptionKey(response["realtimeId"])
                    for (key in ids.mapNotNull(::chartSubscriptionKey)) {
                        preloadCharts[key] = page
                        cancelledChartIds.remove(key); pendingChartCancellations.remove(key); discardedCharts.remove(key)
                    }
                    if (overflow != earlyChartOverflow) load.accept(emptyList(), historical = true, endOfHistory = false, validPacket = false)
                    drainEarlyCharts()
                }
            }
            if (lock.withLock { !load.failed }) withTimeout(PRELOAD_PAGE_TIMEOUT_MS) { page.ended.await() }
        } finally {
            withContext(NonCancellable) { retired = cancelPreloadCharts(socket, ids) }
        }
        // Reading on would open one more subscription for each one the server keeps.
        check(retired) { "历史订阅未能取消" }
    }

    /**
     * Retires a background page's subscriptions and says whether the server confirmed it. What it did not confirm
     * stays pending for the next cleanup, here or the chart's; the connection the quotes use is not reset for it.
     */
    private suspend fun cancelPreloadCharts(socket: TradovateSocket, ids: List<JsonPrimitive>): Boolean {
        val pending = lock.withLock {
            for (id in ids) {
                val key = chartSubscriptionKey(id) ?: continue
                preloadCharts.remove(key)
                cancelledChartIds.add(key)
                pendingChartCancellations[key] = id
            }
            earlyCharts.removeAll { chartSubscriptionKey(it["id"]) in cancelledChartIds }
            pendingChartCancellations.toMap()
        }
        withTimeoutOrNull(5_000) {
            for ((key, id) in pending) {
                try {
                    socket.request("md/cancelChart", buildJsonObject { put("subscriptionId", id) })
                    lock.withLock { pendingChartCancellations.remove(key) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Stays pending. */ }
            }
        }
        return lock.withLock { ids.none { chartSubscriptionKey(it) in pendingChartCancellations } }
    }

    /** Coalesce streaming updates; failed writes stay dirty for the next update or disconnect. */
    private fun scheduleCandleCacheSave() {
        if (cacheSaveJob?.isActive == true) return
        cacheSaveJob = scope.launch {
            delay(1_000)
            val revision = lock.withLock { cacheRevision }
            saveCandleCache()
            lock.withLock {
                cacheSaveJob = null
                if (revision != cacheRevision && dirtyHistory.isNotEmpty()) scheduleCandleCacheSave()
            }
        }
    }

    private suspend fun saveCandleCache() {
        val pending = lock.withLock { dirtyHistory.toMap() }
        for ((series, bars) in pending) {
            try {
                candleCache.write(series, bars)
                lock.withLock { if (dirtyHistory[series] === bars) dirtyHistory.remove(series) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Disposable cache failure must not interrupt market data. */ }
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
        val subscription = chartSubscriptionKey(chart["id"])
        if (subscription in cancelledChartIds) return
        volumeCharts[subscription]?.let { collector ->
            collector.accept(chart)
            return
        }
        preloadCharts[subscription]?.let { page ->
            // Nothing of a background read reaches the chart or the cache before its last page has ended.
            if (page.ended.isCompleted) return
            val incoming = chartBars(chart)
            val endOfHistory = chart.bool("eoh") == true
            val historical = page.historyOnly || endOfHistory ||
                if (page.shared) chart.str("s") !in listOf("", "s") else subscription == page.historicalKey
            page.load.accept(incoming, historical, endOfHistory, incoming.size == chart.array("bars").size)
            if (endOfHistory || page.load.failed) page.ended.complete(Unit)
            return
        }
        if (subscription in discardedCharts) return
        val channel = subscription?.let { charts[it] }
        if (channel == null) {
            if (earlyCharts.size < 256) earlyCharts.add(chart) else earlyChartOverflow++
            return
        }
        val page = channel.page
        if (page.historyOnly && page.ended) return
        val key = page.series
        val incoming = chartBars(chart)
        val endOfHistory = !page.liveOnly && !page.ended && chart.bool("eoh") == true
        val historical = page.historyOnly || endOfHistory ||
            if (channel.shared) chart.str("s") == "db" || (chart.str("s") !in listOf("", "s") && !page.ended) else channel.historical
        page.load.accept(incoming, historical, endOfHistory, incoming.size == chart.array("bars").size, countForBackfill = !page.liveOnly)
        val bars = page.load.candles
        // A cached chart still reading the bars it lacks would draw the live bar right after its stale last one.
        val joining = page.load.anchor != null && !page.load.complete && !page.load.failed && now() - page.startedAt < CHART_JOIN_HOLD_MS
        // The server replays a page newest day first; shown packet by packet, a chart would fill from right to left.
        if (!joining && (chart.str("s") != "db" || chart.bool("eoh") == true)) history = history + (key to bars)
        if (page.load.complete && bars.isNotEmpty()) {
            cachedHistory[key] = bars
            dirtyHistory[key] = bars
            cacheRevision++
            scheduleCandleCacheSave()
        }
        if (!page.ended && chart.bool("eoh") == true) {
            page.ended = true
            if (page.load.failed) problem = "历史补取未接上缓存，已保留原缓存；可重新选择品种或周期重试"
            if (page.historyOnly || !page.liveOnly && page.load.nextPageBefore != null) continueChartHistory(page)
        }
    }

    private fun chartBars(chart: JsonObject): List<Candle> = chart.array("bars").mapNotNull { bar ->
        val time = bar.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null
        val o = bar.priceValue("open") ?: return@mapNotNull null
        val h = bar.priceValue("high") ?: return@mapNotNull null
        val l = bar.priceValue("low") ?: return@mapNotNull null
        val c = bar.priceValue("close") ?: return@mapNotNull null
        if (l <= 0 || h < maxOf(o, c) || l > minOf(o, c)) return@mapNotNull null
        val volume = listOfNotNull(bar.long("upVolume"), bar.long("downVolume")).takeIf { it.isNotEmpty() }?.sum()?.takeIf { it >= 0 }
        Candle(time, o, h, l, c, volume)
    }

    private fun drainEarlyCharts() {
        val pending = earlyCharts.toList()
        earlyCharts.clear()
        pending.forEach(::chartEvent)
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
                    role = relation.role, positionId = if (protection != null && id in protection.confirmed) "$aid:$contract" else null, editable = editable, note = note,
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
            val drawdown = accountDrawdown(tradovateDrawdownRule(book.item("accountRiskStatus", aid), book.item("userAccountAutoLiq", aid)),
                tradovateAccountValue(estimatedEquity, finance?.moneyValue("netLiq"), positions.isEmpty()))
            val snapshot = AccountSnapshot(account.id, balance ?: 0, equity ?: 0, margin ?: 0, positions, pending,
                trades = pairedTrades(book, aid, contractSpecs, fillFees), events = localEvents[account.id] ?: emptyList(), executions = fills,
                balanceKnown = balance != null, equityKnown = equity != null, marginKnown = margin != null, equityEstimated = estimatedEquity != null && positions.isNotEmpty(),
                dailyPnl = financials[aid]?.dailyPnl, drawdown = drawdown)
            nextFlows.getOrPut(account.id) { MutableStateFlow(snapshot) }.value = snapshot
        }
        incompleteAccounts = incomplete.toSet()
        accountFlows = nextFlows.toMap()
        mutableCatalog.value = BrokerCatalog((contractSpecs.values + continuousRoutes.values.map { it.spec }).distinctBy { it.symbol }.sortedBy { it.symbol }, mappedAccounts)
        publish()
    }

    /**
     * The account state a quantity limit depends on. A limit found while this changes is thrown away; book.revision would
     * not do, as it also moves for every contract lookup and history row, which voided nearly every search of a dozen seconds.
     */
    private fun capacityBasis(accountId: Long): List<Any?> = listOf(
        book.list("position").filter { it.long("accountId") == accountId }.map { it.long("contractId") to it.long("netPos") },
        book.list("order").filter { it.long("accountId") == accountId && activeOrder(it) }.map { order ->
            listOf(order.long("id"), order.str("ordStatus"), order.long("id")?.let { book.orderVersion(it) }?.long("orderQty"))
        },
        book.list("cashBalance").filter { it.long("accountId") == accountId }.map { it.long("id") to it["amount"] },
    )

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
        wire.request(credentials.hosts, credentials.accessToken, method, path, body)
    }

    override fun openSession(accountId: String): AccountSession {
        val info = accounts.firstOrNull { it.id == accountId } ?: error("当前授权中没有该账户")
        val flow = accountFlows.getValue(accountId)
        return object : AccountSession {
            override val account = info
            override val state: StateFlow<AccountSnapshot> = flow.asStateFlow()
            override fun preview(request: OrderRequest) = estimate(accountId, request)
            override suspend fun orderCapacity(request: OrderRequest, lastKnown: Long?): OrderCapacity = capacityLock.withLock {
                try { validateContinuous(request) }
                catch (e: IllegalArgumentException) { return@withLock OrderCapacity(message = e.message) }
                if (!market.value.tradingReady || account.readOnly || accountId in incompleteAccounts)
                    return@withLock OrderCapacity(message = "账户或行情尚未就绪，无法确认可下单数量")
                val contractId = lock.withLock { contractSpecs.entries.firstOrNull { it.value.symbol == request.symbol }?.key }
                    ?: return@withLock OrderCapacity(message = "合约规格尚未加载")
                if (request.kind != OrderKind.MARKET && request.entryPrice?.let { it > 0 && it % contractSpecs.getValue(contractId).tickSize == 0L } != true)
                    return@withLock OrderCapacity(message = "请先输入有效的订单价格")
                val basis = lock.withLock { capacityBasis(accountId.toLong()) }
                val result = discoverOrderCapacity(lastKnown) { quantity ->
                    // Keep this endpoint below its documented 500/hour budget, with >=1s between calls.
                    val time = now()
                    capacityChecks.removeAll { time - it >= 3_600_000 }
                    check(capacityChecks.size < 450) { "数量校验频率受限" }
                    capacityChecks.lastOrNull()?.let { delay((1_000 - (time - it)).coerceIn(0, 1_000)) }
                    // Dryrun evaluates server-side risk without sending an order. A quiet/closed
                    // market's last local quote must not prevent quantity selection; estimate()
                    // still enforces fresh quotes before any actual trading command.
                    check(!stopped && market.value.tradingReady && lock.withLock { capacityBasis(accountId.toLong()) == basis }) { "账户状态已变化" }
                    validateContinuous(request)
                    capacityChecks.add(now())
                    rest("POST", "order/dryrun", capacityBody(accountId.toLong(), contractId, request, quantity)).jsonObject
                }
                if (!market.value.tradingReady || lock.withLock { capacityBasis(accountId.toLong()) != basis })
                    OrderCapacity(message = "账户状态已变化，请刷新数量上限")
                else result
            }
            override fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable) return OrderPreview(null, errors = listOf("该单不是可编辑入场单；保护单请使用仓位止损/止盈入口，关联待确认的订单不可改价"))
                return estimate(accountId, order.request.copy(entryPrice = entryPrice), modifying = true)
            }
            override fun previewOrderProtection(orderId: String, stopLoss: Long?, takeProfit: Long?): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                if (order.role != OrderRole.ENTRY || !order.editable || !order.protectionEditable) return OrderPreview(null, errors = listOf(PENDING_BRACKETS_LOCKED))
                return estimate(accountId, order.request.copy(stopLoss = stopLoss, takeProfit = takeProfit), modifying = true)
            }
            override suspend fun placeOrder(requestId: String, request: OrderRequest) = command(accountId, requestId, "place:$request") {
                request.sourceSymbol?.let { refreshContinuous(it) }
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
                    require(price != null && (if (stop) order.request.stopLoss else order.request.takeProfit) != null) { "这里只能修改已有止损/止盈，添加或删除保护请到官网操作" }
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
                val price = requireNotNull(if (stop) stopLoss else takeProfit) { "这里只能修改已有止损/止盈，添加或删除保护请到官网操作" }
                val spec = instruments.first { it.symbol == p.symbol }
                require(fresh(quotes[p.symbol])) { "止损/止盈修改需要有效的实时报价" }
                positionProtectionError(spec, p, quotes[p.symbol], stopLoss, takeProfit)?.let { errorArgument(it) }
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
        validateContinuous(request)
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
        entryProtectionError(spec, request, quote)?.let { errorArgument(it) }
        if (!modifying && (request.stopLoss != null || request.takeProfit != null)) require(accountFlows.getValue(accountId).value.positions.none { it.symbol == request.symbol }) { "该合约已有净仓，不能再叠加括号保护" }
        OrderPreview(entry, stopLossPnlMicros = request.stopLoss?.let { grossPnl(spec, request.side, request.lotsHundredths, entry, it) },
            takeProfitPnlMicros = request.takeProfit?.let { grossPnl(spec, request.side, request.lotsHundredths, entry, it) },
            quoteRevision = market.value.revision, marginKnown = false, feesKnown = false,
            warnings = listOf("盈亏按合约价值估算，未扣手续费。手续费、保证金和最终成交以 Tradovate 为准"))
    } catch (e: IllegalArgumentException) { OrderPreview(null, errors = listOf(safeMessage(e.message ?: "订单无效")), marginKnown = false, feesKnown = false) }

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
