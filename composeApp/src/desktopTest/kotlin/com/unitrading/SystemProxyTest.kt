package com.unitrading

import com.unitrading.rithmic.KtorRithmicWire
import com.unitrading.tradovate.KtorTradovateWire
import com.unitrading.tradovate.TradovateHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TRADOVATE_HOST = "unitrading-proxy-test.invalid.tradovateapi.com"
private const val RITHMIC_HOST = "unitrading-proxy-test.invalid.rithmic.com"
private const val TEST_TOKEN = "local-test-token"

/** Records HTTP CONNECT before TLS, then rejects it. It never forwards traffic or contacts a broker. */
private class RejectingProxy(port: Int = 0) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", port))
    }
    val port: Int get() = server.localPort
    private val requests = LinkedBlockingQueue<List<String>>()
    private val worker = thread(name = "test-http-proxy", isDaemon = true) {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    requests.put(headers)
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                }
            }
        } catch (_: SocketException) {
            // Closing the listening socket ends the worker.
        }
    }

    fun expectConnect(host: String, failure: Exception) {
        val lines = requests.poll(10, TimeUnit.SECONDS) ?: fail("No CONNECT reached the local proxy for $host; request failed: ${failure.stackTraceToString()}")
        assertEquals("CONNECT $host:443 HTTP/1.1", lines.firstOrNull())
        assertFalse(lines.any { it.contains(TEST_TOKEN) }, "The broker token must not appear in the proxy CONNECT headers")
    }

    override fun close() {
        server.close()
        worker.join(5_000)
    }
}

/** A minimal SOCKS5 server; records the requested destination and rejects it. */
private class RejectingSocksProxy : Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = server.localPort
    private val targets = LinkedBlockingQueue<String>()
    private val worker = thread(name = "test-socks-proxy", isDaemon = true) {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                check(input.readUnsignedByte() == 5)
                repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                output.write(byteArrayOf(5, 0))
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                input.readUnsignedByte() // reserved
                val host = when (input.readUnsignedByte()) {
                    3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                    1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                    else -> error("Unexpected SOCKS address type")
                }
                val targetPort = input.readUnsignedShort()
                targets.put("$host:$targetPort")
                output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
            }
        } catch (_: SocketException) {
            // Closing the listening socket ends the worker.
        }
    }

    fun expectTarget(host: String, failure: Exception) {
        assertEquals("$host:443", targets.poll(10, TimeUnit.SECONDS), "SOCKS proxy did not receive target; request failed: ${failure.stackTraceToString()}")
    }

    override fun close() { server.close(); worker.join(5_000) }
}

private suspend fun expectProxyRejection(call: suspend () -> Unit): Exception = withTimeout(15_000) {
    try {
        call()
        fail("The test proxy rejected CONNECT, so the request should not succeed")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Expected: the proxy responds with 502 before the client sends any broker credentials.
        failure
    }
}

private suspend fun checkTradovateRequest(wire: KtorTradovateWire, proxy: RejectingProxy) {
    val failure = expectProxyRejection { wire.request(TradovateHosts(TRADOVATE_HOST, TRADOVATE_HOST), TEST_TOKEN, "GET", "order/list") }
    proxy.expectConnect(TRADOVATE_HOST, failure)
}

private suspend fun checkTradovateSocket(wire: KtorTradovateWire, proxy: RejectingProxy) {
    val failure = expectProxyRejection { wire.socket("wss://$TRADOVATE_HOST/v1/websocket") }
    proxy.expectConnect(TRADOVATE_HOST, failure)
}

private suspend fun checkRithmicSocket(wire: KtorRithmicWire, proxy: RejectingProxy) {
    val failure = expectProxyRejection { wire.socket("wss://$RITHMIC_HOST:443") }
    proxy.expectConnect(RITHMIC_HOST, failure)
}

class SystemProxyTest {
    @Test fun brokerRequestsAndReconnectsFollowTheJvmProxySelector() = runBlocking {
        val names = listOf("https.proxyHost", "https.proxyPort", "http.nonProxyHosts")
        val original = names.associateWith(System::getProperty)
        RejectingProxy().use { first ->
            RejectingProxy().use { second ->
                val tradovate = KtorTradovateWire()
                val rithmic = KtorRithmicWire()
                try {
                    System.setProperty("https.proxyHost", "127.0.0.1")
                    System.setProperty("https.proxyPort", first.port.toString())
                    System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
                    checkTradovateRequest(tradovate, first)

                    // Existing clients must consult the selector again when opening new connections.
                    System.setProperty("https.proxyPort", second.port.toString())
                    checkTradovateSocket(tradovate, second)
                    checkRithmicSocket(rithmic, second)
                } finally {
                    tradovate.close()
                    rithmic.close()
                    original.forEach { (name, value) ->
                        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                    }
                }
            }
        }
    }

    @Test fun brokerWebSocketCanUseExplicitSocksProxy() = runBlocking {
        val names = listOf("https.proxyHost", "https.proxyPort", "proxyHost", "proxyPort", "socksProxyHost", "socksProxyPort", "socksNonProxyHosts")
        val original = names.associateWith(System::getProperty)
        RejectingSocksProxy().use { proxy ->
            val rithmic = KtorRithmicWire()
            try {
                names.forEach { System.clearProperty(it) }
                System.setProperty("socksProxyHost", "127.0.0.1")
                System.setProperty("socksProxyPort", proxy.port.toString())
                val failure = expectProxyRejection { rithmic.socket("wss://$RITHMIC_HOST:443") }
                proxy.expectTarget(RITHMIC_HOST, failure)
            } finally {
                rithmic.close()
                original.forEach { (name, value) ->
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
        }
    }

    /** Opt-in on an isolated Windows runner with the current user's system proxy set to this local port. */
    @Test fun brokerRequestsFollowWindowsSystemProxy() = runBlocking {
        val port = System.getenv("UNITRADING_TEST_WINDOWS_SYSTEM_PROXY")?.toIntOrNull() ?: return@runBlocking
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        assertEquals("true", System.getProperty("java.net.useSystemProxies"))
        val selected = ProxySelector.getDefault().select(URI("https://$TRADOVATE_HOST/v1/order/list"))
        assertTrue(selected.any { it.type() == Proxy.Type.HTTP && (it.address() as? InetSocketAddress)?.port == port }, "System proxy was not selected: $selected")
        RejectingProxy(port).use { proxy ->
            val tradovate = KtorTradovateWire()
            val rithmic = KtorRithmicWire()
            try {
                checkTradovateSocket(tradovate, proxy)
                checkTradovateRequest(tradovate, proxy)
                checkRithmicSocket(rithmic, proxy)
            } finally {
                tradovate.close()
                rithmic.close()
            }
        }
    }
}
