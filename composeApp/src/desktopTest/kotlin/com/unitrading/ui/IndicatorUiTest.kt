package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.IndicatorController
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class IndicatorUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) { Box(Modifier.fillMaxSize()) { TradingScreen(runtime) } }
        }
        compose.waitForIdle()
    }
    private fun chartState(): Map<String, String> = compose.onNodeWithTag("trading-chart").fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun openIndicators() {
        compose.onNode(hasText("指标", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithTag("indicator-settings").assertExists()
    }

    @Test fun indicatorsAreAddedCustomisedPersistedAndDrawn() {
        render()
        assertEquals("0", chartState()["panes"])
        assertEquals("0", chartState()["overlays"])
        openIndicators()
        compose.onNodeWithTag("add-indicator-EXPMA").performClick()
        compose.onNodeWithTag("add-indicator-BOLL").performClick()
        compose.onNodeWithTag("add-indicator-RSI").performClick()
        compose.onNodeWithTag("indicator-param-0-period").performScrollTo().performTextReplacement("30")
        compose.onNodeWithTag("indicator-color-0-EXPMA").performScrollTo().performTextReplacement("#ff0000")
        compose.onNodeWithTag("indicator-param-2-period").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled()
        compose.onNodeWithTag("indicator-param-2-period").performTextReplacement("7")
        compose.onNodeWithTag("indicator-reference-levels-2").performScrollTo().assertTextContains("30, 70").performTextReplacement("101")
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled()
        compose.onNodeWithTag("indicator-reference-levels-2").performTextReplacement("75.25，50，25.5")
        compose.onNodeWithTag("indicators-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 3 }
        val saved = runtime.indicators.settings.value.indicators
        assertEquals(listOf("EXPMA(30)", "BOLL(20,2)", "RSI(7)"), saved.map { it.label })
        assertEquals("#FF0000", saved[0].colors["EXPMA"])
        assertEquals(listOf(25.5, 50.0, 75.25), saved[2].guides)
        assertEquals(saved, IndicatorController(store).settings.value.indicators)
        compose.onNodeWithTag("indicator-settings").assertDoesNotExist()
        compose.onAllNodesWithTag("indicator-readout").assertCountEquals(2)
        compose.onNodeWithText("EXPMA(30)").assertExists()
        compose.onNodeWithText("BOLL(20,2)").assertExists()
        assertEquals("1", chartState()["panes"])
        assertEquals("2", chartState()["overlays"])
        val target = File("../artifacts/chart-indicators.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)

        // The time axis keeps its zoom gesture below the indicator pane, and the pane area pans horizontally.
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { swipe(Offset(size.width * .3f, size.height - 5f), Offset(size.width * .6f, size.height - 5f)) }
        assertTrue(chartState().getValue("bars").toDouble() < 80.0)
        val before = chartState()
        chart.performTouchInput { swipe(Offset(size.width * .3f, size.height - 60f), Offset(size.width * .6f, size.height - 60f)) }
        assertNotEquals(before["offset"], chartState()["offset"])
        assertEquals(before["low"], chartState()["low"])

        // Cancel discards edits; hiding an indicator removes its pane but keeps the configuration.
        openIndicators()
        compose.onNodeWithTag("indicator-reference-levels-2").performScrollTo().assertTextContains("25.5, 50, 75.25").performTextReplacement("10,90")
        compose.onNodeWithTag("remove-indicator-2").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(3, runtime.indicators.settings.value.indicators.size)
        assertEquals(listOf(25.5, 50.0, 75.25), runtime.indicators.settings.value.indicators[2].guides)
        openIndicators()
        compose.onNodeWithTag("indicator-visible-2").performScrollTo().performClick()
        compose.onNodeWithTag("indicators-save").performClick()
        compose.waitUntil(5_000) { !runtime.indicators.settings.value.indicators[2].visible }
        assertEquals(3, runtime.indicators.settings.value.indicators.size)
        assertEquals("0", chartState()["panes"])
        assertEquals("2", chartState()["overlays"])
    }

    @Test fun clearingRsiReferenceLevelsKeepsTheIndicatorAndSurvivesReopening() {
        render()
        openIndicators()
        compose.onNodeWithTag("add-indicator-RSI").performClick()
        compose.onNodeWithTag("indicator-reference-levels-0").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("indicators-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 1 }
        assertEquals("1", chartState()["panes"])
        assertEquals(emptyList(), IndicatorController(store).settings.value.indicators.single().guides)
        openIndicators()
        compose.onNodeWithTag("indicator-reference-levels-0").performScrollTo().assertTextContains("")
        compose.onNodeWithTag("indicators-save").performClick()
        compose.waitForIdle()
        assertEquals(emptyList(), runtime.indicators.settings.value.indicators.single().guides)
    }

    @Test fun savedIndicatorsLoadWithTheChartAndCanBeRemoved() {
        val custom = com.unitrading.app.IndicatorSettings(listOf(
            com.unitrading.chart.IndicatorConfig("m", com.unitrading.chart.IndicatorKind.MACD),
            com.unitrading.chart.IndicatorConfig("k", com.unitrading.chart.IndicatorKind.KDJ),
            com.unitrading.chart.IndicatorConfig("a", com.unitrading.chart.IndicatorKind.MA, mapOf("period" to 5.0)),
        ))
        kotlinx.coroutines.runBlocking { assertTrue(runtime.indicators.apply(custom)) }
        render()
        assertEquals("2", chartState()["panes"])
        assertEquals("1", chartState()["overlays"])
        compose.onNodeWithText("MA(5)").assertExists()
        openIndicators()
        compose.onNodeWithTag("remove-indicator-0").performScrollTo().performClick()
        compose.onNodeWithTag("remove-indicator-0").performScrollTo().performClick()
        compose.onNodeWithTag("indicators-save").performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 1 }
        assertEquals("MA(5)", runtime.indicators.settings.value.indicators.single().label)
        assertEquals("0", chartState()["panes"])
    }
}
