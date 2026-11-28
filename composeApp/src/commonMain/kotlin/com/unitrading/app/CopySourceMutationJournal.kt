package com.unitrading.app

import com.unitrading.broker.CopyOrderLifecycle
import com.unitrading.broker.OrderRole
import com.unitrading.broker.OrderKind
import com.unitrading.broker.PendingOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopySourceMutationKind { CHANGE_ENTRY_PRICE, CANCEL_ENTRY }
@Serializable
internal enum class CopySourceMutationState { INTENDED, ACCEPTED, CONFIRMED, REJECTED, UNCERTAIN }
@Serializable
internal data class CopySourceMutation(
    val brokerId: String,
    val leaderId: String,
    val groupId: String,
    val sourceOrderId: String,
    val requestId: String,
    val previousVersion: Long,
    val kind: CopySourceMutationKind,
    val newPrice: Long? = null,
    val state: CopySourceMutationState = CopySourceMutationState.INTENDED,
)

/** A mutation can lead to follower actions only after the app request and its authoritative broker state are confirmed. */
internal class CopySourceMutationJournal(private val store: SnapshotStore, private val sources: CopySourceOrders) {
    private val key = "copy-source-mutations-v1"
    private val codec = ListSerializer(CopySourceMutation.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopySourceMutation): Boolean = row.brokerId.isNotBlank() && row.leaderId.isNotBlank() &&
        row.groupId.isNotBlank() && row.sourceOrderId.isNotBlank() && row.requestId.isNotBlank() && row.previousVersion > 0 &&
        when (row.kind) {
            CopySourceMutationKind.CHANGE_ENTRY_PRICE -> row.newPrice != null && row.newPrice > 0
            CopySourceMutationKind.CANCEL_ENTRY -> row.newPrice == null
        }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.requestId }.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())
    private val unrecordedRequests = mutableSetOf<String>()

    suspend fun begin(row: CopySourceMutation): Boolean = beginResult(row) == CopySourceBeginResult.RECORDED
    suspend fun beginResult(row: CopySourceMutation): CopySourceBeginResult = mutex.withLock {
        if (unreadable || !valid(row) || row.state != CopySourceMutationState.INTENDED || row.requestId in unrecordedRequests ||
            records.any { it.requestId == row.requestId || (it.brokerId == row.brokerId && it.leaderId == row.leaderId &&
                it.groupId == row.groupId && it.sourceOrderId == row.sourceOrderId &&
                it.state in setOf(CopySourceMutationState.INTENDED, CopySourceMutationState.ACCEPTED, CopySourceMutationState.UNCERTAIN)) })
            return@withLock CopySourceBeginResult.UNSAFE
        val origin = sources.acceptedSource(row.brokerId, row.leaderId, row.groupId, row.sourceOrderId) ?: return@withLock CopySourceBeginResult.UNSAFE
        if (origin.kind !in setOf(OrderKind.LIMIT, OrderKind.STOP)) return@withLock CopySourceBeginResult.UNSAFE
        if (persist(records + row)) CopySourceBeginResult.RECORDED else {
            unrecordedRequests += row.requestId
            CopySourceBeginResult.STORAGE_FAILED
        }
    }

    suspend fun accepted(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceMutationState.INTENDED }?.copy(state = CopySourceMutationState.ACCEPTED) }
    }

    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceMutationState.INTENDED || row.state == CopySourceMutationState.ACCEPTED }
            ?.copy(state = CopySourceMutationState.UNCERTAIN) }
    }

    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceMutationState.INTENDED }?.copy(state = CopySourceMutationState.REJECTED) }
    }

    suspend fun confirmPrice(requestId: String, source: PendingOrder): Boolean = mutex.withLock {
        transition(requestId) { row -> row.takeIf { it.state == CopySourceMutationState.ACCEPTED && it.kind == CopySourceMutationKind.CHANGE_ENTRY_PRICE &&
            source.id == it.sourceOrderId && source.role == OrderRole.ENTRY && source.version > it.previousVersion && source.request.entryPrice == it.newPrice }
            ?.copy(state = CopySourceMutationState.CONFIRMED) }
    }

    suspend fun confirmCancellation(requestId: String, source: CopyOrderLifecycle): Boolean = mutex.withLock {
        transition(requestId) { row -> row.takeIf { it.state == CopySourceMutationState.ACCEPTED && it.kind == CopySourceMutationKind.CANCEL_ENTRY &&
            source.id == it.sourceOrderId && source.status in setOf("Canceled", "Cancelled") }
            ?.copy(state = CopySourceMutationState.CONFIRMED) }
    }

    suspend fun read(): List<CopySourceMutation>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, apply: (CopySourceMutation) -> CopySourceMutation?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }

    private fun persist(next: List<CopySourceMutation>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
