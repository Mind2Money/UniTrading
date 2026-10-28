package com.unitrading

import com.unitrading.broker.Timeframe
import com.unitrading.tradovate.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicitly invoked diagnostic, not a test-suite entry. Its transport refuses every order route. */
private class ReadOnlyProbeWire : TradovateWire {
    private val delegate = KtorTradovateWire()
    var refusedWrites = 0
        private set
    var chartMessages = 0
        private set
    var rawBarCount = 0
        private set
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
        val allowed = method == "GET" && endpoint in setOf("auth/me", "auth/renewaccesstoken", "account/list", "contract/item", "contract/suggest", "contractMaturity/item", "product/item", "currency/item") ||
            method == "POST" && endpoint == "cashBalance/getcashbalancesnapshot"
        if (!allowed) { refusedWrites++; error("Read-only diagnostic rejected a non-read request") }
        return delegate.request(environment, token, method, path, body)
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
    require(args.contentEquals(arrayOf("--demo-read-only")))
    val wire = ReadOnlyProbeWire()
    var broker: TradovateBroker? = null
    var phase = "opening-browser"
    var verified = false
    try {
        println("READ_ONLY_PROBE: Demo only; no order endpoints; credentials remain in memory.")
        val session = withTimeout(180_000) {
            DesktopTradovateLogin().signIn(TradovateEnvironment.DEMO) { status -> phase = phaseCode(status); println("STATUS $phase") }
        }
        println("LOGIN_HANDOFF_OK: environment=demo, userIdPresent=true, separateMarketTokenPresent=${session.mdAccessToken != null}")
        broker = TradovateBroker(session, wire)
        withTimeout(90_000) { broker.connect { status -> phase = phaseCode(status); println("STATUS $phase") } }
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
