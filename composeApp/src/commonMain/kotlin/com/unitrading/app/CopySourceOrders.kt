package com.unitrading.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopySourceState { INTENDED, ACCEPTED, REJECTED, UNCERTAIN }

/** No order is a follow source merely because it appeared in the broker's account stream. */
@Serializable
internal data class CopySourceOrder(
    val brokerId: String,
    val leaderId: String,
    val requestId: String,
    val state: CopySourceState = CopySourceState.INTENDED,
    val brokerOrderId: String? = null,
)

/**
 * Durable origin registry. Call [begin] before sending a leader order and [accepted] only with its broker receipt.
 * An interrupted INTENDED record and an UNCERTAIN outcome require user reconciliation, never automatic replay.
 */
internal class CopySourceOrders(private val store: SnapshotStore) {
    private val key = "copy-source-orders-v1"
    private val codec = ListSerializer(CopySourceOrder.serializer())
    private val mutex = Mutex()
    private val loaded = runCatching { store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty() }
    private var unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())

    suspend fun begin(order: CopySourceOrder): Boolean = mutex.withLock {
        if (unreadable || order.brokerId.isBlank() || order.leaderId.isBlank() || order.requestId.isBlank() ||
            order.state != CopySourceState.INTENDED || order.brokerOrderId != null || records.any { it.requestId == order.requestId }) return@withLock false
        persist(records + order)
    }

    suspend fun accepted(requestId: String, brokerOrderId: String): Boolean = mutex.withLock {
        if (brokerOrderId.isBlank() || records.any { it.brokerOrderId == brokerOrderId && it.requestId != requestId }) return@withLock false
        transition(requestId) { it.takeIf { row -> row.state == CopySourceState.INTENDED }?.copy(state = CopySourceState.ACCEPTED, brokerOrderId = brokerOrderId) }
    }

    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceState.INTENDED }?.copy(state = CopySourceState.UNCERTAIN) }
    }

    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceState.INTENDED }?.copy(state = CopySourceState.REJECTED) }
    }

    /** Null means storage is unreadable; an empty list is a valid registry. */
    suspend fun read(): List<CopySourceOrder>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, transform: (CopySourceOrder) -> CopySourceOrder?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val next = transform(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = next })
    }

    private fun persist(next: List<CopySourceOrder>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
