package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import com.unitrading.broker.Side
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class AnalyticsTradeCacheTest {
    private var now = Instant.parse("2026-11-11T12:00:00Z")
    private val clock = object : Clock { override fun now() = now }
    private fun trade(id: String, age: Int, pnl: Long?) =
        ClosedTrade(id, "p", "MES", Side.BUY, 100, 100, 125, 0, (now - age.days).toEpochMilliseconds(), pnl, "fill")

    @Test fun restoresRecentTradesWithoutCrossingAccountsOrDoubleCounting() = runTest {
        val store = MemorySnapshotStore()
        val initial = AnalyticsTradeCache(store, clock)
        val known = trade("1", 4, 5_000_000)
        val expired = trade("2", 31, 9_000_000)
        initial.update("broker", "child-a", listOf(known, expired))
        initial.update("broker", "child-b", listOf(trade("1", 2, -3_000_000)))
        val reopened = AnalyticsTradeCache(store, clock)
        reopened.update("broker", "child-a", emptyList())
        assertEquals(listOf(known), reopened.rows.value["broker"]?.get("child-a"))
        assertEquals(-3_000_000L, reopened.rows.value["broker"]?.get("child-b")?.single()?.netPnlMicros)
        val pending = known.copy(netPnlMicros = null)
        reopened.update("broker", "child-a", listOf(pending, trade("3", 1, 8_000_000)))
        assertEquals(listOf(known, trade("3", 1, 8_000_000)), reopened.rows.value["broker"]?.get("child-a"))
        val amended = known.copy(netPnlMicros = 4_000_000)
        reopened.update("broker", "child-a", listOf(amended))
        assertEquals(amended, reopened.rows.value["broker"]?.get("child-a")?.first())
        now += 31.days
        reopened.update("broker", "child-a", emptyList())
        assertTrue(reopened.rows.value.isEmpty())
    }
}
