package com.unitrading.chart.drawing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.chart.ChartViewport
import com.unitrading.chart.PriceScale
import com.unitrading.chart.barChange
import com.unitrading.chart.dateTime
import com.unitrading.chart.formatVolume
import com.unitrading.ui.TradingPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The bridge between [ChartDrawings] and one chart's current geometry. */
internal class DrawingLayer(val controller: ChartDrawings) {
    /** Replaced on every recomposition of the chart, so input always sees the current scale. */
    var ctx: DrawingContext? = null
    var orderLineAt: (Offset) -> Boolean = { false }
    var onZoomRange: (Double, Double) -> Unit = { _, _ -> }
    var indicatorValuesAt: (Int) -> List<Double> = { emptyList() }
    val focus = FocusRequester()
    var frame: DrawingFrame? = null
    /** Where the mouse rests, for the dot cursor. */
    var hoverPoint by mutableStateOf<Offset?>(null)

    fun bindChart(orderLineAt: (Offset) -> Boolean, onZoomRange: (Double, Double) -> Unit, indicatorValuesAt: (Int) -> List<Double>) {
        this.orderLineAt = orderLineAt
        this.onZoomRange = onZoomRange
        this.indicatorValuesAt = indicatorValuesAt
    }

    private val placing: Boolean get() = controller.tool != null || controller.zooming
    val showsCrosshair: Boolean get() = placing || controller.cursor == ChartCursor.CROSS || controller.cursor == ChartCursor.DOT
    val showsCrosshairLines: Boolean get() = placing || controller.cursor == ChartCursor.CROSS

    fun indicatorValues(x: Float): List<Double> = ctx?.coords?.candleIndexAt(x)?.let(indicatorValuesAt).orEmpty()

    /** Which pointer shape the chart shows; null leaves it to the order lines. */
    val cursor: PointerIcon? get() = when {
        controller.tool != null || controller.zooming || controller.cursor == ChartCursor.DEMONSTRATION -> PointerIcon.Crosshair
        controller.cursor == ChartCursor.ERASER -> if (controller.hoveredId != null) PointerIcon.Hand else PointerIcon.Crosshair
        controller.hoveredId != null -> PointerIcon.Hand
        else -> null
    }
}

/** Sets up the drawing layer for this composition of the chart and refreshes its geometry. */
@Composable
internal fun rememberDrawingLayer(
    controller: ChartDrawings,
    symbol: String,
    instrument: Instrument,
    timeframe: Timeframe,
    candles: List<Candle>,
    periodMs: Long,
    viewport: ChartViewport,
    scale: PriceScale,
    plot: Rect,
    chartBottom: Float,
    palette: TradingPalette,
): DrawingLayer {
    DisposableEffect(symbol) { controller.bind(symbol); onDispose { } }
    val layer = remember(controller) { DrawingLayer(controller) }
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val images = controller.images
    layer.ctx = DrawingContext(
        DrawingCoordinates(candles, periodMs, viewport, plot, chartBottom, scale), instrument, timeframe, density, palette.Surface, palette.Ink, palette.Up, palette.Down,
        { text, size, bold, italic, maxWidth -> measurer.layout(text, size, bold, italic, maxWidth).size.let { Size(it.width.toFloat(), it.height.toFloat()) } },
        images.mapValues { (_, bitmap) -> Size(bitmap.width.toFloat(), bitmap.height.toFloat()) },
    )
    LaunchedEffect(controller.demoStrokes.any { it.endedAtMs != null }) {
        while (controller.demoStrokes.any { it.endedAtMs != null }) withFrameMillis { controller.tickDemo(currentTimeMs()) }
    }
    return layer
}

private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** Draws the layer's drawings inside the plot, below the order lines and the crosshair. */
internal fun DrawScope.drawLayerDrawings(layer: DrawingLayer, measurer: androidx.compose.ui.text.TextMeasurer, chartColor: androidx.compose.ui.graphics.Color) {
    val ctx = layer.ctx ?: return
    val frame = layer.controller.frame(ctx)
    layer.frame = frame
    drawDrawings(frame, ctx.coords.plot, measurer, chartColor)
}

/** Axis tags, outside the plot's clip. */
internal fun DrawScope.drawLayerMarks(layer: DrawingLayer, labelStyle: TextStyle, measurer: androidx.compose.ui.text.TextMeasurer, axisLeft: Float, axisRight: Float, timeAxisTop: Float) {
    val ctx = layer.ctx ?: return
    val frame = layer.frame ?: return
    drawFullHeightParts(frame, ctx.coords.plot, ctx.coords.chartBottom)
    drawDrawingMarks(frame, ctx, labelStyle, measurer, axisLeft, axisRight, timeAxisTop)
    if (layer.controller.cursor == ChartCursor.DOT && !layer.showsCrosshairLines) layer.hoverPoint?.let { drawCircle(ctx.ink, 3f * density, it) }
}

/** The view showing bars [from]..[to], as the zoom tool leaves it. */
internal fun zoomedViewport(from: Double, to: Double, total: Int): ChartViewport {
    val count = (to - from + 1).coerceIn(12.0, 600.0)
    return ChartViewport(count, total - count - from + .5).bounded(total)
}

/**
 * Gives the drawing layer first refusal on pointer input, on the Initial pass, so a press that places or grabs a drawing never
 * reaches the chart's pan and tap detectors.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.drawingInput(layer: DrawingLayer): Modifier {
    val controller = layer.controller
    return focusRequester(layer.focus)
        .onPreviewKeyEvent { event -> handleKey(event, layer) }
        .pointerInput(layer) {
            coroutineScope {
                val scope = this
                var hold: Job? = null
                var taken = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val ctx = layer.ctx ?: continue
                        val touch = change.type == PointerType.Touch
                        fun info(pressed: Boolean) = PointerInfo(change.position, event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed, event.keyboardModifiers.isShiftPressed, touch, pressed, change.uptimeMillis)
                        when {
                            event.type == PointerEventType.Scroll -> Unit
                            event.type == PointerEventType.Exit -> { layer.hoverPoint = null; controller.pointerExit() }
                            change.changedToDown() -> {
                                runCatching { layer.focus.requestFocus() }
                                if (event.buttons.isSecondaryPressed) {
                                    if (controller.openMenuAt(change.position, ctx, layer.orderLineAt)) event.changes.forEach { it.consume() }
                                    continue
                                }
                                val placing = controller.tool != null || controller.zooming
                                taken = controller.pointerDown(info(true), ctx, layer.indicatorValues(change.position.x), layer.orderLineAt)
                                if (taken) event.changes.forEach { it.consume() }
                                hold?.cancel()
                                val at = change.position
                                if (!placing && (touch || !taken)) hold = scope.launch {
                                    delay(450)
                                    if (taken) controller.openMenuAt(at, ctx, layer.orderLineAt) else controller.showTooltip(at)
                                }
                            }
                            change.changedToUp() -> {
                                hold?.cancel()
                                controller.hideTooltip()
                                val handled = controller.pointerUp(info(false), ctx, layer.indicatorValues(change.position.x), layer.onZoomRange)
                                if (handled || taken) event.changes.forEach { it.consume() }
                                taken = false
                            }
                            change.pressed -> {
                                if (kotlin.math.abs(change.position.x - change.previousPosition.x) + kotlin.math.abs(change.position.y - change.previousPosition.y) > 1f) hold?.cancel()
                                if (controller.pointerMove(info(true), ctx, layer.indicatorValues(change.position.x)) || taken) event.changes.forEach { it.consume() }
                            }
                            else -> { layer.hoverPoint = change.position; controller.pointerHover(info(false), ctx, layer.indicatorValues(change.position.x)) }
                        }
                    }
                }
            }
        }
}

/** Sets the chart's pointer shape; none set keeps the order-line cursors. */
internal fun Modifier.drawingCursor(layer: DrawingLayer): Modifier {
    val icon = layer.cursor
    return pointerHoverIcon(icon ?: PointerIcon.Default, overrideDescendants = icon != null)
}

private fun handleKey(event: KeyEvent, layer: DrawingLayer): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val controller = layer.controller
    val ctx = layer.ctx
    val command = event.isCtrlPressed || event.isMetaPressed
    if (command) return when (event.key) {
        Key.Z -> { if (event.isShiftPressed) controller.redo() else controller.undo(); true }
        Key.Y -> { controller.redo(); true }
        Key.C -> { controller.copySelected(); false }
        Key.V -> { controller.paste(); false }
        else -> false
    }
    if (event.isAltPressed) {
        val tool = when (event.key) {
            Key.T -> DrawingTool.TREND_LINE
            Key.H -> DrawingTool.HORIZONTAL_LINE
            Key.J -> DrawingTool.HORIZONTAL_RAY
            Key.V -> DrawingTool.VERTICAL_LINE
            Key.C -> DrawingTool.CROSS_LINE
            Key.F -> DrawingTool.FIB_RETRACEMENT
            Key.R -> DrawingTool.RECTANGLE.takeIf { event.isShiftPressed }
            else -> null
        } ?: return false
        controller.chooseTool(tool)
        return true
    }
    return when (event.key) {
        Key.Delete, Key.Backspace -> if (controller.selectedId != null) { controller.removeSelected(); true } else false
        Key.Escape -> controller.escape()
        Key.Enter, Key.NumPadEnter -> ctx != null && controller.finishOpen(ctx)
        else -> false
    }
}

/** Value tooltip of the bar under a held pointer; the chart passes its context. */
@Composable
internal fun DrawingValuesTooltip(layer: DrawingLayer) {
    val point = layer.controller.tooltipAt ?: return
    val ctx = layer.ctx ?: return
    val c = ctx.coords
    val bar = c.candleIndexAt(point.x)?.let { c.candles.getOrNull(it) } ?: return
    val previous = c.candles.getOrNull(c.candleIndexAt(point.x)!! - 1)
    val instrument = ctx.instrument
    Box(Modifier.offset { IntOffset(min(point.x.roundToInt() + 16, max(0, (c.plot.right - 180 * ctx.density).roundToInt())), max(point.y.roundToInt() - 90, c.plot.top.roundToInt())) }) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                listOf(dateTime(bar.timeMs), "开 ${instrument.formatPrice(bar.open)}", "高 ${instrument.formatPrice(bar.high)}", "低 ${instrument.formatPrice(bar.low)}", "收 ${instrument.formatPrice(bar.close)}",
                    barChange(bar, previous).label(instrument), "量 ${formatVolume(bar.volume)}").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

