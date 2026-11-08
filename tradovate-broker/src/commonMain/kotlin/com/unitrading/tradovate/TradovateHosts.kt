package com.unitrading.tradovate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Where one login's traffic goes: REST and the user-data WebSocket on [api], market data on [market]. Tradovate moves
 * some organisations' environments to hosts of their own and names them in the login reply's `apiHosts`; the default
 * hosts then answer those users with an HTTP redirect.
 */
@Serializable
data class TradovateHosts(val api: String, val market: String) {
    val restUrl get() = "https://$api/v1"
    val socketUrl get() = "wss://$api/v1/websocket"
    val marketUrl get() = "wss://$market/v1/websocket"
}

// Tradovate's own hosts and the NinjaTrader-hosted ones an organisation can move to, as a lowercase host name only:
// no scheme, port, path or credentials.
private val ALLOWED_HOST = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:tradovateapi|ninjatrader)\\.com")
private val SOCKET_URL = Regex("wss://([^/]+)/v1/websocket")

internal fun allowedTradovateHost(host: String) = host.length <= 253 && ALLOWED_HOST.matches(host)
internal fun allowedTradovateSocket(url: String) = SOCKET_URL.matchEntire(url)?.let { allowedTradovateHost(it.groupValues[1]) } == true

/**
 * Both hosts are allowed names and the trading host is not the other environment's: its default host or one whose
 * first label names it (`live-api`). Later labels name the organisation, whose own name may contain either word.
 */
internal fun TradovateEnvironment.accepts(hosts: TradovateHosts): Boolean {
    val other = TradovateEnvironment.entries.first { it != this }
    return allowedTradovateHost(hosts.api) && allowedTradovateHost(hosts.market) && hosts.api != other.host &&
        !Regex("(?:^|-)${other.wire}(?:-|$)").containsMatchIn(hosts.api.substringBefore('.'))
}

/**
 * The hosts a login reply's `apiHosts` names for one environment, the way Tradovate's web trader re-points itself after
 * login. A missing or empty entry keeps the default. A named host that is not allowed, or a trading host of the other
 * environment, refuses the login: the reply said where the accounts are, so the defaults would not serve them.
 */
internal fun selectTradovateHosts(environment: TradovateEnvironment, apiHosts: JsonElement?): TradovateHosts {
    fun invalid(key: String) = TradovateRemoteException(0, "Tradovate 登录回复指定的主机无效（$key），本程序不会连接该主机")
    val named = when (apiHosts) {
        null, JsonNull -> return environment.defaultHosts
        is JsonObject -> apiHosts
        is JsonArray -> if (apiHosts.isEmpty()) return environment.defaultHosts else throw invalid("apiHosts")
        is JsonPrimitive -> if (apiHosts.isString && apiHosts.content.isEmpty()) return environment.defaultHosts else throw invalid("apiHosts")
    }
    fun pick(key: String, default: String): String {
        val value = named[key]
        if (value == null || value is JsonNull) return default
        val host = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("apiHosts.$key")
        if (host.isEmpty()) return default
        if (!allowedTradovateHost(host)) throw invalid("apiHosts.$key")
        return host
    }
    val demo = environment == TradovateEnvironment.DEMO
    val tradingKey = if (demo) "demo" else "live"
    val hosts = TradovateHosts(pick(tradingKey, environment.host), pick(if (demo) "mdDemo" else "mdLive", environment.marketHost))
    val otherTradingHost = (named[if (demo) "live" else "demo"] as? JsonPrimitive)?.content
    if (!environment.accepts(hosts) || hosts.api == otherTradingHost)
        throw TradovateRemoteException(0, "Tradovate 登录回复把本连接的交易主机指向另一环境（apiHosts.$tradingKey），已拒绝连接")
    return hosts
}
