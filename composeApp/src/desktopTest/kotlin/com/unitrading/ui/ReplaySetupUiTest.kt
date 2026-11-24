package com.unitrading.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ReplayHistoryCache
import com.unitrading.broker.*
import com.unitrading.chart.IntervalOptions
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class ReplaySetupUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun setupOmitsOnlyTheRequestedExplanations() {
        val broker = OfflineOrderBroker()
        compose.setContent { TradingTheme {
            ReplaySetupDialog(broker, ReplayHistoryCache(MemorySnapshotStore()), false, broker.instruments, broker.spec,
                ChartTimeZone.SHANGHAI, broker.time, Timeframe.M5, IntervalOptions(), false, onDismiss = {}, onReady = {})
        } }
        compose.onNodeWithText("分钟行情按图表时区读取，日线使用券商交易日。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("触及日亏损后，本次考核失败。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("触及日亏损后暂停开仓并撤销挂单，下一交易日恢复。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("交易日按芝加哥时间 17:00 切换", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("replay-overnight-allow").performScrollTo().assertExists()
        compose.onNodeWithTag("replay-use-cache").performScrollTo().assertExists()
    }
    @Test fun failedLoadStaysOnSetupAndAllowsRetry() {
        val base = OfflineOrderBroker()
        val broker = object : BrokerPlugin by base {
            override suspend fun historicalRange(symbol: String, timeframe: Timeframe, startMs: Long, endMs: Long) =
                HistoricalRangeResult(emptyList())
        }
        val ready = AtomicBoolean(false)
        compose.setContent { TradingTheme {
            ReplaySetupDialog(broker, ReplayHistoryCache(MemorySnapshotStore()), false, broker.instruments, base.spec,
                ChartTimeZone.SHANGHAI, base.time, Timeframe.M5, IntervalOptions(), false, onDismiss = {}, onReady = { ready.set(true) })
        } }
        compose.onNodeWithTag("replay-begin").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay-load-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replay-load-error").assertTextContains("没有足够的", substring = true)
        compose.onNodeWithTag("replay-begin").assertIsEnabled()
        compose.onNodeWithTag("replay-setup").assertExists()
        assertFalse(ready.get())
    }
    @Test fun cancellingTheLoadNeverOpensReplay() {
        val base = OfflineOrderBroker()
        val started = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val ready = AtomicBoolean(false)
        val dismissed = AtomicBoolean(false)
        val broker = object : BrokerPlugin by base {
            override suspend fun historicalRange(symbol: String, timeframe: Timeframe, startMs: Long, endMs: Long): HistoricalRangeResult {
                started.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
        }
        compose.setContent { TradingTheme {
            var open by remember { mutableStateOf(true) }
            if (open) ReplaySetupDialog(broker, ReplayHistoryCache(MemorySnapshotStore()), false, broker.instruments, base.spec,
                ChartTimeZone.SHANGHAI, base.time, Timeframe.M5, IntervalOptions(), false,
                onDismiss = { dismissed.set(true); open = false }, onReady = { ready.set(true) })
        } }
        compose.onNodeWithTag("replay-begin").performScrollTo().performClick()
        compose.waitUntil(10_000) { started.get() > 0 }
        compose.onNodeWithText("取消加载").performScrollTo().performClick()
        compose.waitUntil(10_000) { dismissed.get() && cancelled.get() > 0 }
        compose.onNodeWithTag("replay-setup").assertDoesNotExist()
        assertFalse(ready.get())
    }
}
