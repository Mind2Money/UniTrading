package com.unitrading.app

import com.unitrading.broker.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HyperpropChartBrokerTest {
    private class Feed : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-1", "Tradovate Demo", "test", true, BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities()
        override val instruments = listOf(Instrument("MESZ6", "MES", "futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000))
        override val accounts = listOf(AccountInfo("feed-account", "feed"))
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(548500, 548525, 1))))
        var disconnected = false
        override fun history(symbol: String, timeframe: Timeframe) = listOf(Candle(1, 1, 1, 1, 1))
        override fun disconnect() { disconnected = true }
        override fun openSession(accountId: String): AccountSession = error("Feed accounts must never handle Hyperprop orders")
    }
    @Test fun snapshotUsesHyperpropAccountsAndFeedChartButNeverTradesOnFeed() = runTest {
        val engine = MockEngine { request ->
            val payload = when (request.url.encodedPath) {
                "/trade/api-keys/session" -> """{"success":true,"data":{"access_token":"fake","session_type":"personal_api_key","scope":"trade","account_ids":["a"]}}"""
                "/trade/accounts" -> """{"success":true,"data":[{"id":"a","account_number":"PRACTICE","current_balance":50000}]}"""
                "/trade/positions" -> """{"success":true,"data":[{"account_id":"a","contract":"MESZ6","quantity":1,"avg_entry_price":5485.25}]}"""
                "/trade/orders/pending" -> """{"success":true,"data":[]}"""
                else -> error("Unexpected trading endpoint")
            }
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val personal = HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        val feed = Feed()
        val broker = HyperpropChartBroker(feed, personal)
        try {
            broker.resynchronize()
            assertEquals("a", broker.accounts.single().id)
            assertTrue(broker.accounts.single().readOnly)
            assertEquals("MESZ6", broker.openSession("a").state.value.positions.single().symbol)
            assertEquals(feed.history("MESZ6", Timeframe.M1), broker.history("MESZ6", Timeframe.M1))
            assertFalse(broker.openSession("a").placeOrder("1", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
        } finally { broker.disconnect() }
        assertFalse(feed.disconnected)
    }
}

class HyperpropChartTradingTest {
    private val contract = "MESZ6"
    private class Feed : BrokerPlugin {
        override val descriptor = BrokerDescriptor("tradovate-demo-1", "Tradovate Demo", "test", true, BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities()
        override val instruments = listOf(Instrument("MESZ6", "MES", "futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000))
        override val accounts = listOf(AccountInfo("feed-account", "feed"))
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot(quotes = mapOf("MESZ6" to Quote(548500, 548525, kotlin.time.Clock.System.now().toEpochMilliseconds()))))
        override fun history(symbol: String, timeframe: Timeframe) = emptyList<Candle>()
        override fun openSession(accountId: String): AccountSession = error("Never send Hyperprop orders through the feed")
    }

    @Test fun practiceOrderAndCancelUseHyperpropAndAreIdempotent() = runTest {
        var working = false
        var submits = 0
        var cancels = 0
        val engine = MockEngine { request ->
            val payload = when (request.url.encodedPath) {
                "/trade/api-keys/session" -> """{"success":true,"data":{"access_token":"fake","session_type":"personal_api_key","scope":"trade","account_ids":["a"]}}"""
                "/trade/accounts" -> """{"success":true,"data":[{"id":"a","account_number":"PRACTICE-1","account_type":"practice","current_balance":50000}]}"""
                "/trade/positions" -> """{"success":true,"data":[]}"""
                "/trade/orders/pending" -> if (!working) """{"success":true,"data":[]}""" else """{"success":true,"data":[{"id":"o1","account_id":"a","contract":"MESZ6","quantity":1,"filled_quantity":0,"side":"buy","order_type":"limit","limit_price":5485.25,"status":"working"}]}"""
                "/trade/order" -> { assertEquals("req-place", request.headers["x-idempotency-key"]); submits++; working = true; """{"accepted":true,"command_id":"01234567-89ab-cdef-0123-456789abcdef","status":"queued"}""" }
                "/trade/order/cancel" -> { assertEquals("req-cancel", request.headers["x-idempotency-key"]); cancels++; working = false; """{"success":true,"data":{"accepted":true,"command_id":"01234567-89ab-cdef-0123-456789abcdef","status":"queued"}}""" }
                "/trade/commands/01234567-89ab-cdef-0123-456789abcdef" -> """{"success":true,"data":{"status":"succeeded"}}"""
                else -> error("Unexpected endpoint")
            }
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val personal = HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        val broker = HyperpropChartBroker(Feed(), personal)
        try {
            broker.resynchronize()
            val session = broker.openSession("a")
            assertFalse(session.account.readOnly)
            val order = OrderRequest(contract, Side.BUY, OrderKind.LIMIT, 100, 548525, stopLoss = 548000)
            assertTrue(session.preview(order).valid)
            assertTrue(session.placeOrder("req-place", order).accepted)
            assertTrue(session.placeOrder("req-place", order).accepted)
            assertEquals(1, submits)
            assertEquals("o1", session.state.value.orders.single().id)
            assertTrue(session.cancelOrder("req-cancel", "o1").accepted)
            assertEquals(1, cancels)
            assertTrue(session.state.value.orders.isEmpty())
        } finally { broker.disconnect() }
    }
    @Test fun onlyExplicitLinkedVersionedPositionStopCanBeModified() = runTest {
        var stopPrice = 5480.0
        var modifications = 0
        val engine = MockEngine { request ->
            val payload = when (request.url.encodedPath) {
                "/trade/api-keys/session" -> """{"success":true,"data":{"access_token":"fake","session_type":"personal_api_key","scope":"trade","account_ids":["a"]}}"""
                "/trade/accounts" -> """{"success":true,"data":[{"id":"a","account_number":"PRACTICE-1","account_type":"practice","current_balance":50000}]}"""
                "/trade/positions" -> """{"success":true,"data":[{"position_id":"p1","state_version":7,"account_id":"a","contract":"MESZ6","quantity":1,"avg_entry_price":5485.25}]}"""
                "/trade/orders/pending" -> """{"success":true,"data":[{"id":"sl1","position_id":"p1","bracket_type":"stop_loss","updated_at":"2026-11-26T10:00:00Z","account_id":"a","contract":"MESZ6","quantity":1,"side":"sell","order_type":"stop","stop_price":$stopPrice,"status":"working"}]}"""
                "/trade/order/modify" -> { assertEquals("req-edit", request.headers["x-idempotency-key"]); modifications++; stopPrice = 5480.25; """{"success":true,"data":{"accepted":true,"command_id":"01234567-89ab-cdef-0123-456789abcdef","status":"queued"}}""" }
                "/trade/commands/01234567-89ab-cdef-0123-456789abcdef" -> """{"success":true,"data":{"status":"succeeded"}}"""
                else -> error("Unexpected endpoint")
            }
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val personal = HyperpropPersonalSession.connect("hpp_test_example") { HttpClient(engine) }
        val broker = HyperpropChartBroker(Feed(), personal)
        try {
            broker.resynchronize()
            val session = broker.openSession("a")
            val position = session.state.value.positions.single()
            assertTrue(position.protectionEditable)
            assertTrue(session.modifyProtection("req-edit", position.id, position.version, 548025, null).accepted)
            assertEquals(1, modifications)
            assertEquals(548025L, session.state.value.positions.single().stopLoss)
            assertFalse(session.modifyProtection("req-remove", position.id, position.version, null, null).accepted)
        } finally { broker.disconnect() }
    }
}
