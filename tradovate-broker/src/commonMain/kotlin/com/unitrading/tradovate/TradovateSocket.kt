package com.unitrading.tradovate

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun decodeFrames(text: String): List<JsonObject> = when {
    text.startsWith("a") -> Json.parseToJsonElement(text.drop(1)).jsonArray.mapNotNull { it as? JsonObject }
    text.startsWith("{") -> listOf(Json.parseToJsonElement(text).jsonObject)
    else -> emptyList()
}

internal class TradovateSocket(
    private val wire: TradovateWireSocket,
    private val scope: CoroutineScope,
    private val onEvent: suspend (JsonObject) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val opened = CompletableDeferred<Unit>()
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private val lock = Mutex()
    private var sequence = 0
    private var reader: Job? = null
    private var heartbeat: Job? = null
    private var intentionallyClosed = false

    suspend fun authorize(token: String) {
        reader = scope.launch {
            try {
                while (true) {
                    val text = wire.receive() ?: break
                    require(text.length <= 8_000_000)
                    if (text == "o") opened.complete(Unit)
                    else if (text.startsWith("c")) break
                    else for (frame in decodeFrames(text)) {
                        val id = frame.long("i")?.toInt()
                        if (frame.str("e") != null) onEvent(frame)
                        else if (id != null) lock.withLock { pending.remove(id) }?.complete(frame)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Never expose raw socket frames or credentials. */ }
            finally {
                val error = TradovateRemoteException(0, "Tradovate 连接已断开")
                opened.completeExceptionally(error)
                lock.withLock { pending.values.forEach { it.completeExceptionally(error) }; pending.clear() }
                heartbeat?.cancel()
                if (!intentionallyClosed) onClosed("Tradovate 连接已断开，正在等待重新同步")
            }
        }
        withTimeout(15_000) { opened.await() }
        rawRequest("authorize", token)
        heartbeat = scope.launch { while (isActive) { delay(2_500); wire.send("[]") } }
    }
    suspend fun request(route: String, body: JsonObject): JsonObject = rawRequest(route, body.toString())
    private suspend fun rawRequest(route: String, body: String): JsonObject {
        val waiter = CompletableDeferred<JsonObject>()
        val id = lock.withLock { ++sequence; pending[sequence] = waiter; sequence }
        return try {
            wire.send("$route\n$id\n\n$body")
            val frame = withTimeout(15_000) { waiter.await() }
            val status = frame.long("s")?.toInt() ?: 0
            val data = frame["d"] as? JsonObject ?: buildJsonObject {}
            if (status !in 200..299 || data.str("errorCode") != null || data.str("errorText") != null) {
                throw TradovateRemoteException(status, "Tradovate $route 被拒绝：${safeMessage(data.str("errorText") ?: data.str("errorCode") ?: status.toString())}")
            }
            data
        } finally { lock.withLock { pending.remove(id) } }
    }
    suspend fun close() {
        intentionallyClosed = true
        heartbeat?.cancel()
        wire.close()
        reader?.cancel()
    }
}
