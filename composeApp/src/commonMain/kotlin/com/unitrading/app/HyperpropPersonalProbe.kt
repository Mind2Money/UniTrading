package com.unitrading.app

import com.unitrading.broker.createBrokerHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlin.random.Random

internal data class HyperpropAccountCheck(val id: String, val number: String, val firm: String?, val balance: String?)
internal data class HyperpropCheck(val scope: String, val accounts: List<HyperpropAccountCheck>)
internal data class HyperpropPendingOrder(val id: String, val accountId: String, val contract: String, val side: String, val type: String, val quantity: String, val price: String?)
internal data class HyperpropCommand(val id: String, val status: String)
internal class HyperpropConnectionException(message: String) : Exception(message)
private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.dataObject(): JsonObject = (this["data"] as? JsonObject) ?: this

internal fun parseHyperpropSession(text: String): JsonObject {
    val root = Json.parseToJsonElement(text).jsonObject
    if ("success" in root) require((root["success"] as? JsonPrimitive)?.booleanOrNull == true) { "会话请求未成功" }
    return if ("data" in root) root["data"] as? JsonObject ?: error("会话数据缺失") else root
}

internal fun parseHyperpropCheck(sessionText: String, accountsText: String): HyperpropCheck {
    val session = parseHyperpropSession(sessionText)
    require(session.stringValue("session_type") == "personal_api_key") { "会话类型不匹配" }
    val scope = session.stringValue("scope")?.takeIf { it == "read" || it == "trade" } ?: error("会话权限不可识别")
    val allowed = session["account_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: error("未返回授权账户")
    val result = Json.parseToJsonElement(accountsText).jsonObject
    require((result["success"] as? JsonPrimitive)?.booleanOrNull == true) { "账户读取失败" }
    val accounts = result["data"]?.jsonArray?.map { entry ->
        val item = entry.jsonObject
        val id = item.stringValue("id") ?: error("账户 ID 缺失")
        require(id in allowed) { "服务器返回未授权账户" }
        HyperpropAccountCheck(id, item.stringValue("account_number") ?: id,
            (item["organization"] as? JsonObject)?.stringValue("name"), item.stringValue("current_balance"))
    } ?: error("账户列表缺失")
    require(accounts.map { it.id }.distinct().size == accounts.size) { "账户列表包含重复项" }
    return HyperpropCheck(scope, accounts)
}

internal fun parseHyperpropPending(text: String, accountId: String): List<HyperpropPendingOrder> {
    val result = Json.parseToJsonElement(text).jsonObject
    require((result["success"] as? JsonPrimitive)?.booleanOrNull == true) { "挂单读取失败" }
    return result["data"]?.jsonArray?.mapNotNull { value ->
        val row = value.jsonObject
        if (row.stringValue("account_id") != accountId || row.stringValue("status") !in setOf("pending", "working")) return@mapNotNull null
        HyperpropPendingOrder(row.stringValue("id") ?: error("订单缺少 ID"), accountId,
            row.stringValue("contract") ?: error("订单缺少合约"), row.stringValue("side") ?: "?",
            row.stringValue("order_type") ?: "?", row.stringValue("quantity") ?: "?",
            row.stringValue("limit_price") ?: row.stringValue("stop_price"))
    } ?: error("挂单列表缺失")
}

internal fun hyperpropOrderBody(accountId: String, contract: String, side: String, kind: String, quantity: Int, price: String): JsonObject {
    require(accountId.isNotBlank()) { "请选择账户" }
    require(contract.matches(Regex("[A-Z]{1,8}[FGHJKMNQUVXZ][0-9]{1,2}"))) { "请输入到期合约，例如 MESZ6（不能使用连续合约）" }
    require(side in setOf("buy", "sell") && kind in setOf("market", "limit", "stop")) { "订单类型错误" }
    require(quantity in 1..1000000) { "数量必须为 1 至 1000000 张" }
    val priceValue = if (kind == "market") null else {
        require(price.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,8})?")) && price.toDoubleOrNull()?.let { it > 0 } == true) { "请输入有效价格" }
        Json.parseToJsonElement(price)
    }
    return buildJsonObject {
        put("account_id", accountId); put("contract", contract); put("side", side); put("order_type", kind); put("quantity", quantity)
        if (priceValue != null) put("price", priceValue)
    }
}

/** Ephemeral personal session: no credential persistence and no automatic command retries. */
internal class HyperpropPersonalSession private constructor(private val client: HttpClient, private val token: String, val check: HyperpropCheck) {
    private suspend fun read(path: String): JsonObject {
        val response = client.get("https://api.hyperprop.com$path") { bearerAuth(token) }
        require(response.status.value in 200..299) { "读取失败 HTTP ${response.status.value}" }
        val text = response.bodyAsText(); require(text.length <= 4_000_000) { "响应过大" }
        return Json.parseToJsonElement(text).jsonObject
    }
    suspend fun pending(accountId: String): List<HyperpropPendingOrder> {
        require(check.accounts.any { it.id == accountId })
        return parseHyperpropPending(read("/trade/orders/pending").toString(), accountId)
    }
    private suspend fun command(path: String, body: JsonObject): HyperpropCommand {
        require(check.scope == "trade") { "密钥只有只读权限" }
        // Every click has a unique key; the HTTP client makes one attempt only. A timeout remains unknown.
        val requestId = Random.Default.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val response = client.post("https://api.hyperprop.com$path") {
            bearerAuth(token); contentType(ContentType.Application.Json); header("x-idempotency-key", requestId); setBody(body.toString())
        }
        require(response.status.value in 200..299) { "请求被拒绝 HTTP ${response.status.value}" }
        val text = response.bodyAsText(); require(text.length <= 1_000_000) { "响应过大" }
        val data = Json.parseToJsonElement(text).jsonObject.dataObject()
        require((data["accepted"] as? JsonPrimitive)?.booleanOrNull == true) { "命令未被受理" }
        val id = data.stringValue("command_id")?.takeIf { it.matches(Regex("[a-fA-F0-9-]{32,36}")) } ?: error("命令 ID 缺失")
        repeat(8) {
            delay(500)
            val status = read("/trade/commands/$id").dataObject()
            when (status.stringValue("status")) {
                "succeeded" -> return HyperpropCommand(id, "服务器已处理；请刷新订单和持仓核对")
                "failed" -> return HyperpropCommand(id, "服务器处理失败；请到 Hyperprop 核对")
            }
        }
        return HyperpropCommand(id, "仍在处理，请到 Hyperprop 核对后再操作")
    }
    suspend fun submit(accountId: String, contract: String, side: String, kind: String, quantity: Int, price: String): HyperpropCommand {
        require(check.accounts.any { it.id == accountId }) { "账户未授权" }
        return command("/trade/order", hyperpropOrderBody(accountId, contract, side, kind, quantity, price))
    }
    suspend fun cancel(order: HyperpropPendingOrder): HyperpropCommand {
        require(check.accounts.any { it.id == order.accountId }) { "账户未授权" }
        return command("/trade/order/cancel", buildJsonObject { put("account_id", order.accountId); put("order_id", order.id) })
    }
    fun close() = client.close()
    companion object {
        suspend fun connect(input: String, clientFactory: () -> HttpClient = {
            createBrokerHttpClient { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000 } }
        }): HyperpropPersonalSession {
            val key = input.trim()
            if (!(key.startsWith("hpp_") && key.length in 8..512 && key.none { it.isWhitespace() || it.code < 32 || it.code == 127 }))
                throw HyperpropConnectionException("请输入完整的 Hyperprop 个人密钥（以 hpp_ 开头）。")
            val client = clientFactory()
            var stage = "身份验证"
            try {
                val response = client.post("https://api.hyperprop.com/trade/api-keys/session") {
                    contentType(ContentType.Application.Json); setBody(buildJsonObject { put("api_key", key) }.toString())
                }
                if (response.status.value !in 200..299) throw HyperpropConnectionException(when (response.status.value) {
                    401 -> "身份验证失败（HTTP 401）：请检查密钥是否完整、有效。"
                    403 -> "身份验证被拒绝（HTTP 403）：请检查账户及密钥权限。"
                    429 -> "请求过于频繁（HTTP 429）：请稍后重试。"
                    else -> "身份验证失败（HTTP ${response.status.value}）：请检查网络或稍后重试。"
                })
                val sessionText = response.bodyAsText(); require(sessionText.length <= 1_000_000)
                val data = parseHyperpropSession(sessionText)
                val token = data.stringValue("access_token")?.takeIf { it.isNotBlank() && it.none { c -> c.isWhitespace() || c.code < 32 || c.code == 127 } } ?: error("缺少会话令牌")
                stage = "读取账户"
                val accounts = client.get("https://api.hyperprop.com/trade/accounts") { bearerAuth(token) }
                if (accounts.status.value !in 200..299) throw HyperpropConnectionException("读取账户失败（HTTP ${accounts.status.value}）：请检查账户权限或稍后重试。")
                val accountsText = accounts.bodyAsText(); require(accountsText.length <= 4_000_000)
                return HyperpropPersonalSession(client, token, parseHyperpropCheck(sessionText, accountsText))
            } catch (cancel: CancellationException) { client.close(); throw cancel }
            catch (e: HyperpropConnectionException) { client.close(); throw e }
            catch (_: Exception) { client.close(); throw HyperpropConnectionException("$stage 未完成：请检查网络或稍后重试；未发送订单。") }
        }
    }
}
