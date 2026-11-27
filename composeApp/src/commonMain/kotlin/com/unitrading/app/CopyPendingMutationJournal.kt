package com.unitrading.app

import com.unitrading.broker.CopyOrderLifecycle
import com.unitrading.broker.PendingOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopyPendingMutationState { INTENDED, ACCEPTED, CONFIRMED, REJECTED, UNCERTAIN }
@Serializable
internal data class CopyPendingMutation(
    val brokerId: String,
    val leaderId: String,
    val groupId: String,
    val sourceMutationId: String,
    val followerId: String,
    val followerOrderId: String,
    val requestId: String,
    val kind: CopySourceMutationKind,
    val followerVersion: Long,
    val newPrice: Long? = null,
    val state: CopyPendingMutationState = CopyPendingMutationState.INTENDED,
)

/** Durable one-command-per-source-mutation-and-follower journal. */
internal class CopyPendingMutationJournal(private val store: SnapshotStore) {
    private val key = "copy-pending-mutations-v1"
    private val codec = ListSerializer(CopyPendingMutation.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopyPendingMutation): Boolean = listOf(row.brokerId, row.leaderId, row.groupId, row.sourceMutationId,
        row.followerId, row.followerOrderId, row.requestId).all(String::isNotBlank) && row.followerId != row.leaderId && row.followerVersion > 0 &&
        when (row.kind) {
            CopySourceMutationKind.CHANGE_ENTRY_PRICE -> row.newPrice != null && row.newPrice > 0
            CopySourceMutationKind.CANCEL_ENTRY -> row.newPrice == null
        }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.requestId }.distinct().size == rows.size)
        val keys = rows.map { listOf(it.brokerId, it.groupId, it.sourceMutationId, it.followerId) }
        require(keys.distinct().size == keys.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())

    suspend fun begin(row: CopyPendingMutation): Boolean = mutex.withLock {
        if (unreadable || !valid(row) || row.state != CopyPendingMutationState.INTENDED || records.any { it.requestId == row.requestId ||
                (it.brokerId == row.brokerId && it.groupId == row.groupId && it.sourceMutationId == row.sourceMutationId && it.followerId == row.followerId) })
            return@withLock false
        persist(records + row)
    }
    suspend fun accepted(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingMutationState.INTENDED }?.copy(state = CopyPendingMutationState.ACCEPTED) }
    }
    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingMutationState.INTENDED || row.state == CopyPendingMutationState.ACCEPTED }
            ?.copy(state = CopyPendingMutationState.UNCERTAIN) }
    }
    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyPendingMutationState.INTENDED }?.copy(state = CopyPendingMutationState.REJECTED) }
    }
    suspend fun confirmPrice(requestId: String, order: PendingOrder): Boolean = mutex.withLock {
        transition(requestId) { row -> row.takeIf { it.state == CopyPendingMutationState.ACCEPTED && it.kind == CopySourceMutationKind.CHANGE_ENTRY_PRICE &&
            order.id == it.followerOrderId && order.version > it.followerVersion && order.request.entryPrice == it.newPrice }
            ?.copy(state = CopyPendingMutationState.CONFIRMED) }
    }
    suspend fun confirmCancellation(requestId: String, order: CopyOrderLifecycle): Boolean = mutex.withLock {
        transition(requestId) { row -> row.takeIf { it.state == CopyPendingMutationState.ACCEPTED && it.kind == CopySourceMutationKind.CANCEL_ENTRY &&
            order.id == it.followerOrderId && order.status in setOf("Canceled", "Cancelled") }
            ?.copy(state = CopyPendingMutationState.CONFIRMED) }
    }
    suspend fun read(): List<CopyPendingMutation>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, apply: (CopyPendingMutation) -> CopyPendingMutation?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }
    private fun persist(next: List<CopyPendingMutation>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
