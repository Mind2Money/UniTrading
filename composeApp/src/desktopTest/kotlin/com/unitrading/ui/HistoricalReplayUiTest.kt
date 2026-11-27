package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import com.unitrading.app.ThemeSettings
import com.unitrading.app.ThemePreset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.ReplayRiskConfig
import com.unitrading.chart.ReplayDrawdown
import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.OrderTagButtons
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HistoricalReplayUiTest {
    @get:Rule val compose = createComposeRule()
    private fun chartButtons() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
    private fun clickChartButton(hint: String) {
        val at = assertNotNull(chartButtons()[hint], "Missing chart button $hint; available: ${chartButtons().keys}").center
        compose.onNodeWithTag("trading-chart").performMouseInput { click(at) }
    }
    private fun hasPosition() = chartButtons().keys.any { it.startsWith("平仓 ") }
    private fun closePosition() = clickChartButton(chartButtons().keys.first { it.startsWith("平仓 ") })
    private fun awaitReplay() {
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            val dialog = compose.onAllNodesWithTag("historical-replay").fetchSemanticsNodes().size
            val loading = compose.onAllNodesWithText("正在准备历史数据…").fetchSemanticsNodes().size
            val missing = compose.onAllNodesWithText("当前图表没有足够的历史 K 线，请等待数据加载后重试。").fetchSemanticsNodes().size
            throw AssertionError("Replay not ready: dialog=$dialog loading=$loading missing=$missing", error)
        }
    }

    @Test fun replayIntervalsShareLiveSettingsWithoutRestartingTheSimulation() {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        compose.setContent { TradingScreen(runtime) }
        compose.onNodeWithTag("open-historical-replay").performScrollTo().performClick()
        compose.onNodeWithTag("replay-begin").performScrollTo().performClick()
        awaitReplay()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-start-controls").assertDoesNotExist()
        val replayMenu = hasTestTag("interval-menu") and hasAnyAncestor(hasTestTag("historical-replay"))
        compose.onNode(replayMenu).performClick()
        compose.onNodeWithTag("interval-favorite-240").performScrollTo().performClick()
        compose.waitUntil(5_000) { Timeframe.ofMinutes(240) in runtime.chartPreferences.preferences.value.favorites }
        compose.onNodeWithTag("interval-favorite-240").assertContentDescriptionEquals("取消收藏 4小时")
        compose.onNodeWithTag("interval-favorite-240").performClick()
        compose.waitUntil(5_000) { Timeframe.ofMinutes(240) !in runtime.chartPreferences.preferences.value.favorites }
        compose.onNodeWithTag("interval-favorite-240").assertContentDescriptionEquals("收藏 4小时")
        compose.onNodeWithTag("interval-custom-count").performScrollTo().performTextReplacement("7")
        compose.onNodeWithTag("interval-custom-add").performClick()
        compose.waitUntil(5_000) { Timeframe.ofMinutes(7) in runtime.chartPreferences.preferences.value.custom }
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 ${broker.spec.symbol} 7分钟")
        compose.onNodeWithTag("replay-start-controls").assertDoesNotExist()
        compose.onNode(replayMenu).performClick()
        compose.onNodeWithTag("interval-option-7").assertExists()
        compose.onNodeWithTag("interval-favorite-7").performScrollTo().performClick()
        compose.waitUntil(5_000) { Timeframe.ofMinutes(7) in runtime.chartPreferences.preferences.value.favorites }
        assertTrue(broker.placed.isEmpty(), "Changing replay intervals must not send an order")
    }

    @Test fun replayOrderUsesAvailableHeightAndKeepsTheActionsOutsideTheScrollableFields() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        val panel = compose.onNodeWithTag("replay-side-panel").fetchSemanticsNode().boundsInRoot
        val ticket = compose.onNodeWithTag("replay-order-ticket").fetchSemanticsNode().boundsInRoot
        val fields = compose.onNodeWithTag("order-fields").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("order-actions").fetchSemanticsNode().boundsInRoot
        assertTrue(ticket.bottom > panel.bottom - 30f, "The ticket should use the available panel height")
        assertTrue(fields.bottom <= actions.top + 1f, "Scrolling fields and the order actions must not overlap")
        compose.onNodeWithTag("order-kind-LIMIT").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("reference-protection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        val folder = File(System.getenv("GITHUB_WORKSPACE") ?: ".", "composeApp/build/replay-consistency-ui").also { it.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("replay-side-panel").captureToImage().toAwtImage(), "png", File(folder, "replay-order.png"))
    }

    @Test fun replayControlBarKeepsPlaybackAndDateEntryUsable() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme(ThemeSettings(preset = ThemePreset.LIGHT)) { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        val folder = File(System.getenv("GITHUB_WORKSPACE") ?: ".", "composeApp/build/replay-consistency-ui").also { it.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("historical-replay").captureToImage().toAwtImage(), "png", File(folder, "replay-light.png"))
        compose.onNodeWithTag("replay-speed-2").performClick()
        compose.onNodeWithTag("replay-start-time").performTextInput("2023-11-15 06:14")
        compose.onNodeWithTag("replay-start-time").assertTextContains("2023-11-15 06:14")
        compose.onNodeWithTag("replay-start-calendar").assertExists()
        compose.onNodeWithTag("replay-start-locate").assertExists()
        compose.onNodeWithTag("replay-next").performScrollTo().performClick()
        compose.onNodeWithTag("replay-start-controls").assertDoesNotExist()
    }

    @Test fun continuousHistoryNoticeIsHiddenButErrorsRemainVisible() {
        assertEquals(null, visibleReplayHistoryError("@MCL：券商连续历史的拼接或复权规则未确认"))
        assertEquals("读取历史失败", visibleReplayHistoryError("@MCL：券商连续历史的拼接或复权规则未确认；读取历史失败"))
        assertEquals("读取历史失败", visibleReplayHistoryError("读取历史失败"))
    }

    @Test fun replayObjectTreeActuallyHidesShowsAndDeletesIndicators() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, listOf(IndicatorConfig("replay-ma", IndicatorKind.MA)))) {} } }
        awaitReplay()
        compose.onNodeWithTag("order-estimates-toggle").assertDoesNotExist()
        compose.onNodeWithTag("order-estimates").assertDoesNotExist()
        compose.onNodeWithText("按当前报价估算，成交价可能变化。").assertDoesNotExist()
        val chart = compose.onNodeWithTag("trading-chart")
        val chartSize = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(chartSize.width * .7f, chartSize.height * .7f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-objects").performScrollTo().performClick()
        compose.onNodeWithTag("object-indicator-visibility-replay-ma").performScrollTo().assertTextContains("隐藏").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("object-indicator-visibility-replay-ma") and hasText("显示")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-indicator-visibility-replay-ma").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("object-indicator-visibility-replay-ma") and hasText("隐藏")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-layer-menu-indicator:replay-ma").performClick()
        compose.onNodeWithTag("object-layer-front-indicator:replay-ma").performClick()
        compose.onNodeWithTag("object-layer-menu-indicator:replay-ma").performClick()
        compose.onNodeWithTag("object-layer-back-indicator:replay-ma").performClick()
        compose.onNodeWithTag("object-indicator-delete-replay-ma").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("object-indicator-replay-ma").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("指标 · 0").assertExists()
    }

    @Test fun replayObjectTreeHidesAndDeletesDrawings() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..30).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        compose.onNodeWithTag("drawing-group-TREND").performClick()
        val chart = compose.onNodeWithTag("trading-chart")
        val chartSize = chart.fetchSemanticsNode().size
        chart.performMouseInput { click(Offset(chartSize.width * .3f, chartSize.height * .4f)); click(Offset(chartSize.width * .5f, chartSize.height * .5f)) }
        chart.performMouseInput { moveTo(Offset(chartSize.width * .7f, chartSize.height * .75f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-objects").performScrollTo().performClick()
        compose.onNodeWithText("绘图 · 1").assertExists()
        fun item(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("chart-object-tree")))
        item("隐藏").performClick()
        item("显示").assertExists().performClick()
        item("删除").performClick()
        compose.onNodeWithText("绘图 · 0").assertExists()
    }

    @Test fun disallowedOvernightClosesAtTheCutoff() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val start = java.time.Instant.parse("2026-01-05T21:57:00Z").toEpochMilli()
        val bars = (0..3).map { Candle(start + it * 60_000, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true,
            riskConfig = ReplayRiskConfig(50_000_000_000L, 2_000_000_000L, 3_000_000_000L, ReplayDrawdown.STATIC, allowOvernight = false))) {} } }
        awaitReplay()
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-trade-count").performScrollTo().assertTextContains("成交 1 笔", substring = true)
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.onNodeWithText("当前处于禁止过夜限制时段").assertExists()
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
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl").performTextClearance()
        compose.onNodeWithTag("order-sl").performTextInput("99.50")
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp").performTextClearance()
        compose.onNodeWithTag("order-tp").performTextInput("100.50")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 0 笔", substring = true)
        fun chartHistory() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringAfter(";history:").substringBefore(';')
        assertTrue(chartHistory() == "1")
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 1 笔", substring = true)
        compose.onNodeWithTag("replay-realized").assertTextContains("-2.50", substring = true)
        compose.onNodeWithTag("replay-daily-pnl").assertTextContains("-2.50", substring = true)
        assertTrue(chartHistory() == "1")
        compose.onNodeWithTag("replay-history-toggle").performClick()
        assertTrue(chartHistory() == "0")
        compose.onNodeWithTag("replay-history-toggle").performClick()
        assertTrue(chartHistory() == "1")
    }

    @Test fun openingReplayFromConnectedWorkspaceNeverSendsBrokerOrders() {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { TradingScreen(runtime) }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.onNodeWithTag("open-historical-replay").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("replay-begin").performScrollTo().assertTextContains("开始回放").performClick()
        awaitReplay()
        compose.onNodeWithTag("replay-setup").assertDoesNotExist()
        compose.onNodeWithTag("replay-header-logo").assertExists()
        compose.onNodeWithTag("replay-close").assertContentDescriptionEquals("关闭回放")
        compose.onNodeWithTag("replay-controls").assert(hasAnyDescendant(hasTestTag("replay-play")))
        compose.onNodeWithTag("replay-controls").assert(hasAnyDescendant(hasTestTag("replay-start-controls")))
        compose.onNodeWithTag("replay-account-strip").assertExists()
        val controlsBottom = compose.onNodeWithTag("replay-controls").fetchSemanticsNode().boundsInRoot.bottom
        val stripTop = compose.onNodeWithTag("replay-account-strip").fetchSemanticsNode().boundsInRoot.top
        assertTrue(stripTop >= controlsBottom, "Account status must be below the replay controls")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        assertTrue(broker.placed.isEmpty(), "Replay must never use the connected broker account")
    }

    @Test fun setupRestrictsReplaySymbolsAndUsesTheRequestedDates() {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        compose.setContent { TradingScreen(runtime) }
        compose.onNodeWithTag("open-historical-replay").performScrollTo().performClick()
        compose.onNodeWithTag("replay-range-start").performScrollTo().performClick()
        compose.onNodeWithTag("replay-calendar-next-month").performClick()
        compose.onNodeWithTag("replay-calendar-2026-12-29").performClick()
        compose.onNodeWithTag("replay-range-end").performScrollTo().performClick()
        compose.onNodeWithTag("replay-calendar-2026-12-30").performClick()
        compose.onNode(hasTestTag("interval-menu") and hasAnyAncestor(hasTestTag("replay-setup"))).assertDoesNotExist()
        compose.onNodeWithTag("replay-rule-row").performScrollTo().assertExists()
        val ruleY = listOf("replay-daily-soft", "replay-daily-hard", "replay-drawdown-STATIC", "replay-drawdown-EOD", "replay-drawdown-TDD")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(ruleY.max() - ruleY.min() < 2f, "Daily loss and drawdown choices should share one row")
        compose.onNodeWithTag("replay-overnight-allow").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("replay-overnight-cutoff").assertExists()
        compose.onNodeWithTag("replay-overnight-allow").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("replay-use-cache").performScrollTo().assertIsOn().performClick().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("replay-risk-row").performScrollTo().assertExists()
        val y = listOf("replay-target-amount", "replay-daily-loss", "replay-total-loss", "replay-consistency").map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(y.max() - y.min() < 2f, "Risk fields should appear in one row on desktop")
        compose.onNodeWithTag("replay-assets").performScrollTo().performClick()
        compose.onNodeWithTag("replay-assets-panel").assertExists()
        compose.onNodeWithTag("replay-select-OTHRZ6").performScrollTo().performClick()
        compose.onNodeWithTag("replay-begin").performScrollTo().assertTextContains("开始回放").performClick()
        awaitReplay()
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 TESTZ6 5分钟")
        compose.onNode(hasTestTag("chart-layers") and hasAnyAncestor(hasTestTag("historical-replay"))).assertDoesNotExist()
        compose.onNodeWithTag("replay-history-toggle").assertExists()
        compose.onNode(hasTestTag("interval-menu") and hasAnyAncestor(hasTestTag("historical-replay"))).performClick()
        compose.onNodeWithTag("interval-option-3").performScrollTo().performClick()
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 TESTZ6 3分钟")
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
        compose.onNodeWithTag("replay-confirm-order").assertDoesNotExist()
        compose.onNodeWithTag("order-side-BUY").assertIsSelected()
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        assertTrue(hasPosition())
        assertFalse("提交买入订单" in chartButtons(), "Accepted orders must clear the draft")
        compose.onNodeWithText("模拟持仓").assertDoesNotExist()
        compose.onNodeWithTag("replay-tab-1").performClick()
        compose.onNodeWithText("回放分析").assertExists()
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
        compose.onNodeWithText("模拟持仓").assertDoesNotExist()
        compose.onNodeWithTag("order-side-SELL").assertIsSelected()
        clickChartButton("提交卖出订单")
        assertTrue(hasPosition())
        assertFalse("提交卖出订单" in chartButtons(), "Accepted orders must clear the draft")
    }

    @Test fun replayCloseUsesConfirmationAndRecordsTheClosedTrade() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..3).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("replay-confirm-order").performClick()
        closePosition()
        compose.onNodeWithText("确认平仓").assertIsDisplayed()
        compose.onNodeWithTag("replay-confirm-line").performClick()
        compose.onNodeWithTag("replay-trade-count").performScrollTo().assertTextContains("成交 1 笔", substring = true)
        compose.onNodeWithTag("replay-tab-1").performClick()
        compose.onNodeWithTag("replay-current-report").performClick()
        compose.onNodeWithTag("replay-dashboard").performScrollToNode(hasTestTag("replay-report-trade-count"))
        compose.onNodeWithTag("replay-report-trade-count").assertTextContains("1 笔", substring = true)
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
        compose.onNodeWithTag("review-order").performClick()
        assertTrue(hasPosition())
        compose.onNodeWithTag("interval-inline-1").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(("MES" to Timeframe.M1) in requested); available = true; revision++ }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("replay-step-1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(hasPosition())
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
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("interval-inline-5").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(("MES" to Timeframe.M5) in requested); available = true; revision++ }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("replay-step-5").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(hasPosition())
        compose.onNodeWithTag("replay-chart-main").assertContentDescriptionEquals("回放 MES 5分钟")
    }

    @Test fun replayCountdownUsesTheEndOfTheCompletedSourceBar() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val start = java.time.Instant.parse("2026-11-24T00:00:00Z").toEpochMilli()
        val bars = (0..6).map { Candle(start + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M5, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), rawFrame = Timeframe.M1)) {} } }
        awaitReplay()
        fun countdown() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            .split(';').first { it.startsWith("countdown:") }.substringAfter(':')
        assertEquals("04:00", countdown())
        repeat(4) { compose.onNodeWithTag("replay-next").performClick() }
        assertEquals("00:00", countdown())
        compose.onNodeWithTag("replay-next").performClick()
        assertEquals("04:00", countdown())
    }

    @Test fun replaySidebarTogglesAndChartDraftQuantityCanBeEditedAndSubmitted() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..3).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList())) {} } }
        awaitReplay()
        compose.onNodeWithTag("replay-tab-0").performClick()
        compose.onNodeWithTag("replay-side-panel").assertDoesNotExist()
        compose.onNodeWithTag("replay-tab-0").performClick()
        compose.onNodeWithTag("replay-side-panel").assertExists()
        compose.onNodeWithTag("replay-order-collapse").performClick()
        compose.onNodeWithTag("replay-side-panel").assertDoesNotExist()
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .65f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-menu-BUY-MARKET").performClick()
        compose.onNodeWithTag("replay-side-panel").assertExists()
        clickChartButton("编辑下单数量")
        compose.onNodeWithTag("chart-draft-quantity").performTextReplacement("2")
        compose.onNodeWithContentDescription("关闭数量编辑").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("2", substring = true)
        clickChartButton("提交买入订单")
        compose.onNodeWithTag("replay-confirm-order").assertIsDisplayed().performClick()
        assertTrue(chartButtons().keys.any { it.startsWith("平仓 买入 2 ") })
        assertFalse("提交买入订单" in chartButtons())
        assertFalse("编辑下单数量" in chartButtons())
        compose.onNodeWithText("模拟持仓").assertDoesNotExist()
        closePosition()
        compose.onNodeWithTag("replay-confirm-line").performClick()
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 1 笔", substring = true)
    }
}
