package com.unitrading.tradovate

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.*

interface TradovateWire {
    suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject? = null): JsonElement
    suspend fun socket(url: String): TradovateWireSocket
    fun close()
}
interface TradovateWireSocket {
    suspend fun send(text: String)
    suspend fun receive(): String?
    suspend fun close()
}
class TradovateRemoteException(val status: Int, message: String) : Exception(message)

internal fun tradovateRequestUrl(environment: TradovateEnvironment, path: String): String {
    require(path.matches(Regex("[a-zA-Z]+/[a-zA-Z]+(?:\\?[^#\\r\\n]*)?")))
    val endpoint = path.substringBefore('?')
    val base = if (endpoint in setOf("auth/me", "auth/renewaccesstoken")) environment.authUrl else {
        require(!endpoint.startsWith("auth/")) { "不支持的认证端点" }
        environment.restUrl
    }
    return "$base/$path"
}

class KtorTradovateWire : TradovateWire {
    private val client = HttpClient {
        followRedirects = false
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000 }
    }
    override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement {
        require(path.matches(Regex("[a-zA-Z]+/[a-zA-Z]+(?:\\?[^#\\r\\n]*)?")))
        try {
            val response = client.request(tradovateRequestUrl(environment, path)) {
                this.method = HttpMethod.parse(method)
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body.toString())
            }
            if (response.status.value !in 200..299) throw TradovateRemoteException(response.status.value, "Tradovate HTTP ${response.status.value}，请检查授权、连接或请求频率")
            val text = response.bodyAsText()
            require(text.length <= 8_000_000) { "服务端响应过大" }
            val data = Json.parseToJsonElement(text)
            val error = (data as? JsonObject)?.str("errorText")
            if (!error.isNullOrBlank()) throw TradovateRemoteException(400, safeMessage(error))
            return data
        } catch (cancel: CancellationException) { throw cancel }
        catch (remote: TradovateRemoteException) { throw remote }
        catch (_: Exception) { throw TradovateRemoteException(0, "Tradovate 请求未取得确定结果；请核对服务端状态，勿重复提交") }
    }
    override suspend fun socket(url: String): TradovateWireSocket {
        require(url in setOf(TradovateEnvironment.DEMO.socketUrl, TradovateEnvironment.LIVE.socketUrl, TradovateEnvironment.DEMO.marketUrl, TradovateEnvironment.LIVE.marketUrl))
        val session = client.webSocketSession { url(url) }
        return object : TradovateWireSocket {
            override suspend fun send(text: String) { session.send(Frame.Text(text)) }
            override suspend fun receive(): String? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                    if (frame is Frame.Text) return frame.readText()
                    if (frame is Frame.Close) return null
                }
            }
            override suspend fun close() { session.close() }
        }
    }
    override fun close() { client.close() }
}
