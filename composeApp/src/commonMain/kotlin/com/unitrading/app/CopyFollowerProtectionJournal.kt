package com.unitrading.app

import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.Side
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopyProtectionLeg { STOP, TARGET }
@Serializable
internal enum class CopyFollowerProtectionState { INTENDED, ACCEPTED, CONFIRMED, REJECTED, UNCERTAIN }

@Serializable
internal data class CopyFollowerProtection(
    val brokerId: String,
    val leaderId: String,
    val groupId: String,
    val sourceMutationId: String,
    val followerId: String,
    val positionId: String,
    val symbol: String,
    val leg: CopyProtectionLeg,
    val requestId: String,
    val previousVersion: Long,
    val previousRevision: Long,
    val epoch: Long,
    val side: Side,
    val lotsHundredths: Long,
    val previousStop: Long?,
    val previousTarget: Long?,
    val requestedStop: Long?,
    val requestedTarget: Long?,
    val state: CopyFollowerProtectionState = CopyFollowerProtectionState.INTENDED,
)

/** A follower protection action is persisted before the broker request, one leg at a time. */
internal class CopyFollowerProtectionJournal(private val store: SnapshotStore, private val origins: CopySourceProtectionJournal,
    private val dispatches: CopyDispatchJournal, private val pending: CopyPendingOrderJournal) {
    private val key = "copy-follower-protections-v1"
    private val codec = ListSerializer(CopyFollowerProtection.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopyFollowerProtection): Boolean = listOf(row.brokerId, row.leaderId, row.groupId, row.sourceMutationId,
        row.followerId, row.positionId, row.symbol, row.requestId).all(String::isNotBlank) && row.leaderId != row.followerId &&
        row.previousVersion > 0 && row.previousRevision > 0 && row.epoch > 0 && row.lotsHundredths > 0 &&
        listOfNotNull(row.previousStop, row.previousTarget, row.requestedStop, row.requestedTarget).all { it > 0 } && when (row.leg) {
            CopyProtectionLeg.STOP -> row.previousStop != row.requestedStop && row.previousTarget == row.requestedTarget
            CopyProtectionLeg.TARGET -> row.previousTarget != row.requestedTarget && row.previousStop == row.requestedStop
        }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.requestId }.distinct().size == rows.size &&
            rows.map { listOf(it.brokerId, it.groupId, it.sourceMutationId, it.followerId, it.leg.name) }.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())

    /** Proves a confirmed origin and a broker-confirmed follower entry, not merely matching account symbols. */
    suspend fun begin(row: CopyFollowerProtection, follower: AccountSnapshot): Boolean = mutex.withLock {
        if (unreadable || !valid(row) || row.state != CopyFollowerProtectionState.INTENDED || records.any { it.requestId == row.requestId ||
                (it.brokerId == row.brokerId && it.groupId == row.groupId && it.sourceMutationId == row.sourceMutationId &&
                    it.followerId == row.followerId && it.leg == row.leg) } || follower.accountId != row.followerId ||
            follower.copyRevision != row.previousRevision || follower.copyEpoch != row.epoch || !follower.copyPositionsComplete ||
            !follower.copyOrdersComplete) return@withLock false
        if (records.any { it.brokerId == row.brokerId && it.followerId == row.followerId &&
                it.state in setOf(CopyFollowerProtectionState.INTENDED, CopyFollowerProtectionState.ACCEPTED, CopyFollowerProtectionState.UNCERTAIN) })
            return@withLock false
        val originRows = origins.read() ?: return@withLock false
        if (originRows.any { it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.groupId == row.groupId &&
                it.state in setOf(CopySourceProtectionState.INTENDED, CopySourceProtectionState.ACCEPTED, CopySourceProtectionState.UNCERTAIN) })
            return@withLock false
        val origin = originRows.singleOrNull { it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.groupId == row.groupId &&
            it.requestId == row.sourceMutationId && it.state == CopySourceProtectionState.CONFIRMED } ?: return@withLock false
        if (origin.symbol != row.symbol || origin.expectedSide != row.side ||
            (row.leg == CopyProtectionLeg.STOP && origin.requestedStop != row.requestedStop) ||
            (row.leg == CopyProtectionLeg.TARGET && origin.requestedTarget != row.requestedTarget)) return@withLock false
        val position = follower.positions.singleOrNull { it.id == row.positionId } ?: return@withLock false
        if (position.symbol != row.symbol || position.side != row.side || position.lotsHundredths != row.lotsHundredths ||
            position.version != row.previousVersion || position.stopLoss != row.previousStop || position.takeProfit != row.previousTarget ||
            !position.protectionEditable) return@withLock false
        val fills = dispatches.read() ?: return@withLock false
        val links = pending.read() ?: return@withLock false
        val marketOrders = fills.filter { it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.followerId == row.followerId &&
            it.sourceOrderId == origin.sourceOrderId && it.sourceExecutionId == origin.sourceExecutionId && it.leg == CopyDispatchLeg.OPEN &&
            it.state == CopyDispatchState.CONFIRMED }.mapNotNull { it.followerOrderId }
        val pendingOrders = links.filter { it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.followerId == row.followerId &&
            it.sourceOrderId == origin.sourceOrderId && it.state in setOf(CopyPendingState.ACCEPTED, CopyPendingState.TERMINAL) &&
            it.request.symbol == row.symbol && it.request.side == row.side }.mapNotNull { it.followerOrderId }
        val linkedOrders = (marketOrders + pendingOrders).toSet()
        if (follower.executions.none { it.orderId in linkedOrders && it.symbol == row.symbol && it.side == row.side && it.lotsHundredths > 0 })
            return@withLock false
        persist(records + row)
    }
    suspend fun accepted(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyFollowerProtectionState.INTENDED }?.copy(state = CopyFollowerProtectionState.ACCEPTED) }
    }
    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopyFollowerProtectionState.INTENDED }?.copy(state = CopyFollowerProtectionState.REJECTED) }
    }
    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state in setOf(CopyFollowerProtectionState.INTENDED, CopyFollowerProtectionState.ACCEPTED) }
            ?.copy(state = CopyFollowerProtectionState.UNCERTAIN) }
    }
    suspend fun confirm(requestId: String, follower: AccountSnapshot): Boolean = mutex.withLock {
        transition(requestId) { row ->
            val position = follower.positions.singleOrNull { it.id == row.positionId }
            row.takeIf { it.state == CopyFollowerProtectionState.ACCEPTED && follower.accountId == it.followerId &&
                (follower.copyRevision ?: 0L) > it.previousRevision && follower.copyEpoch == it.epoch &&
                follower.copyPositionsComplete && follower.copyOrdersComplete && position != null && position.symbol == it.symbol &&
                position.side == it.side && position.lotsHundredths == it.lotsHundredths && position.version > it.previousVersion &&
                position.protectionEditable && position.stopLoss == it.requestedStop && position.takeProfit == it.requestedTarget }
                ?.copy(state = CopyFollowerProtectionState.CONFIRMED)
        }
    }
    suspend fun read(): List<CopyFollowerProtection>? = mutex.withLock { if (unreadable) null else records.toList() }
    private fun transition(requestId: String, apply: (CopyFollowerProtection) -> CopyFollowerProtection?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }
    private fun persist(next: List<CopyFollowerProtection>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next)); records = next; true
    } catch (_: Exception) { false }
}
