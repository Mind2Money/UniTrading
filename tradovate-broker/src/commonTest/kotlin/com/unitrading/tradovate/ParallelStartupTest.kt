package com.unitrading.tradovate

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelStartupTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun session() = TradovateSession(TradovateEnvironment.DEMO,
        "synthetic-parallel-startup-token", null, 42, "test", 4_102_444_800_000)

    @Test fun metadataBalancesAndMarketStartBeforeAnySlowResponseFinishes() = runTest {
        val delegate = MockTradovateWire()
        val maturity = delegate.snapshot.array("contractMaturities").single()
        delegate.snapshot = JsonObject(delegate.snapshot - "contractMaturities")
        val release = CompletableDeferred<Unit>()
        val started = mutableSetOf<String>()
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement {
                if (path.startsWith("contractMaturity/") || path.startsWith("cashBalance/")) {
                    started += if (path.startsWith("cashBalance/")) "balance-${body?.long("accountId")}" else "metadata"
                    release.await()
                }
                return if (path.startsWith("contractMaturity/")) maturity else delegate.request(environment, token, method, path, body)
            }
        }
        val broker = TradovateBroker(session(), wire, backgroundScope)
        val connection = async { broker.connect() }
        advanceTimeBy(5_000); runCurrent()
        assertEquals(setOf("metadata", "balance-100", "balance-101"), started)
        assertTrue("authorize" in delegate.market.sent)
        assertFalse(connection.isCompleted)
        assertFalse(broker.market.value.tradingReady, "Market authorization alone must not enable trading during startup")
        release.complete(Unit)
        connection.await()
        assertEquals(1, broker.instruments.count { !it.isContinuous })
        assertTrue(broker.market.value.tradingReady)
        assertTrue(delegate.requests.none { it.path.startsWith("order/") })
    }

    @Test fun contractsLoadConcurrentlyAndShareProductFetchesDespiteOneFailure() = runTest {
        val delegate = MockTradovateWire()
        val product = delegate.snapshot.array("products").single()
        delegate.snapshot = JsonObject(delegate.snapshot - "contractMaturities" - "products" +
            ("contracts" to JsonArray((0..5).map { index ->
                obj("""{"id":${200 + index},"name":"MES$index","contractMaturityId":${300 + index}}""")
            })))
        var active = 0
        var peak = 0
        var products = 0
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement {
                if (!path.startsWith("contractMaturity/") && !path.startsWith("product/"))
                    return delegate.request(environment, token, method, path, body)
                active++
                peak = maxOf(peak, active)
                try {
                    delay(3_000)
                    if (path.startsWith("product/")) { products++; return product }
                    val id = path.substringAfter("id=").toLong()
                    if (id == 305L) throw TradovateRemoteException(503, "synthetic failure")
                    return obj("""{"id":$id,"productId":400,"expirationMonth":202612}""")
                } finally { active-- }
            }
        }
        val broker = TradovateBroker(session(), wire, backgroundScope)
        broker.connect()
        assertTrue(peak in 2..4, "Independent contract requests must overlap with bounded concurrency: $peak")
        assertEquals(1, products, "Contracts sharing a product must reuse the same cached response")
        assertEquals(5, broker.instruments.count { !it.isContinuous })
        assertEquals(0, active)
        assertEquals(2, broker.accounts.size)
    }

    @Test fun cancellingStartupCancelsAllPendingReads() = runTest {
        val delegate = MockTradovateWire()
        var active = 0
        val wire = object : TradovateWire by delegate {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?): JsonElement {
                if (path.startsWith("cashBalance/")) {
                    active++
                    try { awaitCancellation() } finally { active-- }
                }
                return delegate.request(environment, token, method, path, body)
            }
        }
        val broker = TradovateBroker(session(), wire, backgroundScope)
        val connection = launch { broker.connect() }
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, active)
        connection.cancelAndJoin()
        assertEquals(0, active)
        assertFalse(broker.market.value.tradingReady)
        broker.disconnect()
        runCurrent()
    }

    @Test fun marketFailureDoesNotDiscardLoadedAccountsAndBalances() = runTest {
        val delegate = MockTradovateWire()
        delegate.balanceSnapshots[100] = obj("""{"totalPnL":12}""")
        val wire = object : TradovateWire by delegate {
            override suspend fun socket(url: String): TradovateWireSocket {
                if (url == TradovateEnvironment.DEMO.marketUrl) throw TradovateRemoteException(403, "synthetic denial")
                return delegate.socket(url)
            }
        }
        val broker = TradovateBroker(session(), wire, backgroundScope)
        broker.connect()
        assertEquals(2, broker.accounts.size)
        assertEquals(1, broker.instruments.count { !it.isContinuous })
        assertEquals(12_000_000, broker.openSession("100").state.value.dailyPnl?.totalMicros)
        assertTrue(broker.market.value.connected)
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("行情授权/连接失败"))
    }
}
