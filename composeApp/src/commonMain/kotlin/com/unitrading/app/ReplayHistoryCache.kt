package com.unitrading.app

import com.unitrading.broker.BrokerPlugin
import com.unitrading.broker.Candle
import com.unitrading.broker.HistoricalRangeResult
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val DAY_MS = 86_400_000L
private const val CHUNK_MS = 30 * DAY_MS
private const val CACHE_SLOTS = 64
private const val MEMORY_SLOTS = 8
private const val CONTINUOUS_TTL = 30 * 60_000L
private const val CONTRACT_TTL = 7 * DAY_MS

@Serializable
private data class ReplayCacheEntry(val version: Int = 1, val broker: String, val symbol: String, val source: String,
    val seconds: Int, val start: Long, val end: Long, val savedAt: Long, val candles: List<Candle>, val warning: String?)

internal data class ReplayLoadedHistory(val result: HistoricalRangeResult, val cacheHits: Int, val downloaded: Int)

/** Bounded cache for completed chunks; a partial/failed broker read never becomes a cache hit. */
internal class ReplayHistoryCache(private val store: SnapshotStore, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val permits = Semaphore(2)
    private val storageLock = Mutex()
    private val slotLocks = Array(CACHE_SLOTS) { Mutex() }
    private val memory = linkedMapOf<Int, ReplayCacheEntry>()

    private fun source(broker: BrokerPlugin, symbol: String): String? = if (!symbol.startsWith("@")) symbol else
        broker.catalog.value.instruments.firstOrNull { it.symbol == symbol }?.executionSymbol

    private fun segments(start: Long, end: Long, stableBefore: Long): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        var from = start
        while (from < end) {
            val remainder = CHUNK_MS - from % CHUNK_MS
            var until = if (end - from <= remainder) end else from + remainder
            if (stableBefore > from && stableBefore < until) until = stableBefore
            result += from to until
            from = until
        }
        return result
    }

    suspend fun load(broker: BrokerPlugin, symbol: String, frame: Timeframe, start: Long, end: Long,
        enabled: Boolean = true, persistent: Boolean = true,
        progress: (finished: Int, total: Int, cached: Int) -> Unit = { _, _, _ -> }): ReplayLoadedHistory = coroutineScope {
        require(start >= 0 && start < end) { "历史数据日期范围无效" }
        val clock = now()
        // Recent bars and daily settlements can still be corrected. Do not persist the last two UTC days.
        val stableBefore = ((clock / DAY_MS - 2).coerceAtLeast(0)) * DAY_MS
        val chunks = if (frame.seconds >= Timeframe.MINUTE) segments(start, end, stableBefore) else listOf(start to end)
        val identity = source(broker, symbol)
        val progressLock = Mutex()
        var finished = 0
        var hits = 0
        val results = chunks.map { (from, until) -> async {
            val useCache = enabled && identity != null && frame.seconds >= Timeframe.MINUTE && until <= stableBefore
            val id = "${broker.descriptor.id}|$symbol|$identity|${frame.seconds}|$from|$until"
            val slot = (id.hashCode().toLong() and 0x7fffffffL).rem(CACHE_SLOTS).toInt()
            val outcome = if (!useCache) permits.withPermit { broker.historicalRange(symbol, frame, from, until) } to false
            else slotLocks[slot].withLock {
                val key = "replay-history-v1-$slot"
                val cached = try {
                    storageLock.withLock {
                        memory[slot] ?: if (persistent) withContext(Dispatchers.Default) { store.read(key)?.let { Json.decodeFromString<ReplayCacheEntry>(it) } } else null
                    }
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                val ttl = if (symbol.startsWith("@")) CONTINUOUS_TTL else CONTRACT_TTL
                val valid = cached != null && cached.version == 1 && cached.broker == broker.descriptor.id && cached.symbol == symbol &&
                    cached.source == identity && cached.seconds == frame.seconds && cached.start == from && cached.end == until &&
                    cached.savedAt in 0..clock && clock - cached.savedAt <= ttl && source(broker, symbol) == identity &&
                    cached.candles.size <= 50_000 && cached.candles.all { it.timeMs >= from && it.timeMs < until && it.low > 0 &&
                        it.high >= maxOf(it.open, it.close) && it.low <= minOf(it.open, it.close) && it.volume?.let { v -> v >= 0 } != false } &&
                    cached.candles.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }
                if (valid) HistoricalRangeResult(cached!!.candles, cached.warning, cacheable = true) to true
                else {
                    val fresh = permits.withPermit { broker.historicalRange(symbol, frame, from, until) }
                    val bars = fresh.candles.sortedBy { it.timeMs }
                    // Empty responses and incomplete provider fallbacks are never persisted.
                    if (fresh.cacheable && bars.isNotEmpty() && bars.size <= 50_000 && source(broker, symbol) == identity &&
                        bars.all { it.timeMs >= from && it.timeMs < until && it.low > 0 && it.high >= maxOf(it.open, it.close) &&
                            it.low <= minOf(it.open, it.close) && it.volume?.let { v -> v >= 0 } != false } &&
                        bars.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }) {
                        val entry = ReplayCacheEntry(broker = broker.descriptor.id, symbol = symbol, source = requireNotNull(identity),
                            seconds = frame.seconds, start = from, end = until, savedAt = clock, candles = bars, warning = fresh.warning)
                        try {
                            storageLock.withLock {
                                memory.remove(slot); memory[slot] = entry
                                while (memory.size > MEMORY_SLOTS) memory.remove(memory.keys.first())
                                if (persistent) withContext(Dispatchers.Default) { store.write(key, Json.encodeToString(entry)) }
                            }
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Cache is optional. */ }
                    }
                    fresh to false
                }
            }
            progressLock.withLock {
                finished++
                if (outcome.second) hits++
                progress(finished, chunks.size, hits)
            }
            outcome
        } }.awaitAll()
        val bars = results.flatMap { it.first.candles }.filter { it.timeMs in start until end }
            .associateBy(Candle::timeMs).values.sortedBy(Candle::timeMs)
        val warnings = results.mapNotNull { it.first.warning }.distinct()
        ReplayLoadedHistory(HistoricalRangeResult(bars, warnings.takeIf { it.isNotEmpty() }?.joinToString("；"),
            cacheable = results.all { it.first.cacheable }), results.count { it.second }, results.count { !it.second })
    }
}
