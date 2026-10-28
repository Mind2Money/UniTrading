package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** All routes use synthetic transport; never resubmit a server write during recovery. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReconciliationRecoveryTest {
    private val time = 1_798_589_000_000L
    private fun session() = TradovateSession(TradovateEnvironment.DEMO, "synthetic-audit-token-only-123456", null, 42, "audit", 4_102_444_800_000)

    @Test fun missingPushIsRecoveredAutomaticallyFromFullSnapshot() = runTest {
        val wire = MockTradovateWire()
        var clock = time
        val broker = TradovateBroker(session(), wire, backgroundScope, { clock })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.openSession("100").placeOrder("auto-sync", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
        wire.snapshot = JsonObject(wire.snapshot + ("orders" to Json.parseToJsonElement("""[{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled"}]""")))
        clock += 31_000
        advanceTimeBy(5_000); runCurrent()
        assertTrue(broker.market.value.tradingReady)
        assertEquals(1, wire.requests.count { it.path == "order/placeorder" })
        assertEquals(2, wire.account.sent.count { it == "user/syncrequest" })
    }

    @Test fun lateEvidenceClearsUnknownAndNeverRepeatsTheWrite() = runTest {
        val wire = MockTradovateWire().apply { failWrite = true }
        val broker = TradovateBroker(session(), wire, backgroundScope, { time })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val account = broker.openSession("100")
        val request = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)
        assertEquals(CommandOutcome.UNKNOWN, account.placeOrder("late", request).outcome)
        wire.account.entity("order", Json.parseToJsonElement("""{"id":700,"accountId":100,"contractId":200,"action":"Buy","ordStatus":"Filled","clOrdId":"UT-late"}""").jsonObject)
        runCurrent()
        assertTrue(broker.market.value.tradingReady)
        assertTrue(account.placeOrder("late", request).accepted)
        assertEquals(1, wire.requests.count { it.path == "order/placeorder" })
    }

    @Test fun fullSnapshotConfirmsDeletedPositionButMissingPlacedOrderStaysUnknown() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("positions" to Json.parseToJsonElement("""[{"id":1,"accountId":100,"contractId":200,"netPos":1,"netPrice":5500}]""")))
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { time })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val account = broker.openSession("100")
        assertTrue(account.closePosition("close-sync", "100:200").accepted)
        wire.snapshot = JsonObject(wire.snapshot + ("positions" to JsonArray(emptyList())))
        broker.resynchronize()
        assertTrue(account.state.value.positions.isEmpty())
        assertTrue(broker.market.value.tradingReady)
        wire.failWrite = true
        assertEquals(CommandOutcome.UNKNOWN, account.placeOrder("not-found", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).outcome)
        broker.resynchronize()
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("核对"))
        assertEquals(2, wire.requests.count { it.path.startsWith("order/") })
    }

    @Test fun acceptedCommandTimesOutIntoExplicitRecoveryWithoutResending() = runTest {
        val wire = MockTradovateWire()
        var nowMs = time
        val broker = TradovateBroker(session(), wire, backgroundScope, { nowMs })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        assertTrue(broker.openSession("100").placeOrder("audit-timeout", OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)).accepted)
        nowMs += 120_001
        advanceTimeBy(120_001); runCurrent()
        assertTrue(broker.market.value.connected)
        assertFalse(broker.market.value.tradingReady)
        assertTrue(broker.market.value.statusMessage.contains("核对"))
        assertEquals(1, wire.requests.count { it.path == "order/placeorder" })
    }

    @Test fun fullReconnectSnapshotReconcilesPreviouslyAcceptedOrder() = runTest {
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
        assertTrue(broker.market.value.tradingReady)
        assertFalse(broker.market.value.statusMessage.contains("等待服务端"))
    }

    @Test fun deletedPositionCompletesAcceptedLiquidation() = runTest {
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
        assertTrue(broker.market.value.tradingReady)
        assertFalse(broker.market.value.statusMessage.contains("等待服务端"))
    }
}
