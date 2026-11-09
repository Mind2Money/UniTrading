package com.unitrading.chart.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.ChartViewport
import com.unitrading.chart.PriceScale
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/** Renders every drawing tool in its own small chart, one sheet per toolbar group, to artifacts/drawing-gallery for visual review. */
@OptIn(ExperimentalTestApi::class)
class DrawingGallery {
    private val instrument = Instrument("TEST", "Test", "Test", 2, tickSize = 25, tickValueMicrosPerLot = 5_000_000)
    private val candles = run {
        var price = 40_000.0
        List(120) { i ->
            val step = sin(i * .19) * 180 + sin(i * .73) * 110
            val open = price
            val close = price + step
            val high = maxOf(open, close) + 90 + (i * 37 % 70)
            val low = minOf(open, close) - 90 - (i * 53 % 60)
            price = close
            Candle(1_700_000_000_000L + i * 3_600_000L, snap(open), snap(high), snap(low), snap(close), 900L + (i * 71 % 800))
        }
    }

    private fun snap(value: Double) = (value / 25).toLong() * 25

    private fun anchors(tool: DrawingTool): List<Pair<Double, Double>> = when (tool.anchors) {
        OPEN_ANCHORS -> listOf(.15 to .7, .35 to .3, .6 to .6, .85 to .3)
        FREEHAND -> List(32) { i -> (.1 + i * .025) to (.5 + sin(i * .5) * .2) }
        1 -> listOf(.5 to .55)
        2 -> listOf(.3 to .68, .68 to .3)
        3 -> listOf(.2 to .7, .5 to .3, .75 to .55)
        4 -> listOf(.2 to .7, .4 to .3, .6 to .6, .8 to .25)
        5 -> listOf(.15 to .7, .3 to .3, .5 to .55, .7 to .3, .85 to .65)
        6 -> listOf(.1 to .7, .25 to .4, .4 to .55, .6 to .2, .75 to .4, .9 to .15)
        else -> listOf(.1 to .7, .25 to .45, .4 to .6, .5 to .25, .65 to .6, .8 to .45, .92 to .7)
    }

    private fun sheet(name: String, tools: List<DrawingTool>) {
        val cellWidth = 420f
        val cellHeight = 260f
        val columns = 4
        val rows = (tools.size + columns - 1) / columns
        runDesktopComposeUiTest(width = (cellWidth * columns).toInt(), height = (cellHeight * rows).toInt()) {
            setContent {
                val measurer = rememberTextMeasurer(cacheSize = 512)
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF0F0F0F))
                    tools.forEachIndexed { index, tool ->
                        translate(index % columns * cellWidth, index / columns * cellHeight) {
                            val plot = Rect(8f, 22f, cellWidth - 66f, cellHeight - 22f)
                            val viewport = ChartViewport(100.0, -18.0)
                            val shown = candles.subList(candles.size - 82, candles.size)
                            val scale = PriceScale(shown.minOf { it.low }.toDouble() - 600, shown.maxOf { it.high }.toDouble() + 600, plot.top, plot.bottom)
                            val coords = DrawingCoordinates(candles, 3_600_000, viewport, plot, plot.bottom, scale)
                            val ctx = DrawingContext(coords, instrument, Timeframe(60), density, Color(0xFF1A1A1A), Color(0xFFDBDBDB), Color(0xFF089981), Color(0xFFF23645),
                                { text, size, bold, italic, maxWidth -> measurer.layout(text, size, bold, italic, maxWidth).size.let { Size(it.width.toFloat(), it.height.toFloat()) } })
                            drawRect(Color(0xFF131313), plot.topLeft, plot.size)
                            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                                shown.forEachIndexed { i, candle ->
                                    val x = coords.xOfIndex((candles.size - 82 + i).toDouble())
                                    val up = candle.close >= candle.open
                                    val color = if (up) Color(0xFF089981) else Color(0xFFF23645)
                                    drawLine(color.copy(alpha = .55f), Offset(x, coords.y(candle.high.toDouble())), Offset(x, coords.y(candle.low.toDouble())), 1f)
                                    val top = coords.y(maxOf(candle.open, candle.close).toDouble())
                                    drawRect(color.copy(alpha = .55f), Offset(x - 1.2f, top), Size(2.4f, maxOf(coords.y(minOf(candle.open, candle.close).toDouble()) - top, 1f)))
                                }
                            }
                            val points = anchors(tool).map { (fx, fy) ->
                                coords.pointAt(Offset(plot.left + plot.width * fx.toFloat(), plot.top + plot.height * fy.toFloat()), snapToBar = tool.anchors != FREEHAND)
                            }
                            var drawing = newDrawing(tool, "gallery-$index", points, if (tool == DrawingTool.ICON) "🚀" else null)
                            if (tool.promptsForText || tool.hasText) drawing = drawing.copy(text = if (tool == DrawingTool.TEXT) "文字 Text 123" else "示例文字")
                            if (tool == DrawingTool.POST || tool == DrawingTool.IDEA) drawing = drawing.copy(link = "https://example.com/post")
                            if (tool == DrawingTool.SIGNPOST) drawing = drawing.copy(glyph = "🙂")
                            if (tool == DrawingTool.TABLE) drawing = drawing.copy(table = listOf(listOf("品种", "方向", "数量"), listOf("ES", "多", "1"), listOf("NQ", "空", "2")))
                            drawing = completeAnchors(drawing, ctx)
                            val scene = buildScene(drawing, ctx, expanded = true)
                            drawDrawings(DrawingFrame(listOf(scene), drawing.id, null, emptyMap(), null, null, emptyList()), plot, measurer, Color(0xFF131313))
                            drawFullHeightParts(DrawingFrame(listOf(scene), drawing.id, null, emptyMap(), null, null, emptyList()), plot, plot.bottom)
                            drawText(measurer.measure(tool.label, TextStyle(color = Color.White, fontSize = 13.sp)), topLeft = Offset(8f, 2f))
                        }
                    }
                }
            }
            waitForIdle()
            val folder = File("../artifacts/drawing-gallery").also { it.mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
        }
    }

    @Test fun rendersEveryToolGroup() {
        ToolGroup.entries.forEach { group -> sheet(group.name.lowercase(), group.tools) }
        sheet("others", listOf(DrawingTool.ICON, DrawingTool.MEASURE))
    }
}
