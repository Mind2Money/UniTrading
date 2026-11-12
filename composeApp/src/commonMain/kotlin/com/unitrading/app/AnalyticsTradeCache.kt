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

/**
 * Closed trades this device has synchronized, per login and account, for the analytics page: what the broker returned
 * while the app ran with that account selected, kept for 30 days after the trade closed. It never asks the broker for
 * older history. The trades are stored as plain JSON (`analytics-trades-v1`) in the app's private data folder, not
 * encrypted like the saved logins; forgetting the saved logins clears them all, disconnecting a login clears its part.
 */
internal class AnalyticsTradeCache(private val store: SnapshotStore, private val clock: Clock = Clock.System) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableRows = MutableStateFlow<Map<String, Map<String, List<ClosedTrade>>>>(emptyMap())
    val rows = mutableRows.asStateFlow()
    private var persisted = mutableRows.value

    /** The stored trades; a file that cannot be read counts as empty and is replaced by the next write. */
    private suspend fun load(): Map<String, Map<String, List<ClosedTrade>>> {
        if (!loaded) {
            persisted = withContext(Dispatchers.Default) {
                runCatching { store.read(KEY)?.let { Json.decodeFromString<Map<String, Map<String, List<ClosedTrade>>>>(it) } }.getOrNull().orEmpty()
            }
            mutableRows.value = persisted
            loaded = true
        }
        return mutableRows.value
    }

    /** Reconciles the account's cached trades with the broker's snapshot [fresh], as [mergeAnalyticsTrades] does, and stores the result. */
    suspend fun update(broker: String, account: String, fresh: List<ClosedTrade>) = mutex.withLock {
        val current = load()
        val cutoff = (clock.now() - 30.days).toEpochMilliseconds()
        val latest = mergeAnalyticsTrades(current[broker]?.get(account).orEmpty(), fresh)
        val next = (current + (broker to (current[broker].orEmpty() + (account to latest)))).mapValues { (_, accounts) ->
            accounts.mapValues { (_, trades) -> trades.filter { it.closedAtMs >= cutoff } }.filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
        mutableRows.value = next
        persist(next)
    }

    /** Deletes the cached trades of [broker], or of every login when it is null. The file is rewritten even when nothing was read from it. */
    suspend fun clear(broker: String? = null) = mutex.withLock {
        val next = if (broker == null) emptyMap() else load() - broker
        loaded = true
        mutableRows.value = next
        persist(next, force = true)
    }

    private suspend fun persist(next: Map<String, Map<String, List<ClosedTrade>>>, force: Boolean = false) {
        if (!force && next == persisted) return
        withContext(Dispatchers.Default + NonCancellable) {
            if (runCatching { store.write(KEY, Json.encodeToString(next)) }.isSuccess) persisted = next
        }
    }

    private companion object { const val KEY = "analytics-trades-v1" }
}

/**
 * The trades to show and to keep for one account. The broker's snapshot [fresh] is authoritative from its earliest
 * close on: a cached trade in that range which the snapshot no longer has was withdrawn or paired again under another
 * id, so it is dropped instead of being counted beside its replacement. Cached trades that closed before that stay,
 * and a trade in the snapshot replaces the cached one with its id. An empty snapshot (nothing synchronized yet)
 * changes nothing.
 */
internal fun mergeAnalyticsTrades(cached: List<ClosedTrade>, fresh: List<ClosedTrade>): List<ClosedTrade> {
    if (cached.isEmpty()) return fresh
    if (fresh.isEmpty()) return cached
    val covered = fresh.minOf { it.closedAtMs }
    val known = cached.associateBy { it.id }
    val merged = cached.filter { it.closedAtMs < covered }.associateByTo(linkedMapOf()) { it.id }
    fresh.forEach { trade ->
        val previous = known[trade.id]
        // The broker reports a trade again before its fees are loaded; the same trade keeps the net result cached for it.
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
