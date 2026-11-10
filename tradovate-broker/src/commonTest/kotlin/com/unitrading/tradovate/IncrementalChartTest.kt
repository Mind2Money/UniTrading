package com.unitrading.tradovate

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

private const val CACHE_NOW = 1_798_589_000_000L
private const val BAR_STEP = 300_000L
private const val NAMESPACE = "v2-demo-42"

@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalChartTest {
    private class Store : CandleCacheStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { check(!fail); values[key] = value }
    }

    private class Wire : TradovateWire {
        val delegate = MockTradovateWire()
        val charts = mutableListOf<JsonObject>()
        val cancelled = mutableListOf<String>()
        var sameId = false
        var automatic = true
        var replyGate: CompletableDeferred<Unit>? = null
        var cancelGate: CompletableDeferred<Unit>? = null
        var dropChartAck = false
        var dropCancelAck = false
        var earlyPackets = 0
        var marketClosed = false
        val rejectedCancellations = mutableSetOf<String>()
        private var nextId = 100
        var historicalId = 0
        var realtimeId = 0
        var liveId = 0
        var serverBars: List<Long>? = null
        var pageLimit = 2_000
        var failBackfillAfter: Int? = null
        private var backfillRequests = 0
        override suspend fun request(hosts: TradovateHosts, token: String, method: String, path: String, body: JsonObject?) =
            delegate.request(hosts, token, method, path, body)
        override suspend fun socket(url: String): TradovateWireSocket {
            val socket = delegate.socket(url)
            if (!url.contains("md")) return socket
            return object : TradovateWireSocket by socket {
                override suspend fun close() { marketClosed = true; socket.close() }
                override suspend fun send(text: String) {
                    val route = text.substringBefore('\n')
                    if (route != "md/getChart" && route != "md/cancelChart") { socket.send(text); return }
                    val requestId = text.split('\n')[1]
                    val body = Json.parseToJsonElement(text.substringAfter("\n\n")).jsonObject
                    if (route == "md/cancelChart") {
                        val subscription = body.getValue("subscriptionId").jsonPrimitive.content
                        cancelled += subscription
                        cancelGate?.await()
                        if (dropCancelAck) return
                        val status = if (subscription in rejectedCancellations) 500 else 200
                        delegate.market.queue.send("""a[{"i":$requestId,"s":$status,"d":{}}]""")
                        return
                    }
                    charts += body
                    replyGate?.await()
                    if (dropChartAck) return
                    val range = body.getValue("timeRange").jsonObject
                    if (range["asFarAsTimestamp"] != null && ++backfillRequests > (failBackfillAfter ?: Int.MAX_VALUE)) {
                        delegate.market.queue.send("""a[{"i":$requestId,"s":500,"d":{}}]""")
                        return
                    }
                    historicalId = nextId++
                    realtimeId = if (sameId) historicalId else nextId++
                    if (range["asMuchAsElements"] != null) liveId = realtimeId
                    repeat(earlyPackets) { bars(historicalId, CACHE_NOW - BAR_STEP) }
                    delegate.market.queue.send("""a[{"i":$requestId,"s":200,"d":{"historicalId":$historicalId,"realtimeId":$realtimeId}}]""")
                    if (automatic) {
                        val start = range.str("asFarAsTimestamp")?.let { Instant.parse(it).toEpochMilliseconds() } ?: 0
                        val end = range.str("closestTimestamp")?.let { Instant.parse(it).toEpochMilliseconds() } ?: CACHE_NOW
                        val count = minOf(range.long("asMuchAsElements")?.toInt() ?: Int.MAX_VALUE, pageLimit)
                        page(historicalId, (serverBars ?: listOf(CACHE_NOW - BAR_STEP)).filter { it in start..end }.sorted().takeLast(count))
                        eoh()
                    }
                }
            }
        }
        suspend fun bars(id: Int, time: Long, close: String = "5500.25", source: String? = null) {
            val status = source?.let { "\"s\":\"$it\"," } ?: ""
            delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,${status}"bars":[{"timestamp":"${Instant.fromEpochMilliseconds(time)}","open":5500,"high":5502,"low":5499,"close":$close,"upVolume":3,"downVolume":2}]}]}}]""")
        }
        /** One frame, as the real server batches a page. */
        suspend fun page(id: Int, times: List<Long>) {
            if (times.isEmpty()) return
            val bars = times.joinToString(",") { time -> """{"timestamp":"${Instant.fromEpochMilliseconds(time)}","open":5500,"high":5502,"low":5499,"close":5500.25,"upVolume":3,"downVolume":2}""" }
            delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"bars":[$bars]}]}}]""")
        }
        suspend fun eoh() { delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$historicalId,"eoh":true}]}}]""") }
        override fun close() = delegate.close()
    }

    private suspend fun TestScope.ready(wire: Wire, store: Store = Store(), now: () -> Long = { CACHE_NOW }): TradovateBroker {
        val session = TradovateSession(TradovateEnvironment.DEMO, "synthetic-cache-test-token-123456", null, 42, "test", 4_102_444_800_000)
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        return TradovateBroker(session, wire, scope, now = now, candleCacheStore = store).also { it.connect() }
    }

    private fun lastRange(wire: Wire) = wire.charts.last().getValue("timeRange").jsonObject

    @Test fun switchesBackWithOnlyTheMissingTailAndRefreshesTheOpenBar() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(CHART_BARS.toLong(), wire.charts.first().getValue("timeRange").jsonObject.long("asMuchAsElements"))
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(Instant.fromEpochMilliseconds(CACHE_NOW - BAR_STEP).toString(), lastRange(wire).str("asFarAsTimestamp"))
        assertNull(lastRange(wire)["asMuchAsElements"])
        wire.bars(wire.liveId, CACHE_NOW - BAR_STEP, "5501"); runCurrent()
        wire.bars(wire.liveId, CACHE_NOW); runCurrent()
        assertEquals(2, broker.history("MESZ6", Timeframe.M5).size)
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).first().close)
    }

    @Test fun repeatedWatchKeepsTheActiveSubscription() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val requests = wire.charts.size
        val cancelled = wire.cancelled.toList()
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(requests, wire.charts.size)
        assertEquals(cancelled, wire.cancelled)
    }

    @Test fun restartShowsDiskCacheBeforeTheServerReplies() = runTest {
        val store = Store()
        val first = ready(Wire(), store)
        first.watch("MESZ6", Timeframe.M5); runCurrent()
        first.disconnect(); runCurrent()
        assertEquals(1, store.values.size)
        val wire = Wire().apply { automatic = false }
        val second = ready(wire, store)
        second.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(1, second.history("MESZ6", Timeframe.M5).size)
        assertNotNull(lastRange(wire).str("asFarAsTimestamp"))
    }

    @Test fun unfinishedHistoryAndRealtimeNeverAdvanceTheSavedCheckpoint() = runTest {
        val store = Store()
        val seed = Candle(CACHE_NOW - 2 * BAR_STEP, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000)
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5, listOf(seed))
        val wire = Wire().apply { automatic = false }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.liveId, CACHE_NOW)
        wire.bars(wire.historicalId, CACHE_NOW - BAR_STEP); runCurrent()
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(Instant.fromEpochMilliseconds(seed.timeMs).toString(), lastRange(wire).str("asFarAsTimestamp"))
        broker.disconnect(); runCurrent()
        assertEquals(listOf(seed), CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW))
    }

    @Test fun sharedSubscriptionIdPersistsHistoryAndPrefersRealtimeOverLateHistory() = runTest {
        val store = Store()
        val wire = Wire().apply { sameId = true }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.liveId, CACHE_NOW - BAR_STEP, "5501", "")
        wire.bars(wire.liveId, CACHE_NOW - BAR_STEP, "5500.25", "db"); runCurrent()
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).single().close)
        broker.disconnect(); runCurrent()
        assertEquals(5_501_000_000L, CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW).single().close)
    }

    @Test fun switchingDuringARequestCancelsTheOldSubscriptionBeforeBindingTheNewOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val wire = Wire().apply { replyGate = gate }
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        assertEquals(1, wire.charts.size)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(5L, 1L), wire.charts.map { it.getValue("chartDescription").jsonObject.long("elementSize") }.distinct())
        assertEquals(1, wire.charts.count { it.getValue("chartDescription").jsonObject.long("elementSize") == 5L })
        assertTrue(wire.cancelled.containsAll(listOf("100", "101")))
        wire.bars(100, CACHE_NOW); runCurrent()
        assertEquals(1, broker.history("MESZ6", Timeframe.M1).size)
    }

    @Test fun diskFailureDoesNotBreakStreamingOrInMemoryReuse() = runTest {
        val store = Store().apply { fail = true }
        val wire = Wire()
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertTrue(broker.market.value.connected)
        wire.bars(wire.liveId, CACHE_NOW); runCurrent()
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(Instant.fromEpochMilliseconds(CACHE_NOW).toString(), lastRange(wire).str("asFarAsTimestamp"))
        store.fail = false
        broker.disconnect(); runCurrent()
        assertTrue(store.values.isNotEmpty())
    }

    @Test fun truncatedHistoryPaginatesUntilItJoinsAnAlreadyFullCache() = runTest {
        val anchor = CACHE_NOW - 10 * BAR_STEP
        val store = Store()
        val old = List(CHART_BARS) { i -> Candle(anchor - (CHART_BARS - 1 - i) * BAR_STEP, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000) }
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5, old)
        val wire = Wire().apply { serverBars = (0..10).map { anchor + it * BAR_STEP }; pageLimit = 3 }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(1L, wire.charts.first().getValue("timeRange").jsonObject.long("asMuchAsElements"))
        val pages = wire.charts.drop(1).map { it.getValue("timeRange").jsonObject }
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.str("asFarAsTimestamp") == Instant.fromEpochMilliseconds(anchor).toString() })
        assertTrue(pages.zipWithNext().all { (a, b) -> Instant.parse(a.str("closestTimestamp")!!) > Instant.parse(b.str("closestTimestamp")!!) })
        assertFalse(wire.liveId.toString() in wire.cancelled)
        broker.disconnect(); runCurrent()
        val saved = CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW)
        assertEquals(CHART_BARS, saved.size)
        assertEquals(CACHE_NOW, saved.last().timeMs)
        assertTrue(saved.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == BAR_STEP })
    }

    @Test fun failedBackfillKeepsTheOldCheckpointAndTheSameChartCanRetry() = runTest {
        val anchor = CACHE_NOW - 10 * BAR_STEP
        val store = Store()
        val old = listOf(Candle(anchor, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000))
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5, old)
        val wire = Wire().apply {
            serverBars = (0..10).map { anchor + it * BAR_STEP }
            pageLimit = 3; failBackfillAfter = 1
        }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertEquals(old, CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW))
        val attempts = wire.charts.size
        wire.failBackfillAfter = null
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(wire.charts.size > attempts)
        broker.disconnect(); runCurrent()
        assertEquals(CACHE_NOW, CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW).last().timeMs)
    }

    @Test fun delayedLiveSubscriptionSnapshotCannotOverwriteNewerRealtime() = runTest {
        val store = Store()
        val anchor = CACHE_NOW - BAR_STEP
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5,
            listOf(Candle(anchor, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000)))
        val wire = Wire()
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.liveId, anchor, "5501")
        wire.bars(wire.liveId - 1, anchor, "5500.25"); runCurrent()
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).single().close)
    }

    /** A reopened chart would otherwise draw the live bar right after a last bar that can be hours old. */
    @Test fun cachedChartShowsLiveBarsOnlyOnceTheBarsItLacksAreRead() = runTest {
        val store = Store()
        val anchor = CACHE_NOW - 3 * BAR_STEP
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5, listOf(Candle(anchor, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000)))
        val wire = Wire().apply { automatic = false }
        val broker = ready(wire, store)
        fun times() = broker.history("MESZ6", Timeframe.M5).map { (CACHE_NOW - it.timeMs) / BAR_STEP }
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.liveId, CACHE_NOW, "5501"); runCurrent()
        assertEquals(listOf(3L), times())
        wire.page(wire.historicalId, listOf(anchor, anchor + BAR_STEP, anchor + 2 * BAR_STEP)); runCurrent()
        assertEquals(listOf(3L), times())
        wire.eoh(); runCurrent()
        assertEquals(listOf(3L, 2L, 1L, 0L), times())
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).last().close)
        wire.bars(wire.liveId, CACHE_NOW, "5502"); runCurrent()
        assertEquals(5_502_000_000L, broker.history("MESZ6", Timeframe.M5).last().close)
    }

    @Test fun cachedChartStopsWaitingForBarsThatDoNotArrive() = runTest {
        val store = Store()
        val anchor = CACHE_NOW - 3 * BAR_STEP
        CandleCache(store, NAMESPACE).write("MESZ6" to Timeframe.M5, listOf(Candle(anchor, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000)))
        var time = CACHE_NOW
        val wire = Wire().apply { automatic = false }
        val broker = ready(wire, store) { time }
        fun times() = broker.history("MESZ6", Timeframe.M5).map { (CACHE_NOW - it.timeMs) / BAR_STEP }
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        time += CHART_JOIN_HOLD_MS - 1
        wire.bars(wire.liveId, CACHE_NOW, "5501"); runCurrent()
        assertEquals(listOf(3L), times())
        time += 1
        wire.bars(wire.liveId, CACHE_NOW, "5502"); runCurrent()
        assertEquals(listOf(3L, 0L), times())
    }

    @Test fun switchingWhileCancellingStillCleansEveryOldIdAndRetriesFailures() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val gate = CompletableDeferred<Unit>()
        wire.cancelGate = gate
        wire.rejectedCancellations += "100"
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        broker.watch("MESZ6", Timeframe.M15); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue("101" in wire.cancelled)
        assertTrue(wire.cancelled.count { it == "100" } >= 2)
        wire.rejectedCancellations.clear()
        broker.watch("MESZ6", Timeframe.H1); runCurrent()
        assertTrue(wire.cancelled.count { it == "100" } >= 3)
        assertEquals(60L, wire.charts.last().getValue("chartDescription").jsonObject.long("elementSize"))
    }

    @Test fun earlyPacketOverflowCannotBecomeAPersistedCheckpoint() = runTest {
        val store = Store()
        val wire = Wire().apply { automatic = false; earlyPackets = 257 }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.eoh(); runCurrent()
        broker.disconnect(); runCurrent()
        assertTrue(store.values.isEmpty())
    }

    @Test fun chartTimeoutClosesTheSocketToReleaseUnacknowledgedSubscriptions() = runTest {
        val wire = Wire().apply { dropChartAck = true }
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertTrue(wire.marketClosed)
        assertFalse(broker.market.value.tradingReady)
        broker.disconnect(); runCurrent()
    }

    @Test fun unresponsiveCancellationHasOneFiveSecondBudgetForAllIds() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val requests = wire.charts.size
        wire.dropCancelAck = true
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertTrue(wire.marketClosed)
        assertEquals(requests, wire.charts.size)
        broker.disconnect(); runCurrent()
    }

    private fun range(chart: JsonObject) = chart.getValue("timeRange").jsonObject

    /** The real server answers about a month per request, however many bars are asked for. */
    @Test fun freshChartPagesBackToAFullChartAndKeepsItsLiveStream() = runTest {
        val store = Store()
        val wire = Wire().apply { serverBars = List(CHART_BARS + 500) { CACHE_NOW - it * BAR_STEP }; pageLimit = 700 }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(CHART_BARS.toLong(), range(wire.charts.first()).long("asMuchAsElements"))
        val pages = wire.charts.drop(1).map(::range)
        assertEquals(2, pages.size)
        val floor = Instant.fromEpochMilliseconds(chartHistoryFloor(Timeframe.M5, CACHE_NOW)).toString()
        assertTrue(pages.all { it.str("asFarAsTimestamp") == floor && it["asMuchAsElements"] == null })
        assertTrue(pages.zipWithNext().all { (a, b) -> Instant.parse(a.str("closestTimestamp")!!) > Instant.parse(b.str("closestTimestamp")!!) })
        val bars = broker.history("MESZ6", Timeframe.M5)
        assertEquals(CHART_BARS, bars.size)
        assertEquals(CACHE_NOW, bars.last().timeMs)
        assertTrue(bars.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == BAR_STEP })
        assertFalse(wire.liveId.toString() in wire.cancelled)
        wire.bars(wire.liveId, CACHE_NOW, "5501"); runCurrent()
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).last().close)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(CHART_BARS, CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW).size)
    }

    /** The server replays a page newest day first; showing each packet would draw the chart from right to left. */
    @Test fun replayedPagesReachTheChartWhole() = runTest {
        val wire = Wire().apply { automatic = false; sameId = true }
        val broker = ready(wire)
        fun times() = broker.history("MESZ6", Timeframe.M5).map { (CACHE_NOW - it.timeMs) / BAR_STEP }
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.historicalId, CACHE_NOW, source = "db")
        wire.bars(wire.historicalId, CACHE_NOW - BAR_STEP, source = "db"); runCurrent()
        assertEquals(emptyList(), times())
        wire.bars(wire.historicalId, CACHE_NOW, source = "s"); runCurrent()
        assertEquals(listOf(1L, 0L), times(), "the marker after the replay shows the page")
        wire.eoh(); runCurrent()
        wire.bars(wire.historicalId, CACHE_NOW - 2 * BAR_STEP, source = "db"); runCurrent()
        assertEquals(listOf(1L, 0L), times())
        wire.bars(wire.liveId, CACHE_NOW, "5501", ""); runCurrent()
        assertEquals(listOf(2L, 1L, 0L), times(), "a live update carries what has arrived")
        assertEquals(5_501_000_000L, broker.history("MESZ6", Timeframe.M5).last().close)
        wire.bars(wire.historicalId, CACHE_NOW - 3 * BAR_STEP, source = "db"); runCurrent()
        wire.eoh(); runCurrent()
        assertEquals(listOf(3L, 2L, 1L, 0L), times(), "the end of a page shows it")
    }

    @Test fun freshChartStopsWhereTheServerHistoryEnds() = runTest {
        val store = Store()
        val wire = Wire().apply { serverBars = List(300) { CACHE_NOW - it * BAR_STEP }; pageLimit = 100 }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(300, broker.history("MESZ6", Timeframe.M5).size)
        val requests = wire.charts.size
        advanceTimeBy(1_001); runCurrent()
        assertEquals(requests, wire.charts.size)
        assertEquals(300, CandleCache(store, NAMESPACE).read("MESZ6" to Timeframe.M5, CACHE_NOW).size)
    }

    @Test fun freshChartRequestsAreBounded() = runTest {
        val wire = Wire().apply { serverBars = List(CHART_BARS) { CACHE_NOW - it * BAR_STEP }; pageLimit = 10 }
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(MAX_FRESH_HISTORY_PAGES, wire.charts.size)
        assertEquals(wire.charts.size * 10 - (wire.charts.size - 1), broker.history("MESZ6", Timeframe.M5).size)
    }

    /** A cached series only grows at its end, so a partial one would stay partial. */
    @Test fun freshChartThatFailsPartWayStaysOutOfTheCacheAndStartsOver() = runTest {
        val store = Store()
        val wire = Wire().apply { serverBars = List(CHART_BARS) { CACHE_NOW - it * BAR_STEP }; pageLimit = 700; failBackfillAfter = 1 }
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.history("MESZ6", Timeframe.M5).size > 700)
        assertTrue("更早的历史" in broker.market.value.statusMessage, broker.market.value.statusMessage)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(store.values.isEmpty())
        wire.failBackfillAfter = null
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(CHART_BARS, broker.history("MESZ6", Timeframe.M5).size)
        assertTrue(wire.charts.count { range(it)["asMuchAsElements"] != null } == 2)
    }

    @Test fun switchingAwayStopsPagingTheOldChart() = runTest {
        val wire = Wire().apply { serverBars = List(CHART_BARS) { CACHE_NOW - it * BAR_STEP }; pageLimit = 700; automatic = false }
        val broker = ready(wire)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.bars(wire.historicalId, CACHE_NOW - BAR_STEP)
        broker.watch("MESZ6", Timeframe.M1); runCurrent()
        wire.eoh(); runCurrent()
        assertEquals(1, wire.charts.count { it.getValue("chartDescription").jsonObject.long("elementSize") == 5L })
    }

    /** Entries from before paging hold about a month of intraday bars and would never deepen. */
    @Test fun seriesCachedBeforePagingIsReadAgain() = runTest {
        val store = Store()
        CandleCache(store, "demo-42").write("MESZ6" to Timeframe.M5, listOf(Candle(CACHE_NOW - 2 * BAR_STEP, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000)))
        val wire = Wire()
        val broker = ready(wire, store)
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(CHART_BARS.toLong(), range(wire.charts.first()).long("asMuchAsElements"))
    }
}
