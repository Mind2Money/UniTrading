package com.unitrading.android

import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.rithmic.KtorRithmicWire
import com.unitrading.tradovate.KtorTradovateWire
import com.unitrading.tradovate.TradovateHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs only on a dedicated emulator whose system HTTP proxy points to the supplied local port. */
class SystemProxyDeviceTest {
    @Test fun brokerHttpsAndSocketsUseAndroidSystemProxy() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("proxyPort")
        assumeTrue("A dedicated emulator and proxyPort are required", argument != null)
        val port = requireNotNull(argument).toInt()
        val tradovateHost = "unitrading-proxy-test.invalid.tradovateapi.com"
        val rithmicHost = "unitrading-proxy-test.invalid.rithmic.com"
        val token = "local-test-token"
        val selected = ProxySelector.getDefault().select(URI("https://$tradovateHost/v1/order/list"))
        assertTrue("Android did not select the configured system proxy: $selected", selected.any {
            it.type() == Proxy.Type.HTTP && (it.address() as? InetSocketAddress)?.port == port
        })
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            val requests = LinkedBlockingQueue<List<String>>()
            val worker = thread(name = "android-test-http-proxy", isDaemon = true) {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line
                        }
                        requests.put(headers)
                        socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    }
                } catch (_: SocketException) { /* server closed */ }
            }
            val tradovate = KtorTradovateWire()
            val rithmic = KtorRithmicWire()
            suspend fun rejected(host: String, call: suspend () -> Unit) {
                val failure = withTimeout(15_000) {
                    try { call(); throw AssertionError("The test proxy rejected CONNECT") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (expected: Exception) { expected }
                }
                val headers = requests.poll(10, TimeUnit.SECONDS)
                    ?: throw AssertionError("No CONNECT reached the local Android proxy for $host", failure)
                assertEquals("CONNECT $host:443 HTTP/1.1", headers.firstOrNull())
                assertFalse("Credentials appeared in proxy CONNECT headers", headers.any { it.contains(token) })
            }
            try {
                rejected(tradovateHost) { tradovate.request(TradovateHosts(tradovateHost, tradovateHost), token, "GET", "order/list") }
                rejected(tradovateHost) { tradovate.socket("wss://$tradovateHost/v1/websocket") }
                rejected(rithmicHost) { rithmic.socket("wss://$rithmicHost:443") }
            } finally {
                tradovate.close()
                rithmic.close()
                server.close()
                worker.join(5_000)
            }
        }
    }
}
