package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

private const val CONTINUOUS_TIME = 1_798_589_000_000L
private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

class ContinuousContractsTest {
    private class Wire(val delegate: MockTradovateWire = MockTradovateWire()) : TradovateWire by delegate {
        var clock = CONTINUOUS_TIME
        var leader = "MESZ6"
        var failMapping = false
        var missingVolume = false
        var emptySymbols = emptySet<String>()
        var dailyMode: String? = "RealTime"
        var endHistory = true
        var omitFutureSessions = false
        var newerDailyBar = false
        var penalty: JsonObject? = null
        var chartPenalty = false
        var chartPenaltyRetries = 0
        val dailyChannels = mutableMapOf<String, Int>()
        var mappingRequests = 0
        var maturityBatchRequests = 0
        private var channelId = 1000
        val dailyRequests = mutableListOf<JsonObject>()
        val maturities get() = listOf(300L, 301L).map { id -> json("""{"id":$id,"productId":400,"isFront":${id == 300L},"expirationDate":"2027-03-20T00:00:00Z"}""") }
        val contracts get() = listOf(json("""{"id":200,"name":"MESZ6","contractMaturityId":300}"""),
            json("""{"id":201,"name":"MESH7","contractMaturityId":301}"""))
        val sessions get() = listOf(300L, 301L).flatMap { id -> (28..31).map { day ->
            json("""{"contractMaturityId":$id,"tradeDate":{"year":2026,"month":12,"day":$day},"startTime":"2026-12-${day-1}T23:30Z","openTime":"2026-12-${day-1}T23:00Z","closeTime":"2026-12-${day}T23:29Z","endTime":"2026-12-${day}T23:00Z"}""")
        } }
        init { delegate.snapshot = JsonObject(delegate.snapshot + mapOf("contractMaturities" to JsonArray(maturities), "contracts" to JsonArray(contracts))) }
        override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement = when {
            path.startsWith("contractMaturity/deps") -> {
                mappingRequests++
                if (failMapping) error("offline")
                JsonArray(maturities)
            }
            path.startsWith("contract/ldeps") -> JsonArray(contracts)
            path.startsWith("contractMaturity/items") -> { maturityBatchRequests++; JsonArray(maturities) }
            path.startsWith("tradingSession/ldeps") -> JsonArray(sessions.filter { !omitFutureSessions || timestamp(it.str("endTime")!!)!! <= clock })
            else -> delegate.request(environment, token, method, path, body)
        }
        override suspend fun socket(url: String): TradovateWireSocket {
            val socket = delegate.socket(url)
            if (!url.contains("md")) return socket
            return object : TradovateWireSocket by socket {
                override suspend fun send(text: String) {
                    val parts = text.split('\n')
                    if (parts.first() != "md/getChart") { socket.send(text); return }
                    val body = json(text.substringAfter("\n\n"))
                    if ((body["timeRange"] as? JsonObject)?.long("asMuchAsElements") != 1L) {
                        if (chartPenalty && body.str("p-ticket") == null) {
                            delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"p-ticket":"chart-ticket","p-time":1}}]""")
                        } else {
                            if (body.str("p-ticket") == "chart-ticket") chartPenaltyRetries++
                            socket.send(text)
                        }
                        return
                    }
                    dailyRequests.add(body)
                    if (penalty != null && body.str("p-ticket") == null) {
                        delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":$penalty}]""")
                        return
                    }
                    val id = ++channelId
                    dailyChannels[body.str("symbol")!!] = id
                    val range = body["timeRange"] as JsonObject
                    val end = range.str("closestTimestamp") ?: range.str("asFarAsTimestamp")!!
                    val barTime = if (newerDailyBar && range.str("closestTimestamp") == null)
                        kotlin.time.Instant.fromEpochMilliseconds(timestamp(end)!! + 86_400_000).toString()
                    else "${end.take(10)}T00:00Z"
                    val volume = if (body.str("symbol") == leader) 1000 else 10
                    // Deliver data before the response to exercise subscription registration races.
                    val bars = when {
                        body.str("symbol") in emptySymbols -> "[]"
                        missingVolume -> """[{"timestamp":"$barTime","upVolume":$volume}]"""
                        else -> """[{"timestamp":"$barTime","upVolume":$volume,"downVolume":0}]"""
                    }
                    // Live DailyBar packets have no td, and use the same historical/realtime ID.
                    val ending = if (endHistory) ",{\"id\":$id,\"eoh\":true}" else ""
                    delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"s":"db","bars":$bars}$ending]}}]""")
                    val mode = dailyMode?.let { ",\"mode\":\"$it\"" }.orEmpty()
                    delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"historicalId":$id,"realtimeId":$id$mode}}]""")
                }
            }
        }
        suspend fun dailyUpdate(symbol: String, time: String, volume: Long) {
            val id = dailyChannels.getValue(symbol)
            delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"bars":[{"timestamp":"$time","upVolume":$volume,"downVolume":0}]}]}}]""")
        }
    }
    private suspend fun TestScope.ready(wire: Wire): TradovateBroker {
        val token = "eyJhbGciOiJub25lIn0." + Base64.UrlSafe.encode("{\"sub\":42,\"exp\":4102444800}".encodeToByteArray()).trimEnd('=') + ".test-signature-only"
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO, token, token, 42, "test", 4_102_444_800_000), wire, backgroundScope, now = { wire.clock })
        broker.connect()
        broker.watch("@MES", Timeframe.M5)
        advanceTimeBy(5_000)
        runCurrent()
        return broker
    }
    private fun request() = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100, sourceSymbol = "@MES")

    @Test fun continuousChartAndDatedExecutionUseSeparateSymbols() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals("@MES", json(wire.delegate.market.bodies.getValue("md/getChart")).str("symbol"))
        assertEquals("MESZ6", json(wire.delegate.market.bodies.getValue("md/subscribequote")).str("symbol"))
        assertEquals(1, broker.history("@MES", Timeframe.M5).size)
        assertTrue(broker.history("MESZ6", Timeframe.M5).isEmpty())
        assertEquals(setOf("MESZ6", "MESH7"), wire.dailyRequests.map { it.str("symbol") }.toSet())
        assertNull(broker.market.value.quotes["@MES"], "Do not invent a continuous Bid/Ask")
        val session = broker.openSession("100")
        assertTrue(session.preview(request()).valid)
        assertTrue(session.placeOrder("continuous", request()).accepted)
        assertEquals("MESZ6", wire.delegate.requests.single { it.path == "order/placeorder" }.body?.str("symbol"))
    }

    @Test fun bracketUsesDatedContractAndNeverSendsContinuousMetadata() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        assertTrue(broker.openSession("100").placeOrder("bracket", request().copy(stopLoss = 5_490_000_000, takeProfit = 5_510_000_000)).accepted)
        val body = wire.delegate.requests.single { it.path == "order/placeoso" }.body!!
        assertEquals("MESZ6", body.str("symbol"))
        assertNotNull(body["bracket1"])
        assertNotNull(body["bracket2"])
        assertNull(body["sourceSymbol"])
    }

    @Test fun rolloverBetweenReviewAndSubmitRejectsTheReviewedOrder() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        val session = broker.openSession("100")
        assertTrue(session.preview(request()).valid)
        wire.leader = "MESH7"
        wire.clock += 86_400_000
        val result = session.placeOrder("rolled", request())
        assertFalse(result.accepted)
        assertTrue(result.message.contains("换月"), result.message)
        assertEquals("MESH7", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertFalse(session.preview(request()).valid)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun missingMappingKeepsContinuousHistoryButBlocksOrders() = runTest {
        val wire = Wire().apply { failMapping = true }
        val broker = ready(wire)
        assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals("主力无法确认：offline", broker.instruments.single { it.symbol == "@MES" }.executionLabel)
        assertEquals(1, broker.history("@MES", Timeframe.M5).size)
        assertFalse(broker.openSession("100").placeOrder("unresolved", request()).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun failedResolutionSurvivesStatusUpdatesAndRepeatedWatchRespectsRetryDelay() = runTest {
        val wire = Wire().apply { failMapping = true }
        val broker = ready(wire)
        repeat(3) { broker.watch("@MES", Timeframe.M5); advanceTimeBy(100); runCurrent() }
        assertEquals(1, wire.mappingRequests)
        broker.resynchronize()
        assertEquals(2, wire.mappingRequests, "Explicit synchronization still retries immediately")
        assertEquals("主力无法确认：offline", broker.instruments.single { it.symbol == "@MES" }.executionLabel)
        wire.failMapping = false
        wire.clock += 60_000
        advanceTimeBy(5_000); runCurrent()
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionLabel)
        assertNull(broker.instruments.single { it.symbol == "@MES" }.tradeSymbolStatus)
    }

    @Test fun mappingFailureAtSubmitDoesNotReuseCachedTarget() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        wire.failMapping = true
        wire.clock += 86_400_000
        assertFalse(broker.openSession("100").placeOrder("failed-refresh", request()).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun unpublishedNextSessionUsesLatestHistoryAndDatedExecution() = runTest {
        val wire = Wire().apply { omitFutureSessions = true; emptySymbols = setOf("MESH7") }
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(2, wire.dailyRequests.size)
        wire.dailyRequests.forEach {
            val range = it["timeRange"]!!.jsonObject
            assertEquals("2026-12-29T00:00:00Z", range.str("asFarAsTimestamp"))
            assertNull(range["closestTimestamp"], "A fixed end date would hide an unpublished newer session")
        }
        val session = broker.openSession("100")
        assertTrue(session.preview(request()).valid)
        assertTrue(session.placeOrder("calendar-tail", request()).accepted)
        assertEquals("MESZ6", wire.delegate.requests.single { it.path == "order/placeorder" }.body?.str("symbol"))
    }

    @Test fun calendarTailRefreshesAfterOneMinuteAndRejectsAChangedLeader() = runTest {
        val wire = Wire().apply { omitFutureSessions = true }
        val broker = ready(wire)
        val session = broker.openSession("100")
        assertTrue(session.preview(request()).valid)
        wire.leader = "MESH7"
        wire.clock += 59_999
        broker.watch("@MES", Timeframe.M5); runCurrent()
        assertEquals(1, wire.mappingRequests)
        wire.clock++
        wire.dailyUpdate("MESZ6", "2026-12-29T00:00Z", 10)
        wire.dailyUpdate("MESH7", "2026-12-29T00:00Z", 1000)
        runCurrent()
        val result = session.placeOrder("calendar-tail-roll", request())
        assertFalse(result.accepted)
        assertTrue(result.message.contains("换月"), result.message)
        assertEquals(2, wire.mappingRequests)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun repeatedSelectionReusesConfirmedCalendarTailWithoutMoreNetworkRequests() = runTest {
        val wire = Wire().apply { omitFutureSessions = true }
        val broker = ready(wire)
        repeat(10) { broker.watch("@MES", Timeframe.M5); runCurrent() }
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(1, wire.mappingRequests)
        assertEquals(2, wire.dailyRequests.size, "Cached selections must not repeat candidate history queries")
    }

    @Test fun coldCatalogLoadsMissingMaturitiesInOneBatch() = runTest {
        val wire = Wire().apply {
            omitFutureSessions = true
            delegate.snapshot = JsonObject(delegate.snapshot + ("contractMaturities" to JsonArray(emptyList())))
        }
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(1, wire.maturityBatchRequests)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("contractMaturity/item?") })
    }

    @Test fun newerDailyBarOrFailedRefreshCannotReuseCalendarTailRoute() = runTest {
        for (offline in listOf(false, true)) {
            val wire = Wire().apply { omitFutureSessions = true }
            val broker = ready(wire)
            wire.newerDailyBar = !offline
            wire.failMapping = offline
            wire.clock += 60_000
            if (!offline) {
                wire.dailyUpdate("MESZ6", "2026-12-30T00:00Z", 1000)
                runCurrent()
            }
            assertFalse(broker.openSession("100").placeOrder("stale-tail", request()).accepted)
            assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
            assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
        }
    }

    @Test fun missingFutureSessionOnOneCandidateChecksLatestHistoryForAllCandidates() = runTest {
        val wire = Wire()
        var counts = 0
        val leader = selectVolumeLeader(400, "MES", wire.clock, read = { path ->
            if (path.startsWith("tradingSession/ldeps")) JsonArray(wire.sessions.filter {
                it.long("contractMaturityId") != 301L || timestamp(it.str("endTime")!!)!! <= wire.clock
            }) else wire.request(TradovateEnvironment.DEMO, "test", "GET", path, null) as JsonArray
        }, volume = { symbol, session ->
            assertTrue(session.verifyLatestDailyBar)
            counts++
            if (symbol == "MESZ6") 100 else 10
        })
        assertEquals(2, counts)
        assertTrue(leader.session.verifyLatestDailyBar)
    }

    @Test fun calendarTailRequiresRealtimePermissionCompleteVolumeAndHistoryEnd() = runTest {
        for (mode in listOf(null, "Delayed")) {
            val wire = Wire().apply { omitFutureSessions = true; dailyMode = mode }
            assertNull(ready(wire).instruments.single { it.symbol == "@MES" }.executionSymbol)
        }
        for (missing in listOf(false, true)) {
            val wire = Wire().apply { omitFutureSessions = true; missingVolume = missing; endHistory = missing }
            val broker = ready(wire)
            advanceTimeBy(20_000); runCurrent()
            assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
            assertFalse(broker.openSession("100").placeOrder("incomplete-tail", request()).accepted)
            assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
        }
    }

    @Test fun forcedCalendarRefreshReusesMonitoredDailyVolumesAndNewDateRevokesRouteImmediately() = runTest {
        val wire = Wire().apply { omitFutureSessions = true }
        val broker = ready(wire)
        repeat(5) { broker.refreshContinuous("@MES", force = true) }
        assertEquals(6, wire.mappingRequests)
        assertEquals(2, wire.dailyRequests.size, "Refreshing the calendar must not repeatedly consume historical-query quota")
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        wire.dailyUpdate("MESH7", "2026-12-30T00:00Z", 1)
        runCurrent()
        assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol,
            "Even a non-leading candidate's next-day update revokes the route without waiting for the minute timer")
        assertFalse(broker.openSession("100").preview(request()).valid)
    }

    @Test fun dailyHistoryTimePenaltyRetriesWithTicketButNeverRetriesCaptcha() = runTest {
        val wire = Wire().apply { omitFutureSessions = true; penalty = json("""{"p-ticket":"test-ticket","p-time":1}""") }
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(4, wire.dailyRequests.size)
        assertEquals(2, wire.dailyRequests.count { it.str("p-ticket") == "test-ticket" })
        for (value in listOf("""{"p-ticket":"test-ticket","p-time":1,"p-captcha":true}""",
            """{"p-ticket":"test-ticket","p-time":600}""")) {
            val denied = Wire().apply { omitFutureSessions = true; penalty = json(value) }
            val blocked = ready(denied)
            assertNull(blocked.instruments.single { it.symbol == "@MES" }.executionSymbol)
            assertTrue(denied.dailyRequests.none { it.str("p-ticket") != null })
            assertTrue(blocked.instruments.single { it.symbol == "@MES" }.executionLabel.contains("限流"))
        }
    }

    @Test fun chartHistoryUsesTheSameTimePenaltyProtocolAsVolumeHistory() = runTest {
        val wire = Wire().apply { omitFutureSessions = true; chartPenalty = true }
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(1, broker.history("@MES", Timeframe.M5).size)
        assertEquals(1, wire.chartPenaltyRetries)
        assertEquals(2, wire.dailyRequests.size)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun retainedDailyHistoryInvalidatesOnNewDateOrCompletedVolumeCorrection() = runTest {
        for (update in listOf(
            """{"bars":[{"timestamp":"2026-10-30T00:00Z","upVolume":101,"downVolume":0}]}""",
            """{"bars":[{"timestamp":"2026-11-02T00:00Z","upVolume":1,"downVolume":0}]}""")) {
            val collector = DailyVolumeHistory(20261030, allowEmpty = true)
            collector.accept(json("""{"bars":[{"timestamp":"2026-10-30T00:00Z","upVolume":100,"downVolume":0}],"eoh":true}"""))
            assertEquals(100, collector.result.await())
            assertFalse(collector.invalidated)
            collector.accept(json(update))
            assertTrue(collector.invalidated)
        }
    }

    @Test fun rawContinuousSymbolCannotReachDryrunOrOrderEndpoints() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        val raw = request().copy(symbol = "@MES", sourceSymbol = null)
        val session = broker.openSession("100")
        assertFalse(session.preview(raw).valid)
        assertNull(session.orderCapacity(raw).maximumLotsHundredths)
        assertFalse(session.placeOrder("raw", raw).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun volumeLeaderIgnoresIsFrontAndRejectsTiesMissingAndForeignData() = runTest {
        val wire = Wire()
        suspend fun select(root: String = "MES", volumes: Map<String, Long>) = selectVolumeLeader(400, root, wire.clock,
            { wire.request(TradovateEnvironment.DEMO, "test", "GET", it, null) as JsonArray },
            { symbol, session -> assertEquals(20261229, session.day); volumes.getValue(symbol) })
        assertEquals("MESH7", select(volumes = mapOf("MESZ6" to 10, "MESH7" to 1000)).contract.str("name"))
        assertFailsWith<IllegalArgumentException> { select(volumes = mapOf("MESZ6" to 10, "MESH7" to 10)) }
        assertFailsWith<IllegalArgumentException> { select(volumes = mapOf("MESZ6" to 0, "MESH7" to 0)) }
        assertFailsWith<NoSuchElementException> { select(volumes = mapOf("MESZ6" to 10)) }
        assertFailsWith<IllegalArgumentException> { select("ES", mapOf("MESZ6" to 10, "MESH7" to 1000)) }
    }

    @Test fun missingDailyVolumeCannotBecomeAZeroVolumeCandidate() = runTest {
        val wire = Wire().apply { missingVolume = true }
        val broker = ready(wire)
        assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertFalse(broker.openSession("100").placeOrder("missing-volume", request()).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun dailyCollectorRequiresDateBothVolumesAndEndOfHistory() = runTest {
        val data = DailyVolumeHistory(20261229)
        val bar = json("""{"td":20261229,"bars":[{"timestamp":"2026-12-29T00:00:00Z","upVolume":100,"downVolume":5}]}""")
        data.accept(bar); data.accept(bar)
        assertFalse(data.result.isCompleted)
        data.accept(json("""{"eoh":true}"""))
        assertEquals(105, data.result.await())
        val wrong = DailyVolumeHistory(20261230)
        wrong.accept(bar)
        assertFailsWith<IllegalArgumentException> { wrong.result.await() }
        val missing = DailyVolumeHistory(20261229)
        missing.accept(json("""{"td":20261229,"bars":[{"timestamp":"2026-12-29T00:00:00Z","upVolume":100}]}"""))
        assertFailsWith<IllegalArgumentException> { missing.result.await() }
    }

    @Test fun liveDailyBarWithoutTdUsesExactUtcDateAndStillRejectsContradictions() = runTest {
        val packet = json("""{"s":"db","bars":[{"timestamp":"2026-10-29T00:00Z","upVolume":721725,"downVolume":716336}],"eoh":true}""")
        val collector = DailyVolumeHistory(20261029)
        collector.accept(packet)
        assertEquals(1_438_061, collector.result.await())
        for (bad in listOf(
            packet.toString().replace("2026-10-29T00:00Z", "2026-10-28T00:00Z"),
            packet.toString().replace("2026-10-29T00:00Z", "2026-10-29T01:00Z"),
            packet.toString().replace("\"s\":\"db\"", "\"td\":20261028"),
            packet.toString().replace("\"s\":\"db\"", "\"td\":null"),
        )) {
            val invalid = DailyVolumeHistory(20261029, allowEmpty = true)
            invalid.accept(json(bad))
            assertFailsWith<IllegalArgumentException> { invalid.result.await() }
        }
    }

    @Test fun completedEmptyHistoryIsZeroOnlyWithPermissionAndNeverHidesInvalidBars() = runTest {
        val allowed = DailyVolumeHistory(20261029, allowEmpty = true)
        allowed.accept(json("""{"bars":[]}"""))
        assertFalse(allowed.result.isCompleted)
        allowed.accept(json("""{"eoh":true}"""))
        assertEquals(0, allowed.result.await())
        for (packet in listOf("""{"eoh":true}""", """{"bars":[null],"eoh":true}""", """{"bars":{},"eoh":true}""")) {
            val invalid = DailyVolumeHistory(20261029, allowEmpty = packet.contains("bars"))
            invalid.accept(json(packet))
            assertFailsWith<IllegalArgumentException> { invalid.result.await() }
        }
    }

    @Test fun realProtocolResolvesLeaderDespiteEmptyFarMonthAndCancelsSharedSubscriptions() = runTest {
        val wire = Wire().apply { emptySymbols = setOf("MESH7") }
        val broker = ready(wire)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals(2, wire.dailyRequests.size)
        for (body in wire.dailyRequests) {
            val range = body["timeRange"]!!.jsonObject
            assertEquals("2026-12-29T00:00:00Z", range.str("asFarAsTimestamp"))
            assertEquals("2026-12-29T23:59:59.999Z", range.str("closestTimestamp"))
        }
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun emptyHistoryWithoutRealtimeModeOrEndMarkerCannotResolveLeader() = runTest {
        for (mode in listOf(null, "Delayed")) {
            val wire = Wire().apply { emptySymbols = setOf("MESH7"); dailyMode = mode }
            assertNull(ready(wire).instruments.single { it.symbol == "@MES" }.executionSymbol)
        }
        val wire = Wire().apply { emptySymbols = setOf("MESH7"); endHistory = false }
        val broker = ready(wire)
        advanceTimeBy(20_000); runCurrent()
        assertNull(broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertTrue(broker.market.value.statusMessage.contains("超时"))
    }

    @Test fun dailyRangeUsesExchangeTradeDateEvenWhenMidnightFallsOutsideSessionHours() {
        val session = CompletedSession(20261029, timestamp("2026-10-29T01:00Z")!!, timestamp("2026-10-29T09:00Z")!!, Long.MAX_VALUE)
        assertEquals("2026-10-29T00:00:00Z", dailyVolumeRange(session).str("asFarAsTimestamp"))
    }

    @Test fun actualMesSessionHoursAcceptPreOpenAndPostCloseButRejectReversedTrading() {
        val rows = (29..30).map { day -> json("""{"contractMaturityId":63452,"tradeDate":{"year":2026,"month":10,"day":$day},"startTime":"2026-10-${day-1}T22:00Z","openTime":"2026-10-${day-1}T21:30Z","endTime":"2026-10-${day}T21:00Z","closeTime":"2026-10-${day}T21:29Z"}""") }
        assertEquals(20261029, completedSession(rows, 63452, timestamp("2026-10-30T18:00Z")!!).day)
        assertFailsWith<IllegalArgumentException> {
            completedSession(rows.map { JsonObject(it + ("endTime" to it.getValue("startTime"))) }, 63452, timestamp("2026-10-30T18:00Z")!!)
        }
    }

    @Test fun sessionCalendarHandlesWeekendAndEarlyCloseWithoutDeviceDateGuesses() {
        val rows = listOf(
            json("""{"contractMaturityId":1,"tradeDate":{"year":2026,"month":11,"day":27},"startTime":"2026-11-26T23:00:00Z","openTime":"2026-11-26T23:00:00Z","closeTime":"2026-11-27T18:00:00Z","endTime":"2026-11-27T18:15:00Z"}"""),
            json("""{"contractMaturityId":1,"tradeDate":{"year":2026,"month":11,"day":30},"startTime":"2026-11-29T23:00:00Z","openTime":"2026-11-29T23:00:00Z","closeTime":"2026-11-30T22:00:00Z","endTime":"2026-11-30T22:15:00Z"}"""))
        assertEquals(20261127, completedSession(rows, 1, timestamp("2026-11-28T12:00:00Z")!!).day)
        assertEquals(20261127, completedSession(rows, 1, timestamp("2026-11-30T12:00:00Z")!!).day)
        val tail = completedSession(rows.take(1), 1, timestamp("2026-11-28T12:00:00Z")!!)
        assertEquals(20261127, tail.day)
        assertTrue(tail.verifyLatestDailyBar)
        assertEquals(timestamp("2026-11-30T18:15:00Z"), tail.validUntil)
        assertFailsWith<IllegalArgumentException> { completedSession(rows.take(1), 1, tail.validUntil) }
    }

    @Test fun fridayCalendarWithoutMondayResolvesSaturdayButRejectsOldOrMalformedEvidence() {
        val rows = (29..30).map { day -> json("""{"contractMaturityId":63452,"tradeDate":{"year":2026,"month":10,"day":$day},"startTime":"2026-10-${day-1}T22:00Z","endTime":"2026-10-${day}T21:00Z"}""") }
        val now = timestamp("2026-10-31T15:05Z")!!
        val session = completedSession(rows, 63452, now)
        assertEquals(20261030, session.day)
        assertTrue(session.verifyLatestDailyBar)
        assertNull(dailyVolumeRange(session)["closestTimestamp"])
        assertFailsWith<IllegalArgumentException> { completedSession(rows, 63452, timestamp("2026-11-02T21:00Z")!!) }
        assertFailsWith<IllegalArgumentException> { completedSession(emptyList(), 63452, now) }
        assertFailsWith<IllegalArgumentException> { completedSession(rows + rows.last(), 63452, now) }
        val overlapping = rows.map { JsonObject(it + ("startTime" to JsonPrimitive("2026-10-28T22:00Z"))) }
        assertFailsWith<IllegalArgumentException> { completedSession(overlapping, 63452, now) }
    }
}
