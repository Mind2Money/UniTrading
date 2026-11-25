package com.unitrading.tradovate

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Delayed server responses exercise ordering without measuring wall-clock performance. */
class MarketWatchConcurrencyTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private inner class Wire(includeThirdProduct: Boolean = false, includePriceProducts: Boolean = false, val delegate: MockTradovateWire = MockTradovateWire()) : TradovateWire by delegate {
        var now = timestamp("2026-12-29T12:00Z")!!
        val roots = if (includePriceProducts) listOf("MGC", "MCL") else if (includeThirdProduct) listOf("MES", "MNQ", "RTY") else listOf("MES", "MNQ")
        val monitors = mutableMapOf<String, Long>()
        val contracts = roots.flatMapIndexed { index, root ->
            listOf("Z6", "H7", "M7").mapIndexed { month, suffix ->
                json("""{"id":${200 + index * 10 + month},"name":"$root$suffix","contractMaturityId":${300 + index * 10 + month}}""")
            }
        }
        val maturities = roots.flatMapIndexed { index, _ -> (0..2).map { month ->
            json("""{"id":${300 + index * 10 + month},"productId":${400 + index},"expirationMonth":202706,"expirationDate":"2027-06-20T00:00:00Z"}""")
        } }
        private val products = roots.mapIndexed { index, root ->
            json("""{"id":${400 + index},"name":"$root","description":"$root futures","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":5}""")
        }
        val mappingRequests = mutableMapOf<String, Int>()
        val mappingGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var slowSearchGate: CompletableDeferred<Unit>? = null
        var slowSearchRequests = 0
        var cancelledSearches = 0
        var holdHistory = false
        var cancellationGate: CompletableDeferred<Unit>? = null
        val activeCharts = mutableMapOf<Long, String>()
        val dailyRequests = mutableListOf<String>()
        val cancelledCharts = mutableSetOf<Long>()
        var peakCharts = 0
        private var nextChart = 1000L

        init {
            delegate.snapshot = JsonObject(delegate.snapshot + mapOf(
                "contracts" to JsonArray(contracts), "contractMaturities" to JsonArray(maturities),
                "products" to JsonArray(products),
            ))
        }

        override suspend fun request(hosts: TradovateHosts, token: String, method: String, path: String, body: JsonObject?): JsonElement = when {
            path == "contract/suggest?t=SLOW&l=100" -> {
                slowSearchRequests++
                try { slowSearchGate?.await() }
                catch (cancel: CancellationException) { cancelledSearches++; throw cancel }
                JsonArray(emptyList())
            }
            path.startsWith("contractMaturity/deps?") -> {
                val product = path.substringAfter("masterid=").toLong()
                val root = products.single { it.long("id") == product }.str("name")!!
                mappingRequests[root] = mappingRequests.getOrElse(root) { 0 } + 1
                mappingGates[root]?.await()
                JsonArray(maturities.filter { it.long("productId") == product })
            }
            path.startsWith("contract/ldeps?") -> {
                val ids = path.substringAfter("masterids=").split(',').map(String::toLong)
                JsonArray(contracts.filter { it.long("contractMaturityId") in ids })
            }
            path.startsWith("tradingSession/ldeps?") -> {
                val ids = path.substringAfter("masterids=").split(',').map(String::toLong)
                JsonArray(ids.flatMap { id -> (28..31).map { day ->
                    json("""{"contractMaturityId":$id,"tradeDate":{"year":2026,"month":12,"day":$day},"startTime":"2026-12-${day - 1}T23:30Z","endTime":"2026-12-${day}T23:00Z"}""")
                } })
            }
            else -> delegate.request(hosts, token, method, path, body)
        }

        private suspend fun history(id: Long, symbol: String) {
            val volume = if (symbol.endsWith("Z6")) 1000 else 10
            delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"bars":[{"timestamp":"2026-12-28T00:00Z","upVolume":$volume,"downVolume":0}],"eoh":true}]}}]""")
        }

        suspend fun releaseHistory() {
            holdHistory = false
            activeCharts.toMap().forEach { (id, symbol) -> history(id, symbol) }
        }

        override suspend fun socket(url: String): TradovateWireSocket {
            val socket = delegate.socket(url)
            if (!url.contains("md")) return socket
            return object : TradovateWireSocket by socket {
                override suspend fun send(text: String) {
                    val parts = text.split('\n')
                    when (parts.first()) {
                        "md/subscribequote" -> if ("MGC" in roots) {
                            // The generic fixture emits an MES quote timestamped in the future;
                            // these products receive their own correctly timestamped ticks below.
                            delegate.market.sent.add(parts.first())
                            delegate.market.bodies[parts.first()] = text.substringAfter("\n\n")
                            delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"mode":"RealTime"}}]""")
                            return
                        } else socket.send(text)
                        "md/getChart" -> {
                            val body = json(text.substringAfter("\n\n"))
                            val symbol = body.str("symbol")!!
                            if (symbol.startsWith("@")) {
                                val id = ++nextChart
                                monitors[symbol] = id
                                delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"historicalId":$id,"realtimeId":$id,"mode":"RealTime"}}]""")
                                return
                            }
                            val id = ++nextChart
                            dailyRequests.add(symbol)
                            activeCharts[id] = symbol
                            peakCharts = maxOf(peakCharts, activeCharts.size)
                            delegate.market.queue.send("""a[{"s":200,"i":${parts[1]},"d":{"historicalId":$id,"realtimeId":$id,"mode":"RealTime"}}]""")
                            if (!holdHistory) history(id, symbol)
                        }
                        "md/cancelChart" -> {
                            val id = json(text.substringAfter("\n\n")).long("subscriptionId")!!
                            cancellationGate?.await()
                            activeCharts.remove(id)
                            cancelledCharts.add(id)
                            socket.send(text)
                        }
                        else -> socket.send(text)
                    }
                }
            }
        }
    }

    private suspend fun TestScope.ready(wire: Wire): TradovateBroker {
        val broker = TradovateBroker(TradovateSession(TradovateEnvironment.DEMO,
            "test-access-token-1234567890", "test-md-token-1234567890", 42, "test", 4_102_444_800_000),
            wire, backgroundScope, now = { wire.now })
        broker.connect()
        assertEquals(wire.roots.map { "@$it" }.toSet(), broker.instruments.filter { it.isContinuous }.map { it.symbol }.toSet())
        return broker
    }

    @Test fun watchListMatchesTwoProductsWithoutOpeningTheirCharts() = runTest {
        val wire = Wire(includePriceProducts = true).apply { roots.forEach { mappingGates[it] = CompletableDeferred() } }
        val broker = ready(wire)
        broker.watchQuotes(linkedSetOf("@MGC", "@MCL"))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(setOf("MGC", "MCL"), wire.mappingRequests.keys, "Both products can discover months concurrently")
        wire.mappingGates.values.forEach { it.complete(Unit) }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(setOf("@MGC", "@MCL"), wire.monitors.keys, "Both watch-list aliases receive live prices")
        for (i in 0..2) {
            wire.now += 1000
            val at = kotlin.time.Instant.fromEpochMilliseconds(wire.now).toString()
            for ((index, alias) in listOf("@MGC", "@MCL").withIndex()) {
                val price = 5500.0 + index * 100 + i * 0.25
                val id = wire.monitors.getValue(alias)
                val contractId = 200 + index * 10
                wire.delegate.market.queue.send("""a[{"e":"chart","d":{"charts":[{"id":$id,"s":"s","bars":[{"timestamp":"$at","open":$price,"high":$price,"low":$price,"close":$price}]}]}}]""")
                wire.delegate.market.queue.send("""a[{"e":"md","d":{"quotes":[{"contractId":$contractId,"timestamp":"$at","entries":{"Trade":{"price":$price},"Bid":{"price":$price},"Offer":{"price":${price + 0.25}}}},{"contractId":${contractId + 1},"timestamp":"$at","entries":{"Trade":{"price":${price + 10}}}},{"contractId":${contractId + 2},"timestamp":"$at","entries":{"Trade":{"price":${price + 20}}}}]}}]""")
            }
            runCurrent()
        }
        assertEquals("MGCZ6", broker.instruments.single { it.symbol == "@MGC" }.executionSymbol)
        assertEquals("MCLZ6", broker.instruments.single { it.symbol == "@MCL" }.executionSymbol)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun slowMissingSymbolDoesNotDelayKnownContinuousQuotes() = runTest {
        val wire = Wire().apply { slowSearchGate = CompletableDeferred() }
        val broker = ready(wire)
        broker.watchQuotes(linkedSetOf("@SLOW", "@MES"))
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1, wire.slowSearchRequests)
        assertFalse(wire.slowSearchGate!!.isCompleted)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol,
            "A slow saved symbol must not hold up an already loaded product's verification")
        assertTrue(wire.delegate.market.sent.contains("md/subscribequote"))
        wire.slowSearchGate!!.complete(Unit)
    }

    @Test fun editingWatchListKeepsAnExistingSymbolSearchAlive() = runTest {
        val wire = Wire().apply { slowSearchGate = CompletableDeferred() }
        val broker = ready(wire)
        broker.watchQuotes(linkedSetOf("@SLOW", "@MES"))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, wire.slowSearchRequests)
        broker.watchQuotes(linkedSetOf("@MNQ", "@SLOW", "@MES"))
        advanceTimeBy(1_000); runCurrent()
        broker.watchQuotes(linkedSetOf("@MES", "@SLOW"))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, wire.slowSearchRequests, "Unchanged symbols must reuse the work already in flight")
        assertEquals(0, wire.cancelledSearches)
        wire.slowSearchGate!!.complete(Unit)
    }

    @Test fun anotherProductCanResolveWhileOneProductsMetadataIsBlocked() = runTest {
        val wire = Wire().apply { mappingGates["MES"] = CompletableDeferred() }
        val broker = ready(wire)
        val blocked = launch { broker.refreshContinuous("@MES") }
        advanceTimeBy(1_000); runCurrent()
        val other = launch { broker.refreshContinuous("@MNQ") }
        advanceTimeBy(6_000); runCurrent()
        assertFalse(blocked.isCompleted)
        assertTrue(other.isCompleted, "A different product must not queue behind the blocked product")
        assertEquals("MNQZ6", broker.instruments.single { it.symbol == "@MNQ" }.executionSymbol)
        wire.mappingGates.getValue("MES").complete(Unit)
        blocked.join()
    }

    @Test fun concurrentRequestsForSameProductReuseTheConfirmedResult() = runTest {
        val wire = Wire().apply { mappingGates["MES"] = CompletableDeferred() }
        val broker = ready(wire)
        val first = launch { broker.refreshContinuous("@MES") }
        advanceTimeBy(1_000); runCurrent()
        val second = launch { broker.refreshContinuous("@MES") }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, wire.mappingRequests["MES"])
        wire.mappingGates.getValue("MES").complete(Unit)
        joinAll(first, second)
        assertEquals(1, wire.mappingRequests["MES"])
        assertEquals(3, wire.dailyRequests.size)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
    }

    @Test fun dailyChartBudgetIsSharedAcrossProductsUntilCancellationFinishes() = runTest {
        val wire = Wire().apply { holdHistory = true }
        val broker = ready(wire)
        val first = launch { broker.refreshContinuous("@MES") }
        val second = launch { broker.refreshContinuous("@MNQ") }
        advanceTimeBy(8_000); runCurrent()
        assertEquals(setOf("MES", "MNQ"), wire.mappingRequests.keys)
        assertEquals(3, wire.activeCharts.size)
        assertEquals(3, wire.dailyRequests.size, "All products share the same three daily-history slots")
        wire.cancellationGate = CompletableDeferred()
        wire.releaseHistory(); runCurrent()
        // Keep this below the 2-second cancellation timeout: pending cancellation still owns its slot.
        advanceTimeBy(500); runCurrent()
        assertEquals(3, wire.dailyRequests.size)
        wire.cancellationGate!!.complete(Unit)
        joinAll(first, second)
        assertEquals(6, wire.dailyRequests.size)
        assertEquals(3, wire.peakCharts)
        assertTrue(wire.activeCharts.isEmpty())
        assertEquals(6, wire.cancelledCharts.size)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals("MNQZ6", broker.instruments.single { it.symbol == "@MNQ" }.executionSymbol)
        assertTrue(wire.delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun waitingForAResolutionSlotDoesNotConsumeTheNetworkTimeout() = runTest {
        val wire = Wire(includeThirdProduct = true).apply {
            roots.forEach { mappingGates[it] = CompletableDeferred() }
        }
        val broker = ready(wire)
        val first = launch { broker.refreshContinuous("@MES") }
        val second = launch { broker.refreshContinuous("@MNQ") }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(setOf("MES", "MNQ"), wire.mappingRequests.keys)
        val queuedAt = testScheduler.currentTime
        val queued = launch { broker.refreshContinuous("@RTY") }
        advanceTimeBy(168_000); runCurrent()
        assertFalse(queued.isCompleted)
        assertNull(wire.mappingRequests["RTY"], "Only two products may occupy the resolution pipeline")
        wire.mappingGates.getValue("MES").complete(Unit)
        wire.mappingGates.getValue("MNQ").complete(Unit)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        assertEquals("MNQZ6", broker.instruments.single { it.symbol == "@MNQ" }.executionSymbol)
        assertEquals(1, wire.mappingRequests["RTY"])
        assertTrue(testScheduler.currentTime - queuedAt > 180_000)
        assertFalse(queued.isCompleted, "The queued product gets its full timeout after admission")
        wire.mappingGates.getValue("RTY").complete(Unit)
        queued.join()
        assertEquals("RTYZ6", broker.instruments.single { it.symbol == "@RTY" }.executionSymbol)
    }

    @Test fun validCachedRouteDoesNotWaitForTwoUnrelatedResolutions() = runTest {
        val wire = Wire(includeThirdProduct = true)
        val broker = ready(wire)
        broker.refreshContinuous("@MES")
        wire.mappingGates["MNQ"] = CompletableDeferred()
        wire.mappingGates["RTY"] = CompletableDeferred()
        val first = launch { broker.refreshContinuous("@MNQ") }
        val second = launch { broker.refreshContinuous("@RTY") }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(setOf("MES", "MNQ", "RTY"), wire.mappingRequests.keys)
        val cached = launch { broker.refreshContinuous("@MES") }
        runCurrent()
        assertTrue(cached.isCompleted, "A valid route needs neither a resolution slot nor another server request")
        assertFalse(first.isCompleted || second.isCompleted)
        assertEquals(1, wire.mappingRequests["MES"])
        assertEquals("MESZ6", broker.instruments.single { it.symbol == "@MES" }.executionSymbol)
        wire.mappingGates.getValue("MNQ").complete(Unit)
        wire.mappingGates.getValue("RTY").complete(Unit)
        joinAll(first, second)
    }
}
