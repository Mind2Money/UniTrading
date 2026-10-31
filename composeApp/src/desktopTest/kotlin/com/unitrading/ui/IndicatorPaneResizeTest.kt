package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.chart.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class IndicatorPaneResizeTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }

    private fun render(width: Int = 1500, height: Int = 1000) {
        runBlocking { runtime.indicators.apply(IndicatorSettings(listOf(IndicatorConfig("rsi", IndicatorKind.RSI), IndicatorConfig("macd", IndicatorKind.MACD)))) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) {
            Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
        } }
    }
    private fun state() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun fraction(id: String) = compose.onNodeWithTag("resize-indicator-$id").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun awaitSaved() = compose.waitUntil(5_000) {
        val saved = ChartPreferencesController(store).preferences.value.indicatorPaneFractions["rsi"]
        saved != null && kotlin.math.abs(saved - fraction("rsi")) < .0001f
    }

    @Test fun draggingRsiResizesItWithoutPanningOrResizingTheOtherPane() {
        render()
        val before = state()
        val other = fraction("macd")
        compose.onNodeWithTag("resize-indicator-rsi").performTouchInput { swipe(center, center + Offset(0f, -45f)) }
        assertTrue(fraction("rsi") > .25f)
        assertEquals(other, fraction("macd"), .0001f)
        assertEquals(before["offset"], state()["offset"])
        assertEquals(before["bars"], state()["bars"])
        assertTrue(state().getValue("plotBottom").toFloat() < before.getValue("plotBottom").toFloat())
        awaitSaved()
        compose.onNodeWithTag("resize-indicator-rsi").performTouchInput { doubleClick() }
        assertEquals(.2f, fraction("rsi"), .0001f)
        awaitSaved()
    }

    @Test fun phoneDividerSupportsTouchAndKeyboardAndSavesTheRatio() {
        render(width = 500, height = 950)
        compose.onNodeWithTag("resize-indicator-rsi").performTouchInput { swipe(center, center + Offset(0f, -35f)) }
        assertTrue(fraction("rsi") > .2f)
        awaitSaved()
        val before = fraction("rsi")
        compose.onNodeWithTag("resize-indicator-rsi").performTouchInput { click() }
        compose.onNodeWithTag("resize-indicator-rsi").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        assertEquals(before - .02f, fraction("rsi"), .0001f)
        awaitSaved()
    }
}
