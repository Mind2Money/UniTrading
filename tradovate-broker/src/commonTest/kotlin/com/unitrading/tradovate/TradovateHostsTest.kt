package com.unitrading.tradovate

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

/** The login reply's `apiHosts`, modelled on an organisation whose Demo moved to NinjaTrader-hosted names. */
class TradovateHostsTest {
    private val time = 1_798_589_000_000L
    private val trader = "https://trader.tradovate.com"
    private val moved = TradovateHosts("demo-api.org.ninjatrader.com", "md-demo-api.org.ninjatrader.com")
    private val routing = """{"live":"live.tradovateapi.com","demo":"demo-api.org.ninjatrader.com","mdLive":"md.tradovateapi.com","mdDemo":"md-demo-api.org.ninjatrader.com","replay":"replay.tradovateapi.com"}"""
    private val demo = TradovateEnvironment.DEMO
    private val defaults = demo.defaultHosts
    private fun parsed(value: String) = Json.parseToJsonElement(value)
    private fun select(value: String, environment: TradovateEnvironment = demo) = selectTradovateHosts(environment, parsed(value))
    private fun token(user: Long = 42) = "eyJhbGciOiJub25lIn0." + Base64.UrlSafe.encode("{\"sub\":$user,\"exp\":4102444800}".encodeToByteArray()).trimEnd('=') + ".test-signature-only"
    private fun login(user: Long = 42, extra: String = "") = """{"kind":"auth","environment":"demo","accessToken":"${token(user)}"$extra}"""
    private fun session(hosts: TradovateHosts = defaults, expires: Long = 4_102_444_800_000) = TradovateSession(demo, token(), null, 42, "test", expires, hosts = hosts)
    private fun renewalNaming(apiHosts: String) = parsed("""{"accessToken":"renewed-test-token-1234567890","expirationTime":"2100-01-01T00:00:00Z","apiHosts":$apiHosts}""").jsonObject

    @Test fun aReplyWithoutRoutingKeepsTheDefaultHosts() {
        assertEquals(defaults, selectTradovateHosts(demo, null))
        for (empty in listOf("null", "\"\"", "[]", "{}", """{"demo":null,"mdDemo":""}""")) assertEquals(defaults, select(empty), empty)
    }

    @Test fun eachEnvironmentReadsItsOwnEntries() {
        assertEquals(moved, select(routing))
        assertEquals(TradovateEnvironment.LIVE.defaultHosts, select(routing, TradovateEnvironment.LIVE))
        assertEquals(TradovateHosts("demo-api.org.ninjatrader.com", "md-demo.tradovateapi.com"), select("""{"demo":"demo-api.org.ninjatrader.com"}"""))
    }

    @Test fun onlyBareTradovateAndNinjaTraderHostNamesAreAccepted() {
        for (host in listOf("Demo-api.org.ninjatrader.com", "demo-api.org.ninjatrader.com:443", "https://demo-api.org.ninjatrader.com", "demo-api.org.ninjatrader.com/v1",
            "user@demo-api.org.ninjatrader.com", "demo-api.org.ninjatrader.com.evil.example", "evilninjatrader.com", "ninjatrader.com", "demo.tradovateapi.org", "<invalid>")) {
            val refused = assertFailsWith<TradovateRemoteException>(host) { select("""{"demo":"$host"}""") }
            assertEquals(0, refused.status)
            assertTrue(refused.message!!.contains("apiHosts.demo"), host)
            assertFalse(refused.message!!.contains(host), "The reply's text stays out of the message")
        }
        assertFailsWith<TradovateRemoteException> { select("""{"mdDemo":"md.evil.example"}""") }
        assertFailsWith<TradovateRemoteException> { select("""{"demo":5}""") }
        for (shape in listOf("\"elsewhere\"", "[\"demo\"]", "5", "true")) assertFailsWith<TradovateRemoteException>(shape) { select(shape) }
    }

    @Test fun aTradingHostOfTheOtherEnvironmentIsRefused() {
        for (reply in listOf("""{"demo":"live.tradovateapi.com"}""", """{"demo":"live-api.org.ninjatrader.com"}""", """{"demo":"x.tradovateapi.com","live":"x.tradovateapi.com"}""")) {
            assertTrue(assertFailsWith<TradovateRemoteException>(reply) { select(reply) }.message!!.contains("另一环境"), reply)
        }
        assertFailsWith<TradovateRemoteException> { select("""{"live":"demo-api.org.ninjatrader.com"}""", TradovateEnvironment.LIVE) }
        assertEquals("demo-api.live.ninjatrader.com", select("""{"demo":"demo-api.live.ninjatrader.com"}""").api, "An organisation's own name may contain either word")
        assertEquals("md.tradovateapi.com", select("""{"mdDemo":"md.tradovateapi.com"}""").market, "Only the trading host carries accounts")
    }

    @Test fun aLoginCarriesItsHostsPastTokensThatNameNone() {
        val accumulator = LoginAccumulator(demo)
        assertTrue(accumulator.accept(login(extra = ""","userId":42,"apiHosts":$routing"""), trader, true))
        assertTrue(accumulator.accept(login(extra = ""","userId":42"""), trader, true), "A token seen on a socket")
        assertEquals(moved, accumulator.result().hosts)
        assertEquals(42, accumulator.result().userId)
    }

    @Test fun anotherUsersTokenDropsTheFirstUsersHosts() {
        val accumulator = LoginAccumulator(demo)
        accumulator.accept(login(extra = ""","apiHosts":$routing"""), trader, true)
        accumulator.accept(login(user = 43), trader, true)
        assertEquals(43, accumulator.result().userId)
        assertEquals(defaults, accumulator.result().hosts)
    }

    @Test fun routingIsIgnoredWithoutAnAccessToken() {
        val accumulator = LoginAccumulator(demo)
        accumulator.accept("""{"kind":"auth","environment":"demo","mdAccessToken":"${token()}","apiHosts":{"demo":"demo-api.evil.ninjatrader.com"}}""", trader, true)
        accumulator.accept(login(), trader, true)
        assertEquals(defaults, accumulator.result().hosts)
    }

    @Test fun aRefusedHostFailsTheLoginWithItsReasonInsteadOfUsingTheDefaults() {
        val accumulator = LoginAccumulator(demo)
        assertTrue(accumulator.accept(login(extra = ""","apiHosts":{"demo":"demo-api.evil.example"}"""), trader, true), "The window must be able to close and report it")
        assertTrue(assertFailsWith<TradovateRemoteException> { accumulator.result() }.message!!.contains("apiHosts.demo"))
        accumulator.accept(login(extra = ""","apiHosts":$routing"""), trader, true)
        assertEquals(moved, accumulator.result().hosts, "A later valid reply supersedes it")
    }

    @Test fun savedRecordsKeepTheirFormatAndRejectForeignHosts() {
        val legacy = """{"environment":"DEMO","accessToken":"${token()}","mdAccessToken":null,"userId":42,"userName":"test","expiresAtMs":4102444800000}"""
        val restored = Json.decodeFromString<TradovateSession>(legacy)
        assertEquals(defaults, restored.hosts)
        assertFalse(Json.encodeToString(TradovateSession.serializer(), restored).contains("hosts"), "Default hosts stay out of the record")
        val routed = Json.encodeToString(TradovateSession.serializer(), session(moved))
        assertEquals(moved, Json.decodeFromString<TradovateSession>(routed).hosts)
        for (host in listOf("evil.example", "demo-api.org.ninjatrader.com:8443", "live.tradovateapi.com")) {
            assertFailsWith<IllegalArgumentException>(host) { Json.decodeFromString<TradovateSession>(routed.replace(moved.api, host)) }
        }
    }

    @Test fun onlyAllowedHostsCanReceiveATokenOnTheWire() {
        assertEquals("https://demo-api.org.ninjatrader.com/v1/account/list", tradovateRequestUrl(moved, "account/list"))
        assertEquals("https://live.tradovateapi.com/v1/auth/renewaccesstoken", tradovateRequestUrl(moved, "auth/renewaccesstoken"), "Authentication stays on its fixed host")
        assertEquals("https://live.tradovateapi.com/v1/auth/me", tradovateRequestUrl(moved, "auth/me"))
        assertFailsWith<IllegalArgumentException> { tradovateRequestUrl(TradovateHosts("evil.example", moved.market), "account/list") }
        for (url in listOf(moved.socketUrl, moved.marketUrl, demo.socketUrl, demo.marketUrl, TradovateEnvironment.LIVE.socketUrl, TradovateEnvironment.LIVE.marketUrl)) assertTrue(allowedTradovateSocket(url), url)
        for (url in listOf("wss://md.evil.example/v1/websocket", "wss://md.tradovateapi.com.evil.example/v1/websocket", "ws://md-demo-api.org.ninjatrader.com/v1/websocket",
            "wss://md-demo-api.org.ninjatrader.com/v2/websocket", "wss://user@md-demo-api.org.ninjatrader.com/v1/websocket", "wss://md-demo-api.org.ninjatrader.com:8443/v1/websocket",
            "wss://md-demo-api.org.ninjatrader.com/v1/websocket?x=1")) assertFalse(allowedTradovateSocket(url), url)
    }

    @Test fun aRoutedLoginUsesItsHostsForRestAndBothSockets() = runTest {
        val wire = MockTradovateWire().apply { redirecting = defaults }
        val broker = TradovateBroker(session(moved), wire, backgroundScope, { time })
        broker.connect()
        assertEquals(listOf(moved.socketUrl, moved.marketUrl), wire.socketUrls.toList())
        assertEquals(setOf(moved), wire.requestHosts.toSet())
        assertEquals(2, broker.accounts.size)
        assertEquals(session(moved).accessToken, wire.market.bodies["authorize"])
    }

    @Test fun ordinaryLoginsStayOnTheDefaultHosts() = runTest {
        val wire = MockTradovateWire()
        TradovateBroker(session(), wire, backgroundScope, { time }).connect()
        assertEquals(listOf(demo.socketUrl, demo.marketUrl), wire.socketUrls.toList())
        assertEquals(setOf(defaults), wire.requestHosts.toSet())
    }

    @Test fun aLoginSavedBeforeItsOrganisationMovedFollowsTheRenewalReplyAndKeepsTheHosts() = runTest {
        val wire = MockTradovateWire().apply { redirecting = defaults; renewal = renewalNaming(routing) }
        var saved: TradovateSession? = null
        val broker = TradovateBroker(session(), wire, backgroundScope, { time }, { saved = it })
        broker.connect()
        assertEquals(listOf("account/list", "auth/renewaccesstoken", "account/list"), wire.requests.take(3).map { it.path })
        assertEquals(moved, saved?.hosts)
        assertEquals("renewed-test-token-1234567890", saved?.accessToken)
        assertEquals(listOf(moved.socketUrl, moved.marketUrl), wire.socketUrls.toList())
        assertEquals(2, broker.accounts.size)
        assertTrue(wire.requests.none { it.path.startsWith("order/") })
    }

    @Test fun aRedirectWithoutNewHostsAsksForALoginAndConnectsNothing() = runTest {
        val wire = MockTradovateWire().apply { redirecting = defaults }
        val error = assertFailsWith<TradovateRemoteException> { TradovateBroker(session(), wire, backgroundScope, { time }).connect() }
        assertEquals(307, error.status)
        assertTrue(wire.socketUrls.isEmpty())
        assertEquals(1, wire.requests.count { it.path == "account/list" }, "A redirect is never retried on the same hosts")
    }

    @Test fun aRedirectIsNotFollowedToHostsARenewalDoesNotConfirm() = runTest {
        val wire = MockTradovateWire().apply { redirecting = defaults; renewal = renewalNaming("""{"demo":"demo-api.evil.example"}""") }
        assertEquals(307, assertFailsWith<TradovateRemoteException> { TradovateBroker(session(), wire, backgroundScope, { time }).connect() }.status)
        assertTrue(wire.socketUrls.isEmpty())
        assertEquals(setOf(defaults), wire.requestHosts.toSet())
    }

    @Test fun nearExpiryRestorationAdoptsTheHostsItsRenewalNames() = runTest {
        val wire = MockTradovateWire().apply { redirecting = defaults; renewal = renewalNaming(routing) }
        var saved: TradovateSession? = null
        TradovateBroker(session(expires = time + 60_000), wire, backgroundScope, { time }, { saved = it }).connect()
        assertEquals(listOf("auth/renewaccesstoken", "account/list"), wire.requests.take(2).map { it.path })
        assertEquals(moved, saved?.hosts)
        assertEquals(listOf(moved.socketUrl, moved.marketUrl), wire.socketUrls.toList())
    }

    @Test fun anUnusableRoutingInARenewalKeepsTheHostsAndTheNewToken() = runTest {
        val wire = MockTradovateWire().apply { renewal = renewalNaming("""{"demo":"demo-api.evil.example"}""") }
        var saved: TradovateSession? = null
        val broker = TradovateBroker(session(expires = time + 60_000), wire, backgroundScope, { time }, { saved = it })
        broker.connect()
        assertEquals(defaults, saved?.hosts)
        assertEquals("renewed-test-token-1234567890", saved?.accessToken)
        assertEquals(2, broker.accounts.size)
    }

    @Test fun aRunningSessionKeepsItsHostsWhenARenewalNamesOthers() = runTest {
        val wire = MockTradovateWire().apply { renewal = renewalNaming(routing) }
        var saved: TradovateSession? = null
        var clock = time
        val broker = TradovateBroker(session(expires = time + 180_000), wire, backgroundScope, { clock }, { saved = it })
        broker.connect()
        clock += 70_000
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("renewed-test-token-1234567890", saved?.accessToken)
        assertEquals(defaults, saved?.hosts)
        assertEquals(listOf(demo.socketUrl, demo.marketUrl), wire.socketUrls.toList())
        assertTrue(broker.market.value.connected)
    }
}
