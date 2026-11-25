package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    @Test fun softStopFlattensAndBlocksOpeningWithoutFailing() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.SOFT, records)
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithTag("replay-realized").assertTextContains("-5.00", substring = true)
        compose.onNodeWithTag("replay-floating").assertTextContains("0.00", substring = true)
        val buttons = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
        assertFalse(buttons.keys.any { it.startsWith("平仓 ") })
        compose.waitUntil(5_000) { records.list().singleOrNull()?.trades?.size == 1 }
        compose.runOnIdle { assertEquals("每日软止损自动平仓", records.list().single().trades.single().reason) }
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("已触及每日软止损，下一交易日前禁止开仓").assertExists()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
    }

    @Test fun softStopRemainsLockedUntilTheNextChicagoTradingDay() {
        val start = Instant.parse("2026-11-23T22:57:00Z").toEpochMilli()
        val timeline = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_000, 10_500, 9_900, 10_500),
            Candle(start + 120_000, 10_500, 10_525, 10_475, 10_500),
            Candle(Instant.parse("2026-11-23T23:01:00Z").toEpochMilli(), 10_500, 10_525, 10_475, 10_500))
        val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 50_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.SOFT, consistencyPercent = 40)
        val records = ReplayRecords(MemorySnapshotStore())
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, timeline, emptyList(), skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-realized").assertTextContains("-5.00", substring = true)
        compose.onNodeWithTag("replay-consistency-status").assertTextContains("每日", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
    }

    @Test fun softStopPreventsALaterTotalLossOnTheSameCandle() {
        val config = ReplayRiskConfig(50_000_000_000, 20_000_000, 50_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.SOFT)
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true, riskConfig = config)) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithTag("replay-realized").assertTextContains("-5.00", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aGapBeyondTheTotalLossStillFailsTheEvaluation() {
        val gap = listOf(Candle(1_700_000_000_000, 10_000, 10_025, 9_975, 10_000),
            Candle(1_700_000_060_000, 9_500, 10_500, 9_475, 10_500))
        val config = ReplayRiskConfig(50_000_000_000, 20_000_000, 50_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.SOFT)
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, gap, emptyList(), skipConfirmation = true, riskConfig = config)) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithText("回放考核失败").assertIsDisplayed()
        compose.onNodeWithText("账户触及最大总亏损。").assertExists()
    }

    private fun openHundredMclLots(lowAfterEntry: Long, addPending: Boolean = false, adverseAddition: Boolean = false,
        secondAdverse: Boolean = false): ReplayRecords {
        val start = Instant.parse("2026-11-23T22:57:00Z").toEpochMilli()
        val mcl = Instrument("@MCL", "Micro Crude Oil", "期货", 2, tickSize = 1, tickValueMicrosPerLot = 1_000_000,
            minLotsHundredths = 100, maxLotsHundredths = 10_000, lotStepHundredths = 100, quantityDecimals = 0,
            isContinuous = true, tradeSymbol = "MCLF7")
        val timeline = listOf(Candle(start, 8_247, 8_248, 8_246, 8_247),
            Candle(start + 60_000, 8_247, 8_274, lowAfterEntry, 8_274),
            Candle(start + 120_000, 8_274, 8_275, 8_273, 8_274),
            Candle(Instant.parse("2026-11-23T23:01:00Z").toEpochMilli(), 8_274, 8_275, 8_273, 8_274))
        val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 3_000_000_000, ReplayDrawdown.EOD,
            maxDailyLossMicros = 1_000_000_000, dailyLossMode = ReplayDailyLoss.SOFT, consistencyPercent = 40)
        val records = ReplayRecords(MemorySnapshotStore())
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(mcl, Timeframe.M5, false,
            ChartTimeZone.SHANGHAI, timeline, emptyList(), rawFrame = Timeframe.M1, skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("order-quantity").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        if (addPending || adverseAddition) {
            compose.onNodeWithTag("order-kind-LIMIT").performScrollTo().performClick()
            compose.onNodeWithTag(if (adverseAddition) "order-side-BUY" else "order-side-SELL").performScrollTo().performClick()
            compose.onNodeWithTag("order-quantity").performScrollTo().performTextReplacement(if (adverseAddition) "100" else "1")
            compose.onNodeWithTag("order-entry").performScrollTo().performTextReplacement(if (adverseAddition) "82.42" else "82.48")
            compose.onNodeWithTag("review-order").performScrollTo().performClick()
            val buttons = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
            assertTrue(buttons.keys.any { it.startsWith("撤单 ") })
            if (secondAdverse) {
                compose.onNodeWithTag("order-entry").performScrollTo().performTextReplacement("82.37")
                compose.onNodeWithTag("review-order").performScrollTo().performClick()
            }
        }
        compose.onNodeWithTag("replay-next").performClick()
        return records
    }

    @Test fun mclConsistencyFlattensAtTwelveHundredAndBlocksNewOrders() {
        val records = openHundredMclLots(8_246)
        compose.onNodeWithTag("replay-realized").assertTextContains("+1200.00", substring = true)
        compose.onNodeWithTag("replay-floating").assertTextContains("0.00", substring = true)
        compose.onNodeWithTag("replay-consistency-status").assertTextContains("已触及", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        val buttons = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
        assertFalse(buttons.keys.any { it.startsWith("平仓 ") })
        compose.waitUntil(5_000) { records.list().singleOrNull()?.trades?.size == 1 }
        compose.runOnIdle { assertEquals("一致性自动平仓", records.list().single().trades.single().reason) }
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
    }

    @Test fun mclSoftLossFlattensBeforeTheSameBarRalliesPastConsistency() {
        val records = openHundredMclLots(8_237, addPending = true)
        compose.onNodeWithTag("replay-realized").assertTextContains("-1000.00", substring = true)
        compose.onNodeWithTag("replay-floating").assertTextContains("0.00", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        val buttons = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
        assertFalse(buttons.keys.any { it.startsWith("平仓 ") })
        assertFalse(buttons.keys.any { it.startsWith("撤单 ") })
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .65f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-menu-BUY-MARKET").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        chart.fetchSemanticsNode().config[OrderTagButtons]["提交买入订单"]?.let { action -> chart.performMouseInput { click(action.center) } }
        assertFalse(chart.fetchSemanticsNode().config[OrderTagButtons].keys.any { it.startsWith("平仓 ") })
        compose.waitUntil(5_000) { records.list().singleOrNull()?.trades?.size == 1 }
        compose.runOnIdle { assertEquals("每日软止损自动平仓", records.list().single().trades.single().reason) }
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
    }

    @Test fun mclSoftStopRecalculatesAfterAPendingAdditionFillsFirst() {
        val records = openHundredMclLots(8_235, adverseAddition = true, secondAdverse = true)
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithTag("replay-realized").assertTextContains("-1100.00", substring = true)
        compose.onNodeWithTag("replay-floating").assertTextContains("0.00", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.waitUntil(5_000) { records.list().singleOrNull()?.trades?.size == 2 }
        compose.runOnIdle { assertTrue(records.list().single().trades.all { it.reason == "每日软止损自动平仓" }) }
    }

    @Test fun pendingEntryAtTheOpenHitsConsistencyBeforeALaterTakeProfit() {
        val start = Instant.parse("2026-11-23T22:57:00Z").toEpochMilli()
        val timeline = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 9_900, 10_400, 9_875, 9_900))
        val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 50_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.SOFT, consistencyPercent = 40)
        val records = ReplayRecords(MemorySnapshotStore())
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, timeline, emptyList(), skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("order-kind-LIMIT").performScrollTo().performClick()
        compose.onNodeWithTag("order-entry").performScrollTo().performTextReplacement("99.00")
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp").performScrollTo().performTextReplacement("103.50")
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-realized").assertTextContains("+20.00", substring = true)
        compose.onNodeWithTag("replay-consistency-status").assertTextContains("已触及", substring = true)
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.waitUntil(5_000) { records.list().singleOrNull()?.trades?.size == 1 }
        compose.runOnIdle { assertEquals("一致性自动平仓", records.list().single().trades.single().reason) }
    }
    @Test fun noDailyStopAllowsTradingWhenTheDailyAmountIsCrossed() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(ReplayDailyLoss.NONE, records)
        compose.onNodeWithText("回放考核失败").assertDoesNotExist()
        compose.onNodeWithText("最大日亏损 · 无止损").assertExists()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
    }
    @Test fun noDailyStopStillFailsWhenTheTotalDrawdownIsHit() {
        val records = ReplayRecords(MemorySnapshotStore())
        val config = ReplayRiskConfig(50_000_000_000, 5_000_000, 3_000_000_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.NONE)
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true, riskConfig = config), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithText("回放考核失败").assertIsDisplayed()
        compose.onNodeWithText("账户触及最大总亏损。").assertExists()
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
