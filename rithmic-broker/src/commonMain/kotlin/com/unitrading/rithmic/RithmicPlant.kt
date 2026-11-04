package com.unitrading.rithmic

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One WebSocket connection to a Rithmic plant (ticker, order, history or PnL infrastructure).
 * Requests are correlated through `user_msg`; a reply may arrive in several parts and ends with
 * the part that carries `rp_code`. Everything without a matching request id is an update.
 */
internal class RithmicPlant(
    val name: String,
    private val wire: RithmicWireSocket,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onEvent: suspend (ProtoMessage) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private class Pending(val parts: MutableList<ProtoMessage> = mutableListOf(), val waiter: CompletableDeferred<List<ProtoMessage>> = CompletableDeferred())
    private val pending = mutableMapOf<String, Pending>()
    private val lock = Mutex()
    private val sendLock = Mutex()
    private var sequence = 0L
    private var reader: Job? = null
    private var heartbeat: Job? = null
    private var intentionallyClosed = false
    private var lastInbound = now()
    var heartbeatIntervalMs = 30_000L
        private set
    val connected: Boolean get() = reader?.isActive == true

    fun start() {
        reader = scope.launch {
            var reason = "Rithmic $name 连接已断开"
            try {
                while (true) {
                    val frame = wire.receive() ?: break
                    require(frame.size <= 16_000_000)
                    lastInbound = now()
                    for (message in unframeMessages(frame)) {
                        if (dispatch(message)) { reason = "Rithmic 服务器已强制登出（可能超过了该登录允许的并发会话数）"; return@launch }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Never expose raw frames or credentials. */ }
            finally {
                withContext(NonCancellable) {
                    val error = RithmicRemoteException("closed", reason)
                    lock.withLock { pending.values.forEach { it.waiter.completeExceptionally(error) }; pending.clear() }
                    heartbeat?.cancel()
                    if (!intentionallyClosed) onClosed(reason)
                }
            }
        }
    }

    /** Returns true when the server forced a logout and the reader must stop. */
    private suspend fun dispatch(message: ProtoMessage): Boolean {
        when (message.templateId) {
            Rt.RESPONSE_HEARTBEAT, Rt.REQUEST_HEARTBEAT -> return false
            Rt.FORCED_LOGOUT -> { onEvent(message); return true }
        }
        val id = message.userMsg()
        val waiting = if (id != null) lock.withLock { pending[id] } else null
        if (waiting == null) { onEvent(message); return false }
        if (message.templateId == Rt.REJECT) {
            lock.withLock { pending.remove(id) }
            waiting.waiter.completeExceptionally(RithmicRemoteException(message.rpCode().firstOrNull() ?: "reject", "Rithmic 拒绝了请求：${message.failureMessage()}"))
            return false
        }
        waiting.parts.add(message)
        if (message.isTerminal()) {
            lock.withLock { pending.remove(id) }
            if (message.succeeded()) waiting.waiter.complete(waiting.parts.toList())
            else waiting.waiter.completeExceptionally(RithmicRemoteException(message.rpCode().first(), message.failureMessage()))
        }
        return false
    }

    /** Sends one request and returns every reply part, the terminal part included; "no data" replies are empty. */
    suspend fun request(templateId: Int, timeoutMs: Long = 15_000, build: ProtoWriter.() -> Unit): List<ProtoMessage> {
        val entry = Pending()
        val id = lock.withLock { "ut${++sequence}".also { pending[it] = entry } }
        try {
            val payload = ProtoWriter().int32(Rf.TEMPLATE_ID, templateId).string(Rf.USER_MSG, id).apply(build).toByteArray()
            sendLock.withLock { wire.send(frameMessage(payload)) }
            val parts = try { withTimeout(timeoutMs) { entry.waiter.await() } }
                catch (_: TimeoutCancellationException) { throw RithmicRemoteException("timeout", "Rithmic $name 请求超时，结果未知") }
            return parts.filter { it.templateId != Rt.REJECT }
        } finally { lock.withLock { pending.remove(id) } }
    }

    /** Fire-and-forget frame; heartbeats and logouts expect no correlated reply. */
    suspend fun send(templateId: Int, build: ProtoWriter.() -> Unit = {}) {
        val payload = ProtoWriter().int32(Rf.TEMPLATE_ID, templateId).apply(build).toByteArray()
        sendLock.withLock { wire.send(frameMessage(payload)) }
    }

    suspend fun login(credentials: RithmicCredentials, infraType: Int, aggregatedQuotes: Boolean? = null): ProtoMessage {
        val reply = try { request(Rt.REQUEST_LOGIN, 20_000) {
            string(Rf.TEMPLATE_VERSION, TEMPLATE_VERSION)
            string(Rf.USER, credentials.user.trim())
            string(Rf.PASSWORD, credentials.password)
            string(Rf.APP_NAME, credentials.appName)
            string(Rf.APP_VERSION, credentials.appVersion)
            string(Rf.SYSTEM_NAME, credentials.systemName.trim())
            int32(Rf.INFRA_TYPE, infraType)
            aggregatedQuotes?.let { bool(Rf.AGGREGATED_QUOTES, it) }
        } } catch (error: RithmicRemoteException) {
            throw RithmicRemoteException(error.code, redactSecret(error.message ?: "", credentials.password))
        }
        val last = reply.lastOrNull() ?: throw RithmicRemoteException("login", "Rithmic 登录没有返回结果")
        // "7" means "no data" on list replies but is a refusal on a login.
        if (last.rpCode().firstOrNull() != "0") throw RithmicRemoteException(last.rpCode().firstOrNull() ?: "login", "Rithmic 登录被拒绝：${redactSecret(last.failureMessage(), credentials.password)}")
        val interval = last.double(Rf.HEARTBEAT_INTERVAL)?.takeIf { it.isFinite() && it >= 1.0 } ?: 30.0
        heartbeatIntervalMs = (interval * 1_000).toLong()
        startHeartbeat()
        return last
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        val period = (heartbeatIntervalMs / 2).coerceIn(2_000, 30_000)
        heartbeat = scope.launch {
            while (isActive) {
                delay(period)
                if (now() - lastInbound > heartbeatIntervalMs * 2 + 10_000) {
                    intentionallyClosed = false
                    runCatching { wire.close() }
                    return@launch
                }
                try {
                    val seconds = now() / 1_000
                    send(Rt.REQUEST_HEARTBEAT) { int32(Rf.SSBOE, seconds.toInt()); int32(Rf.USECS, ((now() - seconds * 1_000) * 1_000).toInt()) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { return@launch }
            }
        }
    }

    suspend fun close(logout: Boolean) {
        intentionallyClosed = true
        heartbeat?.cancel()
        if (logout) runCatching { withTimeoutOrNull(2_000) { send(Rt.REQUEST_LOGOUT) } }
        runCatching { wire.close() }
        reader?.cancel()
    }

    companion object {
        /** Template version the public R | Protocol clients send; Rithmic accepts it on all plants. */
        const val TEMPLATE_VERSION = "3.9"
    }
}
