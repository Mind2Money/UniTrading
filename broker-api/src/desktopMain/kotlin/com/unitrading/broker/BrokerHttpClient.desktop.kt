package com.unitrading.broker

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * OkHttp asks the JVM's ProxySelector where the destination should go. Once it selects an HTTP
 * proxy, the socket to that proxy must be direct: on Windows, a plain Socket() can consult the
 * system selector again as `socket://...` and try to speak SOCKS to the same HTTP proxy.
 * OkHttp constructs SOCKS-proxied sockets separately, so an explicitly selected SOCKS route still works.
 */
private object DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote)
            return socket
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))
}

/** A response or connection can be lost after the broker accepted a trading command. */
private class SingleAttemptBody(private val original: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = original.contentType()
    override fun contentLength(): Long = original.contentLength()
    override fun isDuplex(): Boolean = original.isDuplex()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
}

actual fun createBrokerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine { config {
        socketFactory(DirectSocketFactory)
        // OkHttp can otherwise replay even POST requests after a timeout or a 503 Retry-After.
        addInterceptor { chain ->
            val request = chain.request()
            val body = request.body
            val guarded = if (body != null && request.method != "GET" && request.method != "HEAD")
                request.newBuilder().method(request.method, SingleAttemptBody(body)).build()
            else request
            chain.proceed(guarded)
        }
    } }
    configure(this)
}
