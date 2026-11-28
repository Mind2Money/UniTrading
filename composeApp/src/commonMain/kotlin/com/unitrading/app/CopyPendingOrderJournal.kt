package com.unitrading.app

import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopyPendingState { INTENDED, ACCEPTED, REJECTED, UNCERTAIN, TERMINAL }

@Serializable
internal data class CopyPendingOrder(
    val brokerId: String,
    val leaderId: String,
    val sourceOrderId: String,
    val sourceVersion: Long,
    val followerId: String,
    val requestId: String,
    val request: OrderRequest,
    val state: CopyPendingState = CopyPendingState.INTENDED,
    val followerOrderId: String? = null,
)

/** Records the exact broker-held follower order before submission; a lost response must never cause a retry. */
internal class CopyPendingOrderJournal(private val store: SnapshotStore) {
    private val key = "copy-pending-orders-v1"
    private val codec = ListSerializer(CopyPendingOrder.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopyPendingOrder): Boolean = row.brokerId.isNotBlank() && row.leaderId.isNotBlank() &&
        row.followerId.isNotBlank() && row.followerId != row.leaderId && row.sourceOrderId.isNotBlank() && row.sourceVersion > 0 &&
        row.requestId.isNotBlank() && row.request.automated && row.request.kind in setOf(OrderKind.LIMIT, OrderKind.STOP) &&
        row.request.lotsHundredths > 0 && row.request.lotsHundredths % 100L == 0L && row.request.entryPrice != null &&
        when (row.state) {
            CopyPendingState.INTENDED, CopyPendingState.REJECTED -> row.followerOrderId == null
            CopyPendingState.ACCEPTED, CopyPendingState.TERMINAL -> !row.followerOrderId.isNullOrBlank()
            CopyPendingState.UNCERTAIN -> row.followerOrderId == null || row.followerOrderId.isNotBlank()
        }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.requestId }.distinct().size == rows.size)
        val links = rows.map { listOf(it.brokerId, it.leaderId, it.sourceOrderId, it.followerId) }
        val orderIds = rows.mapNotNull { it.followerOrderId }
        require(links.distinct().size == links.size && orderIds.distinct().size == orderIds.size)
        rows
    }
    private var records = loaded.getOrDefault(emptyList())
    private val unreadable = loaded.isFailure

    suspend fun begin(row: CopyPendingOrder): Boolean = mutex.withLock {
        if (unreadable || !valid(row) || row.state != CopyPendingState.INTENDED || records.any { it.requestId == row.requestId ||
                (it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.sourceOrderId == row.sourceOrderId && it.followerId == row.followerId) })
            return@withLock false
        persist(records + row)
    }

    suspend fun accepted(requestId: String, followerOrderId: String): Boolean = mutex.withLock {
        if (followerOrderId.isBlank() || records.any { it.followerOrderId == followerOrderId && it.requestId != requestId }) return@withLock false
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingState.INTENDED }
            ?.copy(state = CopyPendingState.ACCEPTED, followerOrderId = followerOrderId) }
    }

    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingState.INTENDED || row.state == CopyPendingState.ACCEPTED }
            ?.copy(state = CopyPendingState.UNCERTAIN) }
    }

    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingState.INTENDED }?.copy(state = CopyPendingState.REJECTED) }
    }

    suspend fun terminal(requestId: String, followerOrderId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingState.ACCEPTED && row.followerOrderId == followerOrderId }
            ?.copy(state = CopyPendingState.TERMINAL) }
    }

    suspend fun read(): List<CopyPendingOrder>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, apply: (CopyPendingOrder) -> CopyPendingOrder?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }

    private fun persist(next: List<CopyPendingOrder>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
