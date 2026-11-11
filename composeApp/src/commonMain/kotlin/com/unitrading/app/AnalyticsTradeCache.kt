package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

internal class AnalyticsTradeCache(private val store: SnapshotStore, private val clock: Clock = Clock.System) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableRows = MutableStateFlow<Map<String, Map<String, List<ClosedTrade>>>>(emptyMap())
    val rows = mutableRows.asStateFlow()
    private var persisted = mutableRows.value

    suspend fun update(broker: String, account: String, fresh: List<ClosedTrade>) = mutex.withLock {
        val current = if (!loaded) {
            persisted = withContext(Dispatchers.Default) {
                runCatching { store.read(KEY)?.let { Json.decodeFromString<Map<String, Map<String, List<ClosedTrade>>>>(it) } }.getOrNull().orEmpty()
            }
            loaded = true
            persisted
        } else mutableRows.value
        val cutoff = (clock.now() - 30.days).toEpochMilliseconds()
        val latest = mergeAnalyticsTrades(current[broker]?.get(account).orEmpty(), fresh.filter { it.closedAtMs >= cutoff })
        val next = (current + (broker to (current[broker].orEmpty() + (account to latest)))).mapValues { (_, accounts) ->
            accounts.mapValues { (_, trades) -> trades.filter { it.closedAtMs >= cutoff } }.filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
        mutableRows.value = next
        persist(next)
    }

    suspend fun clear() = mutex.withLock {
        loaded = true
        val empty = emptyMap<String, Map<String, List<ClosedTrade>>>()
        mutableRows.value = empty
        persist(empty, force = true)
    }

    private suspend fun persist(next: Map<String, Map<String, List<ClosedTrade>>>, force: Boolean = false) {
        if (!force && next == persisted) return
        withContext(Dispatchers.Default + NonCancellable) {
            if (runCatching { store.write(KEY, Json.encodeToString(next)) }.isSuccess) persisted = next
        }
    }

    private companion object { const val KEY = "analytics-trades-v1" }
}

internal fun mergeAnalyticsTrades(cached: List<ClosedTrade>, fresh: List<ClosedTrade>): List<ClosedTrade> {
    if (cached.isEmpty()) return fresh
    val merged = cached.associateByTo(linkedMapOf()) { it.id }
    fresh.forEach { trade ->
        val previous = merged[trade.id]
        merged[trade.id] = if (previous?.netPnlMicros != null && trade.netPnlMicros == null &&
            previous.closedAtMs == trade.closedAtMs && previous.lotsHundredths == trade.lotsHundredths &&
            previous.symbol == trade.symbol && previous.side == trade.side &&
            previous.entryPrice == trade.entryPrice && previous.exitPrice == trade.exitPrice &&
            (trade.feesMicros == null || previous.feesMicros == trade.feesMicros) &&
            (trade.grossPnlMicros == null || previous.grossPnlMicros == trade.grossPnlMicros)) {
            trade.copy(netPnlMicros = previous.netPnlMicros, grossPnlMicros = trade.grossPnlMicros ?: previous.grossPnlMicros,
                feesMicros = trade.feesMicros ?: previous.feesMicros)
        } else trade
    }
    return merged.values.sortedWith(compareBy(ClosedTrade::closedAtMs, ClosedTrade::id))
}
