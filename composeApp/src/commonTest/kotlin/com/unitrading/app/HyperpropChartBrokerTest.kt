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
