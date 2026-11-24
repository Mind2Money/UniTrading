package com.unitrading.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toAwtImage
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ReplayRecords
import com.unitrading.app.SnapshotStore
import com.unitrading.broker.*
import com.unitrading.chart.ReplayDrawdown
import com.unitrading.chart.ReplayRiskConfig
import com.unitrading.chart.OrderTagButtons
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReplayConsistencyUiTest {
    @get:Rule val compose = createComposeRule()
    private fun chartButtons() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons]
    private fun closePositionOnChart() {
        val at = chartButtons().entries.first { it.key.startsWith("平仓 ") }.value.center
        compose.onNodeWithTag("trading-chart").performMouseInput { click(at) }
    }
    private val instrument = Instrument("MES", "MES future", "期货", 2, tickSize = 25,
        tickValueMicrosPerLot = 1_250_000, minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
    private val start = Instant.parse("2026-11-23T22:57:00Z").toEpochMilli()
    private fun config(target: Long, consistency: Int = 0) = ReplayRiskConfig(
        50_000_000_000, 2_000_000_000, target, ReplayDrawdown.STATIC, maxDailyLossMicros = 1_000_000_000, consistencyPercent = consistency)

    private fun open(bars: List<Candle>, config: ReplayRiskConfig, records: ReplayRecords, exited: AtomicBoolean) {
        compose.setContent { TradingTheme {
            var visible by remember { mutableStateOf(true) }
            if (visible) HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false, ChartTimeZone.SHANGHAI,
                bars, emptyList(), skipConfirmation = true, riskConfig = config), records = records) { exited.set(true); visible = false }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun dailyConsistencyFlattensAndReopensTradingNextDay() {
        val bars = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_000, 10_400, 10_000, 10_000),
            Candle(Instant.parse("2026-11-23T23:01:00Z").toEpochMilli(), 10_000, 10_025, 9_975, 10_000))
        open(bars, config(50_000_000, 40), ReplayRecords(MemorySnapshotStore()), AtomicBoolean())
        val folder = File(System.getenv("GITHUB_WORKSPACE") ?: ".", "composeApp/build/replay-consistency-ui").also { it.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("historical-replay").captureToImage().toAwtImage(), "png", File(folder, "replay.png"))
        compose.onNodeWithTag("order-estimates-toggle").assertDoesNotExist()
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-realized").assertTextContains("+20.00", substring = true)
        compose.onNodeWithTag("replay-consistency-status").assertTextContains("已触及", substring = true)
        compose.onNodeWithTag("replay-trade-count").assertTextContains("成交 1 笔", substring = true)
        assertFalse(chartButtons().keys.any { it.startsWith("平仓 ") })
        compose.onNodeWithTag("review-order").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("replay-consistency-status").assertTextContains("每日", substring = true)
    }

    @Test fun reachingTheGoalSavesClosedTradesAndExits() {
        val bars = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_000, 10_100, 10_000, 10_000))
        val records = ReplayRecords(MemorySnapshotStore())
        val exited = AtomicBoolean()
        open(bars, config(5_000_000), records, exited)
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-success").assertIsDisplayed()
        val folder = File(System.getenv("GITHUB_WORKSPACE") ?: ".", "composeApp/build/replay-consistency-ui").also { it.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("replay-success").captureToImage().toAwtImage(), "png", File(folder, "success.png"))
        compose.onNodeWithTag("replay-success-discard").assertExists()
        compose.onNodeWithTag("replay-success-save").performClick()
        compose.runOnIdle {
            assertTrue(exited.get())
            val record = records.list().single()
            assertEquals("已达成", record.status)
            assertEquals(1, record.trades.size)
            assertEquals(5_000_000L, record.trades.single().netPnlMicros)
            assertEquals(50_005_000_000L, record.finalEquityMicros)
        }
    }

    @Test fun gapToGoalClosesBeforeLaterIntrabarLoss() {
        val bars = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_100, 10_100, 9_900, 9_975))
        val records = ReplayRecords(MemorySnapshotStore())
        val exited = AtomicBoolean()
        open(bars, config(5_000_000).copy(maxDailyLossMicros = 1_000_000), records, exited)
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-success").assertIsDisplayed()
        compose.onNodeWithTag("replay-success-save").performClick()
        compose.runOnIdle {
            assertTrue(exited.get())
            assertEquals(5_000_000L, records.list().single().trades.single().netPnlMicros)
        }
    }

    @Test fun discardAlsoRemovesEarlierAutosavesBeforeExit() {
        val bars = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_000, 10_050, 10_000, 10_050),
            Candle(start + 120_000, 10_050, 10_200, 10_050, 10_050))
        val records = ReplayRecords(MemorySnapshotStore())
        val exited = AtomicBoolean()
        open(bars, config(10_000_000), records, exited)
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        closePositionOnChart()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, records.list().single().trades.size) }
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-success").assertIsDisplayed()
        compose.onNodeWithTag("replay-success-discard").performClick()
        compose.runOnIdle { assertTrue(exited.get()); assertTrue(records.list().isEmpty()) }
    }

    @Test fun saveFailureDoesNotExitOrDiscardTheCompletedSession() {
        val store = object : SnapshotStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) { error("存储不可用") }
        }
        val bars = listOf(Candle(start, 10_000, 10_025, 9_975, 10_000),
            Candle(start + 60_000, 10_000, 10_100, 10_000, 10_000))
        val exited = AtomicBoolean()
        open(bars, config(5_000_000), ReplayRecords(store), exited)
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        compose.onNodeWithTag("replay-next").performClick()
        compose.onNodeWithTag("replay-success-save").performClick()
        compose.onNodeWithTag("replay-success-error").assertTextContains("回放记录保存失败", substring = true)
        compose.onNodeWithTag("replay-success").assertExists()
        assertFalse(exited.get())
    }
}
