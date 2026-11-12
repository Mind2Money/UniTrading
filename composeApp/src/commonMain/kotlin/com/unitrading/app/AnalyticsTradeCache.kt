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
 * encrypted like the saved logins; forgetting the saved logins clears them all. Disconnecting a login keeps its part:
 * the login stays saved and comes back, and the broker would not return its older trades again.
 */
internal class AnalyticsTradeCache(private val store: SnapshotStore, private val clock: Clock = Clock.System) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableRows = MutableStateFlow<Map<String, Map<String, List<ClosedTrade>>>>(emptyMap())
    val rows = mutableRows.asStateFlow()
    private var persisted = mutableRows.value

    /**
     * The stored trades without those older than 30 days; every caller writes its result, so the file loses them too.
     * A file that cannot be read counts as empty and is replaced by the next write.
     */
    private suspend fun load(): Map<String, Map<String, List<ClosedTrade>>> {
        if (!loaded) {
            persisted = withContext(Dispatchers.Default) {
                runCatching { store.read(KEY)?.let { Json.decodeFromString<Map<String, Map<String, List<ClosedTrade>>>>(it) } }.getOrNull().orEmpty()
            }
            mutableRows.value = recent(persisted)
            loaded = true
        }
        return mutableRows.value
    }

    /**
     * Reads the file if nothing has yet and writes it back when trades older than 30 days were dropped, so they leave
     * it even in a run where no login updates the cache.
     */
    suspend fun prune() = mutex.withLock { persist(load()) }

    /** Reconciles the account's cached trades with the broker's snapshot [fresh], as [mergeAnalyticsTrades] does, and stores the result. */
    suspend fun update(broker: String, account: String, fresh: List<ClosedTrade>) = mutex.withLock {
        val current = load()
        val latest = mergeAnalyticsTrades(current[broker]?.get(account).orEmpty(), fresh)
        // The snapshot can reach back further than 30 days, so the trim runs after the reconciliation too.
        val next = recent(current + (broker to (current[broker].orEmpty() + (account to latest))))
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

    private fun recent(rows: Map<String, Map<String, List<ClosedTrade>>>): Map<String, Map<String, List<ClosedTrade>>> {
        val cutoff = (clock.now() - 30.days).toEpochMilliseconds()
        return rows.mapValues { (_, accounts) ->
            accounts.mapValues { (_, trades) -> trades.filter { it.closedAtMs >= cutoff } }.filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
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
 * The trades to show and to keep for one account, reconciled contract by contract. For a contract the broker's
 * snapshot [fresh] has trades of, the snapshot is authoritative from that contract's earliest close in it on: a cached
 * trade of the contract in that range which the snapshot no longer has was withdrawn or paired again under another id,
 * so it is dropped instead of being counted beside its replacement. A contract the snapshot has no trades of keeps all
 * its cached trades: a broker can leave a whole contract out (Rithmic does when the replay of its fills does not end on
 * the server's position, or when the contract's details are missing), which says nothing about them. Cached trades that
 * closed before their contract's range stay, so a withdrawn trade that was its contract's earliest stays until the
 * 30-day trim. A trade in the snapshot replaces the cached one with its id. An empty snapshot (nothing synchronized
 * yet) changes nothing.
 */
internal fun mergeAnalyticsTrades(cached: List<ClosedTrade>, fresh: List<ClosedTrade>): List<ClosedTrade> {
    if (cached.isEmpty()) return fresh
    if (fresh.isEmpty()) return cached
    val covered = fresh.groupBy { it.symbol }.mapValues { (_, trades) -> trades.minOf { it.closedAtMs } }
    val known = cached.associateBy { it.id }
    val merged = cached.filter { trade -> covered[trade.symbol].let { start -> start == null || trade.closedAtMs < start } }
        .associateByTo(linkedMapOf()) { it.id }
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
