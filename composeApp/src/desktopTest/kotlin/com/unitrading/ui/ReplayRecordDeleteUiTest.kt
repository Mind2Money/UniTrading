package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.unitrading.app.ChartTimeZone
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ReplayRecord
import com.unitrading.app.ReplayRecords
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.OrderTagButtons
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReplayRecordDeleteUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
    private val bars = (0..5).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_025, 9_975, 10_000) }

    private fun open(records: ReplayRecords) {
        compose.setContent { TradingTheme { HistoricalReplayDialog(ReplayInput(instrument, Timeframe.M1, false,
            ChartTimeZone.SHANGHAI, bars, emptyList(), skipConfirmation = true), records = records) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("review-order").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closePosition() {
        val chart = compose.onNodeWithTag("trading-chart")
        val at = chart.fetchSemanticsNode().config[OrderTagButtons].entries.first { it.key.startsWith("平仓 ") }.value.center
        chart.performMouseInput { click(at) }
    }

    @Test fun deleteNeedsConfirmationAndPreservesOtherRecords() {
        val records = ReplayRecords(MemorySnapshotStore())
        records.save(ReplayRecord("keep", "MES", "USD", 50_000_000, 1, emptyList()))
        records.save(ReplayRecord("remove", "MGC", "USD", 50_000_000, 2, emptyList()))
        open(records)
        compose.onNodeWithTag("replay-tab-1").performClick()
        compose.onNodeWithTag("replay-records-picker").performClick()
        compose.onNodeWithTag("replay-record-delete-remove").performClick()
        compose.onNodeWithTag("replay-record-delete-cancel").performClick()
        compose.runOnIdle { assertEquals(2, records.list().size) }
        compose.onNodeWithTag("replay-record-delete-remove").performClick()
        compose.onNodeWithTag("replay-record-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("keep"), records.list().map { it.id }) }
        compose.onNodeWithTag("replay-record-delete-remove").assertDoesNotExist()
        compose.onNodeWithTag("replay-record-keep").performClick()
        compose.onNodeWithTag("replay-dashboard").assertExists()
    }

    @Test fun deletingTheCurrentSessionIsNotUndoneByLaterTradesOrNormalExit() {
        val records = ReplayRecords(MemorySnapshotStore())
        open(records)
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        closePosition()
        compose.waitUntil(5_000) { records.list().size == 1 }
        val id = records.list().single().id
        compose.onNodeWithTag("replay-tab-1").performClick()
        compose.onNodeWithTag("replay-records-picker").performClick()
        compose.onNodeWithTag("replay-record-delete-$id").performClick()
        compose.onNodeWithTag("replay-record-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(emptyList(), records.list()) }
        compose.onNodeWithTag("replay-records-close").performClick()
        compose.onNodeWithTag("replay-current-report").performClick()
        compose.onNodeWithTag("replay-description-edit").performClick()
        compose.onNodeWithTag("replay-description").performTextInput("复盘备注")
        compose.onNodeWithTag("replay-description-edit").performClick()
        compose.runOnIdle { assertEquals(emptyList(), records.list()) }
        compose.onNodeWithTag("replay-report-close").performClick()
        compose.onNodeWithTag("replay-tab-0").performClick()
        compose.onNodeWithTag("review-order").performScrollTo().performClick()
        closePosition()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList(), records.list()) }
        compose.onNodeWithTag("replay-close").performClick()
        compose.onNodeWithText("关闭回放").performClick()
        compose.runOnIdle { assertEquals(emptyList(), records.list()) }
    }
}
