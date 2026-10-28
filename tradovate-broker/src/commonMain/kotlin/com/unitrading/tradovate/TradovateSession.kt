package com.unitrading.tradovate

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** Only fixed Tradovate endpoints may ever receive a session token. */
enum class TradovateEnvironment(val wire: String, val host: String, val marketHost: String) {
    DEMO("demo", "demo.tradovateapi.com", "md-demo.tradovateapi.com"),
    LIVE("live", "live.tradovateapi.com", "md.tradovateapi.com");
    val restUrl get() = "https://$host/v1"
    val socketUrl get() = "wss://$host/v1/websocket"
    val marketUrl get() = "wss://$marketHost/v1/websocket"
}

class TradovateSession(
    val environment: TradovateEnvironment,
    val accessToken: String,
    val mdAccessToken: String?,
    val userId: Long,
    val userName: String,
    val expiresAtMs: Long?,
    val marketUrl: String = environment.marketUrl,
) {
    init {
        require(accessToken.length in 20..32_768 && accessToken.matches(Regex("[A-Za-z0-9._~+/=-]+"))) { "登录令牌格式无效" }
        require(mdAccessToken == null || mdAccessToken.length in 20..32_768 && mdAccessToken.matches(Regex("[A-Za-z0-9._~+/=-]+")))
        require(userId > 0)
        require(marketUrl == environment.marketUrl || environment == TradovateEnvironment.DEMO && marketUrl == "wss://md.tradovateapi.com/v1/websocket")
    }
    override fun toString() = "TradovateSession(${environment.wire}, credentials=redacted)"
}

interface TradovateLogin {
    suspend fun signIn(environment: TradovateEnvironment): TradovateSession
}

/** Receives only messages from the current official login window, never a browser's files. */
class LoginAccumulator(private val environment: TradovateEnvironment) {
    private var access: String? = null
    private var md: String? = null
    private var id: Long? = null
    private var name = ""
    private var expires: Long? = null
    private var mdUrl = environment.marketUrl
    val ready get() = access != null && id != null

    fun accept(message: String, origin: String, mainFrame: Boolean): Boolean {
        if (!mainFrame || origin.trimEnd('/') != "https://trader.tradovate.com" || message.length > 131_072) return false
        val data = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return false
        if (data.str("kind") != "auth" || data.str("environment") != environment.wire) return false
        if (listOf("error", "errorText", "p-ticket", "p-captcha").any { !data.str(it).isNullOrBlank() }) return false
        fun token(key: String) = data.str(key)?.takeIf { it.length in 20..32_768 && it.matches(Regex("[A-Za-z0-9._~+/=-]+")) }
        token("accessToken")?.let { value ->
            val claims = jwtClaims(value)
            val candidate = data.long("userId") ?: claims?.long("sub") ?: claims?.long("userId")
            if (candidate != null && candidate > 0) {
                if (id != null && id != candidate) md = null
                access = value
                id = candidate
                name = data.str("userName") ?: data.str("name") ?: claims?.str("name") ?: ""
                expires = data.str("expirationTime")?.let(::timestamp) ?: claims?.long("exp")?.times(1_000)
            }
        }
        token("mdAccessToken")?.let { md = it }
        data.str("marketUrl")?.takeIf { it == environment.marketUrl || environment == TradovateEnvironment.DEMO && it == "wss://md.tradovateapi.com/v1/websocket" }?.let { mdUrl = it }
        return ready
    }
    fun result(): TradovateSession {
        check(ready) { "尚未收到有效登录会话，请完成官方网页登录" }
        check(expires == null || expires!! > Clock.System.now().toEpochMilliseconds()) { "登录令牌已过期，请重新登录" }
        return TradovateSession(environment, access!!, md, id!!, name, expires, mdUrl)
    }
}

internal fun jwtClaims(token: String): JsonObject? = runCatching {
    val part = token.split('.')[1]
    val text = Base64.UrlSafe.decode(part.padEnd((part.length + 3) / 4 * 4, '=')).decodeToString()
    Json.parseToJsonElement(text).jsonObject
}.getOrNull()
internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
internal fun timestamp(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
internal fun safeMessage(value: String): String = value
    .replace(Regex("eyJ[A-Za-z0-9_.-]{12,}"), "[redacted]")
    .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer [redacted]")
    .take(300)
