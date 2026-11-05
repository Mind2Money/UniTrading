package com.unitrading.rithmic

import kotlinx.coroutines.*
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Read-only R | Protocol probe for checking the adapter's protocol assumptions against a real
 * Rithmic system. It only logs in, lists, replays, subscribes and logs out; it never sends a new
 * order, modification, cancel, bracket-level change or exit request (templates 312-335, 3504).
 *
 * Configuration comes from environment variables so that credentials never appear on a command
 * line or in build logs; the password is redacted from every printed line:
 *
 * - `RITHMIC_GATEWAY`: `wss://<host>.rithmic.com:443` (default: the Rithmic Test gateway)
 * - `RITHMIC_SYSTEM`: system name; without it the probe only prints the gateway's system list
 * - `RITHMIC_USER`, `RITHMIC_PASSWORD`
 * - `RITHMIC_APP_NAME`, `RITHMIC_APP_VERSION` (default: UniTrading / 1.1.4)
 * - `RITHMIC_ENV`: `test`, `paper` or `live` (default: by gateway)
 * - `RITHMIC_PRODUCT`, `RITHMIC_EXCHANGE` (default: ES / CME); `RITHMIC_SYMBOL` skips the front-month lookup
 * - `RITHMIC_ACCOUNT`: account id to inspect (default: the first account listed)
 * - `RITHMIC_PLANTS`: subset of `order,pnl,ticker,history` (default: all)
 * - `RITHMIC_LISTEN_SECONDS`: how long the live subscriptions stay open (default: 75)
 */
fun main() {
    val env = System.getenv()
    fun env(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
    val gateway = env("RITHMIC_GATEWAY") ?: RithmicEnvironment.TEST.defaultGateway!!
    val system = env("RITHMIC_SYSTEM")
    val user = env("RITHMIC_USER")
    val password = env["RITHMIC_PASSWORD"]?.takeIf { it.isNotEmpty() }
    val credentials = if (system != null && user != null && password != null) {
        val environment = when (env("RITHMIC_ENV")?.lowercase()) {
            "test" -> RithmicEnvironment.TEST
            "paper" -> RithmicEnvironment.PAPER
            "live" -> RithmicEnvironment.LIVE
            else -> if (validateRithmicGateway(gateway) == RithmicEnvironment.TEST.defaultGateway) RithmicEnvironment.TEST else RithmicEnvironment.PAPER
        }
        RithmicCredentials(environment, gateway, system, user, password,
            appName = env("RITHMIC_APP_NAME") ?: RithmicCredentials.DEFAULT_APP_NAME,
            appVersion = env("RITHMIC_APP_VERSION") ?: RithmicCredentials.DEFAULT_APP_VERSION)
    } else null
    val probe = RithmicProbe(
        gateway = validateRithmicGateway(gateway),
        credentials = credentials,
        product = env("RITHMIC_PRODUCT") ?: "ES",
        exchange = env("RITHMIC_EXCHANGE") ?: "CME",
        symbolOverride = env("RITHMIC_SYMBOL"),
        accountOverride = env("RITHMIC_ACCOUNT"),
        plants = (env("RITHMIC_PLANTS") ?: "order,pnl,ticker,history").split(',').map { it.trim().lowercase() }.toSet(),
        listenMs = (env("RITHMIC_LISTEN_SECONDS")?.toLongOrNull() ?: 75L).coerceIn(0, 900) * 1_000,
    )
    val code = runBlocking { probe.run() }
    System.out.flush()
    // Ktor's engine threads would otherwise keep the JVM alive.
    kotlin.system.exitProcess(code)
}

private val utcTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
private val utcDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun constants(owner: Class<*>): Map<Int, String> = owner.declaredFields
    .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
    .associate { it.getInt(null) to it.name.lowercase() }

private val fieldNames: Map<Int, String> = constants(Rf::class.java)
private val templateNames: Map<Int, String> = constants(Rt::class.java)

private fun ProtoValue.render(): String = when (this) {
    is ProtoValue.Varint -> value.toString()
    is ProtoValue.Fixed64 -> Double.fromBits(value).let { if (it.isFinite()) it.toString() else "0x" + value.toULong().toString(16) }
    is ProtoValue.Fixed32 -> value.toString()
    is ProtoValue.Bytes -> {
        val text = runCatching { value.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        if (text != null && text.none { it.code < 0x20 }) "\"${text.take(200)}\""
        else "hex:" + value.take(32).joinToString("") { "%02x".format(it) } + (if (value.size > 32) "…" else "")
    }
}

private fun ProtoMessage.render(): String = fieldNumbers.filter { it != Rf.TEMPLATE_ID && it != Rf.USER_MSG }.joinToString(", ") { field ->
    val values = values(field)
    val name = fieldNames[field] ?: "f$field"
    name + "=" + if (values.size == 1) values.single().render() else values.joinToString(",", "[", "]") { it.render() }
}

private fun ProtoMessage.label(): String = templateNames[templateId]?.let { "$templateId $it" } ?: "template $templateId"

/** Minute markers are epoch seconds, daily markers are `YYYYMMDD`; print both readings so the semantics can be checked by eye. */
private fun markerText(marker: Long): String = when (marker) {
    in 19_000_000L..21_000_000L -> "date ${marker / 10_000}-${marker / 100 % 100}-${marker % 100}"
    in 1_000_000_000L..4_000_000_000L -> utcDateTime.format(Instant.ofEpochSecond(marker)) + " UTC"
    else -> "?"
}

private fun yyyymmdd(epochMs: Long): Int {
    val date = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate()
    return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
}

internal class RithmicProbe(
    private val gateway: String,
    private val credentials: RithmicCredentials?,
    private val product: String,
    private val exchange: String,
    private val symbolOverride: String?,
    private val accountOverride: String?,
    private val plants: Set<String>,
    private val listenMs: Long,
) {
    private val wire = KtorRithmicWire()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secret = credentials?.password ?: ""
    private val now = { System.currentTimeMillis() }
    private val open = mutableListOf<RithmicPlant>()
    private val counts = sortedMapOf<String, Int>()
    private val lastMarker = mutableMapOf<String, Long>()

    @Synchronized
    fun out(line: String) = println(utcTime.format(Instant.ofEpochMilli(now())) + " " + redactSecret(line, secret))

    private fun event(plant: String, message: ProtoMessage) {
        val key = "$plant/${message.label()}"
        val n = (counts[key] ?: 0) + 1
        counts[key] = n
        if (message.templateId == Rt.TIME_BAR) {
            // Every change of marker is a new bar; its offset from the wall clock tells whether the marker is the open or the close.
            val marker = message.long(Rf.BAR_MARKER) ?: 0
            val symbol = message.str(Rf.SYMBOL) ?: ""
            val changed = lastMarker[symbol] != marker
            lastMarker[symbol] = marker
            if (changed || n <= 3) out("[$plant] <- ${message.label()} marker=$marker (${markerText(marker)}, marker-now=${marker - now() / 1_000}s) ${message.render()}")
            return
        }
        if (n <= 4) out("[$plant] <- ${message.label()} ${message.render()}")
        else if (n == 5) out("[$plant] <- ${message.label()} … further ones only counted")
    }

    private suspend fun <T> step(plant: String, label: String, block: suspend () -> T): T? = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val code = (error as? RithmicRemoteException)?.code?.let { " code=$it" } ?: ""
            out("[$plant] $label FAILED$code: ${error::class.simpleName}: ${error.message}")
            null
        }

    private suspend fun login(name: String, infraType: Int, aggregatedQuotes: Boolean? = null): RithmicPlant? {
        val credentials = credentials ?: return null
        if (name !in plants) return null
        return step(name, "login (10, infra $infraType)") {
            val plant = RithmicPlant(name, wire.socket(gateway), scope, now, { event(name, it) }) { reason -> out("[$name] connection closed: $reason") }
            plant.start()
            open += plant
            val reply = plant.login(credentials, infraType, aggregatedQuotes)
            out("[$name] login ok (heartbeat ${plant.heartbeatIntervalMs} ms): ${reply.render()}")
            plant
        }
    }

    private suspend fun RithmicPlant.show(label: String, templateId: Int, timeoutMs: Long = 30_000, limit: Int = 10, build: ProtoWriter.() -> Unit): List<ProtoMessage>? =
        step(name, label) {
            val parts = request(templateId, timeoutMs, build)
            out("[$name] $label -> ${parts.size} part(s), rp_code=${parts.lastOrNull()?.rpCode()}")
            parts.take(limit).forEach { out("[$name]    ${it.label()} ${it.render()}") }
            if (parts.size > limit) out("[$name]    … ${parts.size - limit} more part(s) not shown")
            parts
        }

    private suspend fun RithmicPlant.replay(label: String, symbol: String, daily: Boolean, start: Long, end: Long) = step(name, label) {
        val parts = request(Rt.REQUEST_TIME_BAR_REPLAY, 60_000) {
            string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange)
            int32(Rf.BAR_TYPE, if (daily) RithmicEnums.BAR_DAILY else RithmicEnums.BAR_MINUTE)
            int32(Rf.BAR_PERIOD, 1)
            if (daily) { int32(Rf.START_INDEX, yyyymmdd(start * 1_000)); int32(Rf.FINISH_INDEX, yyyymmdd(end * 1_000)) }
            else { int32(Rf.START_INDEX, start.toInt()); int32(Rf.FINISH_INDEX, end.toInt()) }
            int32(Rf.TIME_ORDER, RithmicEnums.TIME_ORDER_FORWARDS)
        }
        val bars = parts.filter { it.has(Rf.BAR_MARKER) }
        val window = if (daily) "${yyyymmdd(start * 1_000)}..${yyyymmdd(end * 1_000)}" else "${utcDateTime.format(Instant.ofEpochSecond(start))}..${utcDateTime.format(Instant.ofEpochSecond(end))} UTC"
        out("[$name] $label $window -> ${bars.size} bar(s) in ${parts.size} part(s), rp_code=${parts.lastOrNull()?.rpCode()}")
        (bars.take(3) + bars.takeLast(3)).distinct().forEach { bar ->
            val marker = bar.long(Rf.BAR_MARKER) ?: 0
            val offset = if (daily) "" else " marker-start=${marker - start}s marker-end=${marker - end}s"
            out("[$name]    marker=$marker (${markerText(marker)})$offset ${bar.render()}")
        }
        parts.lastOrNull()?.takeIf { !it.has(Rf.BAR_MARKER) }?.let { out("[$name]    terminal part: ${it.render()}") }
        bars
    }

    suspend fun run(): Int {
        out("probe gateway=$gateway system=${credentials?.systemName ?: "(none: system list only)"} user=${credentials?.user ?: "-"} " +
            "app=${credentials?.appName ?: RithmicCredentials.DEFAULT_APP_NAME}/${credentials?.appVersion ?: RithmicCredentials.DEFAULT_APP_VERSION} template_version=${RithmicPlant.TEMPLATE_VERSION}")
        val systems = step("gateway", "system list (16)") { rithmicSystemNames(gateway, KtorRithmicWire()) }
        out("[gateway] systems: ${systems?.joinToString(" | ") ?: "(unavailable)"}")
        val credentials = credentials ?: run { out("set RITHMIC_SYSTEM, RITHMIC_USER and RITHMIC_PASSWORD to go further; stopping"); return 0 }
        if (systems != null && credentials.systemName !in systems) out("[gateway] WARNING: '${credentials.systemName}' is not in this gateway's system list; the login will probably be refused")
        try {
            var fcmId = ""
            var ibId = ""
            var userType = RithmicEnums.USER_TYPE_TRADER
            var account = accountOverride
            val order = login("order", RithmicEnums.INFRA_ORDER)
            if (order != null) {
                order.show("login info (300)", Rt.REQUEST_LOGIN_INFO) {}
                    ?.lastOrNull { it.has(Rf.FCM_ID) || it.has(Rf.IB_ID) || it.has(Rf.USER_TYPE) }
                    ?.let { fcmId = it.str(Rf.FCM_ID)?.trim() ?: ""; ibId = it.str(Rf.IB_ID)?.trim() ?: ""; userType = it.int(Rf.USER_TYPE)?.takeIf { t -> t in 1..3 } ?: userType }
                val rows = order.show("account list (302)", Rt.REQUEST_ACCOUNT_LIST) { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); int32(Rf.USER_TYPE, userType) }
                    ?.filter { it.has(Rf.ACCOUNT_ID) } ?: emptyList()
                if (account == null) account = rows.firstOrNull()?.str(Rf.ACCOUNT_ID)?.trim()
                rows.firstOrNull { it.str(Rf.ACCOUNT_ID)?.trim() == account }?.let { fcmId = it.str(Rf.FCM_ID)?.trim() ?: fcmId; ibId = it.str(Rf.IB_ID)?.trim() ?: ibId }
                out("[order] using account=$account fcm_id=$fcmId ib_id=$ibId user_type=$userType (${rows.size} account(s) listed)")
                order.show("trade routes (310)", Rt.REQUEST_TRADE_ROUTES) { bool(Rf.SUBSCRIBE_FLAG, false) }
                order.show("unaccepted agreements (500)", Rt.REQUEST_LIST_UNACCEPTED_AGREEMENTS) {}
            }
            val accountId = account
            fun ProtoWriter.account() { string(Rf.FCM_ID, fcmId); string(Rf.IB_ID, ibId); string(Rf.ACCOUNT_ID, accountId ?: "") }
            if (order != null && accountId != null) {
                order.show("rms info (304)", Rt.REQUEST_ACCOUNT_RMS_INFO) { account(); int32(Rf.USER_TYPE, userType) }
                order.show("open orders (320)", Rt.REQUEST_SHOW_ORDERS, limit = 20) { account() }
                order.show("bracket targets (338)", Rt.REQUEST_SHOW_BRACKETS) { account() }
                order.show("bracket stops (340)", Rt.REQUEST_SHOW_BRACKET_STOPS) { account() }
                val end = now() / 1_000
                order.show("fill history (3512, last 7 days)", Rt.REQUEST_SHOW_FILL_HISTORY, limit = 20) {
                    account(); string(Rf.INDEX_FORMAT, "ssboe"); int32(Rf.START_INDEX, (end - 7 * 86_400).toInt()); int32(Rf.FINISH_INDEX, end.toInt()); int32(Rf.USER_MAX_COUNT, 500)
                }
                order.show("subscribe order updates (308)", Rt.REQUEST_SUBSCRIBE_ORDER_UPDATES) { account() }
                order.show("subscribe bracket updates (336)", Rt.REQUEST_SUBSCRIBE_BRACKET_UPDATES) { account() }
            } else if (order != null) out("[order] no account id: account-scoped requests skipped")

            val pnl = login("pnl", RithmicEnums.INFRA_PNL)
            if (pnl != null && accountId != null) {
                pnl.show("pnl snapshot (402)", Rt.REQUEST_PNL_SNAPSHOT, limit = 20) { account() }
                pnl.show("subscribe pnl updates (400)", Rt.REQUEST_PNL_UPDATES) { account(); int32(Rf.REQUEST, RithmicEnums.REQUEST_SUBSCRIBE) }
            }

            val ticker = login("ticker", RithmicEnums.INFRA_TICKER, aggregatedQuotes = false)
            var symbol = symbolOverride
            if (ticker != null) {
                if (symbol == null) {
                    val row = ticker.show("front month (113) $product@$exchange", Rt.REQUEST_FRONT_MONTH) { string(Rf.SYMBOL, product); string(Rf.EXCHANGE, exchange); bool(Rf.NEED_UPDATES, false) }
                        ?.lastOrNull { !it.str(Rf.TRADING_SYMBOL).isNullOrBlank() || !it.str(Rf.SYMBOL).isNullOrBlank() }
                    symbol = row?.str(Rf.TRADING_SYMBOL)?.trim()?.takeIf { it.isNotEmpty() } ?: row?.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() && it != product }
                }
                out("[ticker] symbol=${symbol ?: "(unresolved)"} exchange=$exchange")
                ticker.show("search (109) '$product'", Rt.REQUEST_SEARCH_SYMBOLS, limit = 12) {
                    string(Rf.SEARCH_TEXT, product); string(Rf.EXCHANGE, exchange)
                    int32(Rf.INSTRUMENT_TYPE, RithmicEnums.SEARCH_FUTURE); int32(Rf.SEARCH_PATTERN, RithmicEnums.PATTERN_CONTAINS)
                }
                if (symbol != null) {
                    ticker.show("reference data (14) $symbol", Rt.REQUEST_REFERENCE_DATA) { string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange) }
                    ticker.show("subscribe market data (100) $symbol", Rt.REQUEST_MARKET_DATA_UPDATE) {
                        string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); int32(Rf.REQUEST, RithmicEnums.REQUEST_SUBSCRIBE)
                        uint32(Rf.UPDATE_BITS, (RithmicEnums.UPDATE_LAST_TRADE or RithmicEnums.UPDATE_BBO).toLong())
                    }
                }
            }

            val history = login("history", RithmicEnums.INFRA_HISTORY)
            if (history != null && symbol != null) {
                val end = now() / 1_000
                val minuteStart = (end - 30 * 60) / 60 * 60
                history.replay("minute replay (202)", symbol, daily = false, start = minuteStart, end = end)
                history.replay("daily replay (202)", symbol, daily = true, start = end - 20 * 86_400, end = end)
                history.show("subscribe minute bars (200) $symbol", Rt.REQUEST_TIME_BAR_UPDATE) {
                    string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); int32(Rf.REQUEST, RithmicEnums.REQUEST_SUBSCRIBE)
                    int32(Rf.BAR_TYPE, RithmicEnums.BAR_MINUTE); int32(Rf.BAR_PERIOD, 1)
                }
            } else if (history != null) out("[history] no symbol: replay skipped")

            if (listenMs > 0) {
                out("listening ${listenMs / 1_000}s for live updates (bbo, last trade, bars, pnl, orders)…")
                delay(listenMs)
            }
            if (ticker != null && symbol != null) ticker.show("unsubscribe market data (100)", Rt.REQUEST_MARKET_DATA_UPDATE) {
                string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); int32(Rf.REQUEST, RithmicEnums.REQUEST_UNSUBSCRIBE)
                uint32(Rf.UPDATE_BITS, (RithmicEnums.UPDATE_LAST_TRADE or RithmicEnums.UPDATE_BBO).toLong())
            }
            if (history != null && symbol != null) history.show("unsubscribe minute bars (200)", Rt.REQUEST_TIME_BAR_UPDATE) {
                string(Rf.SYMBOL, symbol); string(Rf.EXCHANGE, exchange); int32(Rf.REQUEST, RithmicEnums.REQUEST_UNSUBSCRIBE)
                int32(Rf.BAR_TYPE, RithmicEnums.BAR_MINUTE); int32(Rf.BAR_PERIOD, 1)
            }
            out("update counts: " + (counts.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { "(none)" }))
            return 0
        } finally {
            withContext(NonCancellable) {
                open.forEach { plant -> runCatching { withTimeoutOrNull(3_000) { plant.close(logout = true) } } }
                wire.close()
                scope.cancel()
            }
            out("done: ${open.size} plant(s) logged out")
        }
    }
}
