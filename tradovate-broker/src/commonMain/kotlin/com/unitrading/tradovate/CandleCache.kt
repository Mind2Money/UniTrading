package com.unitrading.tradovate

import com.unitrading.broker.Candle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** The cache itself now lives in broker-api so every remote adapter shares one format. */
typealias CandleCacheStore = com.unitrading.broker.CandleCacheStore
internal typealias CandleCache = com.unitrading.broker.CandleCache

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
