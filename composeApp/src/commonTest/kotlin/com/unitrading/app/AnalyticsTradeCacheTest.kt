package com.unitrading.app

import com.unitrading.broker.ClosedTrade
import com.unitrading.broker.Side
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AnalyticsTradeCacheTest {
    private var now = Instant.parse("2026-11-11T12:00:00Z")
    private val clock = object : Clock { override fun now() = now }
    private fun trade(id: String, age: Int, pnl: Long?, symbol: String = "MES") =
        ClosedTrade(id, "p", symbol, Side.BUY, 100, 100, 125, 0, (now - age.days).toEpochMilliseconds(), pnl, "fill")

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

    @Test fun aSnapshotDropsTheCachedTradesItNoLongerHasInItsRangeAndKeepsOlderOnes() = runTest {
        val store = MemorySnapshotStore()
        val older = trade("older", 9, 1_000_000)
        val start = trade("start", 4, 2_000_000)
        val withdrawn = trade("withdrawn", 3, 7_000_000)
        val kept = trade("kept", 2, -3_000_000)
        AnalyticsTradeCache(store, clock).update("broker", "a", listOf(older, start, withdrawn, kept))
        // After a restart the cache is all there is until the broker answers.
        val restarted = AnalyticsTradeCache(store, clock)
        restarted.update("broker", "a", emptyList())
        assertEquals(listOf(older, start, withdrawn, kept), restarted.rows.value["broker"]?.get("a"), "an empty snapshot changes nothing")
        // The broker now returns a shorter history, and inside it one trade it used to report is gone.
        val added = trade("added", 1, 4_000_000)
        restarted.update("broker", "a", listOf(start, kept, added))
        assertEquals(listOf(older, start, kept, added), restarted.rows.value["broker"]?.get("a"))
        assertFalse("withdrawn" in store.read("analytics-trades-v1")!!, "the stored cache does not keep it either")
        // So the next start cannot bring it back, before the broker has answered or after.
        val reopened = AnalyticsTradeCache(store, clock)
        reopened.update("broker", "a", emptyList())
        assertEquals(listOf(older, start, kept, added), reopened.rows.value["broker"]?.get("a"))
        reopened.update("broker", "a", listOf(kept, added))
        assertEquals(listOf(older, start, kept, added), reopened.rows.value["broker"]?.get("a"), "what closed before its contract's first close in the snapshot stays")
        // Another account of the login is reconciled on its own.
        reopened.update("broker", "b", listOf(withdrawn))
        reopened.update("broker", "a", listOf(added))
        assertEquals(listOf(withdrawn), reopened.rows.value["broker"]?.get("b"))
    }

    @Test fun tradesTheBrokerPairsAgainUnderOtherIdsAreCountedOnce() = runTest {
        val store = MemorySnapshotStore()
        val cache = AnalyticsTradeCache(store, clock)
        // Two entries and two exits. After a reconnect the broker pairs them the other way round: new ids, other results per trade.
        val before = listOf(trade("fill-B-C", 2, -5_000_000), trade("fill-A-D", 1, 20_000_000))
        val after = listOf(trade("fill-A-C", 2, 5_000_000), trade("fill-B-D", 1, 10_000_000))
        cache.update("rithmic", "a", before)
        val shown = mergeAnalyticsTrades(cache.rows.value["rithmic"]?.get("a").orEmpty(), after)
        assertEquals(after, shown, "the page already shows the snapshot alone before the cache is rewritten")
        assertEquals(2 to 15_000_000L, tradeStatistics(shown).let { it.total to it.netMicros })
        cache.update("rithmic", "a", after)
        assertEquals(after, cache.rows.value["rithmic"]?.get("a"))
        val reopened = AnalyticsTradeCache(store, clock).also { it.update("rithmic", "a", emptyList()) }
        assertEquals(2 to 15_000_000L, tradeStatistics(reopened.rows.value["rithmic"]?.get("a").orEmpty()).let { it.total to it.netMicros })
    }

    @Test fun mergeTakesTheSnapshotForItsRangeAndTheCacheForWhatClosedBefore() {
        val old = trade("old", 8, 1_000_000)
        val moved = trade("moved", 6, 2_000_000)
        val live = trade("live", 3, 3_000_000)
        assertEquals(listOf(old, moved), mergeAnalyticsTrades(listOf(old, moved), emptyList()), "nothing synchronized yet")
        assertEquals(listOf(live), mergeAnalyticsTrades(emptyList(), listOf(live)))
        assertEquals(listOf(old, moved, live), mergeAnalyticsTrades(listOf(old, moved, trade("gone", 3, 9_000_000), trade("later", 1, 9_000_000)), listOf(live)))
        // The same id with a later close replaces the cached trade although that one closed before the snapshot's range.
        val restated = moved.copy(closedAtMs = live.closedAtMs + 1, netPnlMicros = 2_500_000)
        assertEquals(listOf(old, live, restated), mergeAnalyticsTrades(listOf(old, moved), listOf(live, restated)))
        // A trade closing exactly at its contract's first close in the snapshot is inside the range.
        assertEquals(listOf(old, live), mergeAnalyticsTrades(listOf(old, live.copy(id = "same-instant")), listOf(live)))
    }

    /**
     * Rithmic leaves a whole contract out of a snapshot when the replay of its fills does not end on the server's
     * position, which says nothing about that contract's trades: each contract is reconciled with its own trades.
     */
    @Test fun aContractTheSnapshotLeavesOutKeepsItsCachedTrades() = runTest {
        val mnqMonday = trade("mnq-monday", 7, 1_000_000, "MNQZ6")
        val mesMonday = trade("mes-monday", 7, 4_000_000, "MESZ6").let { it.copy(closedAtMs = it.closedAtMs + 2.minutes.inWholeMilliseconds) }
        val mesWednesday = trade("mes-wednesday", 5, -2_000_000, "MESZ6")
        val mnqThursday = trade("mnq-thursday", 4, 3_000_000, "MNQZ6")
        val cached = listOf(mnqMonday, mesMonday, mesWednesday, mnqThursday)
        // MES is left out: its trades stay, although they closed after the snapshot's first close (MNQ's).
        assertEquals(cached, mergeAnalyticsTrades(cached, listOf(mnqMonday, mnqThursday)))
        // MNQ is left out and MES is back from Wednesday on: MNQ keeps its trades, MES's Monday trade closed before MES's range.
        assertEquals(cached, mergeAnalyticsTrades(cached, listOf(mesWednesday)))
        // Inside a contract's own range a trade the snapshot no longer has is still dropped.
        assertEquals(listOf(mnqMonday, mesMonday, mesWednesday), mergeAnalyticsTrades(cached, listOf(mnqMonday, mesMonday, mesWednesday)))
        // The stored cache keeps them too, so a later session that pairs MES again from Wednesday on still has the Monday trade.
        val store = MemorySnapshotStore()
        AnalyticsTradeCache(store, clock).run { update("rithmic", "a", cached); update("rithmic", "a", listOf(mnqMonday, mnqThursday)) }
        assertTrue("mes-monday" in store.read("analytics-trades-v1")!!)
        val reopened = AnalyticsTradeCache(store, clock).also { it.update("rithmic", "a", listOf(mesWednesday)) }
        assertEquals(cached, reopened.rows.value["rithmic"]?.get("a"))
    }

    /**
     * The documented limit of the rule: a trade the broker withdrew that was the earliest of its contract cannot be told
     * from one that left the broker's window, so it stays until the 30-day trim.
     */
    @Test fun aWithdrawnTradeThatWasItsContractsEarliestStaysUntilTheThirtyDayTrim() = runTest {
        val cache = AnalyticsTradeCache(MemorySnapshotStore(), clock)
        val withdrawn = trade("pair-101", 4, 50_000_000)
        val kept = trade("pair-102", 2, -20_000_000)
        cache.update("tradovate", "a", listOf(withdrawn, kept))
        cache.update("tradovate", "a", listOf(kept))
        assertEquals(listOf(withdrawn, kept), cache.rows.value["tradovate"]?.get("a"))
        now += 27.days
        cache.update("tradovate", "a", listOf(kept))
        assertEquals(listOf(kept), cache.rows.value["tradovate"]?.get("a"))
    }

    /** The trim runs when the file is read too, so trades past 30 days leave it in a run where no login updates the cache. */
    @Test fun loadingDropsTradesOlderThanThirtyDaysAndRewritesTheFileOnlyWhenThatRemovedAny() = runTest {
        val memory = MemorySnapshotStore()
        var writes = 0
        val store = object : SnapshotStore {
            override fun read(key: String) = memory.read(key)
            override fun write(key: String, value: String) { writes++; memory.write(key, value) }
        }
        val recent = trade("recent", 2, 1_000_000)
        AnalyticsTradeCache(store, clock).run {
            update("login-a", "x", listOf(trade("aging", 20, 2_000_000), recent))
            update("login-b", "y", listOf(trade("other", 25, 3_000_000)))
        }
        writes = 0
        // Nothing is past 30 days yet: reading the file leaves it as it is.
        AnalyticsTradeCache(store, clock).prune()
        assertEquals(0, writes)
        now += 11.days
        val reopened = AnalyticsTradeCache(store, clock)
        reopened.prune()
        assertEquals(mapOf("login-a" to mapOf("x" to listOf(recent))), reopened.rows.value)
        assertEquals(1, writes)
        val stored = memory.read("analytics-trades-v1")!!
        assertTrue("recent" in stored && "aging" !in stored && "other" !in stored && "login-b" !in stored)
        // The rewritten file has nothing left to trim.
        AnalyticsTradeCache(store, clock).prune()
        assertEquals(1, writes)
    }

    @Test fun trimsToThirtyDaysAfterReconcilingWithTheWholeSnapshot() = runTest {
        val cache = AnalyticsTradeCache(MemorySnapshotStore(), clock)
        val recent = trade("recent", 3, 1_000_000)
        cache.update("broker", "a", listOf(trade("replaced", 20, 2_000_000), recent))
        // The snapshot reaches back past the 30 days kept, so it covers everything cached; only its own trades remain.
        cache.update("broker", "a", listOf(trade("ancient", 45, 6_000_000), recent))
        assertEquals(listOf(recent), cache.rows.value["broker"]?.get("a"))
    }

    @Test fun clearingDeletesOneLoginOrAllOfThemFromTheStoredFileToo() = runTest {
        val store = MemorySnapshotStore()
        val first = AnalyticsTradeCache(store, clock)
        first.update("login-a", "x", listOf(trade("a1", 2, 1_000_000)))
        first.update("login-b", "y", listOf(trade("b1", 2, 2_000_000)))
        // A cache that has not read the file yet still removes that login alone.
        val reopened = AnalyticsTradeCache(store, clock)
        reopened.clear("login-a")
        assertEquals(setOf("login-b"), reopened.rows.value.keys)
        assertEquals(listOf("b1"), reopened.rows.value["login-b"]?.get("y")?.map { it.id })
        val stored = store.read("analytics-trades-v1")!!
        assertTrue("login-b" in stored && "login-a" !in stored && "a1" !in stored)
        reopened.clear("never-cached")
        assertEquals(setOf("login-b"), AnalyticsTradeCache(store, clock).also { it.update("login-b", "y", emptyList()) }.rows.value.keys)
        // Forgetting every login needs no read first, and the file is emptied.
        val last = AnalyticsTradeCache(store, clock)
        last.clear()
        assertTrue(last.rows.value.isEmpty())
        assertEquals("{}", store.read("analytics-trades-v1"))
        last.update("login-b", "y", emptyList())
        assertTrue(last.rows.value.isEmpty(), "nothing is read back after the clear")
    }
}
