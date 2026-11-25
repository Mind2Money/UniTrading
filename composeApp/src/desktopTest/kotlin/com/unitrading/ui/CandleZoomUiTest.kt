package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.ThemeSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.ChartOrderActions
import com.unitrading.chart.PriceScale
import com.unitrading.chart.TradingChart
import com.unitrading.chart.ChartChrome
import com.unitrading.chart.ChartViewMemory
import com.unitrading.chart.SavedChartView
import com.unitrading.chart.chartViewKey
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*

/** Verifies actual rendered candle pixels, in addition to the geometry tests. */
@OptIn(ExperimentalTestApi::class)
class CandleZoomUiTest {
    @get:Rule val compose = createComposeRule()
    private val candles = (0..129).map { Candle(it * 3_600_000L, 10_000L, 10_150L, 9_950L, 10_100L) }
    private val instrument = Instrument("TEST", "测试", "期货", priceDecimals = 2, tickValueMicrosPerLot = 500_000)
    private val actions = ChartOrderActions({ error("unused") }, {}, { _, _ -> }, false, { _, _ -> error("unused") }, { _, _ -> },
        false, { _, _, _ -> error("unused") }, { _, _, _ -> }, { _, _, _ -> })

    private fun state() = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }

    private fun bodyPixelWidth(): Int {
        val chart = compose.onNodeWithTag("trading-chart")
        val image = chart.captureToImage().toAwtImage()
        val s = state()
        val count = s.getValue("bars").toDouble()
        val offset = s.getValue("offset").toDouble()
        val start = candles.size - offset - count
        val index = (start + count * .6).toInt().coerceIn(0, candles.lastIndex)
        val step = s.getValue("barStep").toFloat()
        val center = (12f + (index - start + .5).toFloat() * step).roundToInt()
        val scale = PriceScale(s.getValue("low").toDouble(), s.getValue("high").toDouble(), 18f, s.getValue("plotBottom").toFloat())
        val y = scale.y(10_050).roundToInt()
        val up = themePalette(ThemeSettings()).UpBody.toArgb()
        assertEquals(up, image.getRGB(center, y), "sample must be inside the candle body")
        var left = center
        while (left > 0 && image.getRGB(left - 1, y) == up) left--
        var right = center
        while (right + 1 < image.width && image.getRGB(right + 1, y) == up) right++
        return right - left + 1
    }

    @Test fun zoomInActuallyDrawsWiderSeparatedBodies() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1000.dp, 600.dp)) {
                TradingChart("account", instrument, null, candles, Timeframe.H1, {}, emptyList(), emptyList(), null, null,
                    null, null, emptyList(), actions, Modifier.fillMaxSize())
            }
        } }
        compose.waitForIdle()
        val before = bodyPixelWidth()
        repeat(7) { compose.onNodeWithTag("chart-zoom-in").performClick() }
        compose.waitForIdle()
        val after = bodyPixelWidth()
        val step = state().getValue("barStep").toFloat()
        assertTrue(after > before * 2, "rendered bodies should widen substantially: $before -> $after")
        assertTrue(after < step, "the rendered body must not reach its neighbor")
    }

}

@OptIn(ExperimentalTestApi::class)
class CandleZoomUiTestFullWidth {
    private val instrument = Instrument("TEST", "测试", "期货", priceDecimals = 2, tickValueMicrosPerLot = 500_000)
    private val actions = ChartOrderActions({ error("unused") }, {}, { _, _ -> }, false, { _, _ -> error("unused") }, { _, _ -> },
        false, { _, _, _ -> error("unused") }, { _, _, _ -> }, { _, _, _ -> })

    private fun state(description: String) = description.split(';').associate { it.substringBefore(':') to it.substringAfter(':') }

    @Test fun aFullWidthChartCanShowAbout2400Candles() = runDesktopComposeUiTest(width = 1400, height = 600) {
        val many = (0 until 3000).map { i ->
            val open = 10_000L + (i % 80) * 10L
            Candle(i * 60_000L, open, open + 35, open - 25, open + 20)
        }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1400.dp, 600.dp)) {
                TradingChart("account", instrument, null, many, Timeframe.M1, {}, emptyList(), emptyList(), null, null,
                    null, null, emptyList(), actions, Modifier.fillMaxSize())
            }
        } }
        waitForIdle()
        repeat(16) { onNodeWithTag("chart-zoom-out").performClick() }
        waitForIdle()
        val s = state(onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(2400.0, s.getValue("bars").toDouble(), .001)
        assertEquals(0.0, s.getValue("offset").toDouble(), .001, "The latest bar must stay on screen when zooming out")
        assertTrue(s.getValue("barStep").toFloat() in 0f..1f)
        assertTrue(s.getValue("barBody").toFloat() > 0f)
        val folder = File(System.getenv("GITHUB_WORKSPACE") ?: ".", "composeApp/build/zoom-2400").also { it.mkdirs() }
        val image = onNodeWithTag("trading-chart").captureToImage().toAwtImage()
        assertTrue(image.width >= 1400, "The screenshot must include the complete single chart")
        ImageIO.write(image, "png", File(folder, "chart.png"))
    }

    @Test fun savedWideZoomIsNotClampedBeforeTheChartIsMeasured() = runDesktopComposeUiTest(width = 1400, height = 600) {
        val many = (0 until 3000).map { Candle(it * 60_000L, 10_000, 10_150, 9_950, 10_100) }
        val views = ChartViewMemory(MemorySnapshotStore(), Dispatchers.Unconfined, saveDelayMs = 0).also {
            it.keep(chartViewKey(instrument.symbol, Timeframe.M1), SavedChartView(2400.0))
        }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1400.dp, 600.dp)) {
                TradingChart("account", instrument, null, many, Timeframe.M1, {}, emptyList(), emptyList(), null, null,
                    null, null, emptyList(), actions, Modifier.fillMaxSize(), chrome = ChartChrome(views = views))
            }
        } }
        waitForIdle()
        val s = state(onNodeWithTag("trading-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(2400.0, s.getValue("bars").toDouble(), .001)
    }
}
