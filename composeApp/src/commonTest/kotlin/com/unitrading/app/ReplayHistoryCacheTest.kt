package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val DAY = 86_400_000L

class ReplayHistoryCacheTest {
    private class Broker : BrokerPlugin {
        override val descriptor = BrokerDescriptor("test-replay", "Test", "", true, BrokerEnvironment.REMOTE_DEMO)
        override val capabilities = BrokerCapabilities()
        override val instruments = listOf(Instrument("MESZ6", "MES", "期货", 2, tickValueMicrosPerLot = 1))
        override val accounts = emptyList<AccountInfo>()
        override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
        override val market = MutableStateFlow(MarketSnapshot())
        var active = 0
        var peak = 0
        var calls = 0
        var cacheable = true
        var concurrentGate: CompletableDeferred<Unit>? = null
        override fun openSession(accountId: String): AccountSession = error("not used")
        override fun history(symbol: String, timeframe: Timeframe) = emptyList<Candle>()
        override suspend fun historicalRange(symbol: String, timeframe: Timeframe, startMs: Long, endMs: Long): HistoricalRangeResult {
            active++; peak = maxOf(peak, active); calls++
            try {
                if (active == 2) concurrentGate?.complete(Unit)
                concurrentGate?.await()
                delay(5)
                return HistoricalRangeResult(listOf(Candle(startMs, 100, 100, 100, 100)), cacheable = cacheable)
            }
            finally { active-- }
        }
    }

    @Test fun boundedParallelRequestsPersistAndReuseCompletedChunks() = runTest {
        val store = MemorySnapshotStore()
        val broker = Broker().apply { concurrentGate = CompletableDeferred() }
        val first = ReplayHistoryCache(store) { 200 * DAY }.load(broker, "MESZ6", Timeframe.M1, 0, 60 * DAY)
        assertEquals(2, first.downloaded)
        assertEquals(2, broker.peak)
        assertEquals(2, broker.calls)
        val second = ReplayHistoryCache(store) { 200 * DAY }.load(broker, "MESZ6", Timeframe.M1, 0, 60 * DAY)
        assertEquals(2, second.cacheHits)
        assertEquals(first.result.candles, second.result.candles)
        assertEquals(2, broker.calls)
    }

    @Test fun cacheCanBeDisabledAndRecentOrUnconfirmedDataIsNeverPersisted() = runTest {
        val store = MemorySnapshotStore()
        val broker = Broker()
        val cache = ReplayHistoryCache(store) { 100 * DAY }
        repeat(2) { cache.load(broker, "MESZ6", Timeframe.M1, 98 * DAY, 100 * DAY) }
        assertEquals(2, broker.calls)
        broker.cacheable = false
        repeat(2) { cache.load(broker, "MESZ6", Timeframe.M1, 30 * DAY, 60 * DAY) }
        assertEquals(4, broker.calls)
        broker.cacheable = true
        repeat(2) { cache.load(broker, "MESZ6", Timeframe.M1, 0, 30 * DAY, enabled = false) }
        assertEquals(6, broker.calls)
    }

    @Test fun oneRequestFailureDoesNotPersistItsUnfinishedChunk() = runTest {
        val store = MemorySnapshotStore()
        val broker = Broker().apply { cacheable = false }
        val cache = ReplayHistoryCache(store) { 200 * DAY }
        val first = async { cache.load(broker, "MESZ6", Timeframe.M1, 0, 30 * DAY) }
        first.await()
        cache.load(broker, "MESZ6", Timeframe.M1, 0, 30 * DAY)
        assertEquals(2, broker.calls)
    }
}
