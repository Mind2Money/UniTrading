package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

private const val TEST_TIME = 1_798_589_000_000L
private val TEST_INSTANT get() = kotlin.time.Instant.fromEpochMilliseconds(TEST_TIME).toString()
private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
private fun fakeToken(user: Long = 42) = "eyJhbGciOiJub25lIn0." + Base64.UrlSafe.encode("{\"sub\":$user,\"exp\":4102444800}".encodeToByteArray()).trimEnd('=') + ".test-signature-only"

internal class MockTradovateWire : TradovateWire {
    data class Request(val method: String, val path: String, val body: JsonObject?)
    val requests = mutableListOf<Request>()
    val requestHosts = mutableListOf<TradovateHosts>()
    /** REST calls to these hosts get the redirect a moved organisation's users get from the default hosts. */
    var redirecting: TradovateHosts? = null
    val socketUrls = mutableListOf<String>()
    val account = MockSocket(false)
    val market = MockSocket(true)
    var failWrite = false
    var rejectWrite = false
    var rejectMarket = false
    var capacityCheck: (JsonObject) -> JsonObject = { obj("""{"rejectReason":"Success"}""") }
    var numericChartTime = false
    var chartEventHasRequestId = false
    var fractionalChartId = false
    var reducedPrecisionChartTime = false
    var renewal = obj("""{"accessToken":"renewed-test-token-1234567890","expirationTime":"2100-01-01T00:00:00Z"}""")
    val feeResponses = mutableMapOf<Long, JsonArray>()
    val historyItems = mutableMapOf<String, JsonArray>()
    val balanceSnapshots = mutableMapOf<Long, JsonObject>()
    val deferredBalances = mutableMapOf<Long, CompletableDeferred<JsonObject>>()
    var snapshot = obj("""{
      "accounts":[{"id":100,"name":"Demo account","userId":42},{"id":101,"name":"Second account","userId":42},{"id":999,"name":"Foreign account","userId":9}],
      "contracts":[{"id":200,"name":"MESZ6","contractMaturityId":300}],
      "contractMaturities":[{"id":300,"productId":400,"expirationMonth":202612}],
      "products":[{"id":400,"name":"MES","description":"Micro E-mini S&P","currencyId":1,"productType":"Futures","tickSize":0.25,"valuePerPoint":5}],
      "currencies":[{"id":1,"name":"USD"}],
      "cashBalances":[{"id":1,"accountId":100,"currencyId":1,"amount":25000,"timestamp":"$TEST_INSTANT"}],
      "positions":[],"orders":[],"orderVersions":[],"fills":[]
    }""")
    override suspend fun request(hosts: TradovateHosts, token: String, method: String, path: String, body: JsonObject?): JsonElement {
        requests.add(Request(method, path, body))
        requestHosts.add(hosts)
        if (hosts == redirecting && !path.startsWith("auth/")) throw TradovateRemoteException(307, "Tradovate HTTP 307")
        if (path == "order/dryrun") return capacityCheck(requireNotNull(body))
        if (path == "fillPair/list") return snapshot["fillPairs"] ?: JsonArray(emptyList())
        if (path.startsWith("fillFee/deps")) return feeResponses[path.substringAfter("masterid=").toLong()] ?: JsonArray(emptyList())
        if (path.startsWith("fill/items") || path.startsWith("order/items")) return historyItems[path.substringBefore('/')] ?: JsonArray(emptyList())
        if (path == "auth/renewaccesstoken") return renewal
        if (path == "account/list") return JsonArray(snapshot.array("accounts").filter { it.long("id") != 999L })
        if (path.startsWith("cashBalance/")) {
            deferredBalances.remove(body?.long("accountId"))?.let { return it.await() }
            return balanceSnapshots[body?.long("accountId")]
                ?: obj("""{"totalCashValue":25000,"netLiq":25000,"initialMargin":0}""")
        }
        if (path.startsWith("contract/suggest")) return snapshot["contracts"]!!
        if (path.startsWith("order/")) {
            if (failWrite) throw TradovateRemoteException(0, "timeout")
            if (rejectWrite) return obj("""{"failureReason":"Unauthorized","failureText":"Trading disabled"}""")
            return obj("""{"orderId":700,"oso1Id":701,"oso2Id":702}""")
        }
        error("Unexpected REST request: $path")
    }
    override suspend fun socket(url: String): TradovateWireSocket {
        socketUrls.add(url)
        return if (url.contains("md")) market else account
    }
    override fun close() { account.queue.close(); market.queue.close() }

    inner class MockSocket(private val md: Boolean) : TradovateWireSocket {
        val queue = Channel<String>(Channel.UNLIMITED).apply { trySend("o") }
        val sent = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        override suspend fun send(text: String) {
            if (text == "[]") return
            val parts = text.split('\n')
            val route = parts[0]; val id = parts[1].toInt()
            sent.add(route)
            bodies[route] = text.substringAfter("\n\n")
            val data = when (route) {
                "user/syncrequest" -> {
                    val request = obj(bodies.getValue(route))
                    val types = request["entityTypes"] as? JsonArray
                    if (types?.any { it.jsonPrimitive.content == "account" } == true) snapshot
                    else obj("""{"accounts":[],"orders":[],"orderVersions":[],"positions":[],"fills":[]}""")
                }
                "md/subscribequote" -> if (rejectMarket) obj("""{"errorCode":"AccessDenied","errorText":"No market entitlement"}""") else obj("""{"mode":"RealTime"}""")
                "md/getChart" -> if (fractionalChartId) obj("""{"historicalId":12.5,"realtimeId":13.5}""") else obj("""{"historicalId":12,"realtimeId":13}""")
                else -> obj("{}")
            }
            queue.send("a[${buildJsonObject { put("s", 200); put("i", id); put("d", data) }}]")
            if (route == "md/subscribequote" && !rejectMarket) quote()
            if (route == "md/getChart") {
                val stamp = when {
                    reducedPrecisionChartTime -> "\"2026-10-28T12:35Z\""
                    numericChartTime -> TEST_TIME.toString()
                    else -> "\"$TEST_INSTANT\""
                }
                val correlation = if (chartEventHasRequestId) "\"i\":$id," else ""
                val chartId = if (fractionalChartId) "1.25e1" else "12"
                queue.send("""a[{$correlation"e":"chart","d":{"charts":[{"id":$chartId,"bars":[{"timestamp":$stamp,"open":5500,"high":5501,"low":5499.75,"close":5500.25,"upVolume":3,"downVolume":2}]}]}}]""")
            }
        }
        suspend fun quote(bid: String = "5500", ask: String = "5500.25", time: Long = TEST_TIME) {
            queue.send("""a[{"e":"md","d":{"quotes":[{"contractId":200,"timestamp":"${kotlin.time.Instant.fromEpochMilliseconds(time)}","entries":{"Bid":{"price":$bid},"Offer":{"price":$ask}}}]}}]""")
        }
        suspend fun entity(type: String, data: JsonObject) { queue.send("a[${buildJsonObject { put("e", "props"); putJsonObject("d") { put("entityType", type); put("eventType", "Updated"); put("entity", data) } }}]") }
        override suspend fun receive() = queue.receiveCatching().getOrNull()
        override suspend fun close() { queue.close() }
    }
}

class TradovateTests {
    @Test fun nearExpiryRestorationRenewsBeforeDiscoveryAndNotifiesPersistence() = runTest {
        val wire = MockTradovateWire()
        var saved: TradovateSession? = null
        val initial = TradovateSession(TradovateEnvironment.DEMO, fakeToken(), null, 42, "test", TEST_TIME + 60_000)
        val broker = TradovateBroker(initial, wire, backgroundScope, { TEST_TIME }, { saved = it })
        broker.connect()
        assertEquals("auth/renewaccesstoken", wire.requests.first().path)
        assertEquals("renewed-test-token-1234567890", saved?.accessToken)
        assertEquals(TradovateEnvironment.DEMO, saved?.environment)
        assertEquals(saved?.accessToken, wire.account.bodies["authorize"])
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }

    @Test fun invalidRenewalNeverPersistsAnUnusableSession() = runTest {
        val wire = MockTradovateWire().apply { renewal = obj("""{"accessToken":"renewed-test-token-1234567890"}""") }
        var persisted = false
        val initial = TradovateSession(TradovateEnvironment.DEMO, fakeToken(), null, 42, "test", TEST_TIME + 60_000)
        val broker = TradovateBroker(initial, wire, backgroundScope, { TEST_TIME }, { persisted = true })
        assertFails { broker.connect() }
        assertFalse(persisted)
        assertTrue(wire.socketUrls.isEmpty())
    }

    @Test fun runningSessionRenewalNotifiesPersistence() = runTest {
        val wire = MockTradovateWire()
        var saved: TradovateSession? = null
        var clock = TEST_TIME
        val initial = TradovateSession(TradovateEnvironment.DEMO, fakeToken(), null, 42, "test", TEST_TIME + 180_000)
        val broker = TradovateBroker(initial, wire, backgroundScope, { clock }, { saved = it })
        broker.connect()
        assertNull(saved)
        clock += 70_000
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("renewed-test-token-1234567890", saved?.accessToken)
        assertTrue(broker.market.value.connected)
    }

    private fun session() = TradovateSession(TradovateEnvironment.DEMO, fakeToken(), fakeToken(), 42, "test", 4_102_444_800_000)
    private suspend fun TestScope.ready(wire: MockTradovateWire = MockTradovateWire()): Pair<TradovateBroker, MockTradovateWire> {
        val broker = TradovateBroker(session(), wire, backgroundScope, now = { TEST_TIME })
        broker.connect()
        broker.watch("MESZ6", Timeframe.M5)
        runCurrent()
        assertTrue(broker.market.value.quotes.containsKey("MESZ6"))
        return broker to wire
    }

    @Test fun authorizedAccountsDoNotRequireOwnerIdToMatchLoginUser() = runTest {
        val wire = MockTradovateWire()
        wire.snapshot = JsonObject(wire.snapshot + ("accounts" to JsonArray(listOf(
            obj("""{"id":100,"name":"Authorized delegated account","userId":7}"""),
            obj("""{"id":101,"name":"Authorized account without owner field"}"""),
            obj("""{"id":999,"name":"Not returned by account/list","userId":42}"""),
        ))))
        val (broker, _) = ready(wire)
        assertEquals(listOf("100", "101"), broker.accounts.map { it.id })
        assertFailsWith<IllegalStateException> { broker.openSession("999") }
    }
    @Test fun chartEventsWithCorrelationIdsAndEpochTimesAreNotDropped() = runTest {
        val wire = MockTradovateWire().apply { numericChartTime = true; chartEventHasRequestId = true }
        val (broker, _) = ready(wire)
        assertEquals(1, broker.history("MESZ6", Timeframe.M5).size)
        assertEquals(TEST_TIME, broker.history("MESZ6", Timeframe.M5).single().timeMs)
        assertEquals(5L, broker.history("MESZ6", Timeframe.M5).single().volume)
    }
    @Test fun fractionalChartChannelsAreNotDiscardedAsInvalidEntityIds() = runTest {
        val (broker, _) = ready(MockTradovateWire().apply { fractionalChartId = true })
        assertEquals(1, broker.history("MESZ6", Timeframe.M5).size)
        assertEquals(chartSubscriptionKey(Json.parseToJsonElement("12.50")), chartSubscriptionKey(Json.parseToJsonElement("1.25e1")))
        assertNotEquals(chartSubscriptionKey(Json.parseToJsonElement("9007199254740992")), chartSubscriptionKey(Json.parseToJsonElement("9007199254740993")))
        assertNull(chartSubscriptionKey(JsonNull))
        assertNull(chartSubscriptionKey(JsonPrimitive(false)))
    }
    @Test fun minutePrecisionIsoChartTimesAreAcceptedWithoutGuessingTimezone() = runTest {
        val expected = kotlin.time.Instant.parse("2026-10-28T12:35:00Z").toEpochMilliseconds()
        assertEquals(expected, timestamp("2026-10-28T12:35Z"))
        assertEquals(expected, timestamp("2026-10-28T20:35+08:00"))
        assertEquals(expected, timestamp("2026-10-28T20:35+0800"))
        assertNull(timestamp("2026-10-28T12:35"))
        val (broker, _) = ready(MockTradovateWire().apply { reducedPrecisionChartTime = true })
        assertEquals(expected, broker.history("MESZ6", Timeframe.M5).single().timeMs)
    }
    @Test fun sharedAuthenticationHostDoesNotChangeSelectedTradingEnvironment() {
        assertEquals("https://live.tradovateapi.com/v1/auth/renewaccesstoken", tradovateRequestUrl(TradovateEnvironment.DEMO.defaultHosts, "auth/renewaccesstoken"))
        assertEquals("https://live.tradovateapi.com/v1/auth/me", tradovateRequestUrl(TradovateEnvironment.DEMO.defaultHosts, "auth/me"))
        assertEquals("https://demo.tradovateapi.com/v1/order/placeorder", tradovateRequestUrl(TradovateEnvironment.DEMO.defaultHosts, "order/placeorder"))
        assertEquals("https://live.tradovateapi.com/v1/order/placeorder", tradovateRequestUrl(TradovateEnvironment.LIVE.defaultHosts, "order/placeorder"))
    }
    @Test fun capturedAccountSessionWithoutMarketTokenConnectsDemoAccountsAndQuotes() = runTest {
        val accumulator = LoginAccumulator(TradovateEnvironment.DEMO)
        assertTrue(accumulator.accept("""{"kind":"auth","environment":"demo","accessToken":"${fakeToken()}","userId":42,"authSource":"response"}""", "https://trader.tradovate.com", true))
        val captured = accumulator.result()
        assertNull(captured.mdAccessToken)
        val wire = MockTradovateWire()
        val stages = mutableListOf<String>()
        val broker = TradovateBroker(captured, wire, backgroundScope, now = { TEST_TIME })
        broker.connect { stages.add(it) }
        assertEquals(listOf(TradovateEnvironment.DEMO.socketUrl, TradovateEnvironment.DEMO.marketUrl), wire.socketUrls)
        assertEquals(captured.accessToken, wire.market.bodies["authorize"])
        val syncRequest = obj(wire.account.bodies.getValue("user/syncrequest"))
        assertEquals(false, syncRequest.bool("splitResponses"))
        assertNull(syncRequest["users"], "Personal login must use the server's authorized context, not a live-host user selector")
        assertTrue(syncRequest["entityTypes"]!!.jsonArray.any { it.jsonPrimitive.content == "account" })
        broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertEquals(2, broker.accounts.size)
        assertTrue(broker.market.value.quotes.containsKey("MESZ6"))
        assertTrue(stages.any { it.contains("已同步 2 个账户") })
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    @Test fun incompleteSyncResponseCannotCompleteAccountLogin() = runTest {
        val wire = MockTradovateWire().apply { snapshot = JsonObject(snapshot - "orders") }
        val broker = TradovateBroker(session(), wire, backgroundScope, now = { TEST_TIME })
        val error = assertFailsWith<TradovateRemoteException> { broker.connect() }
        assertTrue(error.message!!.contains("不完整"))
        assertFalse(broker.market.value.connected)
        assertTrue(broker.accounts.isEmpty())
        assertEquals(1, wire.socketUrls.size)
    }
    @Test fun partialBidOfferUpdatesAreMergedButTradesDoNotRefreshQuotes() = runTest {
        val (broker, wire) = ready()
        val updateTime = TEST_TIME + 1000
        wire.market.queue.send("""a[{"e":"md","d":{"quotes":[{"contractId":200,"timestamp":"${kotlin.time.Instant.fromEpochMilliseconds(updateTime)}","entries":{"Bid":{"price":5500.25}}}]}}]""")
        runCurrent()
        assertEquals(5_500_250_000, broker.market.value.quotes.getValue("MESZ6").bid)
        assertEquals(5_500_250_000, broker.market.value.quotes.getValue("MESZ6").ask)
        wire.market.queue.send("""a[{"e":"md","d":{"quotes":[{"contractId":200,"timestamp":"${kotlin.time.Instant.fromEpochMilliseconds(TEST_TIME + 5000)}","entries":{"Trade":{"price":5501}}}]}}]""")
        runCurrent()
        assertEquals(updateTime, broker.market.value.quotes.getValue("MESZ6").timeMs)
    }
    @Test fun loginNeedsUsableExpiryEvenWithAnAccountId() {
        val accumulator = LoginAccumulator(TradovateEnvironment.DEMO)
        assertFalse(accumulator.accept("""{"kind":"auth","environment":"demo","accessToken":"opaque-token-without-expiry","userId":42}""", "https://trader.tradovate.com", true))
        assertTrue(accumulator.accept("""{"kind":"auth","environment":"demo","accessToken":"opaque-token-without-expiry","userId":42,"expires_in":3600}""", "https://trader.tradovate.com", true))
        assertNotNull(accumulator.result().expiresAtMs)
    }
    @Test fun originEnvironmentAndMainFrameAreMandatory() {
        val accumulator = LoginAccumulator(TradovateEnvironment.DEMO)
        val message = """{"kind":"auth","environment":"demo","accessToken":"${fakeToken()}","userId":42}"""
        assertFalse(accumulator.accept(message, "https://evil.example", true))
        assertFalse(accumulator.accept(message, "https://trader.tradovate.com.evil.example", true))
        assertFalse(accumulator.accept(message, "https://trader.tradovate.com", false))
        assertFalse(accumulator.accept(message.replace("\"demo\"", "\"live\""), "https://trader.tradovate.com", true))
        assertTrue(accumulator.accept(message, "https://trader.tradovate.com", true))
        assertEquals(42, accumulator.result().userId)
        assertFalse(accumulator.result().toString().contains(fakeToken()))
    }
    @Test fun websocketCaptureCanRecoverUserIdWithoutReadingBrowserStorage() {
        val accumulator = LoginAccumulator(TradovateEnvironment.DEMO)
        assertFalse(accumulator.accept("""{"kind":"auth","environment":"demo","mdAccessToken":"${fakeToken()}"}""", "https://trader.tradovate.com", true))
        assertTrue(accumulator.accept("""{"kind":"auth","environment":"demo","accessToken":"${fakeToken()}"}""", "https://trader.tradovate.com", true))
        assertEquals(fakeToken(), accumulator.result().mdAccessToken)
        assertEquals(42, accumulator.result().userId)
    }
    @Test fun loginScriptDoesNotReadPasswordFieldsOrStorage() {
        val script = TradovateLoginScript.forEnvironment(TradovateEnvironment.DEMO)
        for (forbidden in listOf("localStorage", "sessionStorage", "input[type", "data.password", "document.cookie")) assertFalse(script.contains(forbidden), forbidden)
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("demo.tradovateapi.com"))
    }
    @Test fun frameDecoderHandlesAllMessagesAndIgnoresHeartbeats() {
        assertEquals(2, decodeFrames("a[{\"s\":200,\"i\":1},{\"e\":\"props\",\"d\":{}}]").size)
        assertTrue(decodeFrames("h").isEmpty())
        assertTrue(decodeFrames("o").isEmpty())
        assertTrue(decodeFrames("c[1000,\"closed\"]").isEmpty())
    }
    @Test fun exactDecimalsAndAveragePriceNeedNotLandOnTick() {
        assertEquals(250_000, decimalUnits("2.5e-1", 6))
        assertEquals(5_500_083_333, decimalUnits("5500.083333333", 6))
        assertNull(decimalUnits("NaN", 6))
        assertNull(decimalUnits("1e100", 6))
        val spec = Instrument("MESZ6", "MES", "Futures", 6, 250_000, 1_250_000, quantityUnit = "张", quantityDecimals = 0)
        assertEquals(3_750_000, grossPnl(spec, Side.BUY, 300, 5_500_250_000, 5_500_500_000))
        assertEquals(2_500_005, grossPnl(spec, Side.BUY, 300, 5_500_083_333, 5_500_250_000))
        assertNull(spec.parseQuantity("0.01"))
        assertEquals(100, spec.parseQuantity("1"))
    }
    @Test fun entityVersionsDoNotRegressAndDeletedOrdersDisappear() {
        val book = EntityBook()
        book.apply("orderVersion", obj("""{"id":12,"orderId":7,"orderQty":2,"price":5500}"""))
        book.apply("orderVersion", obj("""{"id":9,"orderId":7,"orderQty":1,"price":5400}"""))
        assertEquals(2, book.orderVersion(7)?.long("orderQty"))
        book.apply("order", obj("""{"id":7,"ordStatus":"Working"}"""))
        book.apply("order", obj("""{"id":7}"""), deleted = true)
        assertNull(book.item("order", 7))
    }
    @Test fun connectLoadsServerCatalogQuoteAndHistoryWithoutAnyOrder() = runTest {
        val (broker, wire) = ready()
        assertEquals(listOf("100", "101"), broker.accounts.map { it.id })
        val dated = broker.instruments.single { !it.isContinuous }
        assertEquals("MESZ6", dated.symbol)
        assertEquals(250_000, dated.tickSize)
        assertEquals(1_250_000, dated.tickValueMicrosPerLot)
        assertEquals(100, dated.lotStepHundredths)
        assertEquals(5_500_000_000, broker.market.value.quotes.getValue("MESZ6").bid)
        assertEquals(1, broker.history("MESZ6", Timeframe.M5).size)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
        assertFalse(broker.capabilities.hedging)
    }
    @Test fun remotePreviewUsesIntegerContractsAndUnknownFeesMargin() = runTest {
        val (broker, _) = ready()
        val account = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100, stopLoss = 5_490_000_000, takeProfit = 5_510_000_000)
        val preview = account.preview(request)
        assertTrue(preview.valid, preview.errors.toString())
        assertFalse(preview.marginKnown)
        assertFalse(preview.feesKnown)
        assertEquals(-51_250_000, preview.stopLossPnlMicros)
        assertFalse(account.preview(request.copy(lotsHundredths = 1)).valid)
        assertFalse(account.preview(request.copy(stopLoss = 5_499_900_000)).valid)
    }
    @Test fun bracketOrderIsOneServerOsoRequestNotTwoIndependentOrders() = runTest {
        val (broker, wire) = ready()
        val account = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, 5_499_000_000, 5_490_000_000, 5_510_000_000)
        val first = account.placeOrder("test-1", request)
        assertTrue(first.accepted, first.message)
        val sent = wire.requests.single { it.path.startsWith("order/") }
        assertEquals("order/placeoso", sent.path)
        assertEquals(1, sent.body?.long("orderQty"))
        assertEquals("5499.000000", sent.body?.str("price"))
        assertEquals("Stop", (sent.body?.get("bracket1") as JsonObject).str("orderType"))
        assertEquals("Limit", (sent.body?.get("bracket2") as JsonObject).str("orderType"))
        assertEquals(first, account.placeOrder("test-1", request))
        assertEquals(1, wire.requests.count { it.path.startsWith("order/") })
        assertTrue(account.state.value.positions.isEmpty())
    }
    /** Pins the wire format of a short's brackets as it has always been sent: both legs buy, each at its own absolute price. */
    @Test fun aShortMarketBracketIsOneOsoWithABuyStopAndABuyLimitLeg() = runTest {
        val (broker, wire) = ready()
        val account = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.SELL, OrderKind.MARKET, 100,
            stopLoss = 5_510_000_000, takeProfit = 5_490_000_000)
        val result = account.placeOrder("short-market", request)
        assertTrue(result.accepted, result.message)
        val sent = wire.requests.single { it.path == "order/placeoso" }.body!!
        assertEquals("Sell", sent.str("action"))
        val stop = sent["bracket1"]!!.jsonObject
        val target = sent["bracket2"]!!.jsonObject
        assertEquals("Buy", stop.str("action"))
        assertEquals("Stop", stop.str("orderType"))
        assertEquals("5510.000000", stop.str("stopPrice"))
        assertEquals("Buy", target.str("action"))
        assertEquals("Limit", target.str("orderType"))
        assertEquals("5490.000000", target.str("price"))
    }
    @Test fun timeoutBlocksDuplicateWritesInsteadOfPretendingFailure() = runTest {
        val wire = MockTradovateWire().apply { failWrite = true }
        val (broker, _) = ready(wire)
        val account = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)
        val result = account.placeOrder("unknown", request)
        assertEquals(CommandOutcome.UNKNOWN, result.outcome)
        assertFalse(result.accepted)
        assertEquals(result, account.placeOrder("unknown", request))
        assertFalse(account.placeOrder("new-id", request).accepted)
        assertEquals(1, wire.requests.count { it.path.startsWith("order/") })
        assertFalse(broker.market.value.tradingReady)
    }
    @Test fun rejectedOrderDoesNotCreateLocalPosition() = runTest {
        val wire = MockTradovateWire().apply { rejectWrite = true }
        val (broker, _) = ready(wire)
        val account = broker.openSession("100")
        val result = account.placeOrder("reject", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertFalse(result.accepted)
        assertTrue(account.state.value.positions.isEmpty())
        assertTrue(broker.market.value.tradingReady)
    }
    @Test fun missingMarketPermissionNeverGeneratesSyntheticQuotes() = runTest {
        val wire = MockTradovateWire().apply { rejectMarket = true }
        val broker = TradovateBroker(session(), wire, backgroundScope, now = { TEST_TIME })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.market.value.quotes.isEmpty())
        assertFalse(broker.openSession("100").preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).valid)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }
    @Test fun netPositionAndRawFillsRemainServerAuthoritative() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":3,"netPrice":5500.083333333,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("order", obj("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled","timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("fill", obj("""{"id":800,"orderId":700,"contractId":200,"action":"Buy","qty":3,"price":5500.25,"active":true,"timestamp":"$TEST_INSTANT"}"""))
        runCurrent()
        val account = broker.openSession("100")
        assertEquals(300, account.state.value.positions.single().lotsHundredths)
        assertFalse(account.state.value.positions.single().protectionEditable)
        assertEquals(1, account.state.value.executions.size)
        assertTrue(account.state.value.trades.isEmpty())
        assertTrue(broker.openSession("101").state.value.positions.isEmpty())
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":-1,"netPrice":5500.25,"timestamp":"$TEST_INSTANT"}"""))
        runCurrent()
        assertEquals(Side.SELL, account.state.value.positions.single().side)
        assertEquals(100, account.state.value.positions.single().lotsHundredths)
    }
    @Test fun stopEntryModificationUsesStopPriceAndRejectsStaleVersions() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("order", obj("""{"id":710,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Working","timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1110,"orderId":710,"orderQty":1,"orderType":"Stop","stopPrice":5510}"""))
        runCurrent()
        val account = broker.openSession("100")
        assertFalse(account.modifyOrder("stale", "710", 1100, 5_511_000_000).accepted)
        assertFalse(broker.openSession("101").modifyOrder("foreign", "710", 1110, 5_511_000_000).accepted)
        assertTrue(account.modifyOrder("move", "710", 1110, 5_511_000_000).accepted)
        val body = wire.requests.last().body!!
        assertEquals("5511.000000", body.str("stopPrice"))
        assertNull(body["price"])
        assertFalse(broker.market.value.tradingReady)
        wire.account.entity("orderVersion", obj("""{"id":1111,"orderId":710,"orderQty":1,"orderType":"Stop","stopPrice":5511}"""))
        runCurrent()
        assertTrue(broker.market.value.tradingReady)
        assertEquals(5_511_000_000, account.state.value.orders.single().request.entryPrice)
    }
    @Test fun protectiveOrdersRequireAnUnambiguousServerBracket() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":1,"netPrice":5500,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("order", obj("""{"id":701,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Working","parentId":700,"ocoId":702,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("order", obj("""{"id":702,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Working","parentId":700,"ocoId":701,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1701,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5490}"""))
        wire.account.entity("orderVersion", obj("""{"id":1702,"orderId":702,"orderQty":1,"orderType":"Limit","price":5510}"""))
        runCurrent()
        val account = broker.openSession("100")
        val p = account.state.value.positions.single()
        assertTrue(p.protectionEditable)
        assertEquals(5_490_000_000, p.stopLoss)
        assertFalse(account.modifyProtection("both", p.id, p.version, 5_491_000_000, 5_512_000_000).accepted)
        assertTrue(account.modifyProtection("stop", p.id, p.version, 5_491_000_000, p.takeProfit).accepted)
        assertEquals(701, wire.requests.last().body!!.long("orderId"))
        wire.account.entity("orderVersion", obj("""{"id":1703,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5491}"""))
        runCurrent()
        assertTrue(broker.market.value.tradingReady)
        assertEquals(5_491_000_000, account.state.value.positions.single().stopLoss)
    }
    /** The quote is 5500 / 5500.25 against a 5490 entry, and the target has been reached without filling. */
    @Test fun aStopMovesToBreakEvenAndPastItButNotThroughTheMarket() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":1,"netPrice":5490,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("order", obj("""{"id":701,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Working","parentId":700,"ocoId":702,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("order", obj("""{"id":702,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Working","parentId":700,"ocoId":701,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1701,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5480}"""))
        wire.account.entity("orderVersion", obj("""{"id":1702,"orderId":702,"orderQty":1,"orderType":"Limit","price":5500}"""))
        runCurrent()
        val account = broker.openSession("100")
        val p = account.state.value.positions.single()
        assertFalse(account.modifyProtection("at-bid", p.id, p.version, 5_500_000_000, p.takeProfit).accepted, "A stop at the bid would trigger at once")
        assertTrue(wire.requests.none { it.path == "order/modifyorder" })
        val even = account.modifyProtection("even", p.id, p.version, 5_490_000_000, p.takeProfit)
        assertTrue(even.accepted, even.message)
        assertEquals("5490.000000", wire.requests.last().body!!.str("stopPrice"))
        wire.account.entity("orderVersion", obj("""{"id":1703,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5490}"""))
        runCurrent()
        val moved = account.state.value.positions.single()
        assertEquals(5_490_000_000, moved.stopLoss)
        val past = account.modifyProtection("past", moved.id, moved.version, 5_495_000_000, moved.takeProfit)
        assertTrue(past.accepted, past.message)
    }
    @Test fun aMarketOrderTargetMustClearItsFillPrice() = runTest {
        val (broker, _) = ready()
        val account = broker.openSession("100")
        fun target(side: Side, price: Long) = account.preview(OrderRequest("MESZ6", side, OrderKind.MARKET, 100, takeProfit = price))
        assertFalse(target(Side.BUY, 5_500_250_000).valid, "A buy fills at the ask")
        assertTrue(target(Side.BUY, 5_500_500_000).valid)
        assertFalse(target(Side.SELL, 5_500_000_000).valid, "A sell fills at the bid")
        assertTrue(target(Side.SELL, 5_499_750_000).valid)
    }
    @Test fun pendingBracketPricesMoveOneLineAtATimeBeforeTheEntryFills() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("order", obj("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Working","timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1700,"orderId":700,"orderQty":1,"orderType":"Limit","price":5490}"""))
        wire.account.entity("order", obj("""{"id":701,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Suspended","parentId":700,"ocoId":702,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1701,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5480}"""))
        wire.account.entity("order", obj("""{"id":702,"accountId":100,"contractId":200,"action":"Sell","ordStatus":"Suspended","parentId":700,"ocoId":701,"timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1702,"orderId":702,"orderQty":1,"orderType":"Limit","price":5510}"""))
        runCurrent()
        val account = broker.openSession("100")
        val entry = account.state.value.orders.single { it.id == "700" }
        assertEquals(OrderRole.ENTRY, entry.role)
        assertEquals("701" to "702", entry.stopLossOrderId to entry.takeProfitOrderId)
        // The suspended legs the entry names wait for it, and cancel each other once it has filled: one OCO group.
        val legs = account.state.value.orders.filter { it.id in setOf("701", "702") }
        assertEquals(listOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT), legs.sortedBy { it.id }.map { it.role })
        assertNotNull(legs.first().ocoGroup)
        assertEquals(1, legs.map { it.ocoGroup }.distinct().size)
        assertNull(entry.ocoGroup)
        assertEquals(5_480_000_000L to 5_510_000_000L, entry.request.stopLoss to entry.request.takeProfit)
        assertTrue(entry.protectionEditable)
        assertEquals("入场尚未成交，保护单等待激活", account.state.value.orders.single { it.id == "701" }.note)
        val preview = account.previewOrderProtection("700", 5_485_000_000, 5_510_000_000)
        assertTrue(preview.valid, preview.errors.toString())
        assertEquals(-25_000_000, preview.stopLossPnlMicros)
        assertFalse(account.previewOrderProtection("701", 5_485_000_000, null).valid, "A bracket is moved through its entry")
        assertFalse(account.modifyOrderProtection("both", "700", entry.version, 5_485_000_000, 5_512_000_000).accepted)
        assertFalse(account.modifyOrderProtection("remove", "700", entry.version, null, 5_510_000_000).accepted)
        assertFalse(account.modifyOrderProtection("wrong-side", "700", entry.version, 5_495_000_000, 5_510_000_000).accepted)
        assertFalse(account.modifyOrderProtection("stale", "700", entry.version - 1, 5_485_000_000, 5_510_000_000).accepted)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
        assertTrue(account.modifyOrderProtection("stop", "700", entry.version, 5_485_000_000, 5_510_000_000).accepted)
        val sent = wire.requests.single { it.path == "order/modifyorder" }.body!!
        assertEquals(701, sent.long("orderId"))
        assertEquals(1, sent.long("orderQty"))
        assertEquals("Stop", sent.str("orderType"))
        assertEquals("5485.000000", sent.str("stopPrice"))
        assertNull(sent["price"])
        assertFalse(broker.market.value.tradingReady)
        wire.account.entity("orderVersion", obj("""{"id":1703,"orderId":701,"orderQty":1,"orderType":"Stop","stopPrice":5485}"""))
        runCurrent()
        assertTrue(broker.market.value.tradingReady)
        assertEquals(5_485_000_000, account.state.value.orders.single { it.id == "700" }.request.stopLoss)
        wire.account.entity("fill", obj("""{"id":800,"orderId":700,"contractId":200,"action":"Buy","qty":1,"price":5490,"active":true,"timestamp":"$TEST_INSTANT"}"""))
        runCurrent()
        assertFalse(account.state.value.orders.single { it.id == "700" }.protectionEditable, "A partly filled entry no longer moves its brackets")
        assertFalse(account.modifyOrderProtection("after-fill", "700", entry.version, 5_486_000_000, 5_510_000_000).accepted)
        assertEquals(1, wire.requests.count { it.path.startsWith("order/") })
    }
    @Test fun incompletePositionsCannotBeMistakenForAFlatAccount() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":1,"timestamp":"$TEST_INSTANT"}"""))
        runCurrent()
        val preview = broker.openSession("100").preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100))
        assertFalse(preview.valid)
        assertTrue(preview.errors.any { it.contains("未加载") })
    }
    @Test fun staleQuotesBlockNewOrdersButNotCancellation() = runTest {
        var clock = TEST_TIME
        val wire = MockTradovateWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, now = { clock })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        wire.account.entity("order", obj("""{"id":710,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Working","timestamp":"$TEST_INSTANT"}"""))
        wire.account.entity("orderVersion", obj("""{"id":1110,"orderId":710,"orderQty":1,"orderType":"Limit","price":5490}"""))
        runCurrent()
        clock += 16_000
        val account = broker.openSession("100")
        assertFalse(account.preview(OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).valid)
        assertTrue(account.cancelOrder("cancel", "710").accepted)
        assertEquals("order/cancelorder", wire.requests.last().path)
    }
    @Test fun closeUsesServerLiquidationAndDoesNotLocallyFlatten() = runTest {
        val (broker, wire) = ready()
        wire.account.entity("position", obj("""{"id":900,"accountId":100,"contractId":200,"netPos":1,"netPrice":5500,"timestamp":"$TEST_INSTANT"}"""))
        runCurrent()
        val account = broker.openSession("100")
        val result = account.closePosition("close", account.state.value.positions.single().id)
        assertTrue(result.accepted)
        assertEquals("order/liquidateposition", wire.requests.last().path)
        assertEquals(200, wire.requests.last().body?.long("contractId"))
        assertEquals(1, account.state.value.positions.size)
    }
}
