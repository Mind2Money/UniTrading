package com.unitrading.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.unitrading.broker.OrderRole

@Serializable
internal data class CopyRecoveryCheckpoint(val brokerId: String, val groupId: String, val leaderId: String,
    val accounts: Map<String, CopyRecoveryAccount>, val followerStatus: Map<String, CopyFollowerRunStatus>)

/** Persistent checkpoints are evidence for reconciliation, not permission to send an order. */
internal class CopyRecoveryCheckpointStore(private val store: SnapshotStore) {
    private val key = "copy-recovery-checkpoints-v1"
    private val codec = ListSerializer(CopyRecoveryCheckpoint.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopyRecoveryCheckpoint): Boolean = row.brokerId.startsWith("tradovate-") && row.groupId.isNotBlank() &&
        row.leaderId.isNotBlank() && row.followerStatus.isNotEmpty() && row.leaderId !in row.followerStatus &&
        row.followerStatus.keys.all(String::isNotBlank) && row.accounts.keys == row.followerStatus.keys + row.leaderId &&
        row.accounts.all { (id, account) -> id == account.accountId && account.positions.all { it.id.isNotBlank() && it.symbol.isNotBlank() && it.lotsHundredths > 0 } &&
            account.orders.all { it.id.isNotBlank() && it.request.symbol.isNotBlank() && it.role != OrderRole.UNRESOLVED &&
                it.filledLotsHundredths >= 0 && it.filledLotsHundredths <= it.request.lotsHundredths } &&
            account.executions.all { it.id.isNotBlank() && it.orderId.isNotBlank() && it.symbol.isNotBlank() && it.lotsHundredths > 0 } &&
            account.positions.map { it.id }.distinct().size == account.positions.size && account.orders.map { it.id }.distinct().size == account.orders.size &&
            account.executions.map { it.id }.distinct().size == account.executions.size }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.brokerId }.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())

    suspend fun save(row: CopyRecoveryCheckpoint): Boolean = mutex.withLock {
        if (unreadable || !valid(row)) return@withLock false
        val next = records.filterNot { it.brokerId == row.brokerId } + row
        try { store.write(key, Json.encodeToString(codec, next)); records = next; true } catch (_: Exception) { false }
    }
    /** After restart, previously running followers wait for reconciliation; prior failures stay paused. */
    suspend fun readAfterRestart(brokerId: String): CopyRecoveryCheckpoint? = mutex.withLock {
        if (unreadable) return@withLock null
        records.singleOrNull { it.brokerId == brokerId }?.let { it.copy(followerStatus = CopyFollowerIsolation.afterRestart(it.followerStatus)) }
    }
}
