package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.unitrading.broker.Instrument
import com.unitrading.broker.Timeframe
import com.unitrading.broker.formatFixed
import com.unitrading.chart.dateTime
import com.unitrading.chart.parseHexColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

internal data class StrokeSpec(val color: Color, val width: Float, val dash: DrawingDash = DrawingDash.SOLID, val round: Boolean = false)

internal enum class HAlign { LEFT, CENTER, RIGHT }
internal enum class VAlign { TOP, MIDDLE, BOTTOM }

/** What a drawing is made of. Rendering and hit testing both work from these parts, so a shape is clickable where it is painted. */
internal sealed interface ScenePart { val hit: Boolean }

internal class PolyPart(
    val points: List<Offset>,
    val stroke: StrokeSpec?,
    val fill: Color?,
    val closed: Boolean,
    override val hit: Boolean,
    /** Runs down through the indicator panes instead of stopping at the bottom of the price pane. */
    val fullHeight: Boolean,
) : ScenePart

internal class TextPart(
    val text: String,
    val box: Rect,
    val color: Color,
    val sizeSp: Float,
    val bold: Boolean,
    val italic: Boolean,
    val background: Color?,
    val border: Color?,
    val corner: Float,
    val padding: Float,
    val angle: Float,
    val maxWidth: Float?,
    override val hit: Boolean,
) : ScenePart

internal class ImagePart(val imageId: String, val box: Rect, val angle: Float, override val hit: Boolean = true) : ScenePart

/** A grab point of a selected drawing. */
internal data class DrawingHandle(val id: Int, val position: Offset)

/** A price or time shown on the axis; `always` ones stay when the drawing is not selected. */
internal sealed interface AxisMark { val color: Color; val always: Boolean }
internal data class PriceMark(val price: Double, override val color: Color, override val always: Boolean) : AxisMark
internal data class TimeMark(val timeMs: Long, override val color: Color, override val always: Boolean) : AxisMark

internal class DrawingScene(val drawing: Drawing, val parts: List<ScenePart>, val handles: List<DrawingHandle>, val marks: List<AxisMark>)

/** Everything a drawing needs to know about the chart it sits on. */
internal class DrawingContext(
    val coords: DrawingCoordinates,
    val instrument: Instrument,
    val timeframe: Timeframe,
    val density: Float,
    val surface: Color,
    val ink: Color,
    val up: Color,
    val down: Color,
    /** Size of [text] at this font; `maxWidth` wraps it. */
    val measure: (text: String, sizeSp: Float, bold: Boolean, italic: Boolean, maxWidth: Float?) -> Size,
    val images: Map<String, Size> = emptyMap(),
) {
    fun price(value: Double): String {
        val tick = instrument.tickSize.toDouble()
        return instrument.formatPrice(((value / tick).roundToLong() * instrument.tickSize).coerceAtLeast(0))
    }
    /** Absolute price difference with a sign, tick rounded. */
    fun priceDiff(value: Double): String = (if (value < 0) "−" else "") + price(abs(value))
    fun ticks(value: Double): Long = (value / instrument.tickSize).roundToLong()
}

internal fun colorOf(hex: String?, fallback: Color = Color(0xFF2962FF)): Color = parseHexColor(hex) ?: fallback

/** Ratio or coefficient without trailing zeros: `0.236`, `1`, `2.618`. */
internal fun formatRatio(value: Double, decimals: Int = 3): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val text = formatFixed((abs(value) * scale).roundToLong(), decimals).trimEnd('0').trimEnd('.')
    return (if (value < 0) "−" else "") + text.ifEmpty { "0" }
}

internal fun signed(value: Double, decimals: Int = 2): String = (if (value < 0) "−" else "+") + formatRatio(abs(value), decimals)

/** `3天 4小时`, `45分钟`. */
internal fun formatSpan(milliseconds: Long): String {
    val minutes = abs(milliseconds) / 60_000
    val days = minutes / 1440
    val hours = minutes % 1440 / 60
    val rest = minutes % 60
    return listOfNotNull(days.takeIf { it > 0 }?.let { "${it}天" }, hours.takeIf { it > 0 }?.let { "${it}小时" }, rest.takeIf { it > 0 && days == 0L }?.let { "${it}分钟" }).joinToString(" ").ifEmpty { "0分钟" }
}

internal fun formatDate(timeMs: Long): String = dateTime(timeMs)

internal fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
internal fun distance(a: Offset, b: Offset) = (a - b).getDistance()

/** Lengthens the segment to where its infinite line leaves [view], on the sides asked for. */
internal fun extendSegment(a: Offset, b: Offset, view: Rect, left: Boolean, right: Boolean): Pair<Offset, Offset> {
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (dx == 0f && dy == 0f || !left && !right) return a to b
    var enter = Double.NEGATIVE_INFINITY
    var exit = Double.POSITIVE_INFINITY
    var outside = false
    fun clip(p: Float, q: Float) {
        if (p == 0f) { if (q < 0) outside = true; return }
        val r = q.toDouble() / p
        if (p < 0) enter = max(enter, r) else exit = min(exit, r)
    }
    clip(-dx, a.x - view.left); clip(dx, view.right - a.x); clip(-dy, a.y - view.top); clip(dy, view.bottom - a.y)
    if (outside || enter > exit) return a to b
    fun at(t: Double) = Offset((a.x + dx * t).toFloat(), (a.y + dy * t).toFloat())
    return (if (left) at(min(0.0, enter)) else a) to (if (right) at(max(1.0, exit)) else b)
}

/** Points along an ellipse; angles in radians with y pointing down, as on the screen. */
internal fun ellipsePoints(center: Offset, rx: Float, ry: Float, rotation: Double = 0.0, from: Double = 0.0, sweep: Double = 2 * PI, steps: Int = 96): List<Offset> {
    val cr = cos(rotation)
    val sr = sin(rotation)
    return List(steps + 1) { i ->
        val t = from + sweep * i / steps
        val x = rx * cos(t)
        val y = ry * sin(t)
        Offset((center.x + x * cr - y * sr).toFloat(), (center.y + x * sr + y * cr).toFloat())
    }
}

internal fun quadPoints(a: Offset, control: Offset, b: Offset, steps: Int = 48): List<Offset> = List(steps + 1) { i ->
    val t = i.toFloat() / steps
    a * ((1 - t) * (1 - t)) + control * (2 * t * (1 - t)) + b * (t * t)
}

internal fun cubicPoints(a: Offset, c1: Offset, c2: Offset, b: Offset, steps: Int = 64): List<Offset> = List(steps + 1) { i ->
    val t = i.toFloat() / steps
    val u = 1 - t
    a * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b * (t * t * t)
}

/** Screen angle of a→b in radians, y down. */
internal fun angleOf(a: Offset, b: Offset): Double = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())

internal class SceneBuilder(val ctx: DrawingContext, val d: Drawing, val expanded: Boolean = false) {
    private val parts = ArrayList<ScenePart>()
    private val handleList = ArrayList<DrawingHandle>()
    private val markList = ArrayList<AxisMark>()
    val c: DrawingCoordinates get() = ctx.coords
    val plot: Rect get() = c.plot
    val px: Float get() = ctx.density
    val s: DrawingStyle get() = d.style
    val pts: List<Offset> = d.points.map(c::offset)
    val base: Color = colorOf(s.color)
    val stroke = StrokeSpec(base, s.width * px, s.dash)
    val ink: Color = colorOf(s.textColor ?: s.color)
    /** Slightly larger than the plot so line ends fall outside its clip. */
    val view: Rect = Rect(plot.left - 2f, plot.top - 2f, plot.right + 2f, plot.bottom + 2f)
    val area: Color? = s.fill?.let { colorOf(it).copy(alpha = s.fillAlpha) }
    val textSize: Float get() = s.fontSize.toFloat()

    fun line(a: Offset, b: Offset, spec: StrokeSpec = stroke, hit: Boolean = true, fullHeight: Boolean = false) {
        parts += PolyPart(listOf(a, b), spec, null, closed = false, hit, fullHeight)
    }

    fun polyline(points: List<Offset>, spec: StrokeSpec? = stroke, fill: Color? = null, closed: Boolean = false, hit: Boolean = true) {
        if (points.size >= 2) parts += PolyPart(points, spec, fill, closed, hit, false)
    }

    fun fillArea(points: List<Offset>, color: Color?, hit: Boolean = false) {
        if (color != null && points.size >= 3) parts += PolyPart(points, null, color, closed = true, hit, false)
    }

    fun rectPoints(a: Offset, b: Offset) = listOf(Offset(a.x, a.y), Offset(b.x, a.y), Offset(b.x, b.y), Offset(a.x, b.y))

    fun arrowHead(tip: Offset, from: Offset, spec: StrokeSpec = stroke, length: Float = (6f + s.width * 2) * px) {
        if (tip == from) return
        val angle = angleOf(tip, from)
        fun wing(delta: Double) = Offset((tip.x + length * cos(angle + delta)).toFloat(), (tip.y + length * sin(angle + delta)).toFloat())
        polyline(listOf(wing(.5), tip, wing(-.5)), spec.copy(dash = DrawingDash.SOLID))
    }

    fun dot(at: Offset, radius: Float = 3f * px, color: Color = base, hit: Boolean = true) {
        parts += PolyPart(ellipsePoints(at, radius, radius, steps = 24), null, color, closed = true, hit, false)
    }

    /** A label whose [h]/[v] corner sits at [at]. */
    fun label(
        text: String, at: Offset, color: Color = ink, h: HAlign = HAlign.CENTER, v: VAlign = VAlign.MIDDLE, sizeSp: Float = 12f, bold: Boolean = false, italic: Boolean = false,
        background: Color? = null, border: Color? = null, corner: Float = 2f * px, padding: Float = 0f, angle: Float = 0f, maxWidth: Float? = null, hit: Boolean = true,
    ): Rect {
        val size = ctx.measure(text, sizeSp, bold, italic, maxWidth)
        val w = size.width + padding * 2
        val tall = size.height + padding * 2
        val left = when (h) { HAlign.LEFT -> at.x; HAlign.CENTER -> at.x - w / 2; HAlign.RIGHT -> at.x - w }
        val top = when (v) { VAlign.TOP -> at.y; VAlign.MIDDLE -> at.y - tall / 2; VAlign.BOTTOM -> at.y - tall }
        val box = Rect(left, top, left + w, top + tall)
        parts += TextPart(text, box, color, sizeSp, bold, italic, background, border, corner, padding, angle, maxWidth, hit)
        return box
    }

    fun image(imageId: String, box: Rect, angle: Float = 0f) { parts += ImagePart(imageId, box, angle) }

    /** The drawing's caption, centred above the a→b line and turned along it. */
    fun captionAlong(a: Offset, b: Offset) {
        if (d.text.isBlank()) return
        val center = mid(a, b)
        var angle = angleOf(a, b) * 180 / PI
        if (angle > 90) angle -= 180 else if (angle < -90) angle += 180
        label(d.text, center, h = HAlign.CENTER, v = VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic, angle = angle.toFloat())
    }

    /** The drawing's caption centred in [box]. */
    fun captionIn(box: Rect) {
        if (d.text.isNotBlank()) label(d.text, box.center, h = HAlign.CENTER, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic, maxWidth = max(box.width, 80f * px))
    }

    fun handle(id: Int, position: Offset) { handleList += DrawingHandle(id, position) }
    fun anchorHandles() { pts.forEachIndexed { index, point -> handle(index, point) } }
    fun markPrice(price: Double, color: Color = base, always: Boolean = false) { markList += PriceMark(price, color, always) }
    fun markTime(timeMs: Long, color: Color = base, always: Boolean = false) { markList += TimeMark(timeMs, color, always) }

    /** Axis tags of every anchor while selected. */
    fun anchorMarks() { d.points.forEach { markPrice(it.price); markTime(it.timeMs) } }

    fun fullHeightLine(x: Float, spec: StrokeSpec = stroke, hit: Boolean = true) = line(Offset(x, plot.top - 2f), Offset(x, c.chartBottom), spec, hit, fullHeight = true)

    fun build() = DrawingScene(d, parts, handleList, markList)
}

/** Builds what [d] looks like now. A drawing still being placed has fewer points, and each tool draws what it has so far. */
internal fun buildScene(d: Drawing, ctx: DrawingContext, expanded: Boolean = false): DrawingScene = SceneBuilder(ctx, d, expanded).apply {
    if (ctx.coords.candles.isEmpty()) return@apply
    when (d.tool) {
        DrawingTool.TREND_LINE, DrawingTool.RAY, DrawingTool.INFO_LINE, DrawingTool.EXTENDED_LINE, DrawingTool.ARROW -> trendLine()
        DrawingTool.TREND_ANGLE -> trendAngle()
        DrawingTool.HORIZONTAL_LINE -> horizontalLine()
        DrawingTool.HORIZONTAL_RAY -> horizontalRay()
        DrawingTool.VERTICAL_LINE -> verticalLine()
        DrawingTool.CROSS_LINE -> crossLine()
        DrawingTool.PARALLEL_CHANNEL -> parallelChannel()
        DrawingTool.REGRESSION_TREND -> regressionTrend()
        DrawingTool.FLAT_TOP_BOTTOM -> flatTopBottom()
        DrawingTool.DISJOINT_CHANNEL -> disjointChannel()
        DrawingTool.PITCHFORK, DrawingTool.SCHIFF_PITCHFORK, DrawingTool.MODIFIED_SCHIFF_PITCHFORK, DrawingTool.INSIDE_PITCHFORK -> pitchfork()
        DrawingTool.FIB_RETRACEMENT -> fibRetracement()
        DrawingTool.FIB_EXTENSION -> fibExtension()
        DrawingTool.FIB_CHANNEL -> fibChannel()
        DrawingTool.FIB_TIME_ZONE -> fibTimeZone()
        DrawingTool.FIB_SPEED_FAN -> fibSpeedFan()
        DrawingTool.FIB_TREND_TIME -> fibTrendTime()
        DrawingTool.FIB_CIRCLES -> fibCircles()
        DrawingTool.FIB_SPIRAL -> fibSpiral()
        DrawingTool.FIB_SPEED_ARCS -> fibSpeedArcs()
        DrawingTool.FIB_WEDGE -> fibWedge()
        DrawingTool.PITCHFAN -> pitchfan()
        DrawingTool.GANN_BOX -> gannBox()
        DrawingTool.GANN_SQUARE_FIXED, DrawingTool.GANN_SQUARE -> gannSquare()
        DrawingTool.GANN_FAN -> gannFan()
        DrawingTool.XABCD, DrawingTool.CYPHER -> harmonicPattern()
        DrawingTool.HEAD_SHOULDERS -> headAndShoulders()
        DrawingTool.ABCD -> abcdPattern()
        DrawingTool.TRIANGLE_PATTERN -> trianglePattern()
        DrawingTool.THREE_DRIVES -> threeDrives()
        DrawingTool.ELLIOTT_IMPULSE, DrawingTool.ELLIOTT_CORRECTION, DrawingTool.ELLIOTT_TRIANGLE, DrawingTool.ELLIOTT_DOUBLE, DrawingTool.ELLIOTT_TRIPLE -> elliottWave()
        DrawingTool.CYCLIC_LINES -> cyclicLines()
        DrawingTool.TIME_CYCLES -> timeCycles()
        DrawingTool.SINE_LINE -> sineLine()
        DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION -> position()
        DrawingTool.FORECAST -> forecast()
        DrawingTool.BARS_PATTERN -> barsPattern()
        DrawingTool.GHOST_FEED -> ghostFeed()
        DrawingTool.PROJECTION -> projection()
        DrawingTool.ANCHORED_VWAP -> anchoredVwap()
        DrawingTool.FIXED_RANGE_VOLUME_PROFILE, DrawingTool.ANCHORED_VOLUME_PROFILE -> volumeProfile()
        DrawingTool.PRICE_RANGE, DrawingTool.DATE_RANGE, DrawingTool.DATE_PRICE_RANGE, DrawingTool.MEASURE -> measureRange()
        DrawingTool.BRUSH, DrawingTool.HIGHLIGHTER -> freehand()
        DrawingTool.ARROW_MARKER -> arrowMarker()
        DrawingTool.ARROW_UP, DrawingTool.ARROW_DOWN -> arrowMark()
        DrawingTool.RECTANGLE -> rectangle()
        DrawingTool.ROTATED_RECTANGLE -> rotatedRectangle()
        DrawingTool.PATH -> pathShape()
        DrawingTool.CIRCLE -> circleShape()
        DrawingTool.ELLIPSE -> ellipseShape()
        DrawingTool.POLYLINE -> polylineShape()
        DrawingTool.TRIANGLE -> triangleShape()
        DrawingTool.ARC -> arcShape()
        DrawingTool.CURVE -> curveShape()
        DrawingTool.DOUBLE_CURVE -> doubleCurveShape()
        DrawingTool.TEXT -> textNote()
        DrawingTool.NOTE, DrawingTool.PIN -> markerNote()
        DrawingTool.PRICE_NOTE -> priceNote()
        DrawingTool.TABLE -> tableShape()
        DrawingTool.CALLOUT -> callout()
        DrawingTool.COMMENT -> comment()
        DrawingTool.PRICE_LABEL -> priceLabel()
        DrawingTool.SIGNPOST, DrawingTool.POST, DrawingTool.IDEA -> signpost()
        DrawingTool.FLAG -> flag()
        DrawingTool.IMAGE -> imageShape()
        DrawingTool.ICON -> iconShape()
    }
}.build()
