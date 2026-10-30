package com.unitrading.demo

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DemoProtectionSoundsTest {
    private val config = DemoConfig(
        id = "sound-demo", name = "Sound test", description = "Deterministic audio events",
        instruments = listOf(Instrument("TEST", "Test", "Test", 2, tickSize = 5, tickValueMicrosPerLot = 5_000_000,
            leverage = 10, minStopTicks = 4)),
        accounts = listOf(AccountInfo("A", "A"), AccountInfo("B", "B")),
        initialBids = mapOf("TEST" to 10_000L), spreadTicks = mapOf("TEST" to 2L),
        volatilityTicks = mapOf("TEST" to 1), randomSeed = 1234567, startTimeMs = 0,
    )
    private suspend fun quote(broker: DemoBroker, bid: Long = 10_000) =
        broker.setQuote("TEST", bid, bid + 10, broker.market.value.simulatedTimeMs + 15_000)
    private fun request(side: Side) = OrderRequest("TEST", side, OrderKind.MARKET, 100,
        stopLoss = if (side == Side.BUY) 9_950 else 10_060, takeProfit = if (side == Side.BUY) 10_060 else 9_950)

    @Test fun bothSidesProduceExactlyOneTypedSoundAfterCommittedProtectionClose() = runTest {
        for (side in Side.entries) for (stop in listOf(true, false)) {
            val broker = DemoBroker(config, seedTrades = false)
            quote(broker)
            val events = mutableListOf<TradeFill>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                broker.tradeFills.collect {
                    if (it.role != OrderRole.ENTRY) {
                        assertTrue(broker.openSession(it.accountId).state.value.trades.any { trade -> trade.id == it.executionId })
                        events.add(it)
                    }
                }
            }
            assertTrue(broker.openSession("A").placeOrder("open", request(side)).accepted)
            val bid = if ((side == Side.BUY) == stop) 9_900L else 10_100L
            quote(broker, bid); quote(broker, bid)
            assertEquals(1, events.size)
            assertEquals("A", events.single().accountId)
            assertEquals(if (stop) OrderRole.STOP_LOSS else OrderRole.TAKE_PROFIT, events.single().role)
        }
    }

    @Test fun manualCloseCancelRestorationAndResetDoNotReplaySounds() = runTest {
        val broker = DemoBroker(config, seedTrades = false)
        quote(broker)
        val events = mutableListOf<TradeFill>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect { if (it.role != OrderRole.ENTRY) events.add(it) } }
        val session = broker.openSession("A")
        val opened = session.placeOrder("open", request(Side.BUY))
        assertTrue(opened.accepted)
        assertTrue(session.closePosition("manual", opened.entityId!!).accepted)
        val pending = session.placeOrder("pending", request(Side.BUY).copy(kind = OrderKind.LIMIT, entryPrice = 9_980))
        assertTrue(pending.accepted)
        assertTrue(session.cancelOrder("cancel", pending.entityId!!).accepted)
        assertTrue(events.isEmpty())
        session.placeOrder("target", request(Side.BUY)); quote(broker, 10_100)
        assertEquals(1, events.size)
        val restored = DemoBroker(config, broker.exportState())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.tradeFills.collect { if (it.role != OrderRole.ENTRY) events.add(it) } }
        restored.advance(0)
        assertEquals(1, events.size)
        val oldOrder = events.single().orderId
        broker.reset(seedTrades = false); quote(broker)
        assertEquals(1, events.size)
        session.placeOrder("target", request(Side.BUY)); quote(broker, 10_100)
        assertEquals(2, events.size)
        assertNotEquals(oldOrder, events.last().orderId, "A reset must not reuse a sound deduplication identity")
    }

    @Test fun restoredUnclosedProtectionStillProducesANewSound() = runTest {
        val broker = DemoBroker(config, seedTrades = false)
        quote(broker)
        broker.openSession("B").placeOrder("open", request(Side.BUY))
        val restored = DemoBroker(config, broker.exportState())
        val events = mutableListOf<TradeFill>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.tradeFills.collect { if (it.role != OrderRole.ENTRY) events.add(it) } }
        quote(restored, 9_900)
        assertEquals("B", events.single().accountId)
        assertEquals(OrderRole.STOP_LOSS, events.single().role)
    }
}
