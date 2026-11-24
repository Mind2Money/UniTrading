package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local, closed-trade snapshots. Unreadable data is never replaced with an empty list. */
@Serializable
internal data class ReplayRecord(
    val id: String,
    val symbol: String,
    val currency: String,
    val initialMicros: Long,
    val savedAtMs: Long,
    val trades: List<ClosedTrade>,
)

internal class ReplayRecords(private val store: SnapshotStore) {
    fun list(): List<ReplayRecord> = store.read(KEY)?.let { Json.decodeFromString<List<ReplayRecord>>(it) }.orEmpty()

    fun save(record: ReplayRecord) {
        val rows = list().filterNot { it.id == record.id } + record
        store.write(KEY, Json.encodeToString(rows))
    }

    companion object { internal const val KEY = "replay-records-v1" }
}
