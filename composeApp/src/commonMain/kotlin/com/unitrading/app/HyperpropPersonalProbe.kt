package com.unitrading.app

import com.unitrading.broker.createBrokerHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Read-only personal-key connection check. This never invokes a trading endpoint. */
internal data class HyperpropAccountCheck(val id: String, val number: String, val firm: String?, val balance: String?)
internal data class HyperpropCheck(val scope: String, val accounts: List<HyperpropAccountCheck>)

private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun parseHyperpropCheck(sessionText: String, accountsText: String): HyperpropCheck {
    val session = Json.parseToJsonElement(sessionText).jsonObject
    require(session.stringValue("session_type") == "personal_api_key") { "会话类型不匹配" }
    val scope = session.stringValue("scope")?.takeIf { it == "read" || it == "trade" } ?: error("会话权限不可识别")
    val allowed = session["account_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: error("未返回授权账户")
    val result = Json.parseToJsonElement(accountsText).jsonObject
    require((result["success"] as? JsonPrimitive)?.booleanOrNull == true) { "账户读取失败" }
    val accounts = result["data"]?.jsonArray?.map { entry ->
        val item = entry.jsonObject
        val id = item.stringValue("id") ?: error("账户 ID 缺失")
        require(id in allowed) { "服务器返回未授权账户" }
        val number = item.stringValue("account_number") ?: id
        val firm = (item["organization"] as? JsonObject)?.stringValue("name")
        HyperpropAccountCheck(id, number, firm, item.stringValue("current_balance"))
    } ?: error("账户列表缺失")
    require(accounts.map { it.id }.distinct().size == accounts.size) { "账户列表包含重复项" }
    return HyperpropCheck(scope, accounts)
}

internal suspend fun checkHyperpropPersonalKey(input: String): HyperpropCheck {
    val key = input.trim()
    require(key.startsWith("hpp_") && key.length in 8..512 && key.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) { "请输入 Hyperprop 个人密钥（hpp_ 开头）" }
    val client = createBrokerHttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000 }
    }
    try {
        val response = client.post("https://api.hyperprop.com/trade/api-keys/session") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("api_key", key) }.toString())
        }
        if (response.status.value !in 200..299) error("Hyperprop 登录失败（HTTP ${response.status.value}）")
        val sessionText = response.bodyAsText()
        require(sessionText.length <= 1_000_000) { "会话响应过大" }
        val session = Json.parseToJsonElement(sessionText).jsonObject
        val token = session.stringValue("access_token")?.takeIf { it.isNotBlank() && it.none { c -> c.isWhitespace() || c.code < 32 || c.code == 127 } }
            ?: error("服务器没有返回会话令牌")
        val accounts = client.get("https://api.hyperprop.com/trade/accounts") { bearerAuth(token) }
        if (accounts.status.value !in 200..299) error("账户读取失败（HTTP ${accounts.status.value}）")
        val accountText = accounts.bodyAsText()
        require(accountText.length <= 4_000_000) { "账户响应过大" }
        return parseHyperpropCheck(sessionText, accountText)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: IllegalArgumentException) { throw error }
    catch (_: Exception) { error("连接失败，请检查密钥、网络和 Hyperprop 服务状态") }
    finally { client.close() }
}
