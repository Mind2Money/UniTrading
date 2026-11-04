package com.unitrading.rithmic

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*

interface RithmicWire {
    suspend fun socket(url: String): RithmicWireSocket
    fun close()
}

interface RithmicWireSocket {
    suspend fun send(bytes: ByteArray)
    /** Next binary frame, or null once the connection is closed. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

/** Only Rithmic-operated gateways may ever receive the login credentials. */
internal fun validateRithmicGateway(url: String): String {
    val trimmed = url.trim()
    val match = Regex("^wss://([A-Za-z0-9][A-Za-z0-9.-]*\\.rithmic\\.com)(?::([0-9]{1,5}))?/?$").matchEntire(trimmed)
        ?: throw IllegalArgumentException("网关地址必须是 wss://<主机>.rithmic.com[:端口]")
    val port = match.groupValues[2].ifEmpty { "443" }
    require(port.toInt() in 1..65535) { "网关端口无效" }
    return "wss://${match.groupValues[1].lowercase()}:$port"
}

class KtorRithmicWire : RithmicWire {
    private val client = HttpClient {
        followRedirects = false
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000 }
    }
    override suspend fun socket(url: String): RithmicWireSocket {
        val target = validateRithmicGateway(url)
        val session = client.webSocketSession { url(target) }
        return object : RithmicWireSocket {
            override suspend fun send(bytes: ByteArray) { session.send(Frame.Binary(true, bytes)) }
            override suspend fun receive(): ByteArray? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                    when (frame) {
                        is Frame.Binary -> return frame.data
                        is Frame.Close -> return null
                        else -> continue
                    }
                }
            }
            override suspend fun close() { session.close() }
        }
    }
    override fun close() { client.close() }
}
