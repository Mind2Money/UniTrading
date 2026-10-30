package com.unitrading.tradovate

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Non-secret market data; implementations should replace each value atomically. */
interface CandleCacheStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

@Serializable
private data class CachedCandles(val version: Int = 1, val candles: List<Candle>)

internal class CandleCache(private val store: CandleCacheStore?, private val namespace: String) {
    // Hex encoding is collision-free and keeps symbols such as @MES out of filesystem paths.
    private fun key(series: Pair<String, Timeframe>): String =
        "candles-v1-$namespace-${series.first.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}-${series.second.minutes}"

    suspend fun read(series: Pair<String, Timeframe>, now: Long): List<Candle> = try {
        val text = store?.read(key(series))
        val cached = text?.let { Json.decodeFromString<CachedCandles>(it) }
        if (cached == null || cached.version != 1 || cached.candles.size > CHART_BARS ||
            cached.candles.any { it.timeMs !in 0..now || it.low <= 0 || it.high < maxOf(it.open, it.close) ||
                it.low > minOf(it.open, it.close) || it.volume?.let { v -> v < 0 } == true } ||
            cached.candles.zipWithNext().any { (a, b) -> a.timeMs >= b.timeMs }) emptyList()
        else cached.candles
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { emptyList() }

    suspend fun write(series: Pair<String, Timeframe>, candles: List<Candle>) {
        store?.write(key(series), Json.encodeToString(CachedCandles(candles = candles)))
    }
}

internal fun chartTimeRange(cached: List<Candle>) = buildJsonObject {
    // Cached subscriptions only establish the live stream; history is backfilled separately.
    put("asMuchAsElements", if (cached.isEmpty()) CHART_BARS else 1)
}

internal fun chartBackfillRange(anchor: Long, closest: Long) = buildJsonObject {
    put("asFarAsTimestamp", Instant.fromEpochMilliseconds(anchor).toString())
    put("closestTimestamp", Instant.fromEpochMilliseconds(closest).toString())
}

internal fun mergeCandles(previous: List<Candle>, incoming: List<Candle>): List<Candle> =
    (previous + incoming).associateBy(Candle::timeMs).values.sortedBy(Candle::timeMs).takeLast(CHART_BARS)

/** A partial historical response must never become the next incremental checkpoint. */
internal class ChartLoad(cached: List<Candle>) {
    val anchor = cached.lastOrNull()?.timeMs
    var candles = cached
        private set
    var complete = false
        private set
    var nextPageBefore: Long? = null
        private set
    var failed = false
        private set
    // Only this load's historical bars can establish a complete checkpoint.
    private var history = emptyList<Candle>()
    private var realtime = emptyList<Candle>()
    private var pageOldest: Long? = null
    private var previousPageOldest: Long? = null

    fun beginPage() {
        pageOldest = null
        nextPageBefore = null
    }

    fun accept(incoming: List<Candle>, historical: Boolean, endOfHistory: Boolean, validPacket: Boolean, countForBackfill: Boolean = true) {
        if (!validPacket) {
            failed = true
            complete = false
            nextPageBefore = null
        }
        if (historical) {
            if (countForBackfill) {
                history = mergeCandles(history, incoming)
                incoming.minOfOrNull(Candle::timeMs)?.let { oldest ->
                    pageOldest = minOf(pageOldest ?: oldest, oldest)
                }
            }
        } else realtime = mergeCandles(realtime, incoming)
        candles = mergeCandles(mergeCandles(candles, incoming), realtime)
        if (!historical || !endOfHistory || !countForBackfill || failed) return

        if (anchor == null || history.firstOrNull()?.let { it.timeMs <= anchor } == true || history.size >= CHART_BARS) {
            complete = true
            nextPageBefore = null
        } else {
            complete = false
            val oldest = pageOldest
            val previous = previousPageOldest
            if (oldest != null && (previous == null || oldest < previous)) {
                nextPageBefore = oldest
                previousPageOldest = oldest
            } else {
                failed = true
                nextPageBefore = null
            }
        }
    }
}
