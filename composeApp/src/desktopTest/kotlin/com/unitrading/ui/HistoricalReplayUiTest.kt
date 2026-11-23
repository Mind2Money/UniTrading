package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class HistoricalReplayUiTest {
    @get:Rule val compose = createComposeRule()
    private fun awaitReplay() {
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay-buy").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            val dialog = compose.onAllNodesWithTag("historical-replay").fetchSemanticsNodes().size
            val loading = compose.onAllNodesWithText("正在准备历史数据…").fetchSemanticsNodes().size
            val missing = compose.onAllNodesWithText("当前图表没有足够的历史 K 线，请等待数据加载后重试。").fetchSemanticsNodes().size
            throw AssertionError("Replay not ready: dialog=$dialog loading=$loading missing=$missing", error)
        }
    }

    @Test fun replayTradesOnlyLocallyAndAppliesStopOnNextBar() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0, quantityUnit = "张")
        val bars = listOf(Candle(1_700_000_000_000, 10_000, 10_025, 9_975, 10_000),
            Candle(1_700_000_060_000, 10_000, 10_075, 9_925, 10_000),
            Candle(1_700_000_120_000, 90_000, 90_025, 89_975, 90_000))
        compose.setContent { TradingTheme { Box(Modifier.fillMaxSize()) { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false, ChartTimeZone.SHANGHAI, bars, emptyList())) {} } } }
        awaitReplay()
        val chart = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
        assertTrue(chart.getValue("high").toDouble() < 50_000, "Future bars must not enter the visible chart scale")
        compose.onNodeWithTag("replay-stop").performTextInput("99.50")
        compose.onNodeWithTag("replay-target").performTextInput("100.50")
        compose.onNodeWithTag("replay-buy").performClick()
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 0 笔", substring = true)
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 1 笔", substring = true)
        compose.onNodeWithTag("replay-realized").assertTextContains("-2.50", substring = true)
    }

    @Test fun openingReplayFromConnectedWorkspaceNeverSendsBrokerOrders() {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { TradingScreen(runtime) }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.onNodeWithTag("open-historical-replay").performScrollTo().assertIsDisplayed().performClick()
        awaitReplay()
        compose.onNodeWithTag("replay-buy").performClick()
        compose.onNodeWithTag("replay-next").performClick()
        assertTrue(broker.placed.isEmpty(), "Replay must never use the connected broker account")
    }
}
