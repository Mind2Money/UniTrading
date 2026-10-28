package com.unitrading.tradovate

import com.unitrading.broker.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.time.Clock

/** Server-authoritative adapter. No remote order is executed by the local demo engine. */
class TradovateBroker(
    private var credentials: TradovateSession,
    private val wire: TradovateWire = KtorTradovateWire(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : BrokerPlugin {
    override val descriptor = BrokerDescriptor("tradovate-${credentials.environment.wire}-${credentials.userId}",
        "Tradovate ${credentials.environment.wire.replaceFirstChar(Char::uppercaseChar)}", "服务器行情与净额期货账户", credentials.environment == TradovateEnvironment.DEMO,
        if (credentials.environment == TradovateEnvironment.DEMO) BrokerEnvironment.REMOTE_DEMO else BrokerEnvironment.LIVE)
    override val capabilities = BrokerCapabilities(hedging = false)
    private val mutableCatalog = MutableStateFlow(BrokerCatalog(emptyList(), emptyList()))
    override val catalog: StateFlow<BrokerCatalog> = mutableCatalog.asStateFlow()
    override val instruments get() = catalog.value.instruments
    override val accounts get() = catalog.value.accounts
    private val mutableMarket = MutableStateFlow(MarketSnapshot(connected = false, tradingReady = false, statusMessage = "尚未连接"))
    override val market: StateFlow<MarketSnapshot> = mutableMarket.asStateFlow()
    private var accountFlows = emptyMap<String, MutableStateFlow<AccountSnapshot>>()
    private var incompleteAccounts = emptySet<String>()
    private val book = EntityBook()
    private val lock = Mutex()
    private val metadataLock = Mutex()
    private val commandLock = Mutex()
    private val requestPace = Mutex()
    private var lastRequestMs: Long? = null
    private val financials = mutableMapOf<Long, JsonObject>()
    private val contractSpecs = mutableMapOf<Long, Instrument>()
    private val localEvents = mutableMapOf<String, List<ActivityEvent>>()
    private var quotes = emptyMap<String, Quote>()
    private var history = emptyMap<Pair<String, Timeframe>, List<Candle>>()
    private val charts = mutableMapOf<Long, Pair<String, Timeframe>>()
    private val earlyCharts = mutableListOf<JsonObject>()
    private val subscribedQuotes = mutableSetOf<String>()
    private var accountSocket: TradovateSocket? = null
    private var marketSocket: TradovateSocket? = null
    private var connected = false
    private var marketReady = false
    private var synchronizing = true
    private var stopped = false
    private var problem = "正在连接 Tradovate"
    private val queuedEvents = mutableListOf<JsonObject>()
    private var maintenance: Job? = null
    private var watchJob: Job? = null
    private var reconnectJob: Job? = null
    private var metadataJob: Job? = null
    private var balanceJob: Job? = null
    private var watching: Pair<String, Timeframe>? = null
    private val results = mutableMapOf<String, Pair<String, TradeResult>>()
    private val unknownCommands = mutableSetOf<String>()
    private val awaiting = mutableMapOf<String, Awaiting>()
    private data class Awaiting(val account: String, val kind: String, val entity: Long?, val tag: String, val started: Long,
        val baselineVersion: Long = 0, val priceKey: String? = null, val expectedPrice: Long? = null)

    suspend fun connect() {
        check(!stopped)
        connected = false
        marketReady = false
        problem = "正在连接 Tradovate 账户流"
        publish()
        val account = TradovateSocket(wire.socket(credentials.environment.socketUrl), scope, ::accountEvent) { message ->
            connected = false
            problem = message
            publish()
            scheduleReconnect()
        }
        accountSocket = account
        account.authorize(credentials.accessToken)
        lock.withLock { synchronizing = true; queuedEvents.clear() }
        val snapshot = account.request("user/syncrequest", buildJsonObject { putJsonArray("users") { add(credentials.userId) } })
        lock.withLock {
            book.snapshot(snapshot)
            queuedEvents.forEach(book::event)
            queuedEvents.clear()
            synchronizing = false
            connected = true
            rebuild()
        }
        hydrateContracts()
        refreshBalances()
        problem = if (accounts.isEmpty()) "当前会话没有可访问的交易账户" else "账户已连接，正在连接行情"
        connectMarket()
        if (instruments.isEmpty()) searchInstruments("MES")
        watching?.let { watch(it.first, it.second) }
        maintenance?.cancel()
        maintenance = scope.launch {
            while (isActive && !stopped) {
                delay(1_000)
                lock.withLock { publish() }
                val expiry = credentials.expiresAtMs
                if (expiry != null && expiry - now() < 120_000) {
                    try {
                        val renewed = rest("GET", "auth/renewaccesstoken").jsonObject
                        val token = renewed.str("accessToken") ?: error("renewal denied")
                        credentials = TradovateSession(credentials.environment, token, renewed.str("mdAccessToken") ?: credentials.mdAccessToken,
                            credentials.userId, credentials.userName, renewed.str("expirationTime")?.let(::timestamp), credentials.marketUrl)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        connected = false; marketReady = false; problem = "登录会话已失效，请重新打开官方登录页"; publish(); return@launch
                    }
                }
            }
        }
    }

    private suspend fun connectMarket() {
        val token = credentials.mdAccessToken
        if (token == null) { marketReady = false; problem = "账户已连接；缺少行情令牌，请在登录窗口完成行情连接"; publish(); return }
        try {
            val socket = TradovateSocket(wire.socket(credentials.marketUrl), scope, ::marketEvent) {
                marketReady = false; problem = "行情已断开，交易已暂停"; publish(); scheduleReconnect()
            }
            marketSocket = socket
            socket.authorize(token)
            socket.request("md/clientcapabilities", buildJsonObject { put("deltaUpdatesDOM", false); put("deltaUpdatesQuote", false) })
            marketReady = true
            problem = "已连接 · 服务器行情 · 净额持仓"
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { marketReady = false; problem = "账户已连接；行情授权/连接失败，请检查行情订阅" }
        publish()
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
        maintenance?.cancel(); reconnectJob?.cancel(); watchJob?.cancel(); metadataJob?.cancel(); balanceJob?.cancel()
        scope.launch { runCatching { accountSocket?.close() }; runCatching { marketSocket?.close() }; wire.close(); scope.cancel() }
    }

    override suspend fun searchInstruments(query: String) {
        if (!connected || query.trim().length !in 1..32) return
        val data = rest("GET", "contract/suggest?t=${query.trim().encodeURLParameter()}&l=15") as? JsonArray ?: return
        lock.withLock { data.mapNotNull { it as? JsonObject }.forEach { book.apply("contract", it) } }
        hydrateContracts()
    }

    private suspend fun hydrateContracts() = metadataLock.withLock {
        val ids = lock.withLock {
            (book.list("contract").mapNotNull { it.long("id") } + book.list("position").mapNotNull { it.long("contractId") } +
                book.list("order").mapNotNull { it.long("contractId") }).distinct().filter { it !in contractSpecs }.take(100)
        }
        for (id in ids) {
            try {
                suspend fun entity(type: String, entityId: Long): JsonObject {
                    lock.withLock { book.item(type, entityId) }?.let { return it }
                    val item = rest("GET", "$type/item?id=$entityId").jsonObject
                    lock.withLock { book.apply(type, item) }
                    return item
                }
                val contract = entity("contract", id)
                val maturity = entity("contractMaturity", contract.long("contractMaturityId") ?: continue)
                val product = entity("product", maturity.long("productId") ?: continue)
                val currency = entity("currency", product.long("currencyId") ?: continue).str("name") ?: continue
                val spec = mapInstrument(contract, maturity, product, currency) ?: continue
                lock.withLock { contractSpecs[id] = spec; rebuild() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { problem = "部分合约规格无法加载，缺失规格的品种不可交易" }
        }
        lock.withLock { rebuild() }
    }

    private suspend fun refreshBalances() {
        for (account in accounts) {
            val id = account.id.toLongOrNull() ?: continue
            try {
                val snapshot = rest("POST", "cashBalance/getcashbalancesnapshot", buildJsonObject { put("accountId", id) }).jsonObject
                lock.withLock { financials[id] = snapshot; rebuild() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Missing values stay unknown instead of becoming zero. */ }
        }
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
                if (data.str("entityType") in setOf("position", "cashBalance", "fill")) {
                    financials.clear()
                    if (balanceJob?.isActive != true) balanceJob = scope.launch { delay(2_000); refreshBalances() }
                }
                reconcile()
                rebuild()
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
                val oldCharts = lock.withLock { charts.keys.toList().also { charts.clear() } }
                for (id in oldCharts) socket.request("md/cancelChart", buildJsonObject { put("subscriptionId", id) })
                val response = socket.request("md/getChart", buildJsonObject {
                    put("symbol", symbol)
                    putJsonObject("chartDescription") { put("underlyingType", "MinuteBar"); put("elementSize", timeframe.minutes); put("elementSizeUnit", "UnderlyingUnits"); put("withHistogram", false) }
                    putJsonObject("timeRange") { put("asMuchAsElements", 500) }
                })
                lock.withLock {
                    listOfNotNull(response.long("historicalId"), response.long("realtimeId")).forEach { charts[it] = symbol to timeframe }
                    earlyCharts.toList().forEach(::chartEvent); earlyCharts.clear()
                    publish()
                }
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
                val bid = (entries["Bid"] as? JsonObject)?.priceValue("price") ?: continue
                val ask = (entries["Offer"] as? JsonObject)?.priceValue("price") ?: continue
                val time = q.str("timestamp")?.let(::timestamp) ?: continue
                if (bid <= 0 || ask < bid || bid % spec.tickSize != 0L || ask % spec.tickSize != 0L) continue
                if (quotes[spec.symbol]?.timeMs?.let { it > time } == true) continue
                quotes = quotes + (spec.symbol to Quote(bid, ask, time))
            }
        }
        if (frame.str("e") == "chart") data.array("charts").forEach(::chartEvent)
        rebuild()
    }

    private fun chartEvent(chart: JsonObject) {
        val key = charts[chart.long("id")]
        if (key == null) { if (earlyCharts.size < 256) earlyCharts.add(chart); return }
        val incoming = chart.array("bars").mapNotNull { bar ->
            val time = bar.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null
            val o = bar.priceValue("open") ?: return@mapNotNull null
            val h = bar.priceValue("high") ?: return@mapNotNull null
            val l = bar.priceValue("low") ?: return@mapNotNull null
            val c = bar.priceValue("close") ?: return@mapNotNull null
            if (l <= 0 || h < maxOf(o, c) || l > minOf(o, c)) return@mapNotNull null
            Candle(time, o, h, l, c)
        }
        history = history + (key to ((history[key] ?: emptyList()) + incoming).associateBy { it.timeMs }.values.sortedBy { it.timeMs }.takeLast(500))
    }

    private fun rebuild() {
        val mappedAccounts = book.list("account").mapNotNull { a ->
            val id = a.long("id") ?: return@mapNotNull null
            if (a.long("userId") != credentials.userId || a.bool("closed") == true) return@mapNotNull null
            AccountInfo(id.toString(), a.str("name") ?: id.toString(), readOnly = a.bool("readonly") == true || a.bool("restricted") == true || a.bool("futuresDisabled") == true)
        }
        val nextFlows = accountFlows.toMutableMap()
        val incomplete = mutableSetOf<String>()
        for (account in mappedAccounts) {
            val aid = account.id.toLong()
            val orders = book.list("order").filter { it.long("accountId") == aid }
            val active = orders.filter(::activeOrder)
            val old = accountFlows[account.id]?.value
            val rawPositions = book.list("position").filter { it.long("accountId") == aid && (it.long("netPos") ?: 0) != 0L }
            val positions = rawPositions.mapNotNull { p ->
                val cid = p.long("contractId") ?: return@mapNotNull null
                val spec = contractSpecs[cid] ?: return@mapNotNull null
                val net = p.long("netPos") ?: return@mapNotNull null
                val entry = p.priceValue("netPrice") ?: return@mapNotNull null
                if (entry <= 0) return@mapNotNull null
                val side = if (net > 0) Side.BUY else Side.SELL
                val protection = protectionOrders(aid, cid, side, abs(net))
                val sl = protection.first?.let { book.orderVersion(it)?.priceValue("stopPrice") }
                val tp = protection.second?.let { book.orderVersion(it)?.priceValue("price") }
                val id = "$aid:$cid"
                val candidate = Position(id, spec.symbol, side, checkedMultiply(abs(net), 100), entry, p.str("timestamp")?.let(::timestamp) ?: now(),
                    sl, tp, 0, version = 1, protectionEditable = protection.first != null || protection.second != null, feesKnown = false)
                val prior = old?.positions?.firstOrNull { it.id == id }
                candidate.copy(version = if (prior?.copy(version = 1) == candidate) prior.version else (prior?.version ?: 0) + 1)
            }
            if (positions.size != rawPositions.size) incomplete.add(account.id)
            val pending = active.filter { (it.long("parentId") ?: 0) == 0L }.mapNotNull { order ->
                val id = order.long("id") ?: return@mapNotNull null
                val version = book.orderVersion(id) ?: return@mapNotNull null
                val spec = contractSpecs[order.long("contractId")] ?: return@mapNotNull null
                val kind = when (version.str("orderType")) { "Limit" -> OrderKind.LIMIT; "Stop" -> OrderKind.STOP; else -> return@mapNotNull null }
                val side = if (order.str("action") == "Buy") Side.BUY else Side.SELL
                val price = version.priceValue(if (kind == OrderKind.LIMIT) "price" else "stopPrice") ?: return@mapNotNull null
                val children = active.filter { it.long("parentId") == id }
                val stop = children.firstOrNull { book.orderVersion(it.long("id") ?: 0)?.str("orderType") == "Stop" }
                val target = children.firstOrNull { book.orderVersion(it.long("id") ?: 0)?.str("orderType") == "Limit" }
                PendingOrder(id.toString(), OrderRequest(spec.symbol, side, kind, (version.long("orderQty") ?: return@mapNotNull null) * 100, price,
                    stop?.long("id")?.let { book.orderVersion(it)?.priceValue("stopPrice") }, target?.long("id")?.let { book.orderVersion(it)?.priceValue("price") }),
                    order.str("timestamp")?.let(::timestamp) ?: 0, version.long("id") ?: 1)
            }
            val fills = book.list("fill").filter { f -> f.bool("active") != false && orders.any { it.long("id") == f.long("orderId") } }.mapNotNull { f ->
                val spec = contractSpecs[f.long("contractId")] ?: return@mapNotNull null
                Execution(f.long("id")?.toString() ?: return@mapNotNull null, spec.symbol, if (f.str("action") == "Buy") Side.BUY else Side.SELL,
                    (f.long("qty") ?: return@mapNotNull null) * 100, f.priceValue("price") ?: return@mapNotNull null,
                    f.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null, f.long("orderId").toString())
            }.sortedBy { it.timeMs }
            val finance = financials[aid]
            val cash = book.list("cashBalance").filter { it.long("accountId") == aid && book.item("currency", it.long("currencyId") ?: 0)?.str("name") == "USD" }.maxByOrNull { it.str("timestamp") ?: "" }
            val balance = finance?.moneyValue("totalCashValue") ?: cash?.moneyValue("amount")
            val pnlKnown = positions.size == rawPositions.size && positions.all { fresh(quotes[it.symbol]) }
            val floating = if (pnlKnown) runCatching { positions.sumOf { p -> positionPnl(contractSpecs.values.first { it.symbol == p.symbol }, p, quotes.getValue(p.symbol)) } }.getOrNull() else null
            val estimatedEquity = if (balance != null && floating != null) checkedAdd(balance, floating) else null
            val equity = estimatedEquity ?: finance?.moneyValue("netLiq")
            val margin = finance?.moneyValue("initialMargin")
            val snapshot = AccountSnapshot(account.id, balance ?: 0, equity ?: 0, margin ?: 0, positions, pending,
                events = localEvents[account.id] ?: emptyList(), executions = fills,
                balanceKnown = balance != null, equityKnown = equity != null, marginKnown = margin != null, equityEstimated = estimatedEquity != null && positions.isNotEmpty())
            nextFlows.getOrPut(account.id) { MutableStateFlow(snapshot) }.value = snapshot
        }
        incompleteAccounts = incomplete.toSet()
        accountFlows = nextFlows.toMap()
        mutableCatalog.value = BrokerCatalog(contractSpecs.values.distinctBy { it.symbol }.sortedBy { it.symbol }, mappedAccounts)
        publish()
    }

    private fun protectionOrders(account: Long, contract: Long, side: Side, quantity: Long): Pair<Long?, Long?> {
        val candidates = book.list("order").filter { activeOrder(it) && it.long("accountId") == account && it.long("contractId") == contract &&
            it.str("action") == (if (side == Side.BUY) "Sell" else "Buy") }
            .filter { it.long("parentId") != null && book.orderVersion(it.long("id") ?: 0)?.long("orderQty") == quantity }
        if (candidates.mapNotNull { it.long("parentId") }.distinct().size != 1) return null to null
        val stops = candidates.filter { book.orderVersion(it.long("id") ?: 0)?.str("orderType") == "Stop" }
        val targets = candidates.filter { book.orderVersion(it.long("id") ?: 0)?.str("orderType") == "Limit" }
        if (stops.size > 1 || targets.size > 1) return null to null
        if (stops.isNotEmpty() && targets.isNotEmpty()) {
            val a = stops.single(); val b = targets.single()
            if (a.long("ocoId") != b.long("id") && b.long("ocoId") != a.long("id") &&
                (a.long("ocoId") == null || a.long("ocoId") != b.long("ocoId"))) return null to null
        }
        return stops.firstOrNull()?.long("id") to targets.firstOrNull()?.long("id")
    }

    private fun publish() {
        val unresolved = unknownCommands.isNotEmpty()
        val pending = awaiting.isNotEmpty()
        val message = when {
            unresolved -> "存在结果未知的交易指令，请在 Tradovate 官网核对；本会话已禁止继续写入"
            pending -> "指令已受理，等待服务端订单/仓位更新"
            else -> problem
        }
        mutableMarket.value = MarketSnapshot(quotes, now(), mutableMarket.value.revision + 1, connected,
            statusMessage = message, tradingReady = connected && marketReady && !unresolved && !pending)
    }
    private fun fresh(quote: Quote?): Boolean = quote != null && now() - quote.timeMs in -5_000..15_000
    private suspend fun rest(method: String, path: String, body: JsonObject? = null): JsonElement {
        requestPace.withLock {
            lastRequestMs?.let { previous ->
                val elapsed = Clock.System.now().toEpochMilliseconds() - previous
                if (elapsed < 125) delay(125 - elapsed.coerceAtLeast(0))
            }
            lastRequestMs = Clock.System.now().toEpochMilliseconds()
        }
        return wire.request(credentials.environment, credentials.accessToken, method, path, body)
    }

    override fun openSession(accountId: String): AccountSession {
        val info = accounts.firstOrNull { it.id == accountId } ?: error("当前授权中没有该账户")
        val flow = accountFlows.getValue(accountId)
        return object : AccountSession {
            override val account = info
            override val state: StateFlow<AccountSnapshot> = flow.asStateFlow()
            override fun preview(request: OrderRequest) = estimate(accountId, request)
            override fun previewOrderModification(orderId: String, entryPrice: Long): OrderPreview {
                val order = state.value.orders.firstOrNull { it.id == orderId } ?: return OrderPreview(null, errors = listOf("挂单已结束或不属于当前账户"))
                return estimate(accountId, order.request.copy(entryPrice = entryPrice), modifying = true)
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
                require(changes == 1) { "每次只能修改一条保护线，不能把两次服务器改单当作原子操作" }
                val stop = p.stopLoss != stopLoss
                val price = requireNotNull(if (stop) stopLoss else takeProfit) { "首版仅修改已有保护价；增删保护请在官网操作" }
                val spec = instruments.first { it.symbol == p.symbol }
                validateProtection(spec, p.side, quotes[p.symbol], stopLoss, takeProfit)
                val ids = lock.withLock { protectionOrders(accountId.toLong(), positionId.substringAfter(':').toLong(), p.side, p.lotsHundredths / 100) }
                val id = requireNotNull(if (stop) ids.first else ids.second) { "没有唯一可修改的保护订单" }
                sendWrite(accountId, requestId, "modify", id, "order/modifyorder", buildJsonObject {
                    put("orderId", id); put("orderQty", p.lotsHundredths / 100); put("orderType", if (stop) "Stop" else "Limit")
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
            warnings = listOf("盈亏为按合约价值估算的毛结果；手续费、保证金及最终成交由 Tradovate 确定"))
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
            require(connected && unknownCommands.isEmpty() && awaiting.isEmpty()) { market.value.statusMessage }
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
            body.priceValue("stopPrice") ?: body.priceValue("price")) }
        lock.withLock { awaiting[key] = wait; publish() }
        return try {
            val response = rest("POST", route, body).jsonObject
            val failure = response.str("failureReason")
            if (failure != null && failure != "Success") {
                lock.withLock { awaiting.remove(key) }
                TradeResult(false, "Tradovate 拒绝：${safeMessage(response.str("failureText") ?: failure)}")
            } else {
                if (kind == "place" && response.long("orderId") == null) throw TradovateRemoteException(0, "缺少订单回执 ID，无法确认结果")
                lock.withLock {
                    if (kind == "place") awaiting[key] = wait.copy(entity = response.long("orderId"))
                    reconcile(); publish()
                }
                TradeResult(true, "Tradovate 已受理，最终结果以服务器订单/成交更新为准", response.long("orderId")?.toString() ?: entity?.toString())
            }
        } catch (cancel: CancellationException) {
            lock.withLock { unknownCommands.add(key); awaiting.remove(key); publish() }
            throw cancel
        } catch (error: Exception) {
            val certainRejection = error is TradovateRemoteException && error.status in 400..499
            lock.withLock { awaiting.remove(key); if (!certainRejection) unknownCommands.add(key); publish() }
            if (certainRejection) TradeResult(false, safeMessage(error.message ?: "请求被拒绝"))
            else TradeResult(false, "交易结果未知，未自动重试。请在 Tradovate 官网核对，当前会话禁止继续写入。", outcome = CommandOutcome.UNKNOWN)
        }
    }

    private fun reconcile() {
        val completed = awaiting.filter { (_, wait) ->
            when (wait.kind) {
                "place" -> book.list("order").any { it.long("accountId")?.toString() == wait.account &&
                    (wait.entity != null && it.long("id") == wait.entity || it.str("clOrdId") == wait.tag) }
                "modify" -> book.item("order", wait.entity ?: 0)?.let { order ->
                    val version = book.orderVersion(wait.entity ?: 0)
                    !activeOrder(order) || order.str("clOrdId") == wait.tag || version != null &&
                        (version.long("id") ?: 0) > wait.baselineVersion && wait.priceKey != null && version.priceValue(wait.priceKey) == wait.expectedPrice
                } == true
                "cancel" -> book.item("order", wait.entity ?: 0)?.let { !activeOrder(it) } == true
                "close" -> book.list("position").any { it.long("accountId")?.toString() == wait.account && it.long("contractId") == wait.entity && it.long("netPos") == 0L }
                else -> false
            }
        }.keys
        completed.forEach(awaiting::remove)
    }
    private fun tag(id: String) = "UT-$id"
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
}
