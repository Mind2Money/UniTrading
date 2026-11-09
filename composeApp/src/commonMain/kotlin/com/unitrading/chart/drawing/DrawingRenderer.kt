package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun drawingTextStyle(sizeSp: Float, bold: Boolean, italic: Boolean, color: Color = Color.Unspecified) = TextStyle(
    color = color, fontSize = sizeSp.sp, fontFeatureSettings = "tnum",
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
)

internal fun TextMeasurer.layout(text: String, sizeSp: Float, bold: Boolean, italic: Boolean, maxWidth: Float?, color: Color = Color.Unspecified) = measure(
    text, drawingTextStyle(sizeSp, bold, italic, color),
    constraints = if (maxWidth != null) Constraints(maxWidth = maxWidth.roundToInt().coerceAtLeast(1)) else Constraints(),
)

private fun dashEffect(dash: DrawingDash, width: Float, density: Float): PathEffect? = when (dash) {
    DrawingDash.SOLID -> null
    DrawingDash.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(max(width, density), max(width, density) * 2.5f))
    DrawingDash.DASHED -> PathEffect.dashPathEffect(floatArrayOf(max(width * 4, 6f * density), max(width * 3, 4f * density)))
}

private fun DrawScope.drawPoly(part: PolyPart) {
    val path = Path()
    part.points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
    if (part.closed || part.fill != null && part.stroke == null) path.close()
    part.fill?.let { drawPath(path, it) }
    part.stroke?.let { spec ->
        val outline = if (part.closed || part.fill == null) path else Path().apply {
            part.points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        }
        drawPath(outline, spec.color, style = Stroke(spec.width, cap = if (spec.round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = dashEffect(spec.dash, spec.width, density)))
    }
}

private fun DrawScope.drawTextPart(part: TextPart, measurer: TextMeasurer) {
    val layout = measurer.layout(part.text, part.sizeSp, part.bold, part.italic, part.maxWidth, part.color)
    fun paint() {
        part.background?.let { drawRoundRect(it, part.box.topLeft, part.box.size, CornerRadius(part.corner)) }
        part.border?.let { drawRoundRect(it, part.box.topLeft, part.box.size, CornerRadius(part.corner), style = Stroke(1f * density)) }
        drawText(layout, topLeft = Offset(part.box.left + part.padding, part.box.top + part.padding))
    }
    if (part.angle != 0f) rotate(part.angle, part.box.center) { paint() } else paint()
}

private fun DrawScope.drawImagePart(part: ImagePart, images: Map<String, ImageBitmap>) {
    val image = images[part.imageId] ?: return
    drawImage(image, srcSize = IntSize(image.width, image.height), dstOffset = IntOffset(part.box.left.roundToInt(), part.box.top.roundToInt()),
        dstSize = IntSize(part.box.width.roundToInt().coerceAtLeast(1), part.box.height.roundToInt().coerceAtLeast(1)))
}

private fun DrawScope.drawHandles(scene: DrawingScene, chartColor: Color, accent: Color, hovered: Boolean) {
    val radius = 4.5f * density
    scene.handles.forEach { handle ->
        drawCircle(chartColor, radius, handle.position)
        drawCircle(if (hovered) accent.copy(alpha = .6f) else accent, radius, handle.position, style = Stroke(1.5f * density))
    }
}

/** What the chart hands the renderer for one frame. */
internal class DrawingFrame(
    val scenes: List<DrawingScene>,
    val selectedId: String?,
    val hoveredId: String?,
    val images: Map<String, ImageBitmap>,
    val draft: DrawingScene?,
    val zoomBox: Rect?,
    val demoStrokes: List<DemoStroke>,
)

/** Paints the drawings of [frame] inside the price pane, under the order lines. */
internal fun DrawScope.drawDrawings(frame: DrawingFrame, plot: Rect, measurer: TextMeasurer, chartColor: Color) {
    fun paint(scene: DrawingScene) {
        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            scene.parts.forEach { part ->
                when (part) {
                    is PolyPart -> drawPoly(part)
                    is TextPart -> drawTextPart(part, measurer)
                    is ImagePart -> drawImagePart(part, frame.images)
                }
            }
        }
    }
    frame.scenes.forEach(::paint)
    frame.draft?.let(::paint)
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        frame.scenes.forEach { scene ->
            val selected = scene.drawing.id == frame.selectedId
            val hovered = scene.drawing.id == frame.hoveredId
            if (selected || hovered) drawHandles(scene, chartColor, colorOf(scene.drawing.style.color).let { if (it.alpha < .5f) it.copy(alpha = 1f) else it }, hovered && !selected)
        }
        frame.draft?.let { drawHandles(it, chartColor, colorOf(it.drawing.style.color), false) }
        frame.zoomBox?.let { box ->
            drawRect(Color(0xFF2962FF).copy(alpha = .15f), box.topLeft, box.size)
            drawRect(Color(0xFF2962FF), box.topLeft, box.size, style = Stroke(1f * density))
        }
        frame.demoStrokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = Path()
            stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, Color(0xFFF23645).copy(alpha = .7f * stroke.opacity), style = Stroke(4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Continues full-height lines down through the indicator panes, which are painted after the price pane. */
internal fun DrawScope.drawFullHeightParts(frame: DrawingFrame, plot: Rect, chartBottom: Float) {
    if (chartBottom <= plot.bottom) return
    clipRect(plot.left, plot.bottom, plot.right, chartBottom) {
        (frame.scenes + listOfNotNull(frame.draft)).forEach { scene -> scene.parts.forEach { if (it is PolyPart && it.fullHeight) drawPoly(it) } }
    }
}

/** Axis tags: every `always` mark, plus the anchors of the selected or just-placed drawing with a band between them. */
internal fun DrawScope.drawDrawingMarks(frame: DrawingFrame, ctx: DrawingContext, labelStyle: TextStyle, measurer: TextMeasurer, axisLeft: Float, axisRight: Float, timeAxisTop: Float) {
    val c = ctx.coords
    val pad = 3f * density
    val height = measurer.measure("0", labelStyle).size.height + pad * 2
    val active = frame.scenes.firstOrNull { it.drawing.id == frame.selectedId } ?: frame.draft
    frame.scenes.plus(listOfNotNull(frame.draft)).forEach { scene ->
        val on = scene === active
        val prices = scene.marks.filterIsInstance<PriceMark>().filter { it.always || on }
        val times = scene.marks.filterIsInstance<TimeMark>().filter { it.always || on }
        if (on && prices.size >= 2) {
            val low = prices.maxOf { c.y(it.price) }.coerceIn(c.plot.top, c.plot.bottom)
            val high = prices.minOf { c.y(it.price) }.coerceIn(c.plot.top, c.plot.bottom)
            drawRect(prices.first().color.copy(alpha = .18f), Offset(axisLeft, high), Size(axisRight - axisLeft, low - high))
        }
        if (on && times.size >= 2) {
            val left = times.minOf { c.x(it.timeMs) }.coerceIn(c.plot.left, c.plot.right)
            val right = times.maxOf { c.x(it.timeMs) }.coerceIn(c.plot.left, c.plot.right)
            drawRect(times.first().color.copy(alpha = .18f), Offset(left, timeAxisTop), Size(right - left, height))
        }
        prices.forEach { mark ->
            val y = c.y(mark.price)
            if (y !in c.plot.top..c.plot.bottom) return@forEach
            val text = measurer.measure(ctx.price(mark.price), labelStyle.copy(color = com.unitrading.ui.contentOn(mark.color)))
            drawRect(mark.color, Offset(axisLeft, y - height / 2), Size(axisRight - axisLeft, height))
            drawText(text, topLeft = Offset(axisLeft + pad, y - text.size.height / 2f))
        }
        times.forEach { mark ->
            val x = c.x(mark.timeMs)
            if (x !in c.plot.left..c.plot.right) return@forEach
            val text = measurer.measure(formatDate(mark.timeMs), labelStyle.copy(color = com.unitrading.ui.contentOn(mark.color)))
            val width = text.size.width + pad * 2
            val left = (x - width / 2).coerceIn(c.plot.left, max(c.plot.left, c.plot.right - width))
            drawRect(mark.color, Offset(left, timeAxisTop), Size(width, height))
            drawText(text, topLeft = Offset(left + pad, timeAxisTop + pad))
        }
    }
}

// ------------------------------------------------- hit testing ---------------------------------------------------

/** Distance from [point] to the segment a-b. */
internal fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distance(point, a)
    val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return distance(point, Offset(a.x + t * dx, a.y + t * dy))
}

internal fun insidePolygon(point: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** Whether [point] touches a clickable part of [scene], within [tolerance] pixels of its strokes. */
internal fun DrawingScene.hits(point: Offset, tolerance: Float): Boolean = parts.any { part ->
    part.hit && when (part) {
        is PolyPart -> {
            val strokeHit = part.stroke != null && part.points.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= tolerance + part.stroke.width / 2 } ||
                part.stroke != null && part.closed && part.points.size >= 2 && distanceToSegment(point, part.points.last(), part.points.first()) <= tolerance + part.stroke.width / 2
            strokeHit || part.fill != null && part.points.size >= 3 && insidePolygon(point, part.points)
        }
        is TextPart -> part.box.inflate(tolerance).contains(point)
        is ImagePart -> part.box.inflate(tolerance).contains(point)
    }
}

/** The handle within [tolerance] of [point], nearest first. */
internal fun DrawingScene.handleAt(point: Offset, tolerance: Float): DrawingHandle? =
    handles.minByOrNull { distance(it.position, point) }?.takeIf { distance(it.position, point) <= tolerance }

/** Absolute rectangle between two points, for overlays. */
internal fun absoluteRect(a: Offset, b: Offset): Rect = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
