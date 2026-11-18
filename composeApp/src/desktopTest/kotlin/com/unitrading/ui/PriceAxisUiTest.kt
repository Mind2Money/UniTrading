package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.chart.PriceScale
import com.unitrading.chart.PriceScaleMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PriceAxisUiTest {
    @get:Rule val compose = createComposeRule()
    private val broker = OfflineOrderBroker()
    private lateinit var runtime: TradingRuntime

    @Test fun autoFitMenuStaysCheckedAfterDraggingChart() {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("自动适应屏幕", substring = true).performClick()
        compose.waitForIdle()
        val chart = compose.onNodeWithTag("trading-chart")
        chart.performTouchInput { swipe(Offset(width * .35f, height * .45f), Offset(width * .55f, height * .50f)) }
        compose.waitForIdle()
        val state = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(state.contains("auto:true"), state)
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("✓  自动适应屏幕").assertExists()
    }

    @Test fun labelsAndLinesShareOneMenuAndStillControlTheirSettings() {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("标签与线条", substring = true).performClick()
        compose.onNodeWithText("最新价格标签", substring = true).assertExists()
        compose.onNodeWithText("K 线倒计时", substring = true).assertExists()
        compose.onNodeWithText("最新价格线", substring = true).assertExists().performClick()
        compose.onNodeWithText("卖价线", substring = true).assertExists()
        assertFalse(runtime.chartPreferences.priceAxis.value.showPriceLine)
    }

    @Test fun menuChangesAxisWhileOrderMenuStillUsesAbsolutePrice() {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("百分比", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(PriceScaleMode.PERCENT, runtime.chartPreferences.priceAxis.value.mode)
        compose.onNodeWithTag("price-axis-menu-button").performClick()
        compose.onNodeWithText("反转价格轴", substring = true).performClick()
        compose.onNodeWithText("价格轴移到左侧").performClick()
        compose.waitForIdle()
        assertTrue(runtime.chartPreferences.priceAxis.value.inverted)
        assertTrue(runtime.chartPreferences.priceAxis.value.left)
        val chart = compose.onNodeWithTag("trading-chart")
        val state = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
        val scale = PriceScale(state.getValue("low").toDouble(), state.getValue("high").toDouble(), 18f * .6f,
            state.getValue("plotBottom").toFloat(), inverted = true)
        val point = Offset(chart.fetchSemanticsNode().size.width * .7f, scale.y(549_000))
        chart.performMouseInput { moveTo(point); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-menu-BUY-LIMIT").assertTextContains("买入限价 1 张 @ 5490.00")
        assertTrue(broker.placed.isEmpty())
    }
}
