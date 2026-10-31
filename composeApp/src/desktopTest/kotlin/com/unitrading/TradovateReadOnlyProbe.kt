package com.unitrading

import com.unitrading.broker.Timeframe
import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

/** Explicit diagnostics only. Trading writes are always refused, including when dryrun is enabled. */
internal class ReadOnlyProbeWire(private val allowCapacityChecks: Boolean = false) : TradovateWire {
    private val delegate = KtorTradovateWire()
    var refusedWrites = 0
        private set
    var chartMessages = 0
        private set
    var rawBarCount = 0
        private set
    var capacityChecks = 0
        private set
    var implicitSuccessResponses = 0
        private set
    val dailyVolumeRequests = AtomicInteger()
    val chartKeys = mutableSetOf<String>()
    val timestampKinds = mutableSetOf<String>()
    val timestampShapes = mutableSetOf<String>()
    val chartIdKinds = mutableSetOf<String>()
    var parseableBarTimes = 0
        private set
    private fun inspectShape(text: String?) {
        if (text == null || !text.startsWith("a")) return
        val frames = runCatching { Json.parseToJsonElement(text.drop(1)).jsonArray }.getOrNull() ?: return
        for (value in frames) {
            val frame = value as? JsonObject ?: continue
            if ((frame["e"] as? JsonPrimitive)?.content != "chart") continue
            val charts = ((frame["d"] as? JsonObject)?.get("charts") as? JsonArray) ?: continue
            for (item in charts) {
                val chart = item as? JsonObject ?: continue
                chartMessages++
                chartKeys.addAll(chart.keys)
                val chartId = chart["id"] as? JsonPrimitive
                chartIdKinds.add(if (chartId == null) "missing" else if (chartId.longOrNull != null) "integer" else if (chartId.isString) "string" else "number")
                val bars = chart["bars"] as? JsonArray ?: continue
                rawBarCount += bars.size
                parseableBarTimes += bars.count { value ->
                    val stamp = ((value as? JsonObject)?.get("timestamp") as? JsonPrimitive)?.content
                    stamp != null && (stamp.toLongOrNull() != null || runCatching { kotlin.time.Instant.parse(stamp) }.isSuccess)
                }
                for (bar in bars.take(1)) {
                    val timestamp = (bar as? JsonObject)?.get("timestamp") as? JsonPrimitive
                    timestampKinds.add(if (timestamp == null) "missing" else if (timestamp.isString) "string" else "number")
                    timestamp?.content?.takeIf { it.length <= 80 }?.let { timestampShapes.add(it.map { char -> if (char.isDigit()) '#' else char }.joinToString("")) }
                }
            }
        }
    }
    override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement {
        check(environment == TradovateEnvironment.DEMO)
        val endpoint = path.substringBefore('?')
        val allowed = method == "GET" && endpoint in setOf("auth/me", "auth/renewaccesstoken", "account/list", "contract/item", "contract/suggest", "contract/ldeps", "contractMaturity/item", "contractMaturity/items", "contractMaturity/deps", "tradingSession/ldeps", "product/item", "currency/item", "fillPair/list", "fill/items", "order/items", "fillFee/deps") ||
            method == "POST" && endpoint == "cashBalance/getcashbalancesnapshot" ||
            allowCapacityChecks && (method == "POST" && endpoint == "order/dryrun" || method == "GET" && endpoint == "order/list")
        if (!allowed) { refusedWrites++; error("Read-only diagnostic rejected a non-read request") }
        return delegate.request(environment, token, method, path, body).also { response ->
            if (endpoint == "order/dryrun") {
                capacityChecks++
                val data = response as? JsonObject
                if (data != null && "rejectReason" !in data && "details" in data) implicitSuccessResponses++
            }
        }
    }
    override suspend fun socket(url: String): TradovateWireSocket {
        check(url in setOf(TradovateEnvironment.DEMO.socketUrl, TradovateEnvironment.DEMO.marketUrl))
        val socket = delegate.socket(url)
        return object : TradovateWireSocket {
            override suspend fun send(text: String) {
                val route = text.substringBefore('\n')
                if (route !in setOf("[]", "authorize", "user/syncrequest", "md/subscribequote", "md/unsubscribequote", "md/getChart", "md/cancelChart")) {
                    refusedWrites++; error("Read-only diagnostic rejected a non-read socket request")
                }
                if (route == "md/getChart") {
                    val body = Json.parseToJsonElement(text.substringAfter("\n\n")).jsonObject
                    if ((body["timeRange"] as? JsonObject)?.get("asMuchAsElements")?.jsonPrimitive?.intOrNull == 1)
                        dailyVolumeRequests.incrementAndGet()
                }
                socket.send(text)
            }
            override suspend fun receive() = socket.receive().also(::inspectShape)
            override suspend fun close() = socket.close()
        }
    }
    override fun close() = delegate.close()
}

private fun phaseCode(message: String): String = when {
    message.contains("保存") -> "PASSWORD_SAVE_GRACE"
    message.contains("已同步") || message.contains("已返回") -> "ACCOUNTS_DISCOVERED"
    message.contains("完整账户") -> "ACCOUNT_SNAPSHOT"
    message.contains("访问令牌") -> "ACCOUNT_AUTHORIZATION"
    message.contains("授权账户列表") -> "ACCOUNT_DISCOVERY"
    message.contains("资金") -> "FUNDS_READ"
    message.contains("合约") -> "CONTRACT_METADATA"
    message.contains("行情") -> "MARKET_AUTHORIZATION"
    message.contains("已连接") -> "CONNECTED"
    message.contains("已成功") || message.contains("已取得") || message.contains("交接") -> "SESSION_CAPTURED"
    else -> "WAITING_BROWSER"
}
private fun asciiJson(value: JsonObject): String = buildString {
    for (char in value.toString()) {
        if (char.code > 127) append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
    }
}

fun main(args: Array<String>) = runBlocking {
    val continuous = args.contentEquals(arrayOf("--demo-continuous-read-only"))
    require(continuous || args.contentEquals(arrayOf("--demo-read-only")))
    val wire = ReadOnlyProbeWire()
    var broker: TradovateBroker? = null
    var phase = "opening-browser"
    var verified = false
    try {
        println("READ_ONLY_PROBE: Demo only; no order endpoints; credentials remain in memory.")
        val session = if (continuous) {
            phase = "saved-demo-session"
            val saved = requireNotNull(DesktopCredentialStore().read()) { "No saved login" }
            val json = Json { ignoreUnknownKeys = true }
            requireNotNull(json.parseToJsonElement(saved).jsonObject["sessions"]?.jsonArray?.map {
                json.decodeFromJsonElement(TradovateSession.serializer(), it)
            }?.firstOrNull { it.environment == TradovateEnvironment.DEMO }) { "No saved Demo login" }
        } else withTimeout(180_000) {
            DesktopTradovateLogin().signIn(TradovateEnvironment.DEMO) { status -> phase = phaseCode(status); println("STATUS $phase") }
        }
        println("LOGIN_HANDOFF_OK: environment=demo, userIdPresent=true, separateMarketTokenPresent=${session.mdAccessToken != null}")
        broker = TradovateBroker(session, wire)
        withTimeout(90_000) { broker.connect { status -> phase = phaseCode(status); println("STATUS $phase") } }
        if (continuous) {
            verified = true
            for (root in listOf("MES", "MNQ", "MGC")) {
                phase = "continuous-$root"
                val initialStarted = System.nanoTime()
                withTimeout(210_000) { broker.searchInstruments(root) }
                val discoveryMs = (System.nanoTime() - initialStarted) / 1_000_000
                val resolutionStarted = System.nanoTime()
                withTimeout(210_000) { broker.refreshContinuous("@$root") }
                val resolutionMs = (System.nanoTime() - resolutionStarted) / 1_000_000
                val initialMs = (System.nanoTime() - initialStarted) / 1_000_000
                val alias = "@$root"
                broker.watch(alias, Timeframe.M5)
                withTimeoutOrNull(25_000) {
                    while (broker.instruments.singleOrNull { it.symbol == alias }?.executionSymbol?.let {
                        broker.market.value.quotes[it]
                    } == null || broker.history(alias, Timeframe.M5).isEmpty()) delay(250)
                }
                // Repeat while the continuous chart is subscribed: exercise daily-volume
                // channel cleanup/registration without ever calling an order endpoint.
                val refreshMs = mutableListOf<Long>()
                val initialTarget = broker.instruments.singleOrNull { it.symbol == alias }?.executionSymbol
                val initialRequests = wire.dailyVolumeRequests.get()
                val cacheMs = (1..10).map {
                    val started = System.nanoTime()
                    broker.refreshContinuous(alias)
                    (System.nanoTime() - started) / 1_000_000
                }
                val cachedRequests = wire.dailyVolumeRequests.get() - initialRequests
                val repeatedTargets = (1..5).map {
                    val started = System.nanoTime()
                    withTimeout(210_000) { broker.refreshContinuous(alias, force = true) }
                    refreshMs.add((System.nanoTime() - started) / 1_000_000)
                    broker.instruments.singleOrNull { it.symbol == alias }?.executionSymbol
                }
                val instrument = broker.instruments.singleOrNull { it.symbol == "@$root" }
                val resolved = instrument?.executionSymbol != null
                val quoteAvailable = instrument?.executionSymbol?.let { broker.market.value.quotes[it] } != null
                val historyBars = broker.history(alias, Timeframe.M5).size
                val repeatedResolution = resolved && initialTarget == instrument?.executionSymbol && repeatedTargets.all { it == initialTarget }
                verified = verified && repeatedResolution && cachedRequests == 0 && quoteAvailable && historyBars > 0 && wire.refusedWrites == 0
                println("CONTINUOUS_READ_ONLY_RESULT " + asciiJson(buildJsonObject {
                    put("symbol", "@$root")
                    put("resolved", resolved)
                    put("tradeSymbol", instrument?.executionSymbol)
                    put("status", instrument?.tradeSymbolStatus)
                    put("repeatedResolution", repeatedResolution)
                    put("quoteAvailable", quoteAvailable)
                    put("historyBars", historyBars)
                    put("initialResolutionMs", initialMs)
                    put("discoveryMs", discoveryMs)
                    put("resolutionMs", resolutionMs)
                    putJsonArray("refreshMs") { refreshMs.forEach { add(it) } }
                    putJsonArray("cachedResolutionMs") { cacheMs.forEach { add(it) } }
                    put("cachedDailyRequests", cachedRequests)
                    put("refreshDailyRequests", wire.dailyVolumeRequests.get() - initialRequests)
                    put("refusedWriteRequests", wire.refusedWrites)
                    put("sentOrderRequests", 0)
                }))
            }
        } else {
        val symbol = broker.accounts.firstOrNull()?.let { broker.openSession(it.id).state.value.positions.firstOrNull()?.symbol }
            ?: broker.instruments.firstOrNull()?.symbol
        if (symbol != null) {
            broker.watch(symbol, Timeframe.M5)
            withTimeoutOrNull(25_000) {
                while (broker.market.value.quotes[symbol] == null || broker.history(symbol, Timeframe.M5).isEmpty()) delay(250)
            }
        }
        verified = broker.market.value.connected && broker.accounts.isNotEmpty() && symbol != null &&
            broker.market.value.quotes[symbol] != null && broker.history(symbol, Timeframe.M5).isNotEmpty() && wire.refusedWrites == 0
        println("READ_ONLY_RESULT " + asciiJson(buildJsonObject {
            put("verified", verified)
            put("environment", "demo")
            put("connected", broker.market.value.connected)
            put("accountCount", broker.accounts.size)
            put("instrumentCount", broker.instruments.size)
            put("quoteAvailable", symbol != null && broker.market.value.quotes[symbol] != null)
            put("historyBars", symbol?.let { broker.history(it, Timeframe.M5).size } ?: 0)
            put("chartMessages", wire.chartMessages)
            put("rawBarCount", wire.rawBarCount)
            putJsonArray("chartKeys") { wire.chartKeys.forEach { add(it) } }
            putJsonArray("barTimestampKinds") { wire.timestampKinds.forEach { add(it) } }
            putJsonArray("barTimestampShapes") { wire.timestampShapes.forEach { add(it) } }
            putJsonArray("chartIdKinds") { wire.chartIdKinds.forEach { add(it) } }
            put("parseableBarTimes", wire.parseableBarTimes)
            put("marketStatus", broker.market.value.statusMessage)
            put("refusedWriteRequests", wire.refusedWrites)
            put("sentOrderRequests", 0)
        }))
        }
    } catch (error: Exception) {
        println("READ_ONLY_RESULT " + asciiJson(buildJsonObject {
            put("connected", false)
            put("phase", phase)
            put("errorType", error.javaClass.simpleName)
            if (error is TradovateRemoteException) put("httpStatus", error.status)
            put("sentOrderRequests", 0)
        }))
    } finally {
        broker?.disconnect()
        if (broker == null) wire.close()
        delay(200)
    }
    if (!verified) kotlin.system.exitProcess(2)
}
