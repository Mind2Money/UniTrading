package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** The closed trades cached for the analytics page: what the page makes of them, and every way they are deleted. */
class AnalyticsCacheUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val now = Clock.System.now()
    private fun trade(id: String, daysAgo: Int, pnl: Long) =
        ClosedTrade(id, "p", "TESTZ6", Side.BUY, 100, 550000, 550100, 0, (now - daysAgo.days).toEpochMilliseconds(), pnl, "fixture")
    private fun stored() = store.read("analytics-trades-v1").orEmpty()

    /** One saved Tradovate login that has expired: it is listed and can be forgotten, and restoring it connects nowhere. */
    private class SavedLogins : CredentialStore {
        var payload: String? = """{"sessions":[{"environment":"DEMO","accessToken":"synthetic-test-token-1234567890","mdAccessToken":null,"userId":42,"userName":"test","expiresAtMs":1}]}"""
        override suspend fun read() = payload
        override suspend fun write(value: String) { payload = value }
        override suspend fun clear() { payload = null }
    }

    /** What the one account of [remote] reports; a test changes its closed trades here. */
    private val snapshot = MutableStateFlow(AccountSnapshot("100", 25_000_000_000))
    private fun remote(): BrokerPlugin {
        val offline = OfflineOrderBroker()
        return object : BrokerPlugin by offline {
            override fun openSession(accountId: String): AccountSession =
                object : AccountSession by offline.openSession(accountId) { override val state = snapshot }
        }
    }
    private fun render(runtime: TradingRuntime) =
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }

    /** Trades cached by an earlier run for the saved login. */
    private fun seed(runtime: TradingRuntime) {
        runBlocking { runtime.analyticsCache.update("tradovate-demo-42", "1001", listOf(trade("cached", 3, 5_000_000))) }
        assertTrue("cached" in stored())
    }
    private fun forgetAndExpectNothingCached(runtime: TradingRuntime) {
        compose.waitUntil(5_000) { runtime.savedLogins.savedCount.value == 1 }
        compose.onNodeWithTag("forget-saved-logins").performScrollTo().performClick()
        compose.onNodeWithText("本机缓存的交易分析平仓记录", substring = true).assertExists("the confirmation says what else is deleted")
        compose.onNodeWithText("确认清除").performClick()
        compose.waitUntil(5_000) { runtime.savedLogins.savedCount.value == 0 && runtime.analyticsCache.rows.value.isEmpty() && stored() == "{}" }
    }

    @Test fun forgettingSavedLoginsOnTheWelcomePageDeletesTheCachedTrades() {
        val runtime = TradingRuntime(store, credentialStore = SavedLogins())
        seed(runtime)
        render(runtime)
        compose.onNodeWithTag("welcome-login").assertExists()
        forgetAndExpectNothingCached(runtime)
        compose.onNodeWithTag("welcome-login").assertExists()
    }

    /** The controls leave the welcome page as soon as the logins are gone; the cache is still cleared once a write under way has finished. */
    @Test fun forgettingWaitsForACacheWriteUnderWayAfterItsControlsHaveLeftThePage() {
        val hold = AtomicBoolean(false)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : SnapshotStore {
            override fun read(key: String) = store.read(key)
            override fun write(key: String, value: String) {
                if (key == "analytics-trades-v1" && hold.compareAndSet(true, false)) { writing.countDown(); release.await() }
                store.write(key, value)
            }
        }
        val runtime = TradingRuntime(slow, credentialStore = SavedLogins())
        seed(runtime)
        render(runtime)
        compose.waitUntil(5_000) { runtime.savedLogins.savedCount.value == 1 }
        hold.set(true)
        val update = thread(isDaemon = true) { runBlocking { runtime.analyticsCache.update("tradovate-demo-42", "1001", listOf(trade("newer", 1, 1_000_000))) } }
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("forget-saved-logins").performScrollTo().performClick()
            compose.onNodeWithText("确认清除").performClick()
            compose.waitUntil(5_000) { runtime.savedLogins.savedCount.value == 0 }
            compose.onNodeWithTag("forget-saved-logins").assertDoesNotExist()
        } finally { release.countDown() }
        update.join()
        compose.waitUntil(5_000) { runtime.analyticsCache.rows.value.isEmpty() && stored() == "{}" }
    }

    @Test fun forgettingSavedLoginsInSettingsDeletesTheCachedTrades() {
        val runtime = TradingRuntime(store, credentialStore = SavedLogins(), includeDemoBrokers = true).apply { paused.value = true }
        seed(runtime)
        render(runtime)
        compose.onNodeWithTag("open-settings").performClick()
        forgetAndExpectNothingCached(runtime)
    }

    @Test fun forgettingSavedLoginsOnTheEmptyCatalogPageDeletesTheCachedTrades() {
        val offline = OfflineOrderBroker()
        val empty = object : BrokerPlugin by offline { override val catalog = MutableStateFlow(BrokerCatalog(emptyList(), emptyList())) }
        val runtime = TradingRuntime(store, listOf(empty), credentialStore = SavedLogins())
        seed(runtime)
        render(runtime)
        compose.onNodeWithText("查询合约").assertExists()
        forgetAndExpectNothingCached(runtime)
        compose.onNodeWithText("查询合约").assertExists("the connection that was not a saved login stays")
    }

    @Test fun disconnectingALoginDeletesItsCachedTradesAndKeepsThoseOfOtherLogins() {
        val live = listOf(trade("live", 1, 5_000_000))
        snapshot.value = snapshot.value.copy(trades = live)
        val runtime = TradingRuntime(store, listOf(remote()))
        runBlocking { runtime.analyticsCache.update("another-login", "9", listOf(trade("elsewhere", 2, 1_000_000))) }
        render(runtime)
        // The screen records the selected account's closed trades itself; the analytics page was never opened.
        compose.waitUntil(5_000) { runtime.analyticsCache.rows.value["offline-orders"]?.get("100") == live && "live" in stored() }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-disconnect").performScrollTo().performClick()
        compose.waitUntil(5_000) { runtime.analyticsCache.rows.value.keys == setOf("another-login") && "offline-orders" !in stored() }
        assertTrue("elsewhere" in stored())
        compose.onNodeWithTag("welcome-login").assertExists()
    }

    /** The session of a login that was just removed can still report a change before the screen lets go of it; that is not cached any more. */
    @Test fun aLoginThatLeftTheRegistryIsNotWrittenByWhatItsSessionStillReports() {
        val live = listOf(trade("live", 1, 5_000_000))
        snapshot.value = snapshot.value.copy(trades = live)
        val runtime = TradingRuntime(store, listOf(remote()))
        render(runtime)
        compose.waitUntil(5_000) { "live" in stored() }
        compose.runOnIdle {
            runtime.registry.unregister("offline-orders")
            snapshot.value = snapshot.value.copy(trades = live + trade("late", 0, 1_000_000))
        }
        compose.waitForIdle()
        assertEquals(live, runtime.analyticsCache.rows.value["offline-orders"]?.get("100"))
        assertFalse("late" in stored())
    }

    @Test fun analyticsCountsCachedAndBrokerTradesOnceAndNamesTheDayTheCacheStarts() {
        val cachedOnly = trade("cached-only", 10, 1_000_000)
        snapshot.value = snapshot.value.copy(trades = listOf(trade("fill-A-C", 2, 5_000_000)))
        val runtime = TradingRuntime(store, listOf(remote()))
        // From an earlier run: a trade older than what the broker returns now, and one the broker has since paired under another id.
        runBlocking { runtime.analyticsCache.update("offline-orders", "100", listOf(cachedOnly, trade("fill-B-C", 2, -5_000_000))) }
        render(runtime)
        compose.onNodeWithTag("toggle-account-overview").performClick()
        compose.onNodeWithTag("account-tab-2").performClick()
        compose.onNodeWithTag("analytics-range-all").performClick()
        val day = cachedOnly.closeDate(TimeZone.of("America/Chicago"))
        compose.onNodeWithTag("analytics-coverage").assertTextContains("券商已同步记录 + 本机缓存（自 $day 起，仅含本机运行并选中该子账户时同步到的记录）", substring = true)
        compose.onNodeWithTag("account-analytics").performScrollToNode(hasText("盈利 2 · 亏损 0 · 持平 0 · 待确认 0"))
        compose.onNodeWithText("盈利 2 · 亏损 0 · 持平 0 · 待确认 0").assertExists()
        // The stored cache is reconciled as well.
        compose.waitUntil(5_000) {
            runtime.analyticsCache.rows.value["offline-orders"]?.get("100")?.map { it.id } == listOf("cached-only", "fill-A-C") && "fill-B-C" !in stored()
        }
    }
}
