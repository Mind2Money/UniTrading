package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ReplayEquityPoint(val timeMs: Long, val equityMicros: Long)

/** Local replay snapshots. Unreadable data is never replaced with an empty list. */
@Serializable
internal data class ReplayRecord(
    val id: String,
    val symbol: String,
    val currency: String,
    val initialMicros: Long,
    val savedAtMs: Long,
    val trades: List<ClosedTrade>,
    val status: String = "已保存",
    val endedAtMs: Long? = null,
    val finalEquityMicros: Long? = null,
    val equity: List<ReplayEquityPoint> = emptyList(),
    val priceDecimals: Int? = null,
    val description: String = "",
)

internal class ReplayRecords(private val store: SnapshotStore) {
    fun list(): List<ReplayRecord> {
        val old = store.read(LEGACY_KEY)?.let { Json.decodeFromString<List<ReplayRecord>>(it) }.orEmpty()
        val current = store.read(KEY)?.let { Json.decodeFromString<List<ReplayRecord>>(it) }.orEmpty()
        val updatedIds = current.map { it.id }.toSet()
        return old.filterNot { it.id in updatedIds } + current
    }

    fun save(record: ReplayRecord) {
        val rows = list().filterNot { it.id == record.id } + record
        store.write(KEY, Json.encodeToString(rows))
    }

    fun delete(id: String) {
        val rows = list().filterNot { it.id == id }
        store.write(KEY, Json.encodeToString(rows))
        val legacy = store.read(LEGACY_KEY)?.let { Json.decodeFromString<List<ReplayRecord>>(it) }
        if (legacy != null) store.write(LEGACY_KEY, Json.encodeToString(legacy.filterNot { it.id == id }))
    }

    companion object {
        internal const val KEY = "replay-records-v2"
        internal const val LEGACY_KEY = "replay-records-v1"
    }
}
