package com.unitrading.tradovate

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

private const val CACHE_TIME = 1_798_589_000_000L
private const val CACHE_STEP = 5 * 60_000L

class CandleCacheTest {
    @Test fun secondsNeverShareCacheEntriesWithOtherSecondsOrMinutes() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val frames = listOf(Timeframe.seconds(5), Timeframe.seconds(15), Timeframe.M5)
        frames.forEachIndexed { index, frame -> cache.write("MES" to frame, listOf(candle(close = 100L + index))) }
        frames.forEachIndexed { index, frame -> assertEquals(100L + index, cache.read("MES" to frame, CACHE_TIME).single().close) }
        assertEquals(3, store.values.size)
    }

    @Test fun liveSubscriptionSnapshotDoesNotCountAsHistoricalBackfill() {
        val seed = Candle(1, 100, 110, 90, 100)
        val load = ChartLoad(listOf(seed))
        load.accept(listOf(seed.copy(close = 105)), historical = false, endOfHistory = false, validPacket = true)
        load.accept(listOf(seed), historical = true, endOfHistory = true, validPacket = true, countForBackfill = false)
        assertFalse(load.complete)
        assertEquals(105L, load.candles.single().close)
        load.beginPage()
        load.accept(listOf(seed.copy(timeMs = 2)), historical = true, endOfHistory = true, validPacket = true)
        assertFalse(load.complete)
        assertEquals(2L, load.nextPageBefore)
    }
    private val series = "@MES" to Timeframe.M5

    private class MemoryStore : CandleCacheStore {
        val values = mutableMapOf<String, String>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null

        override suspend fun read(key: String): String? {
            readFailure?.let { throw it }
            return values[key]
        }

        override suspend fun write(key: String, value: String) {
            writeFailure?.let { throw it }
            values[key] = value
        }

        fun replaceOnlyValue(value: String) {
            values[values.keys.single()] = value
        }
    }

    private fun candle(timeMs: Long = CACHE_TIME, close: Long = 105) =
        Candle(timeMs, open = 100, high = 120, low = 90, close = close, volume = 10)

    @Test fun namespacesKeepIndependentValuesInTheSameStore() = runTest {
        val store = MemoryStore()
        val demo = CandleCache(store, "demo")
        val live = CandleCache(store, "live")
        val demoBars = listOf(candle(close = 101))
        val liveBars = listOf(candle(close = 102))
        demo.write(series, demoBars)
        assertTrue(live.read(series, CACHE_TIME).isEmpty())
        live.write(series, liveBars)
        assertEquals(demoBars, CandleCache(store, "demo").read(series, CACHE_TIME))
        assertEquals(liveBars, CandleCache(store, "live").read(series, CACHE_TIME))
        assertEquals(2, store.values.size)
    }

    @Test fun symbolsIncludingContinuousAndDatedContractsKeepIndependentValues() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val symbols = listOf("@MES", "MES", "MESZ6", "@mes")
        for ((index, symbol) in symbols.withIndex()) {
            assertTrue(cache.read(symbol to Timeframe.M5, CACHE_TIME).isEmpty())
            cache.write(symbol to Timeframe.M5, listOf(candle(close = 101L + index)))
        }
        for ((index, symbol) in symbols.withIndex()) {
            assertEquals(listOf(candle(close = 101L + index)), cache.read(symbol to Timeframe.M5, CACHE_TIME))
        }
        assertEquals(symbols.size, store.values.size)
    }

    @Test fun timeframesKeepIndependentValuesAndEquivalentIntervalsShareTheCache() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val fiveMinutes = listOf(candle(close = 101))
        val fifteenMinutes = listOf(candle(close = 102))
        cache.write(series, fiveMinutes)
        assertTrue(cache.read(series.first to Timeframe.M15, CACHE_TIME).isEmpty())
        cache.write(series.first to Timeframe.M15, fifteenMinutes)
        assertEquals(fiveMinutes, cache.read(series.first to Timeframe(5), CACHE_TIME))
        assertEquals(fifteenMinutes, cache.read(series.first to Timeframe.M15, CACHE_TIME))
        assertEquals(2, store.values.size)
    }

    @Test fun missingAndDisabledStoresBehaveAsEmptyCaches() = runTest {
        assertTrue(CandleCache(MemoryStore(), "demo").read(series, CACHE_TIME).isEmpty())
        val disabled = CandleCache(null, "demo")
        disabled.write(series, listOf(candle()))
        assertTrue(disabled.read(series, CACHE_TIME).isEmpty())
    }

    @Test fun validCandlesRoundTripIncludingTimePriceAndVolumeBoundaries() = runTest {
        val cache = CandleCache(MemoryStore(), "demo")
        val bars = listOf(
            candle(0).copy(open = 1, high = 1, low = 1, close = 1, volume = null),
            candle(CACHE_TIME - CACHE_STEP).copy(open = 120, close = 90, volume = 0),
            candle().copy(open = 90, close = 120),
        )
        cache.write(series, bars)
        assertEquals(bars, cache.read(series, CACHE_TIME))
        cache.write(series, emptyList())
        assertTrue(cache.read(series, CACHE_TIME).isEmpty())
    }

    @Test fun corruptOrStructurallyInvalidCacheFallsBackToEmpty() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        cache.write(series, listOf(candle()))
        for (text in listOf("", "{", "null", "[]", "{}",
            """{"version":1,"candles":{}}""",
            """{"version":1,"candles":[{"timeMs":"invalid"}]}""")) {
            store.replaceOnlyValue(text)
            assertTrue(cache.read(series, CACHE_TIME).isEmpty(), "Corrupt cache: $text")
        }
    }

    @Test fun onlyTheSupportedCacheVersionIsAccepted() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val bars = listOf(candle())
        cache.write(series, bars)
        val payload = Json.parseToJsonElement(store.values.values.single()).jsonObject
        store.replaceOnlyValue(JsonObject(payload + ("version" to JsonPrimitive(1))).toString())
        assertEquals(bars, cache.read(series, CACHE_TIME))
        for (version in listOf(-1, 0, 2)) {
            store.replaceOnlyValue(JsonObject(payload + ("version" to JsonPrimitive(version))).toString())
            assertTrue(cache.read(series, CACHE_TIME).isEmpty(), "Unsupported version: $version")
        }
    }

    @Test fun outOfOrderAndDuplicateCachedTimestampsRejectTheWholeCache() = runTest {
        val cache = CandleCache(MemoryStore(), "demo")
        val first = candle(CACHE_TIME - CACHE_STEP)
        val last = candle()
        for (bars in listOf(listOf(last, first), listOf(first, last, last.copy(close = 106)))) {
            cache.write(series, bars)
            assertTrue(cache.read(series, CACHE_TIME).isEmpty(), "Invalid order: $bars")
        }
    }

    @Test fun negativeAndFutureCachedTimestampsAreRejected() = runTest {
        val cache = CandleCache(MemoryStore(), "demo")
        for (timeMs in listOf(-1L, CACHE_TIME + 1)) {
            cache.write(series, listOf(candle(timeMs)))
            assertTrue(cache.read(series, CACHE_TIME).isEmpty(), "Invalid timestamp: $timeMs")
        }
    }

    @Test fun invalidPricesOrVolumeRejectTheWholeCache() = runTest {
        val cache = CandleCache(MemoryStore(), "demo")
        val valid = candle(CACHE_TIME - CACHE_STEP)
        val bar = candle()
        val invalid = listOf(
            "zero low" to bar.copy(low = 0),
            "negative low" to bar.copy(low = -1),
            "zero open" to bar.copy(open = 0),
            "negative open" to bar.copy(open = -1),
            "zero close" to bar.copy(close = 0),
            "negative close" to bar.copy(close = -1),
            "high below open" to bar.copy(close = 95, high = 99),
            "high below close" to bar.copy(high = 104),
            "low above open" to bar.copy(low = 101),
            "low above close" to bar.copy(close = 95, low = 96),
            "inverted range" to bar.copy(high = 89),
            "negative volume" to bar.copy(volume = -1),
        )
        for ((reason, bad) in invalid) {
            cache.write(series, listOf(valid, bad))
            assertTrue(cache.read(series, CACHE_TIME).isEmpty(), reason)
        }
    }

    @Test fun cacheAcceptsTwoThousandCandlesAndRejectsAnyMore() = runTest {
        val cache = CandleCache(MemoryStore(), "demo")
        val bars = List(2_001) { candle(CACHE_TIME - (2_000 - it) * CACHE_STEP) }
        cache.write(series, bars.takeLast(2_000))
        assertEquals(bars.takeLast(2_000), cache.read(series, CACHE_TIME))
        cache.write(series, bars)
        assertTrue(cache.read(series, CACHE_TIME).isEmpty())
    }

    @Test fun readFailureFallsBackToEmptyAndLaterReadsCanRecover() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val bars = listOf(candle())
        cache.write(series, bars)
        store.readFailure = IllegalStateException("read failed")
        assertTrue(cache.read(series, CACHE_TIME).isEmpty())
        store.readFailure = null
        assertEquals(bars, cache.read(series, CACHE_TIME))
    }

    @Test fun readCancellationIsPropagated() = runTest {
        val cancellation = CancellationException("cancelled")
        val store = MemoryStore().apply { readFailure = cancellation }
        assertSame(cancellation, assertFailsWith<CancellationException> {
            CandleCache(store, "demo").read(series, CACHE_TIME)
        })
    }

    @Test fun writeFailuresIncludingCancellationArePropagated() = runTest {
        val store = MemoryStore()
        val cache = CandleCache(store, "demo")
        val failure = IllegalStateException("write failed")
        store.writeFailure = failure
        assertSame(failure, assertFailsWith<IllegalStateException> { cache.write(series, listOf(candle())) })
        val cancellation = CancellationException("cancelled")
        store.writeFailure = cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { cache.write(series, listOf(candle())) })
        assertTrue(store.values.isEmpty())
    }

    @Test fun emptyCacheRequestsOnlyTwoThousandElements() {
        assertEquals(buildJsonObject { put("asMuchAsElements", 2_000) }, chartTimeRange(emptyList()))
    }

    @Test fun populatedCacheRequestsOnlyOneElementForTheLiveSubscription() {
        val bars = listOf(candle(CACHE_TIME - 2 * CACHE_STEP), candle(CACHE_TIME - CACHE_STEP))
        assertEquals(buildJsonObject { put("asMuchAsElements", 1) }, chartTimeRange(bars))
    }

    @Test fun backfillRequestsOnlyTheAnchorAndClosestTimestamps() {
        val anchor = CACHE_TIME - 10 * CACHE_STEP
        val closest = CACHE_TIME - CACHE_STEP
        assertEquals(buildJsonObject {
            put("asFarAsTimestamp", Instant.fromEpochMilliseconds(anchor).toString())
            put("closestTimestamp", Instant.fromEpochMilliseconds(closest).toString())
        }, chartBackfillRange(anchor, closest))
    }

    @Test fun freshHistoryFloorCoversFourChartWidthsAndStaysPositive() {
        assertEquals(CACHE_TIME - 8_000 * 60_000L, chartHistoryFloor(Timeframe.M1, CACHE_TIME))
        assertEquals(0, chartHistoryFloor(Timeframe(Timeframe.MAX_MINUTES), 1_000))
    }

    @Test fun freshLoadCompletesAtItsFloorOrWhenThePageAddsNothingOlder() {
        val floor = CACHE_TIME - 4 * CACHE_STEP
        val reached = ChartLoad(emptyList(), floor)
        reached.accept(listOf(candle(floor), candle(CACHE_TIME)), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(reached.complete)
        assertNull(reached.nextPageBefore)

        val paging = ChartLoad(emptyList(), floor)
        paging.accept(listOf(candle(CACHE_TIME - CACHE_STEP)), historical = true, endOfHistory = true, validPacket = true)
        assertFalse(paging.complete)
        assertEquals(CACHE_TIME - CACHE_STEP, paging.nextPageBefore)
        paging.beginPage()
        paging.accept(listOf(candle(CACHE_TIME - CACHE_STEP)), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(paging.complete)
        assertFalse(paging.failed)
        assertNull(paging.nextPageBefore)
    }

    @Test fun historicalChunksStayIncompleteUntilEndOfHistory() {
        val cached = candle(CACHE_TIME - 2 * CACHE_STEP)
        val first = candle(CACHE_TIME - CACHE_STEP)
        val last = candle()
        val load = ChartLoad(listOf(cached))
        assertEquals(listOf(cached), load.candles)
        assertFalse(load.complete)
        load.beginPage()
        load.accept(listOf(first, cached), historical = true, endOfHistory = false, validPacket = true)
        assertEquals(listOf(cached, first), load.candles)
        assertFalse(load.complete)
        load.accept(listOf(last), historical = true, endOfHistory = false, validPacket = true)
        assertEquals(listOf(cached, first, last), load.candles)
        assertFalse(load.complete)
        load.accept(emptyList(), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(load.complete)
        assertEquals(listOf(cached, first, last), load.candles)
        assertFalse(load.failed)
        assertNull(load.nextPageBefore)
    }

    @Test fun realtimeEndOfHistoryCannotCompleteAHistoricalLoad() {
        val load = ChartLoad(emptyList())
        load.accept(listOf(candle()), historical = false, endOfHistory = true, validPacket = true)
        assertEquals(listOf(candle()), load.candles)
        assertFalse(load.complete)
        load.accept(emptyList(), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(load.complete)
    }

    @Test fun latestRealtimeCandleWinsOverCachedAndLateHistoricalCandles() {
        val cached = candle(close = 101)
        val older = candle(CACHE_TIME - CACHE_STEP)
        val realtime = candle(close = 106)
        val latest = candle(close = 109)
        val load = ChartLoad(listOf(cached))
        load.accept(listOf(realtime), historical = false, endOfHistory = false, validPacket = true)
        load.accept(listOf(older, candle(close = 104)), historical = true, endOfHistory = false, validPacket = true)
        assertEquals(listOf(older, realtime), load.candles)
        assertFalse(load.complete)
        load.accept(listOf(latest), historical = false, endOfHistory = false, validPacket = true)
        load.accept(listOf(candle(close = 110)), historical = true, endOfHistory = true, validPacket = true)
        assertEquals(listOf(older, latest), load.candles)
        assertTrue(load.complete)
        val next = candle(CACHE_TIME + CACHE_STEP)
        load.accept(listOf(next), historical = false, endOfHistory = false, validPacket = true)
        assertEquals(listOf(older, latest, next), load.candles)
        assertTrue(load.complete)
    }

    @Test fun chartLoadSortsAndDeduplicatesWithinAndAcrossPackets() {
        for (historical in listOf(true, false)) {
            val first = candle(CACHE_TIME - 2 * CACHE_STEP)
            val second = candle(CACHE_TIME - CACHE_STEP)
            val last = candle()
            val updated = second.copy(close = 107)
            val latest = second.copy(close = 108)
            val load = ChartLoad(listOf(first, second))
            load.accept(listOf(last, second, first, updated), historical, endOfHistory = false, validPacket = true)
            assertEquals(listOf(first, updated, last), load.candles)
            load.accept(listOf(last, latest, first), historical, endOfHistory = false, validPacket = true)
            assertEquals(listOf(first, latest, last), load.candles)
        }
    }

    @Test fun chartLoadKeepsOnlyTheNewestTwoThousandCandles() {
        for (historical in listOf(true, false)) {
            val bars = List(2_002) { candle(CACHE_TIME - (2_001 - it) * CACHE_STEP) }
            val load = ChartLoad(bars.take(2_000))
            load.accept(listOf(bars.last(), bars[2_000], bars[1_999]), historical, endOfHistory = false, validPacket = true)
            assertEquals(2_000, load.candles.size)
            assertEquals(bars.drop(2), load.candles)
            load.accept(bars.reversed(), historical, endOfHistory = false, validPacket = true)
            assertEquals(bars.drop(2), load.candles)
        }
    }

    @Test fun fullOldCacheAndOneHundredNewBarsNeedAnotherPageUntilHistoryCoversTheAnchor() {
        val cached = List(2_000) { candle(CACHE_TIME - (2_200 - it) * CACHE_STEP) }
        val anchor = cached.last().timeMs
        val firstPage = List(100) { candle(CACHE_TIME - (99 - it) * CACHE_STEP) }
        val realtime = firstPage.last().copy(close = 109)
        val load = ChartLoad(cached)
        load.accept(listOf(realtime), historical = false, endOfHistory = false, validPacket = true)
        load.beginPage()
        load.accept(firstPage.take(40).reversed(), historical = true, endOfHistory = false, validPacket = true)
        assertFalse(load.complete)
        assertNull(load.nextPageBefore)
        load.accept(firstPage.drop(40).reversed(), historical = true, endOfHistory = true, validPacket = true)
        assertEquals(2_000, load.candles.size)
        assertEquals(anchor, load.anchor)
        assertEquals(firstPage.first().timeMs, load.nextPageBefore)
        assertFalse(load.complete)
        assertFalse(load.failed)

        val collected = load.candles
        load.beginPage()
        assertNull(load.nextPageBefore)
        assertEquals(collected, load.candles)
        val secondPage = List(103) { candle(anchor + it * CACHE_STEP) }
        load.accept(secondPage.reversed(), historical = true, endOfHistory = false, validPacket = true)
        assertFalse(load.complete)
        load.accept(emptyList(), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(load.complete)
        assertFalse(load.failed)
        assertNull(load.nextPageBefore)
        assertEquals(anchor, load.anchor)
        val expected = cached.dropLast(1) + secondPage + firstPage.drop(1).dropLast(1) + realtime
        assertEquals(expected.takeLast(2_000), load.candles)
    }

    @Test fun twoThousandDistinctHistoricalBarsAcrossPagesCompleteWithoutReachingTheAnchor() {
        val anchor = CACHE_TIME - 2_001 * CACHE_STEP
        val bars = List(2_000) { candle(CACHE_TIME - (1_999 - it) * CACHE_STEP) }
        val load = ChartLoad(listOf(candle(anchor)))
        val firstPage = bars.drop(1_000)
        load.beginPage()
        // Repeated timestamps must not turn 1000 distinct historical bars into 2000.
        load.accept(firstPage.reversed() + firstPage, historical = true, endOfHistory = true, validPacket = true)
        assertFalse(load.complete)
        assertFalse(load.failed)
        assertEquals(firstPage.first().timeMs, load.nextPageBefore)

        load.beginPage()
        load.accept(bars.subList(1, 1_000), historical = true, endOfHistory = true, validPacket = true)
        assertFalse(load.complete)
        assertFalse(load.failed)
        assertEquals(bars[1].timeMs, load.nextPageBefore)

        load.beginPage()
        load.accept(listOf(bars.first()), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(bars.first().timeMs > anchor)
        assertTrue(load.complete)
        assertFalse(load.failed)
        assertNull(load.nextPageBefore)
        assertEquals(anchor, load.anchor)
        assertEquals(bars, load.candles)
    }

    @Test fun realtimeBarsCannotSatisfyHistoricalCountOrAnchorCoverage() {
        val anchor = CACHE_TIME - 2_001 * CACHE_STEP
        val realtime = List(2_000) { candle(CACHE_TIME - (1_999 - it) * CACHE_STEP) }
        val load = ChartLoad(listOf(candle(anchor)))
        load.beginPage()
        load.accept(listOf(candle(anchor)) + realtime, historical = false, endOfHistory = false, validPacket = true)
        load.accept(listOf(realtime.last()), historical = true, endOfHistory = true, validPacket = true)
        assertEquals(2_000, load.candles.size)
        assertEquals(anchor, load.anchor)
        assertFalse(load.complete)
        assertFalse(load.failed)
        assertEquals(realtime.last().timeMs, load.nextPageBefore)
    }

    @Test fun emptyFirstOrLaterHistoricalPageFailsWithoutCompleting() {
        for (afterPartialPage in listOf(false, true)) {
            val cached = candle(CACHE_TIME - 10 * CACHE_STEP)
            val load = ChartLoad(listOf(cached))
            load.beginPage()
            if (afterPartialPage) {
                load.accept(listOf(candle()), historical = true, endOfHistory = true, validPacket = true)
                assertEquals(CACHE_TIME, load.nextPageBefore)
                assertFalse(load.failed)
                load.beginPage()
            }
            assertNull(load.nextPageBefore)
            load.accept(listOf(candle(CACHE_TIME - CACHE_STEP)), historical = false, endOfHistory = false, validPacket = true)
            val collected = load.candles
            load.accept(emptyList(), historical = true, endOfHistory = true, validPacket = true)
            assertTrue(load.failed)
            assertFalse(load.complete)
            assertNull(load.nextPageBefore)
            assertEquals(collected, load.candles)
            load.beginPage()
            load.accept(listOf(cached), historical = true, endOfHistory = true, validPacket = true)
            assertTrue(load.failed)
            assertFalse(load.complete)
        }
    }

    @Test fun repeatedOrNewerHistoricalPageFailsWithoutCompleting() {
        val firstPage = listOf(candle(CACHE_TIME - 3 * CACHE_STEP), candle())
        for (nextPage in listOf(firstPage.reversed(), listOf(candle(CACHE_TIME - CACHE_STEP)))) {
            val load = ChartLoad(listOf(candle(CACHE_TIME - 10 * CACHE_STEP)))
            load.beginPage()
            load.accept(firstPage, historical = true, endOfHistory = true, validPacket = true)
            assertEquals(firstPage.first().timeMs, load.nextPageBefore)
            assertFalse(load.complete)
            assertFalse(load.failed)
            load.beginPage()
            load.accept(nextPage, historical = true, endOfHistory = true, validPacket = true)
            assertTrue(load.failed)
            assertFalse(load.complete)
            assertNull(load.nextPageBefore)
        }
    }

    @Test fun invalidPacketClearsAPendingPageAndBeginPageDoesNotClearFailure() {
        val load = ChartLoad(listOf(candle(CACHE_TIME - CACHE_STEP)))
        load.beginPage()
        load.accept(listOf(candle()), historical = true, endOfHistory = true, validPacket = true)
        assertEquals(CACHE_TIME, load.nextPageBefore)
        load.accept(emptyList(), historical = false, endOfHistory = false, validPacket = false)
        assertTrue(load.failed)
        assertFalse(load.complete)
        assertNull(load.nextPageBefore)
        load.beginPage()
        load.accept(listOf(candle(CACHE_TIME - CACHE_STEP)), historical = true, endOfHistory = true, validPacket = true)
        assertTrue(load.failed)
        assertFalse(load.complete)
        assertNull(load.nextPageBefore)
    }

    @Test fun invalidHistoricalPacketsPreventCompletionEvenAfterLaterValidEndOfHistory() {
        for (endOfHistory in listOf(false, true)) {
            val load = ChartLoad(emptyList())
            load.accept(emptyList(), historical = true, endOfHistory = endOfHistory, validPacket = false)
            assertFalse(load.complete)
            assertTrue(load.failed)
            load.accept(listOf(candle()), historical = true, endOfHistory = true, validPacket = true)
            assertEquals(listOf(candle()), load.candles)
            assertFalse(load.complete)
            assertTrue(load.failed)
        }
    }

    @Test fun invalidPacketsResetCompletionAndLaterValidPacketsDoNotRestoreIt() {
        for (historical in listOf(true, false)) {
            for (endOfHistory in listOf(false, true)) {
                val load = ChartLoad(emptyList())
                load.accept(listOf(candle()), historical = true, endOfHistory = true, validPacket = true)
                assertTrue(load.complete)
                load.accept(emptyList(), historical, endOfHistory, validPacket = false)
                assertFalse(load.complete)
                assertTrue(load.failed)
                load.accept(emptyList(), historical = true, endOfHistory = true, validPacket = true)
                assertFalse(load.complete)
                assertTrue(load.failed)
                assertEquals(listOf(candle()), load.candles)
            }
        }
    }
}
