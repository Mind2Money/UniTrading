package com.unitrading.tradovate

import com.unitrading.broker.Timeframe
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MarketWatchBrokerTest {
    private suspend fun TestScope.ready(wire: TradovateWire): TradovateBroker {
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO,
            "test-access-token-1234567890", "test-md-token-1234567890", 42, "test", 4_102_444_800_000),
            wire, backgroundScope, now = { 1_798_589_000_000L })
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
        assertEquals(3, searches.size)
        assertTrue(searches.all { it.path == "contract/suggest?t=MES&l=100" })
        assertTrue(wire.requests.none { it.path.startsWith("contractMaturity/deps") })
        assertTrue(wire.market.sent.none { it == "md/getChart" })
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
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
