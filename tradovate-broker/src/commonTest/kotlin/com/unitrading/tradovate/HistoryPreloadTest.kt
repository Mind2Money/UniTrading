package com.unitrading.tradovate

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

private const val NOW = 1_798_589_000_000L
private const val NAMESPACE = "v2-demo-42"
private val M5 = Timeframe.M5
private val H1 = Timeframe.H1

/** The watch list's history is read whole in the background, so that a chart opens from the cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPreloadTest {
    private class Store : CandleCacheStore {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
    }

    /** Answers every series with [serverBars] bars ending at [NOW], at most [pageLimit] per request, like the real paging. */
    private class Wire : TradovateWire {
        val delegate = MockTradovateWire()
        class Chart(val symbol: String, val minutes: Long, val range: JsonObject, val historicalId: Int, val realtimeId: Int) {
            val firstPage get() = range.long("asMuchAsElements") == CHART_BARS.toLong()
            val ids get() = listOf(historicalId.toString(), realtimeId.toString())
        }
        val charts = mutableListOf<Chart>()
        val cancelled = mutableListOf<String>()
        val markets = mutableListOf<MockTradovateWire.MockSocket>()
        var serverBars = CHART_BARS + 500
        var pageLimit = 700
        var sameId = false
        val refused = mutableSetOf<String>()
        var refusals = 0
        /** Series whose pages are acknowledged and then never arrive. */
        val stalled = mutableSetOf<Pair<String, Timeframe>>()
        /** Symbols whose chart requests get no acknowledgement at all. */
        val silent = mutableSetOf<String>()
        var unanswered = 0
        var rejectCancellations = false
        /** Holds contract searches, and with them the watch list's discovery of a symbol. */
        var searchGate: CompletableDeferred<Unit>? = null
        private var nextId = 100

        override suspend fun request(hosts: TradovateHosts, token: String, method: String, path: String, body: JsonObject?): JsonElement {
            if (path.startsWith("contract/suggest")) searchGate?.await()
            return delegate.request(hosts, token, method, path, body)
        }
        override suspend fun socket(url: String): TradovateWireSocket {
            // A reconnect opens new sockets, so every call gets its own.
            if (!url.contains("md")) return delegate.MockSocket(false)
            val socket = delegate.MockSocket(true).also { markets += it }
            return object : TradovateWireSocket by socket {
                override suspend fun send(text: String) {
                    val route = text.substringBefore('\n')
                    if (route != "md/getChart" && route != "md/cancelChart") { socket.send(text); return }
                    val requestId = text.split('\n')[1]
                    val body = Json.parseToJsonElement(text.substringAfter("\n\n")).jsonObject
                    if (route == "md/cancelChart") {
                        cancelled += body.getValue("subscriptionId").jsonPrimitive.content
                        socket.queue.send("""a[{"i":$requestId,"s":${if (rejectCancellations) 500 else 200},"d":{}}]""")
                        return
                    }
                    val symbol = body.str("symbol")!!
                    if (symbol in silent) { unanswered++; return }
                    val description = body.getValue("chartDescription").jsonObject
                    val minutes = description.long("elementSize")!! * if (description.str("underlyingType") == "DailyBar") 1440 else 1
                    val range = body.getValue("timeRange").jsonObject
                    if (symbol in refused) {
                        refusals++
                        socket.queue.send("""a[{"i":$requestId,"s":500,"d":{}}]""")
                        return
                    }
                    val historicalId = nextId++
                    val realtimeId = if (sameId) historicalId else nextId++
                    charts += Chart(symbol, minutes, range, historicalId, realtimeId)
                    socket.queue.send("""a[{"i":$requestId,"s":200,"d":{"historicalId":$historicalId,"realtimeId":$realtimeId}}]""")
                    if (symbol to Timeframe(minutes.toInt()) in stalled) return
                    val step = minutes * 60_000
                    val start = range.str("asFarAsTimestamp")?.let { Instant.parse(it).toEpochMilliseconds() } ?: 0
                    val end = range.str("closestTimestamp")?.let { Instant.parse(it).toEpochMilliseconds() } ?: NOW
                    val count = minOf(range.long("asMuchAsElements")?.toInt() ?: Int.MAX_VALUE, pageLimit)
                    val times = List(serverBars) { NOW - it * step }.filter { it in start..end }.sorted().takeLast(count)
                    if (times.isNotEmpty()) {
                        val bars = times.joinToString(",") { """{"timestamp":"${Instant.fromEpochMilliseconds(it)}","open":5500,"high":5502,"low":5499,"close":5500.25,"upVolume":3,"downVolume":2}""" }
                        socket.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$historicalId,"s":"db","bars":[$bars]}]}}]""")
                    }
                    socket.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$historicalId,"eoh":true}]}}]""")
                }
            }
        }
        suspend fun live(id: Int, time: Long, close: String) {
            markets.last().queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"s":"","bars":[{"timestamp":"${Instant.fromEpochMilliseconds(time)}","open":5500,"high":5502,"low":5499,"close":$close,"upVolume":3,"downVolume":2}]}]}}]""")
        }
        fun of(timeframe: Timeframe, symbol: String = "MESZ6") = charts.filter { it.symbol == symbol && it.minutes == timeframe.minutes.toLong() }
        override fun close() = delegate.close()
    }

    private suspend fun TestScope.ready(wire: Wire, store: Store = Store(), now: () -> Long = { NOW }): TradovateBroker {
        val session = TradovateSession(TradovateEnvironment.DEMO, "synthetic-preload-test-token-123456", null, 42, "test", 4_102_444_800_000)
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        return TradovateBroker(session, wire, scope, now = now, candleCacheStore = store).also { it.connect() }
    }

    private fun assertWhole(bars: List<Candle>, timeframe: Timeframe) {
        assertEquals(CHART_BARS, bars.size)
        assertEquals(NOW, bars.last().timeMs)
        assertTrue(bars.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == timeframe.minutes * 60_000L })
    }

    @Test fun listedSeriesAreReadWholeAndCachedWithoutTouchingTheChart() = runTest {
        val wire = Wire()
        val store = Store()
        val broker = ready(wire, store)
        broker.watch("MESZ6", M5); runCurrent()
        val chart = wire.of(M5).single { it.firstPage }
        broker.preloadHistory(listOf("MESZ6" to M5, "MESZ6" to H1, "@MES" to Timeframe(1440))); runCurrent()

        assertWhole(broker.history("MESZ6", H1), H1)
        assertWhole(broker.history("@MES", Timeframe(1440)), Timeframe(1440))
        // The requests are a fresh chart's: the latest bars first, then pages back from the oldest bar read.
        val pages = wire.of(H1)
        assertEquals(3, pages.size)
        assertTrue(pages.first().firstPage)
        val floor = Instant.fromEpochMilliseconds(chartHistoryFloor(H1, NOW)).toString()
        assertTrue(pages.drop(1).all { it.range.str("asFarAsTimestamp") == floor && it.range["asMuchAsElements"] == null })
        assertEquals(listOf(NOW - 699 * 3_600_000L, NOW - 1_398 * 3_600_000L),
            pages.drop(1).map { Instant.parse(it.range.str("closestTimestamp")!!).toEpochMilliseconds() })
        // No background subscription outlives its page, and the chart keeps its own.
        val background = wire.charts.filter { it.minutes != 5L }
        assertTrue(background.flatMap { it.ids }.all { it in wire.cancelled })
        assertTrue(chart.ids.none { it in wire.cancelled })
        assertEquals(1, wire.of(M5).count { it.firstPage }, "the chart reads the series it shows")
        wire.live(chart.realtimeId, NOW, "5501"); runCurrent()
        assertEquals(5_501_000_000L, broker.history("MESZ6", M5).last().close)

        advanceTimeBy(1_001); runCurrent()
        assertWhole(CandleCache(store, NAMESPACE).read("MESZ6" to H1, NOW), H1)
        assertWhole(CandleCache(store, NAMESPACE).read("@MES" to Timeframe(1440), NOW), Timeframe(1440))
    }

    @Test fun preloadedChartShowsItsBarsAtOnceAndFetchesOnlyTheBarsSince() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        broker.watch("MESZ6", M5); runCurrent()
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        val before = wire.charts.size
        wire.stalled += "MESZ6" to H1

        broker.watch("MESZ6", H1); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
        val opened = wire.charts.drop(before)
        assertEquals(listOf(1L, null), opened.map { it.range.long("asMuchAsElements") })
        assertEquals(Instant.fromEpochMilliseconds(NOW).toString(), opened.last().range.str("asFarAsTimestamp"))
    }

    @Test fun cachedSeriesComeFromTheDiskWithoutAServerRequest() = runTest {
        val store = Store()
        val saved = List(40) { Candle(NOW - (39 - it) * 3_600_000L, 5_500_000_000, 5_502_000_000, 5_499_000_000, 5_500_250_000) }
        CandleCache(store, NAMESPACE).write("MESZ6" to H1, saved)
        val wire = Wire()
        val broker = ready(wire, store)
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        assertEquals(saved, broker.history("MESZ6", H1))
        assertTrue(wire.charts.isEmpty())
    }

    @Test fun shortServerHistoryIsCachedAsFarAsItGoes() = runTest {
        val wire = Wire().apply { serverBars = 300; pageLimit = 100 }
        val store = Store()
        val broker = ready(wire, store)
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        assertEquals(300, broker.history("MESZ6", H1).size)
        val requests = wire.charts.size
        advanceTimeBy(PRELOAD_IDLE_MS * 2 + 1); runCurrent()
        assertEquals(requests, wire.charts.size, "a cached series is not read again")
        assertEquals(300, CandleCache(store, NAMESPACE).read("MESZ6" to H1, NOW).size)
    }

    @Test fun sharedSubscriptionIdIsReadAndCancelledOnce() = runTest {
        val wire = Wire().apply { sameId = true }
        val broker = ready(wire)
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
        assertEquals(wire.charts.map { it.historicalId.toString() }, wire.cancelled)
    }

    @Test fun openingASeriesThatIsBeingReadLeavesItToTheChart() = runTest {
        val wire = Wire().apply { stalled += "MESZ6" to H1 }
        val broker = ready(wire)
        broker.watch("MESZ6", M5); runCurrent()
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        val background = wire.of(H1).single()
        assertTrue(background.ids.none { it in wire.cancelled })
        assertTrue(broker.history("MESZ6", H1).isEmpty())

        wire.stalled.clear()
        broker.watch("MESZ6", H1); runCurrent()
        assertTrue(background.ids.all { it in wire.cancelled })
        assertWhole(broker.history("MESZ6", H1), H1)
        assertEquals(2, wire.of(H1).count { it.firstPage })
        val live = wire.of(H1).last { it.firstPage }
        assertTrue(live.ids.none { it in wire.cancelled })
    }

    @Test fun chartLeftBeforeItsHistoryWasWholeIsReadInTheBackground() = runTest {
        val wire = Wire().apply { stalled += "MESZ6" to H1 }
        val store = Store()
        val broker = ready(wire, store)
        broker.preloadHistory(listOf("MESZ6" to H1, "MESZ6" to M5))
        broker.watch("MESZ6", H1); runCurrent()
        assertEquals(1, wire.of(H1).size, "only the chart asks for the series it shows")
        assertTrue(broker.history("MESZ6", H1).isEmpty())
        assertWhole(broker.history("MESZ6", M5), M5)

        wire.stalled.clear()
        broker.watch("MESZ6", M5); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
        advanceTimeBy(1_001); runCurrent()
        assertWhole(CandleCache(store, NAMESPACE).read("MESZ6" to H1, NOW), H1)
    }

    @Test fun refusedSeriesWaitsLongerEachTimeAndTheOthersAreStillRead() = runTest {
        var time = NOW
        val wire = Wire().apply { refused += "NOPE" }
        val broker = ready(wire, now = { time })
        val status = broker.market.value.statusMessage
        broker.preloadHistory(listOf("NOPE" to H1, "MESZ6" to H1)); runCurrent()
        assertEquals(1, wire.refusals)
        assertTrue(wire.charts.isEmpty(), "what refused one series may refuse them all, so the reader pauses")
        assertEquals(status, broker.market.value.statusMessage, "background failures stay off the status line")

        time += PRELOAD_PAUSE_MS; advanceTimeBy(PRELOAD_PAUSE_MS + 1); runCurrent()
        assertEquals(CHART_BARS, broker.history("MESZ6", H1).size)
        assertEquals(1, wire.refusals)
        time += PRELOAD_RETRY_MS - PRELOAD_PAUSE_MS - 1; advanceTimeBy(PRELOAD_RETRY_MS); runCurrent()
        assertEquals(1, wire.refusals)
        time += 1; advanceTimeBy(PRELOAD_IDLE_MS + 1); runCurrent()
        assertEquals(2, wire.refusals)
        time += PRELOAD_RETRY_MS; advanceTimeBy(PRELOAD_RETRY_MS + 1); runCurrent()
        assertEquals(2, wire.refusals, "the second wait is twice the first")
    }

    @Test fun stalledPageIsGivenUpAndItsSubscriptionCancelled() = runTest {
        var time = NOW
        val wire = Wire().apply { stalled += "MESZ6" to H1 }
        val broker = ready(wire, now = { time })
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        val page = wire.of(H1).single()
        time += PRELOAD_PAGE_TIMEOUT_MS; advanceTimeBy(PRELOAD_PAGE_TIMEOUT_MS + 1); runCurrent()
        assertTrue(page.ids.all { it in wire.cancelled })
        assertTrue(broker.history("MESZ6", H1).isEmpty())
        assertTrue(broker.market.value.connected)

        wire.stalled.clear()
        time += PRELOAD_RETRY_MS; advanceTimeBy(PRELOAD_RETRY_MS + 1); runCurrent()
        assertEquals(CHART_BARS, broker.history("MESZ6", H1).size)
    }

    /** A subscription the server keeps takes one of its chart slots, which the chart and the leader checks need. */
    @Test fun unconfirmedCancellationPausesTheReaderInsteadOfOpeningMoreSubscriptions() = runTest {
        var time = NOW
        val wire = Wire().apply { rejectCancellations = true }
        val broker = ready(wire, now = { time })
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        val first = wire.charts.single()
        assertTrue(broker.history("MESZ6", H1).isEmpty())
        assertTrue(broker.market.value.connected)

        wire.rejectCancellations = false
        time += PRELOAD_RETRY_MS; advanceTimeBy(PRELOAD_RETRY_MS + 1); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
        assertTrue(first.ids.all { id -> wire.cancelled.count { it == id } == 2 }, "the next cleanup retries what stayed pending")
    }

    /** Only closing the socket releases a subscription whose identifiers never arrived; the quotes use that socket too. */
    @Test fun unansweredRequestReleasesTheConnectionOnceAndThenStaysAway() = runTest {
        var time = NOW
        val wire = Wire().apply { silent += "MESZ6" }
        val broker = ready(wire, now = { time })
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        time += 15_000; advanceTimeBy(15_001); runCurrent()
        assertFalse(broker.market.value.tradingReady)
        // The reconnect waits three seconds, and its REST calls are paced.
        time += 30_000; advanceTimeBy(30_000); runCurrent()
        assertEquals(2, wire.markets.size)
        assertTrue(broker.market.value.connected, broker.market.value.statusMessage)
        assertEquals(1, wire.unanswered)

        time += PRELOAD_RESET_PAUSE_MS - 31_000; advanceTimeBy(PRELOAD_RESET_PAUSE_MS - 31_000); runCurrent()
        assertEquals(1, wire.unanswered)
        assertEquals(2, wire.markets.size)
        wire.silent.clear()
        time += 1_000; advanceTimeBy(PRELOAD_IDLE_MS + 1); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
    }

    /** Leader checks share the server's chart budget and gate orders. */
    @Test fun watchListChecksGoFirst() = runTest {
        val gate = CompletableDeferred<Unit>()
        val wire = Wire().apply { searchGate = gate }
        val broker = ready(wire)
        broker.watchQuotes(setOf("ZZZ")); runCurrent()
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        assertTrue(wire.charts.isEmpty())
        gate.complete(Unit)
        advanceTimeBy(1_000); runCurrent()
        assertWhole(broker.history("MESZ6", H1), H1)
    }

    @Test fun readerStartsAgainOnTheNewConnectionAfterAReconnect() = runTest {
        val wire = Wire().apply { stalled += "MESZ6" to H1 }
        val broker = ready(wire)
        broker.preloadHistory(listOf("MESZ6" to H1)); runCurrent()
        assertEquals(1, wire.of(H1).size)

        wire.stalled.clear()
        wire.markets.single().queue.close()
        // The reconnect waits three seconds, and its REST calls are paced.
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, wire.markets.size)
        assertTrue(broker.market.value.connected, broker.market.value.statusMessage)
        assertWhole(broker.history("MESZ6", H1), H1)
    }

    @Test fun emptyListStopsTheReader() = runTest {
        val wire = Wire().apply { refused += "NOPE" }
        var time = NOW
        val broker = ready(wire, now = { time })
        broker.preloadHistory(listOf("NOPE" to H1, "MESZ6" to H1)); runCurrent()
        broker.preloadHistory(emptyList())
        time += PRELOAD_RETRY_MS; advanceTimeBy(PRELOAD_RETRY_MS + 1); runCurrent()
        assertEquals(1, wire.refusals)
        assertTrue(wire.charts.isEmpty())
    }
}
