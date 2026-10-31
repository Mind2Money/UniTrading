package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import kotlin.math.abs
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

    /** Find repeated colour runs and gaps across the plot, tolerating subpixel antialiasing at .6 density. */
    private fun dashedGuideRow(pixels: PixelMap, expected: Color): Int {
        val left = pixels.width / 8
        val right = pixels.width * 3 / 4
        val width = right - left
        var bestRow = -1
        var bestCount = 0
        for (y in 0 until pixels.height) {
            var count = 0
            var runs = 0
            var gaps = 0
            var previous = false
            var first = -1
            var last = -1
            for (x in left until right) {
                val pixel = pixels[x, y]
                val low = minOf(pixel.red, pixel.green, pixel.blue)
                val chroma = maxOf(pixel.red, pixel.green, pixel.blue) - low
                // A subpixel line blends with the blue chart background; allow that hue bias while
                // still requiring repeated coloured segments separated by gaps across most of the plot.
                val matches = chroma > .1f && abs((pixel.red - low) / chroma - expected.red) < .35f &&
                    abs((pixel.green - low) / chroma - expected.green) < .35f && abs((pixel.blue - low) / chroma - expected.blue) < .35f
                if (matches) {
                    count++
                    if (!previous) runs++
                    if (first < 0) first = x
                    last = x
                } else if (previous) gaps++
                previous = matches
            }
            if (runs >= 8 && gaps >= 7 && count in width / 5..width * 4 / 5 && last - first > width * .8f && count > bestCount) {
                bestRow = y
                bestCount = count
            }
        }
        assertTrue(bestRow >= 0, "No dashed guide for $expected: expected at least 8 colour runs with gaps across the plot")
        return bestRow
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
        compose.onNodeWithTag("indicator-reference-value-2-0").performScrollTo().assertTextContains("30").performTextReplacement("101")
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled()
        compose.onNodeWithTag("indicator-reference-value-2-0").performTextReplacement("70.00")
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled() // duplicate numeric value
        compose.onNodeWithTag("indicator-reference-value-2-0").performTextReplacement("75.25")
        compose.onNodeWithTag("indicator-reference-color-2-0").performScrollTo().performTextReplacement("bad")
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled()
        compose.onNodeWithTag("indicator-reference-color-2-0").performTextReplacement("#ff00ff")
        compose.onNodeWithTag("indicator-reference-value-2-1").performScrollTo().assertTextContains("70").performTextReplacement("25.5")
        compose.onNodeWithTag("pick-indicator-reference-color-2-1").performScrollTo().performClick()
        compose.onNodeWithText("RSI 参考线 2 颜色").assertExists()
        val sliders = compose.onAllNodes(SemanticsMatcher("RGB channel slider") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 0f..255f
        })
        sliders.assertCountEquals(3)
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        sliders[1].performSemanticsAction(SemanticsActions.SetProgress) { it(255f) }
        sliders[2].performSemanticsAction(SemanticsActions.SetProgress) { it(255f) }
        compose.onNodeWithText("使用颜色").performClick()
        compose.onNodeWithTag("indicator-reference-color-2-1").performScrollTo().assertTextContains("#00FFFF")
        compose.onNodeWithTag("add-indicator-reference-2").performScrollTo().performClick()
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled() // blank added row
        compose.onNodeWithTag("indicator-reference-value-2-2").performScrollTo().performTextReplacement("50")
        compose.onNodeWithTag("indicator-reference-color-2-2").performScrollTo().performTextReplacement("#00ff00")
        val formImage = File("../artifacts/rsi-reference-form.png")
        formImage.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", formImage)
        compose.onNodeWithTag("indicators-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 3 }
        val saved = runtime.indicators.settings.value.indicators
        assertEquals(listOf("EXPMA(30)", "BOLL(20,2)", "RSI(7)"), saved.map { it.label })
        assertEquals("#FF0000", saved[0].colors["EXPMA"])
        assertEquals(listOf(25.5, 50.0, 75.25), saved[2].guides)
        assertEquals(mapOf("25.5" to "#00FFFF", "50" to "#00FF00", "75.25" to "#FF00FF"), saved[2].referenceColors)
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
        val chartImage = compose.onNodeWithTag("trading-chart").captureToImage()
        ImageIO.write(chartImage.toAwtImage(), "png", File(target.parentFile, "rsi-reference-colors.png"))
        val pixels = chartImage.toPixelMap()
        val upper = dashedGuideRow(pixels, Color.Magenta)
        val middle = dashedGuideRow(pixels, Color.Green)
        val lower = dashedGuideRow(pixels, Color.Cyan)
        assertTrue(upper < middle && middle < lower, "Sorted reference values must keep their individual colours")

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
        compose.onNodeWithTag("indicator-reference-value-2-0").performScrollTo().assertTextContains("25.5").performTextReplacement("10")
        compose.onNodeWithTag("indicator-reference-color-2-0").performScrollTo().assertTextContains("#00FFFF")
        compose.onNodeWithTag("indicator-reference-color-2-1").performScrollTo().assertTextContains("#00FF00")
        compose.onNodeWithTag("indicator-reference-value-2-2").performScrollTo().assertTextContains("75.25")
        compose.onNodeWithTag("indicator-reference-color-2-2").performScrollTo().assertTextContains("#FF00FF")
        compose.onNodeWithTag("remove-indicator-2").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(3, runtime.indicators.settings.value.indicators.size)
        assertEquals(listOf(25.5, 50.0, 75.25), runtime.indicators.settings.value.indicators[2].guides)
        assertEquals(saved[2].referenceColors, runtime.indicators.settings.value.indicators[2].referenceColors)
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
        compose.onNodeWithTag("indicator-reference-color-0-1").performScrollTo().performTextReplacement("#112233")
        compose.onNodeWithTag("remove-indicator-reference-0-0").performScrollTo().performClick()
        compose.onNodeWithTag("indicator-reference-value-0-0").performScrollTo().assertTextContains("70")
        compose.onNodeWithTag("indicator-reference-color-0-0").performScrollTo().assertTextContains("#112233")
        compose.onNodeWithTag("remove-indicator-reference-0-0").performScrollTo().performClick()
        compose.onNodeWithTag("indicator-reference-value-0-0").assertDoesNotExist()
        compose.onNodeWithTag("indicators-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 1 }
        assertEquals("1", chartState()["panes"])
        assertEquals(emptyList(), IndicatorController(store).settings.value.indicators.single().guides)
        assertEquals(emptyMap(), IndicatorController(store).settings.value.indicators.single().referenceColors)
        openIndicators()
        compose.onNodeWithTag("indicator-reference-value-0-0").assertDoesNotExist()
        compose.onNodeWithTag("add-indicator-reference-0").performScrollTo().performClick()
        compose.onNodeWithTag("indicators-save").assertIsNotEnabled()
        compose.onNodeWithTag("remove-indicator-reference-0-0").performScrollTo().performClick()
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
