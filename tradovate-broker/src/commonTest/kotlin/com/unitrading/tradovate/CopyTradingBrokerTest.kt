package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CopyTradingBrokerTest {
    private val now = 1_798_589_000_000L
    private fun session() = TradovateSession(TradovateEnvironment.DEMO, "synthetic-audit-token-only-123456", null, 42, "audit", 4_102_444_800_000)
    private fun json(value: String) = Json.parseToJsonElement(value)

    @Test fun sameBookRevisionAndCompleteEmptyAccounts() = runTest {
        val wire = MockTradovateWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { now })
        broker.connect(); runCurrent()
        val one = broker.openSession("100").state.value
        val two = broker.openSession("101").state.value
        assertNotNull(one.copyRevision)
        assertEquals(one.copyRevision, two.copyRevision)
        assertTrue(one.copyPositionsComplete)
        assertTrue(one.copyOrdersClear)
        assertTrue(one.copyOrdersComplete)
    }

    @Test fun omittedPositionOrUnmappedWorkingOrderFailClosed() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + mapOf(
                "positions" to json("""[{"id":1,"accountId":100,"contractId":999,"netPos":1,"netPrice":5500}]"""),
                "orders" to json("""[{"id":2,"accountId":100,"contractId":200,"ordStatus":"Working"}]""")))
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { now })
        broker.connect(); runCurrent()
        val state = broker.openSession("100").state.value
        assertTrue(state.positions.isEmpty())
        assertTrue(state.orders.isEmpty())
        assertFalse(state.copyPositionsComplete)
        assertFalse(state.copyOrdersClear)
        assertFalse(state.copyOrdersComplete)
    }

    @Test fun malformedSnapshotIsNotTreatedAsEmpty() = runTest {
        val wire = MockTradovateWire().apply {
            snapshot = JsonObject(snapshot + ("positions" to json("""[{"accountId":100,"contractId":200,"netPos":1}]""")))
        }
        val broker = TradovateBroker(session(), wire, backgroundScope, { now })
        broker.connect(); runCurrent()
        assertFalse(broker.openSession("100").state.value.copyPositionsComplete)
    }

    @Test fun automatedFlagReachesBrokerWithoutChangingManualDefault() = runTest {
        val wire = MockTradovateWire()
        val broker = TradovateBroker(session(), wire, backgroundScope, { now })
        broker.connect(); broker.watch("MESZ6", Timeframe.M5); runCurrent()
        val order = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100, automated = true)
        assertTrue(broker.openSession("100").placeOrder("copy-1", order).accepted)
        assertEquals(true, wire.requests.single { it.path == "order/placeorder" }.body?.bool("isAutomated"))
        assertFalse(order.copy(automated = false).automated)
    }
}
