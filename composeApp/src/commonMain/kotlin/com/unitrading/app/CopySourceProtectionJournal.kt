package com.unitrading.app

import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.Position
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal enum class CopySourceProtectionState { INTENDED, ACCEPTED, CONFIRMED, REJECTED, UNCERTAIN }

/** The exact app request, written before the leader's protection command is sent. */
@Serializable
internal data class CopySourceProtection(
    val brokerId: String,
    val leaderId: String,
    val groupId: String,
    val sourceOrderId: String,
    val sourceExecutionId: String,
    val positionId: String,
    val symbol: String,
    val requestId: String,
    val previousVersion: Long,
    val previousRevision: Long,
    val epoch: Long,
    val previousStop: Long?,
    val previousTarget: Long?,
    val requestedStop: Long?,
    val requestedTarget: Long?,
    val state: CopySourceProtectionState = CopySourceProtectionState.INTENDED,
)

/** Never infer an app-originated protection edit from a price change alone. */
internal class CopySourceProtectionJournal(private val store: SnapshotStore, private val sources: CopySourceOrders) {
    private val key = "copy-source-protections-v1"
    private val codec = ListSerializer(CopySourceProtection.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopySourceProtection): Boolean = listOf(row.brokerId, row.leaderId, row.groupId, row.sourceOrderId,
        row.sourceExecutionId, row.positionId, row.symbol, row.requestId).all(String::isNotBlank) && row.previousVersion > 0 &&
        row.previousRevision > 0 && row.epoch > 0 &&
        listOfNotNull(row.previousStop, row.previousTarget, row.requestedStop, row.requestedTarget).all { it > 0 } &&
        (row.previousStop != row.requestedStop || row.previousTarget != row.requestedTarget)
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.requestId }.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())

    /** A same-group app entry and its exact leader execution must be visible in a complete broker snapshot. */
    suspend fun begin(row: CopySourceProtection, snapshot: AccountSnapshot): Boolean = mutex.withLock {
        if (unreadable || !valid(row) || row.state != CopySourceProtectionState.INTENDED || records.any { it.requestId == row.requestId } ||
            snapshot.accountId != row.leaderId || snapshot.copyRevision != row.previousRevision || snapshot.copyEpoch != row.epoch ||
            !snapshot.copyPositionsComplete || !snapshot.copyOrdersComplete) return@withLock false
        if (records.any { it.brokerId == row.brokerId && it.leaderId == row.leaderId && it.groupId == row.groupId &&
                it.state in setOf(CopySourceProtectionState.INTENDED, CopySourceProtectionState.ACCEPTED, CopySourceProtectionState.UNCERTAIN) })
            return@withLock false
        val origin = sources.acceptedSource(row.brokerId, row.leaderId, row.groupId, row.sourceOrderId) ?: return@withLock false
        val request = origin.request ?: return@withLock false
        val position = snapshot.positions.singleOrNull { it.id == row.positionId } ?: return@withLock false
        if (request.symbol != row.symbol || position.symbol != row.symbol || position.side != request.side ||
            position.version != row.previousVersion || position.stopLoss != row.previousStop || position.takeProfit != row.previousTarget ||
            !position.protectionEditable || position.lotsHundredths <= 0 ||
            snapshot.executions.none { it.id == row.sourceExecutionId && it.orderId == row.sourceOrderId && it.symbol == row.symbol &&
                it.side == request.side && it.lotsHundredths > 0 }) return@withLock false
        persist(records + row)
    }

    suspend fun accepted(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceProtectionState.INTENDED }?.copy(state = CopySourceProtectionState.ACCEPTED) }
    }
    suspend fun rejected(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state == CopySourceProtectionState.INTENDED }?.copy(state = CopySourceProtectionState.REJECTED) }
    }
    suspend fun uncertain(requestId: String): Boolean = mutex.withLock {
        transition(requestId) { it.takeIf { row -> row.state in setOf(CopySourceProtectionState.INTENDED, CopySourceProtectionState.ACCEPTED) }
            ?.copy(state = CopySourceProtectionState.UNCERTAIN) }
    }
    /** A successful broker response alone is insufficient; the changed position must arrive in a fresh complete snapshot. */
    suspend fun confirm(requestId: String, snapshot: AccountSnapshot): Boolean = mutex.withLock {
        transition(requestId) { row ->
            val position: Position? = snapshot.positions.singleOrNull { it.id == row.positionId }
            row.takeIf { it.state == CopySourceProtectionState.ACCEPTED && snapshot.accountId == it.leaderId &&
                (snapshot.copyRevision ?: 0L) > it.previousRevision && snapshot.copyEpoch == it.epoch &&
                snapshot.copyPositionsComplete && snapshot.copyOrdersComplete && position != null &&
                position.symbol == it.symbol && position.version > it.previousVersion && position.protectionEditable &&
                position.stopLoss == it.requestedStop && position.takeProfit == it.requestedTarget }
                ?.copy(state = CopySourceProtectionState.CONFIRMED)
        }
    }
    suspend fun read(): List<CopySourceProtection>? = mutex.withLock { if (unreadable) null else records.toList() }

    private fun transition(requestId: String, apply: (CopySourceProtection) -> CopySourceProtection?): Boolean {
        if (unreadable) return false
        val index = records.indexOfFirst { it.requestId == requestId }
        if (index < 0) return false
        val replacement = apply(records[index]) ?: return false
        return persist(records.toMutableList().also { it[index] = replacement })
    }
    private fun persist(next: List<CopySourceProtection>): Boolean = try {
        store.write(key, Json.encodeToString(codec, next))
        records = next
        true
    } catch (_: Exception) { false }
}
