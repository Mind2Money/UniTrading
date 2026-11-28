package com.unitrading.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class CopySkippedEvent(val brokerId: String, val groupId: String, val followerId: String, val eventId: String)

/** A recorded skip is permanent for this group and follower, including after process restart. */
internal class CopySkippedEvents(private val store: SnapshotStore) {
    private val key = "copy-skipped-events-v1"
    private val codec = ListSerializer(CopySkippedEvent.serializer())
    private val mutex = Mutex()
    private fun valid(row: CopySkippedEvent) = row.brokerId.startsWith("tradovate-") &&
        listOf(row.groupId, row.followerId, row.eventId).all(String::isNotBlank)
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private var records = loaded.getOrDefault(emptyList())
    private val unrecorded = mutableSetOf<CopySkippedEvent>()

    /** The caller may advance that follower's source checkpoint only after this succeeds. */
    suspend fun skip(row: CopySkippedEvent, state: CopyFollowerRunState): Boolean = mutex.withLock {
        if (unreadable || !valid(row) || state == CopyFollowerRunState.RUNNING) return@withLock false
        if (row in records) return@withLock true
        val next = records + row
        try { store.write(key, Json.encodeToString(codec, next)); records = next; unrecorded.remove(row); true }
        catch (_: Exception) { unrecorded += row; false }
    }

    /** Null means the ledger is unreadable or the event is invalid; never dispatch in that case. */
    suspend fun mayDispatch(row: CopySkippedEvent, state: CopyFollowerRunState): Boolean? = mutex.withLock {
        if (unreadable || !valid(row) || unrecorded.any { it.brokerId == row.brokerId && it.groupId == row.groupId && it.followerId == row.followerId }) null
        else state == CopyFollowerRunState.RUNNING && row !in records
    }
}
