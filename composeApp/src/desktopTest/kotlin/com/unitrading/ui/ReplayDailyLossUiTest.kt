package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ReplayRecords
import com.unitrading.broker.*
import com.unitrading.chart.ReplayDailyLoss
import com.unitrading.chart.ReplayDrawdown
import com.unitrading.chart.ReplayRiskConfig
import com.unitrading.chart.OrderTagButtons
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReplayDailyLossUiTest {
    @get:Rule val compose = createComposeRule()
    private fun closePositionOnChart() {
        val chart = compose.onNodeWithTag("trading-chart")
        val at = chart.fetchSemanticsNode().config[OrderTagButtons].entries.first { it.key.startsWith("平仓 ") }.value.center
        chart.performMouseInput { click(at) }
    }
    private val instrument = Instrument("MES", "MES future", "期货", 2, tickSize = 25,
        tickValueMicrosPerLot = 1_250_000, minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
    private val bars = listOf(
        Candle(1_700_000_000_000, 10_000, 10_025, 9_975, 10_000),
        Candle(1_700_000_060_000, 10_000, 10_025, 9_000, 10_000),
        Candle(1_700_000_120_000, 10_000, 10_025, 9_975, 10_000))
    private fun open(mode: ReplayDailyLoss, records: ReplayRecords) {
        val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 3_000_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = mode)
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
    }
    @Test fun hardStopCanDiscardTheSession() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.HARD, records)
        compose.onNodeWithText("回放考核失败").assertIsDisplayed()
        compose.onNodeWithTag("replay-failure-discard").performClick()
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, records.list().size) }
    }
    @Test fun hardStopCanSaveTheSession() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.HARD, records)
        compose.onNodeWithTag("replay-failure-save").performClick()
        compose.runOnIdle { assertEquals(1, records.list().size) }
    }
    @Test fun softStopBlocksOpeningWithoutFailing() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.SOFT, records)
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("已触及每日软止损，下一交易日前禁止开仓").assertExists()
    }
    @Test fun noDailyStopAllowsTradingWhenTheDailyAmountIsCrossed() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.NONE, records)
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithText("最大日亏损 · 无止损").assertExists()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
    }
    @Test fun savedFailureContainsClosedTradesAndCanBeImported() {
        val records = ReplayRecords(MemorySnapshotStore())
        val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 3_000_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.HARD)
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        closePositionOnChart()
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-failure-save").performClick()
        compose.runOnIdle { assertEquals(1, records.list().single().trades.size) }
        compose.onNodeWithTag("replay-tab-1").performClick()
        compose.onNodeWithTag("replay-records-picker").performClick()
        compose.onNodeWithText("MES · 1 笔", substring = true).performClick()
        compose.onNodeWithTag("replay-dashboard").assertExists()
        compose.onNodeWithTag("replay-dashboard").performScrollToNode(hasTestTag("replay-report-trade-count"))
        compose.onNodeWithTag("replay-report-trade-count").assertTextContains("1 笔", substring = true)
    }

}
