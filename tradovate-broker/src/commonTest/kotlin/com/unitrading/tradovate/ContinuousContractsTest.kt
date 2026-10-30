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
        private var channelId = 1000
        val dailyRequests = mutableListOf<JsonObject>()
        val maturities get() = listOf(300L, 301L).map { id -> json("""{"id":$id,"productId":400,"isFront":${id == 300L},"expirationDate":"2027-03-20T00:00:00Z"}""") }
        val contracts get() = listOf(json("""{"id":200,"name":"MESZ6","contractMaturityId":300}"""),
            json("""{"id":201,"name":"MESH7","contractMaturityId":301}"""))
        val sessions get() = listOf(300L, 301L).flatMap { id -> (28..31).map { day ->
            json("""{"contractMaturityId":$id,"tradeDate":{"year":2026,"month":12,"day":$day},"startTime":"2026-12-${day-1}T23:30:00Z","openTime":"2026-12-${day-1}T23:30:00Z","closeTime":"2026-12-${day}T23:00:00Z","endTime":"2026-12-${day}T23:00:00Z"}""")
        } }
        init { delegate.snapshot = JsonObject(delegate.snapshot + mapOf("contractMaturities" to JsonArray(maturities), "contracts" to JsonArray(contracts))) }
        override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement = when {
            path.startsWith("contractMaturity/deps") -> {
                if (failMapping) error("offline")
                JsonArray(maturities)
            }
            path.startsWith("contract/ldeps") -> JsonArray(contracts)
            path.startsWith("tradingSession/ldeps") -> JsonArray(sessions)
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
                    if ((body["timeRange"] as? JsonObject)?.long("asMuchAsElements") != 1L) { socket.send(text); return }
                    dailyRequests.add(body)
                    val id = ++channelId
                    val end = (body["timeRange"] as JsonObject).str("closestTimestamp")!!
                    val day = end.take(10).replace("-", "").toLong()
                    val volume = if (body.str("symbol") == leader) 1000 else 10
                    // Deliver data before the response to exercise subscription registration races.
                    val bars = if (missingVolume) "[]" else """[{"timestamp":"${end.take(10)}T00:00:00Z","upVolume":$volume,"downVolume":0}]"""
                    delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"td":$day,"bars":$bars},{"id":$id,"eoh":true}]}}]""")
                    delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"historicalId":$id,"realtimeId":${id + 10000}}}]""")
                }
            }
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
        assertEquals(1, broker.history("@MES", Timeframe.M5).size)
        assertFalse(broker.openSession("100").placeOrder("unresolved", request()).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun mappingFailureAtSubmitDoesNotReuseCachedTarget() = runTest {
        val wire = Wire()
        val broker = ready(wire)
        wire.failMapping = true
        wire.clock += 86_400_000
        assertFalse(broker.openSession("100").placeOrder("failed-refresh", request()).accepted)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
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

    @Test fun sessionCalendarHandlesWeekendAndEarlyCloseWithoutDeviceDateGuesses() {
        val rows = listOf(
            json("""{"contractMaturityId":1,"tradeDate":{"year":2026,"month":11,"day":27},"startTime":"2026-11-26T23:00:00Z","openTime":"2026-11-26T23:00:00Z","closeTime":"2026-11-27T18:00:00Z","endTime":"2026-11-27T18:15:00Z"}"""),
            json("""{"contractMaturityId":1,"tradeDate":{"year":2026,"month":11,"day":30},"startTime":"2026-11-29T23:00:00Z","openTime":"2026-11-29T23:00:00Z","closeTime":"2026-11-30T22:00:00Z","endTime":"2026-11-30T22:15:00Z"}"""))
        assertEquals(20261127, completedSession(rows, 1, timestamp("2026-11-28T12:00:00Z")!!).day)
        assertEquals(20261127, completedSession(rows, 1, timestamp("2026-11-30T12:00:00Z")!!).day)
        assertFailsWith<IllegalArgumentException> { completedSession(rows.take(1), 1, timestamp("2026-11-28T12:00:00Z")!!) }
    }
}
