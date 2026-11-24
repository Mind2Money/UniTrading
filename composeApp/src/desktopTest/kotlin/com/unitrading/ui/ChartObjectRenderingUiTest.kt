package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.*
import com.unitrading.chart.*
import com.unitrading.chart.drawing.ChartDrawings
import com.unitrading.chart.drawing.DrawingPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/** Check the rendered pixels, not only the values stored in the object tree. */
@OptIn(ExperimentalTestApi::class)
class ChartObjectRenderingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overlappingIndicatorAndDrawingReallyExchangeTheirPaintOrder() {
        val instrument = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
            minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
        val bars = (0..159).map { Candle(1_700_000_000_000L + it * 60_000L, 10_000, 10_050, 9_950, 10_000) }
        val drawings = ChartDrawings(MemorySnapshotStore(), Dispatchers.Unconfined).apply {
            bind("MES")
            addHorizontalLine(DrawingPoint(bars[80].timeMs, 10_000.0))
            update(this.drawings.single().let { it.copy(style = it.style.copy(color = "#FF0000", width = 5f, showPrices = false)) })
            select(null)
        }
        val drawingId = drawings.drawings.single().id
        val layers = ChartObjectLayers(MemorySnapshotStore())
        val indicator = IndicatorConfig("ma", IndicatorKind.MA, params = mapOf("period" to 1.0), colors = mapOf("MA" to "#00FF00"),
            lineStyles = mapOf("MA" to IndicatorLineStyle(width = 5f)))
        val actions = ChartOrderActions(
            estimateDraft = { it to OrderPreview(null) }, onDraftEntry = {}, onDraftProtection = { _, _ -> },
            editPending = false, estimatePending = { _, _ -> OrderPreview(null) }, onPendingEntry = { _, _ -> },
            editPendingProtection = false, estimatePendingProtection = { _, _, _ -> OrderPreview(null) },
            onPendingProtection = { _, _, _ -> }, onProtection = { _, _, _ -> })
        compose.setContent { TradingTheme { Box(Modifier.requiredSize(800.dp, 600.dp)) {
            TradingChart("layer-test", instrument, null, bars, Timeframe.M1, {}, emptyList(), emptyList(), null, null,
                null, null, emptyList(), actions, modifier = Modifier.fillMaxSize(), indicators = listOf(indicator),
                chrome = ChartChrome(drawings = drawings, objectLayers = layers, sharedToolbar = true, desktopAxisStyle = true))
        } } }
        fun counts(): Pair<Int, Int> {
            val node = compose.onNodeWithTag("trading-chart")
            val state = node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
            val scale = PriceScale(state.getValue("low").toDouble(), state.getValue("high").toDouble(),
                state.getValue("plotTop").toFloat(), state.getValue("plotBottom").toFloat())
            val image = node.captureToImage().toAwtImage()
            val y = scale.y(10_000).roundToInt().coerceIn(1, image.height - 2)
            var red = 0
            var green = 0
            for (x in (image.width * .35).toInt() until (image.width * .75).toInt()) for (dy in -1..1) {
                val pixel = image.getRGB(x, y + dy)
                val r = pixel shr 16 and 255
                val g = pixel shr 8 and 255
                if (r > 200 && g < 70) red++
                if (g > 200 && r < 70) green++
            }
            return red to green
        }
        val initial = counts()
        assertTrue(initial.first > initial.second + 100, "The drawing should initially cover the indicator: $initial")
        compose.runOnIdle { runBlocking { assertTrue(layers.move("MES", indicatorLayerKey("ma"), listOf("ma"), listOf(drawingId), true)) } }
        val indicatorFront = counts()
        assertTrue(indicatorFront.second > indicatorFront.first + 100, "The indicator should cover the drawing: $indicatorFront")
        compose.runOnIdle { runBlocking { assertTrue(layers.move("MES", drawingLayerKey(drawingId), listOf("ma"), listOf(drawingId), true)) } }
        val drawingFront = counts()
        assertTrue(drawingFront.first > drawingFront.second + 100, "Moving the drawing again should cover the indicator: $drawingFront")
    }
}
