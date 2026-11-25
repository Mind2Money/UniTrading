package com.unitrading.chart

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderPreview
import com.unitrading.broker.Timeframe
import com.unitrading.ui.TradingTheme
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class PineChartUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrument = Instrument("TEST", "Test", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1)
    private val bars = List(120) { index ->
        val close = 10_000L + (index % 11) * 25
        Candle(1_700_000_000_000L + index * Timeframe.M1.millis, close - 25, close + 25, close - 50, close, 10 + index.toLong())
    }
    private val actions = ChartOrderActions({ it to OrderPreview(null) }, {}, { _, _ -> }, false, { _, _ -> OrderPreview(null) },
        { _, _ -> }, false, { _, _, _ -> OrderPreview(null) }, { _, _, _ -> }, { _, _, _ -> })
    private val configs = mutableStateOf<List<IndicatorConfig>>(emptyList())
    private val candles = mutableStateOf(bars)

    private fun display(axis: PriceAxisSettings = PriceAxisSettings(fitAllBars = true, showPlus = false)) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TradingTheme {
                    TradingChart("test", instrument, null, candles.value, Timeframe.M1, {}, emptyList(), emptyList(), null, null, null, null,
                        emptyList(), actions, modifier = Modifier.size(720.dp, 460.dp), indicators = configs.value,
                        chrome = ChartChrome(sharedToolbar = true, showNavigation = false, desktopAxisStyle = true, priceAxis = axis))
                }
            }
        }
    }

    @Test fun scriptPaintsPriceCoordinatesObjectsAndAnIndependentPane() {
        // Keep the label below the chart legend so the test checks its paint rather than legend occlusion.
        configs.value = listOf(
            IndicatorConfig("overlay", IndicatorKind.PINE, pineSource = """
                //@version=6
                indicator("Pine Overlay", overlay=true)
                plot(101, "Price", color=#FF00FF, linewidth=3)
                if barstate.islast
                    line.new(bar_index - 62, 100.6, bar_index - 20, 100.6, color=#00FFFF, width=3)
                    box.new(bar_index - 52, 100.1, bar_index - 32, 99.4, border_color=na, bgcolor=#F00477)
                    label.new(bar_index - 14, 101.6, "P", color=#00FF00, textcolor=#000000)
            """.trimIndent()),
            IndicatorConfig("pane", IndicatorKind.PINE, pineSource = """
                //@version=5
                indicator("Pine pane")
                hline(30, color=#FFFF00, linewidth=2)
                plot(ta.rsi(close, 4), color=#4555FF, linewidth=2)
            """.trimIndent()),
        )
        display()
        compose.waitUntil(15_000) { chartValue("panes") == "1" && chartValue("overlays") == "1" }
        compose.onNodeWithText("Pine Overlay").assertExists()
        compose.onNodeWithText("101.00").assertExists()
        val priceBottom = chartValue("plotBottom").toFloat().toInt()
        val chart = compose.onNodeWithTag("trading-chart").captureToImage()
        val screenshot = File("../artifacts/chart-pine.png").also { it.parentFile.mkdirs() }
        ImageIO.write(chart.toAwtImage(), "png", screenshot)
        val image = chart.toPixelMap()
        assertTrue(image.countColor(Color.Magenta, 0 until priceBottom) > 120, "plot must paint a price-scale line")
        assertTrue(image.countColor(Color.Cyan, 0 until priceBottom) > 80, "line.new must paint a line segment")
        assertTrue(image.countColor(Color(0xFFF00477), 0 until priceBottom) > 100, "box.new must paint its rectangle")
        assertTrue(image.countColor(Color.Green, 0 until priceBottom) > 10, "label.new must paint the label")
        assertTrue(image.countColor(Color.Yellow, priceBottom until image.height) > 100, "hline must paint below the price pane")
        assertTrue(image.countColor(Color(0xFF4555FF), priceBottom until image.height) > 60, "RSI must paint in its own pane")
        assertTrue(image.countColor(Color.Yellow, 0 until priceBottom) == 0, "subpane guides must not bleed to the price pane")
    }

    @Test fun columnsWithDefaultZeroBaselinePaintOnAnInvertedLogPriceAxis() {
        configs.value = listOf(IndicatorConfig("log", IndicatorKind.PINE,
            pineSource = "indicator(\"Pine columns\", overlay=true)\nplot(100.5, color=#FD46A1, style=plot.style_columns, show_last=12)"))
        display(PriceAxisSettings(mode = PriceScaleMode.LOGARITHMIC, inverted = true, fitAllBars = true, showPlus = false))
        compose.waitUntil(15_000) { chartValue("overlays") == "1" }
        val image = compose.onNodeWithTag("trading-chart").captureToImage().toPixelMap()
        assertTrue(image.countColor(Color(0xFFFD46A1), 0 until chartValue("plotBottom").toFloat().toInt()) > 120,
            "the baseline of an overlay column must be clipped to the positive price domain")
    }

    @Test fun scriptFailureLeavesOtherIndicatorsAndRemovingItClearsTheError() {
        val failing = IndicatorConfig("fail", IndicatorKind.PINE, pineSource = """
            indicator("Bad", overlay=true)
            plot(close[-1])
        """.trimIndent())
        val working = IndicatorConfig("good", IndicatorKind.MA)
        configs.value = listOf(failing, working)
        display()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chart-pine-error").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(chartValue("overlays") == "1")
        compose.onNodeWithText("MA(20)").assertExists()
        compose.runOnIdle { configs.value = listOf(working) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chart-pine-error").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun lastBarChangesRefreshPineReadoutAndHidingRemovesIt() {
        val indicator = IndicatorConfig("overlay", IndicatorKind.PINE,
            pineSource = "indicator(\"Live Pine\", overlay=true)\nplot(close + 5)")
        configs.value = listOf(indicator)
        display()
        compose.waitUntil(15_000) { chartValue("overlays") == "1" }
        compose.onNodeWithText(instrument.formatPrice(bars.last().close + 500)).assertExists()
        compose.runOnIdle { candles.value = bars.dropLast(1) + bars.last().copy(close = 11_400, high = 11_425) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("119.00")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { configs.value = listOf(indicator.copy(visible = false)) }
        compose.waitUntil(5_000) { chartValue("overlays") == "0" }
    }

    private fun chartValue(key: String): String = compose.onNodeWithTag("trading-chart").fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription].split(';').first { it.startsWith("$key:") }.substringAfter(':')

    private fun PixelMap.countColor(color: Color, rows: IntRange): Int {
        var count = 0
        for (y in rows) for (x in 30 until width - 90) {
            val at = this[x, y]
            if (abs(at.red - color.red) < .1f && abs(at.green - color.green) < .1f && abs(at.blue - color.blue) < .1f) count++
        }
        return count
    }
}
