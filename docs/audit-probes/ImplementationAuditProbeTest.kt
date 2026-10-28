package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Review probes assert observed gaps, not desired behavior. Never included in production sources. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImplementationAuditProbeTest {
    private val time = 1_798_589_000_000L
    private fun session() = TradovateSession(TradovateEnvironment.DEMO, "synthetic-audit-token-only-123456", null, 42, "audit", 4_102_444_800_000)

    @Test fun acceptedCommandStillBlocksAfterTwoMinutesWithoutEntityEvent() = runTest {
        val wire = MockTradovateWire()
        var nowMs = time
        val broker = TradovateBroker(session(), wire, backgroundScope, { nowMs })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.openSession("100").placeOrder("audit-timeout", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
        nowMs += 120_001
        advanceTimeBy(120_001); runCurrent()
        assertTrue(broker.market.value.connected)
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("等待服务端"))
        assertEquals(1, wire.requests.count { it.path == "order/placeorder" })
    }

    @Test fun fullReconnectSnapshotDoesNotReconcilePreviouslyAcceptedOrder() = runTest {
        val initial = MockTradovateWire()
        val recovered = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + mapOf(
                "orders" to Json.parseToJsonElement("""[{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Working"}]"""),
                "orderVersions" to Json.parseToJsonElement("""[{"id":900,"orderId":700,"orderQty":1,"orderType":"Limit","price":5499}]""")))
        }
        var active = initial
        val wire = object : TradovateWire {
            override suspend fun request(environment: TradovateEnvironment, token: String, method: String, path: String, body: JsonObject?) = active.request(environment, token, method, path, body)
            override suspend fun socket(url: String) = active.socket(url)
            override fun close() { initial.close(); recovered.close() }
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { time })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.openSession("100").placeOrder("audit-reconnect", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, 5_499_000_000)).accepted)
        active = recovered
        broker.connect(); runCurrent()
        assertTrue(broker.openSession("100").state.value.orders.any { it.id == "700" })
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("等待服务端"))
    }

    @Test fun deletedPositionDoesNotCompleteAcceptedLiquidation() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("positions" to Json.parseToJsonElement("""[{"id":1,"accountId":100,"contractId":200,"netPos":1,"netPrice":5500}]""")))
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { time })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val account = broker.openSession("100")
        assertTrue(account.closePosition("audit-close", "100:200").accepted)
        wire.account.queue.send("""a[{"e":"props","d":{"entityType":"position","eventType":"Deleted","entity":{"id":1}}}]""")
        runCurrent()
        assertTrue(account.state.value.positions.isEmpty())
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("等待服务端"))
    }
}
