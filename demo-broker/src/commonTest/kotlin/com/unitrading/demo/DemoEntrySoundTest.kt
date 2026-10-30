package com.unitrading.demo

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DemoEntrySoundTest {
    @Test fun marketAndPendingOrdersSoundOnlyOnFillAndNeverOnRetry() = runTest {
        for (kind in OrderKind.entries) {
            val broker = DemoBroker(DemoConfigs.all.first(), seedTrades = false)
            val spec = broker.instruments.first()
            val session = broker.openSession(broker.accounts.first().id)
            val quote = broker.market.value.quotes.getValue(spec.symbol)
            val distance = (spec.minStopTicks + 10) * spec.tickSize
            val price = when (kind) { OrderKind.MARKET -> null; OrderKind.LIMIT -> quote.ask - distance; OrderKind.STOP -> quote.ask + distance }
            val request = OrderRequest(spec.symbol, Side.BUY, kind, spec.minLotsHundredths, price)
            val events = mutableListOf<TradeFill>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect { events.add(it) } }
            val result = session.placeOrder("entry", request)
            assertTrue(result.accepted, result.message)
            if (price != null) {
                assertTrue(events.isEmpty(), "Accepting a pending order is not a fill")
                broker.setQuote(spec.symbol, price - spec.tickSize, price, broker.market.value.simulatedTimeMs + 15_000)
            }
            assertEquals(OrderRole.ENTRY, events.single().role)
            session.placeOrder("entry", request)
            assertEquals(1, events.size)
            val position = session.state.value.positions.single()
            session.closePosition("close", position.id)
            assertEquals(1, events.size, "Manual close must not play entry audio")
        }
    }

    @Test fun entryAndProtectionInSameTransactionAreDistinctEvents() = runTest {
        val broker = DemoBroker(DemoConfigs.all.first(), seedTrades = false)
        val spec = broker.instruments.first()
        val session = broker.openSession(broker.accounts.first().id)
        val quote = broker.market.value.quotes.getValue(spec.symbol)
        val distance = (spec.minStopTicks + 10) * spec.tickSize
        val price = quote.ask + distance
        val target = price + distance
        val events = mutableListOf<TradeFill>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broker.tradeFills.collect { events.add(it) } }
        assertTrue(session.placeOrder("entry", OrderRequest(spec.symbol, Side.BUY, OrderKind.STOP, spec.minLotsHundredths,
            price, takeProfit = target)).accepted)
        broker.setQuote(spec.symbol, target + spec.tickSize, target + 2 * spec.tickSize, broker.market.value.simulatedTimeMs + 15_000)
        assertEquals(listOf(OrderRole.ENTRY, OrderRole.TAKE_PROFIT), events.map { it.role })
        assertEquals(2, events.map { it.orderId }.distinct().size)
    }
}
