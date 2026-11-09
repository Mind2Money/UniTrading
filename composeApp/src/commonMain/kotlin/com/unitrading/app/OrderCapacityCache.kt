package com.unitrading.app

import com.unitrading.broker.Side
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedCapacity(val maximum: Long, val message: String? = null)

/**
 * The last order quantity limit the server gave for each account, contract and side. It only fills the order form while
 * a check runs: the broker still validates every order, so a stale limit can never let one through.
 */
class OrderCapacityCache(private val store: SnapshotStore) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(try {
        store.read(KEY)?.let { Json.decodeFromString<Map<String, CachedCapacity>>(it) }.orEmpty()
    } catch (_: Exception) { emptyMap() })
    val entries = state.asStateFlow()

    /** Stores [value] unless it is what is already known, so an unchanged answer writes nothing. */
    suspend fun put(key: String, value: CachedCapacity) = mutex.withLock {
        if (state.value[key] == value) return@withLock
        state.value = state.value + (key to value)
        try { withContext(Dispatchers.Default) { store.write(KEY, Json.encodeToString(state.value)) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Only a convenience: the value stays for this run and the next launch asks the server. */ }
    }

    /**
     * The limit worth confirming before a full search, which takes up to a minute while the app is starting: this side's,
     * the other side's, or the largest known for the account, since an account-wide order limit applies to every contract.
     */
    fun hint(brokerId: String, accountId: String, symbol: String, side: Side): Long? {
        val known = state.value
        val other = if (side == Side.BUY) Side.SELL else Side.BUY
        return (known[key(brokerId, accountId, symbol, side)] ?: known[key(brokerId, accountId, symbol, other)])?.maximum
            ?: known.filterKeys { it.startsWith("$brokerId/$accountId/") }.values.maxOfOrNull { it.maximum }
    }

    companion object {
        private const val KEY = "order-capacity-v1"
        fun key(brokerId: String, accountId: String, symbol: String, side: Side) = "$brokerId/$accountId/$symbol/$side"
    }
}
