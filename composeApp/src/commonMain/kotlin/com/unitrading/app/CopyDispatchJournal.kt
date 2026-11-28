package com.unitrading.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopyDispatchState { INTENDED, ACCEPTED, CONFIRMED, REJECTED, UNCERTAIN }

@Serializable
internal enum class CopyDispatchLeg { CLOSE, OPEN }

/** One event and leg per follower. INTENDED may already have been sent after a process interruption. */
@Serializable
internal data class CopyDispatch(
    val brokerId: String,
    val leaderId: String,
    val sourceOrderId: String,
    val sourceExecutionId: String,
    val followerId: String,
    val leg: CopyDispatchLeg,
    val requestId: String,
    val signedContracts: Int,
    val expectedBefore: Int,
    val expectedAfter: Int,
    val state: CopyDispatchState = CopyDispatchState.INTENDED,
    val followerOrderId: String? = null,
)

/** All state changes are durable before the corresponding network write can be attempted. */
internal class CopyDispatchJournal(private val store: SnapshotStore) {
    private val key = "copy-dispatches-v1"
    private val codec = ListSerializer(CopyDispatch.serializer())
    private val mutex = Mutex()
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all { it.brokerId.isNotBlank() && it.leaderId.isNotBlank() && it.sourceOrderId.isNotBlank() &&
            it.sourceExecutionId.isNotBlank() && it.followerId.isNotBlank() && it.followerId != it.leaderId &&
            it.requestId.isNotBlank() && it.signedContracts != 0 && it.expectedBefore.toLong() + it.signedContracts == it.expectedAfter.toLong() &&
            when (it.state) {
                CopyDispatchState.ACCEPTED, CopyDispatchState.CONFIRMED -> !it.followerOrderId.isNullOrBlank()
                CopyDispatchState.INTENDED, CopyDispatchState.REJECTED -> it.followerOrderId == null
                CopyDispatchState.UNCERTAIN -> it.followerOrderId == null || it.followerOrderId.isNotBlank()
            } })
        require(rows.map { it.requestId }.distinct().size == rows.size)
        val orderIds = rows.mapNotNull { it.followerOrderId }
        require(orderIds.distinct().size == orderIds.size)
        require(rows.map { listOf(it.brokerId, it.leaderId, it.sourceExecutionId, it.followerId, it.leg.name) }.distinct().size == rows.size)
        rows
    }
    private var records = loaded.getOrDefault(emptyList())
    private val unreadable = loaded.isFailure

    suspend fun begin(dispatch: CopyDispatch): Boolean = mutex.withLock {
        if (unreadable || dispatch.brokerId.isBlank() || dispatch.leaderId.isBlank() || dispatch.sourceOrderId.isBlank() ||
            dispatch.sourceExecutionId.isBlank() || dispatch.followerId.isBlank() || dispatch.followerId == dispatch.leaderId ||
            dispatch.requestId.isBlank() || dispatch.signedContracts == 0 || dispatch.state != CopyDispatchState.INTENDED ||
            dispatch.followerOrderId != null || dispatch.expectedBefore.toLong() + dispatch.signedContracts != dispatch.expectedAfter.toLong() ||
            records.any { it.requestId == dispatch.requestId ||
                (it.brokerId == dispatch.brokerId && it.leaderId == dispatch.leaderId && it.sourceExecutionId == dispatch.sourceExecutionId &&
                    it.followerId == dispatch.followerId && it.leg == dispatch.leg) })
            return@withLock false
        persist(records + dispatch)
    }

    suspend fun accepted(requestId: String, orderId: String): Boolean = mutex.withLock {
        if (orderId.isBlank() || records.any { it.followerOrderId == orderId && it.requestId != requestId }) return@withLock false
        transition(requestId) { it.takeIf { row -> row.state == CopyDispatchState.INTENDED }?.copy(state = CopyDispatchState.ACCEPTED, followerOrderId = orderId) }
    }

    /** The caller must supply a final broker-confirmed fill, not merely an accepted order and matching net position. */
    suspend fun confirmed(requestId: String, observedOrderId: String, filledContracts: Int, observedNet: Int, isFinal: Boolean): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> isFinal && row.state == CopyDispatchState.ACCEPTED && row.followerOrderId == observedOrderId &&
                filledContracts.toLong() == kotlin.math.abs(row.signedContracts.toLong()) && row.expectedAfter == observedNet }
            ?.copy(state = CopyDispatchState.CONFIRMED) }
    }

    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyDispatchState.INTENDED }?.copy(state = CopyDispatchState.REJECTED) }
    }

    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyDispatchState.INTENDED || row.state == CopyDispatchState.ACCEPTED }
            ?.copy(state = CopyDispatchState.UNCERTAIN) }
    }

    suspend fun read(): List<CopyDispatch>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, apply: (CopyDispatch) -> CopyDispatch?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }

    private fun persist(next: List<CopyDispatch>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
