package com.unitrading.tradovate

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
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

/** How far a fresh chart reads back: four chart-widths of wall time cover weekends and holidays. */
internal fun chartHistoryFloor(timeframe: Timeframe, now: Long): Long = maxOf(0, now - timeframe.millis * CHART_BARS * 4)

/** Bounds a fresh chart's requests against the server's hourly chart budget, whatever a custom timeframe asks for. */
internal const val MAX_FRESH_HISTORY_PAGES = 24

/** How long a cached chart keeps showing its cached bars alone while the bars it lacks are read. */
internal const val CHART_JOIN_HOLD_MS = 5_000L

/** A background page that has not ended by then is given up, and its subscription cancelled. */
internal const val PRELOAD_PAGE_TIMEOUT_MS = 30_000L
/** How often an idle background reader looks for work without being woken. */
internal const val PRELOAD_IDLE_MS = 15_000L
/** Pause of the whole background reader after a series fails; doubles with each failure in a row. */
internal const val PRELOAD_PAUSE_MS = 30_000L
/** Pause after a background request went unanswered and the market connection was closed to release it. */
internal const val PRELOAD_RESET_PAUSE_MS = 10 * 60_000L
/** Before a failed series is read again; multiplied by its number of failures. */
internal const val PRELOAD_RETRY_MS = 5 * 60_000L
/** Before a series the server had no bars for is asked for again. */
internal const val PRELOAD_EMPTY_RETRY_MS = 60 * 60_000L

/**
 * A partial historical response must never become the next incremental checkpoint. One request returns only about
 * a month of intraday bars, so a fresh chart pages backward to [freshFloor] as well. It stays uncached until it
 * ends, because a cached series only ever grows at its end and a shallow one would stay shallow.
 */
internal class ChartLoad(cached: List<Candle>, freshFloor: Long? = null) {
    val anchor = cached.lastOrNull()?.timeMs
    val floor = anchor ?: freshFloor
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
    private var freshPages = 0

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

        if (floor == null || history.firstOrNull()?.let { it.timeMs <= floor } == true || history.size >= CHART_BARS) {
            complete = true
            nextPageBefore = null
        } else {
            complete = false
            val oldest = pageOldest
            val previous = previousPageOldest
            val advanced = oldest != null && (previous == null || oldest < previous)
            if (advanced && (anchor != null || ++freshPages < MAX_FRESH_HISTORY_PAGES)) {
                nextPageBefore = oldest
                previousPageOldest = oldest
            } else {
                nextPageBefore = null
                // A fresh chart has simply reached the start of what the server keeps; a cached one must join its anchor.
                if (anchor == null) complete = true else failed = true
            }
        }
    }
}
