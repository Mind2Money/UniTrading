package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
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
        compose.onNodeWithTag("replay-buy").performScrollTo().performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
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
        compose.onNodeWithTag("replay-load").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay-begin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replay-begin").performClick()
        awaitReplay()
        compose.onNodeWithTag("replay-buy").performScrollTo().performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        assertTrue(broker.placed.isEmpty(), "Replay must never use the connected broker account")
    }

    @Test fun setupRestrictsReplaySymbolsAndUsesTheRequestedDates() {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        compose.setContent { TradingScreen(runtime) }
        compose.onNodeWithTag("open-historical-replay").performScrollTo().performClick()
        compose.onNodeWithTag("replay-range-start").performTextReplacement("2026-12-29")
        compose.onNodeWithTag("replay-range-end").performTextReplacement("2026-12-30")
        compose.onNodeWithTag("replay-select-OTHRZ6").performClick()
        compose.onNodeWithTag("replay-load").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay-begin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replay-begin").performClick()
        awaitReplay()
        compose.onNode(hasTestTag("chart-symbol") and hasAnyAncestor(hasTestTag("historical-replay"))).performClick()
        compose.onNodeWithTag("symbol-option-TESTZ6").assertExists()
        compose.onNodeWithTag("symbol-option-OTHRZ6").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay-chart-main").fetchSemanticsNodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.contains("OTHRZ6") } == true
        } }
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun replayAxisMenuRetainsSelectionsAndRightClickUsesLocalConfirmation() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0, quantityUnit = "张")
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M15, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), rawFrame = Timeframe.M1)) {} } }
        awaitReplay()
        compose.onNodeWithTag("replay-step-1").assertExists()
        compose.onNodeWithTag("replay-step-5").assertExists()
        compose.onNodeWithTag("replay-step-15").assertDoesNotExist()
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).performClick()
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).assertTextContains("✓", substring = true)
        compose.onNodeWithText("反转价格轴", substring = true).performClick()
        compose.onNodeWithText("反转价格轴", substring = true).assertTextContains("✓", substring = true)
        compose.onNodeWithText("常规", substring = true).performClick()
        val chart = compose.onNodeWithTag("trading-chart")
        val chartSize = chart.fetchSemanticsNode().size
        chart.performMouseInput {
            moveTo(Offset(chartSize.width * .7f, chartSize.height * .65f)); press(MouseButton.Secondary); release(MouseButton.Secondary)
        }
        compose.onNodeWithTag("plot-settings").assertDoesNotExist()
        compose.onNodeWithTag("plot-menu-BUY-MARKET").performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        compose.onNodeWithText("模拟持仓").performScrollTo().assertExists()
        compose.onNodeWithTag("replay-tab-1").performScrollTo().performClick()
        compose.onNodeWithText("交易表现").assertExists()
    }

    @Test fun rightClickCanSubmitDirectlyWhenConfirmationIsSkipped() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..3).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true)) {} } }
        awaitReplay()
        val chart = compose.onNodeWithTag("trading-chart")
        val chartSize = chart.fetchSemanticsNode().size
        chart.performMouseInput {
            moveTo(Offset(chartSize.width * .7f, chartSize.height * .65f)); press(MouseButton.Secondary); release(MouseButton.Secondary)
        }
        compose.onNodeWithTag("plot-menu-SELL-MARKET").performClick()
        compose.onNodeWithTag("replay-confirm-order").assertDoesNotExist()
        compose.onNodeWithText("模拟持仓").performScrollTo().assertExists()
    }

    @Test fun replayLayoutHasIndependentFramesAndSessionChoice() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        compose.onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
        compose.onNodeWithTag("chart-layout-TWO_COLUMNS").performClick()
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(2)
        compose.onNodeWithTag("replay-chart-split1").performTouchInput { click(center) }
        compose.onNodeWithTag("interval-inline-5").performScrollTo().performClick()
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 MES 1分钟")
        compose.onNodeWithTag("replay-chart-split1").assertContentDescriptionEquals("回放 MES 5分钟")
        compose.onNodeWithTag("chart-session-menu").performScrollTo().performClick()
        compose.onNodeWithTag("chart-session-REGULAR").performClick()
        compose.onNodeWithTag("chart-session-menu").assertTextContains("RTH", substring = true)
        compose.onNodeWithTag("replay-chart-main").performTouchInput { click(center) }
        compose.onNodeWithTag("chart-session-menu").assertTextContains("ETH", substring = true)
    }

    @Test fun missingFineHistoryIsRequestedWithoutClearingSimulatedPosition() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val coarse = (0..3).map { Candle(it * 300_000L, 10_000, 10_025, 9_975, 10_000) }
        val fine = (0..20).map { Candle(it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        var available by mutableStateOf(false)
        var revision by mutableLongStateOf(0)
        val requested = mutableListOf<Pair<String, Timeframe>>()
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M5, false,
            ChartTimeZone.SHANGHAI, coarse, emptyList(), skipConfirmation = true), revision,
            history = { _, frame -> if (available && frame == Timeframe.M1) fine else emptyList() },
            watch = { symbol, frame -> requested.add(symbol to frame) }) {} } }
        awaitReplay()
        compose.onNodeWithTag("replay-buy").performScrollTo().performClick()
        compose.onNodeWithText("模拟持仓").assertExists()
        compose.onNodeWithTag("interval-inline-1").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(("MES" to Timeframe.M1) in requested); available = true; revision++ }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("replay-step-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("模拟持仓").assertExists()
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 MES 1分钟")
    }

    @Test fun missingFiveMinuteHistoryCanRebaseAFifteenMinuteReplay() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val coarse = (0..3).map { Candle(it * 900_000L, 10_000, 10_025, 9_975, 10_000) }
        val fine = (0..20).map { Candle(it * 300_000L, 10_000, 10_025, 9_975, 10_000) }
        var available by mutableStateOf(false)
        var revision by mutableLongStateOf(0)
        val requested = mutableListOf<Pair<String, Timeframe>>()
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M15, false,
            ChartTimeZone.SHANGHAI, coarse, emptyList(), skipConfirmation = true), revision,
            history = { _, frame -> if (available && frame == Timeframe.M5) fine else emptyList() },
            watch = { symbol, frame -> requested.add(symbol to frame) }) {} } }
        awaitReplay()
        compose.onNodeWithTag("replay-buy").performScrollTo().performClick()
        compose.onNodeWithTag("interval-inline-5").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(("MES" to Timeframe.M5) in requested); available = true; revision++ }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("replay-step-5").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("模拟持仓").assertExists()
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 MES 5分钟")
    }
}
