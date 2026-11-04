package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MarketWatchBrokerTest {
    private suspend fun TestScope.ready(wire: TradovateWire, now: () -> Long = { 1_798_589_000_000L }): TradovateBroker {
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO,
            "test-access-token-1234567890", "test-md-token-1234567890", 42, "test", 4_102_444_800_000),
            wire, backgroundScope, now = now)
        broker.connect()
        return broker
    }

    @Test fun searchingUnloadedEsCreatesItsContinuousAliasFromServerMetadata() = runTest {
        val delegate = MockTradovateWire()
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement = when (path) {
                "contract/suggest?t=ES&l=100" -> JsonArray(listOf(json("""{"id":202,"name":"ESZ6","contractMaturityId":302}""")))
                "contractMaturity/item?id=302" -> json("""{"id":302,"productId":402,"expirationMonth":202612}""")
                "product/item?id=402" -> json("""{"id":402,"name":"ES","description":"E-mini S&P 500","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":50}""")
                else -> delegate.request(environment, token, method, path, body)
            }
        }
        val broker = ready(wire)
        assertTrue(broker.instruments.none { it.symbol == "@ES" })
        broker.searchInstruments("@ES")
        val alias = broker.instruments.single { it.symbol == "@ES" }
        assertTrue(alias.isContinuous)
        assertEquals("E-mini S&P 500", alias.name)
        assertNull(alias.executionSymbol, "Discovery must not invent a main contract before volume verification")
        assertTrue(delegate.market.sent.none { it == "md/getChart" })
    }

    @Test fun continuousDiscoveryDoesNotWaitForVolumeOrSendAliasToSuggest() = runTest {
        val wire = MockTradovateWire()
        val broker = ready(wire)
        for (query in listOf("MES", " @mes ", "MES1!")) broker.searchInstruments(query)
        assertNotNull(broker.instruments.singleOrNull { it.symbol == "@MES" })
        val searches = wire.requests.filter { it.path.startsWith("contract/suggest") }
        assertEquals(1, searches.size, "Equivalent searches reuse the recent server discovery")
        assertTrue(searches.all { it.path == "contract/suggest?t=MES&l=100" })
        assertTrue(wire.requests.none { it.path.startsWith("contractMaturity/deps") })
        assertTrue(wire.market.sent.none { it == "md/getChart" })
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }

    @Test fun cachedDiscoveryExpiresAndDoesNotReplaceServerSearch() = runTest {
        val wire = MockTradovateWire()
        var clock = 1_798_589_000_000L
        val broker = ready(wire) { clock }
        broker.searchInstruments("MES")
        clock += 29_999
        broker.searchInstruments("@MES")
        assertEquals(1, wire.requests.count { it.path.startsWith("contract/suggest") })
        clock += 1
        broker.searchInstruments("MES1!")
        assertEquals(2, wire.requests.count { it.path.startsWith("contract/suggest") })
    }

    @Test fun fourMonthsWaitingForOneSlowProductDoNotBlockAnIndependentOrEmptySearch() = runTest {
        val delegate = MockTradovateWire()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement = when (path) {
                "contract/suggest?t=ES&l=100" -> JsonArray(listOf(202, 203, 205, 206).map { id ->
                    json("""{"id":$id,"name":"ESZ$id","contractMaturityId":${id + 100}}""")
                })
                "contract/suggest?t=NQ&l=100" -> JsonArray(listOf(json("""{"id":204,"name":"NQZ6","contractMaturityId":304}""")))
                "contract/suggest?t=NOTFOUND&l=100" -> JsonArray(emptyList())
                "contractMaturity/items?ids=302,303,305,306" -> JsonArray(listOf(302, 303, 305, 306).map { id ->
                    json("""{"id":$id,"productId":402,"expirationMonth":202612}""")
                })
                "product/item?id=402" -> {
                    requested.complete(Unit)
                    release.await()
                    json("""{"id":402,"name":"ES","description":"E-mini S&P 500","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":50}""")
                }
                "contractMaturity/item?id=304" -> json("""{"id":304,"productId":404,"expirationMonth":202612}""")
                "product/item?id=404" -> json("""{"id":404,"name":"NQ","description":"E-mini Nasdaq","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":20}""")
                else -> delegate.request(environment, token, method, path, body)
            }
        }
        val broker = ready(wire)
        val slow = async { broker.searchInstruments("ES") }
        requested.await()
        runCurrent()
        try {
            withTimeout(10_000) {
                broker.searchInstruments("NQ")
                broker.searchInstruments("NOTFOUND")
            }
            assertFalse(slow.isCompleted, "Unrelated searches must finish while the ES metadata request is still pending")
            assertNotNull(broker.instruments.singleOrNull { it.symbol == "@NQ" })
        } finally { release.complete(Unit) }
        slow.await()
        assertNotNull(broker.instruments.singleOrNull { it.symbol == "@ES" })
    }

    @Test fun overlappingSearchesShareSpecificationsAndFailedHydrationCanRetryCachedDiscovery() = runTest {
        val delegate = MockTradovateWire()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondDiscovery = CompletableDeferred<Unit>()
        var maturityRequests = 0
        var productRequests = 0
        var discoveryRequests = 0
        var wrongMaturity = true
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement = when {
                path.startsWith("contract/suggest?t=ES") -> {
                    discoveryRequests++
                    if (path == "contract/suggest?t=ESZ6&l=100") secondDiscovery.complete(Unit)
                    JsonArray(listOf(json("""{"id":202,"name":"ESZ6","contractMaturityId":302}""")))
                }
                path == "contractMaturity/item?id=302" -> {
                    maturityRequests++
                    if (wrongMaturity) json("""{"id":999,"productId":402,"expirationMonth":202612}""")
                    else {
                        requested.complete(Unit)
                        release.await()
                        json("""{"id":302,"productId":402,"expirationMonth":202612}""")
                    }
                }
                path == "product/item?id=402" -> {
                    productRequests++
                    json("""{"id":402,"name":"ES","description":"E-mini S&P 500","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":50}""")
                }
                else -> delegate.request(environment, token, method, path, body)
            }
        }
        val broker = ready(wire)
        broker.searchInstruments("ES")
        assertTrue(broker.instruments.none { it.symbol == "@ES" }, "A mismatched metadata ID must not create a tradable specification")
        wrongMaturity = false
        val first = async { broker.searchInstruments("@ES") }
        requested.await()
        val second = async { broker.searchInstruments("ESZ6") }
        secondDiscovery.await()
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(2, maturityRequests, "One rejected item and one successful retry; overlapping searches share the successful lookup")
        assertEquals(1, productRequests)
        assertEquals(2, discoveryRequests, "The failed specification retries without repeating the cached root discovery")
        assertNotNull(broker.instruments.singleOrNull { it.symbol == "@ES" })
    }

    @Test fun watchListQuotesStaySubscribedAcrossChartSwitchesAndRemovalKeepsSelectedChart() = runTest {
        val wire = MockTradovateWire()
        val extra = Json.parseToJsonElement("""{"id":201,"name":"MESH7","contractMaturityId":300}""")
        wire.snapshot = JsonObject(wire.snapshot + ("contracts" to JsonArray(wire.snapshot.array("contracts") + extra)))
        val broker = ready(wire)
        broker.watchQuotes(setOf("MESZ6", "MESH7"))
        runCurrent()
        assertEquals(2, wire.market.sent.count { it == "md/subscribequote" })
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        broker.watch("MESH7", Timeframe.M5); runCurrent()
        assertEquals(0, wire.market.sent.count { it == "md/unsubscribequote" })
        broker.watchQuotes(emptySet()); runCurrent()
        assertEquals(1, wire.market.sent.count { it == "md/unsubscribequote" })
        assertEquals("MESZ6", Json.parseToJsonElement(wire.market.bodies.getValue("md/unsubscribequote")).jsonObject.str("symbol"))
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
}
