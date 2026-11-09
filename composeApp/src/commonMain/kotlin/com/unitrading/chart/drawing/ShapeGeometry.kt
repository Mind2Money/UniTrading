package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Round joins through the midpoints of the raw pointer samples, so a stroke drawn by hand does not look faceted. */
private fun smoothStroke(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val out = ArrayList<Offset>(points.size * 4)
    out += points.first()
    for (i in 1 until points.lastIndex) out += quadPoints(mid(points[i - 1], points[i]), points[i], mid(points[i], points[i + 1]), 4)
    out += points.last()
    return out
}

internal fun SceneBuilder.freehand() {
    if (pts.isEmpty()) return
    handle(0, pts.first())
    if (pts.size > 1) handle(pts.lastIndex, pts.last())
    d.points.firstOrNull()?.let { markTime(it.timeMs) }
    val highlighter = d.tool == DrawingTool.HIGHLIGHTER
    val spec = if (highlighter) StrokeSpec(base.copy(alpha = s.fillAlpha), s.width * px, round = true) else stroke.copy(round = true)
    polyline(smoothStroke(pts), spec, fill = if (!highlighter) area else null)
    if (s.leftArrow && pts.size >= 2) arrowHead(pts[0], pts[1])
    if (s.rightArrow && pts.size >= 2) arrowHead(pts.last(), pts[pts.lastIndex - 1])
}

internal fun SceneBuilder.arrowMarker() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val tail = pts[0]
    val tip = pts[1]
    val length = distance(tail, tip)
    if (length < 1f) return
    val headLength = min(18f * px, length * .5f)
    val headHalf = min(12f * px, length * .4f)
    val shaftHalf = headHalf * .45f
    val dir = (tip - tail) / length
    val normal = Offset(-dir.y, dir.x)
    val neck = tip - dir * headLength
    val outline = listOf(tail + normal * shaftHalf, neck + normal * shaftHalf, neck + normal * headHalf, tip, neck - normal * headHalf, neck - normal * shaftHalf, tail - normal * shaftHalf)
    polyline(outline, null, base, closed = true)
    if (d.text.isNotBlank()) {
        val before = tail - dir * (6f * px)
        label(d.text, before, color = ink, h = if (dir.x >= 0) HAlign.RIGHT else HAlign.LEFT, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic)
    }
}

internal fun SceneBuilder.arrowMark() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val up = d.tool == DrawingTool.ARROW_UP
    val sign = if (up) 1f else -1f
    val tip = pts[0]
    val head = 8f * px
    val shaft = 3f * px
    val tall = 20f * px
    fun p(dx: Float, dy: Float) = Offset(tip.x + dx, tip.y + sign * dy)
    polyline(listOf(p(0f, 0f), p(head, head + 2f * px), p(shaft, head + 2f * px), p(shaft, tall), p(-shaft, tall), p(-shaft, head + 2f * px), p(-head, head + 2f * px)), null, base, closed = true)
    if (d.text.isNotBlank()) label(d.text, Offset(tip.x, tip.y + sign * (tall + 3f * px)), color = ink, h = HAlign.CENTER, v = if (up) VAlign.TOP else VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic)
}

internal fun SceneBuilder.rectangle() {
    anchorMarks()
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    val left = if (s.extendLeft) view.left else min(a.x, b.x)
    val right = if (s.extendRight) view.right else max(a.x, b.x)
    val top = min(a.y, b.y)
    val bottom = max(a.y, b.y)
    polyline(listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom)), stroke, area, closed = true)
    if (s.showMiddle) line(Offset(left, (top + bottom) / 2), Offset(right, (top + bottom) / 2), stroke.copy(width = px, dash = DrawingDash.DASHED), hit = false)
    captionIn(Rect(left, top, right, bottom))
    handle(0, a); handle(1, b)
    handle(2, Offset(b.x, a.y)); handle(3, Offset(a.x, b.y))
    handle(4, Offset((a.x + b.x) / 2, a.y)); handle(5, Offset((a.x + b.x) / 2, b.y))
    handle(6, Offset(a.x, (a.y + b.y) / 2)); handle(7, Offset(b.x, (a.y + b.y) / 2))
}

internal fun SceneBuilder.rotatedRectangle() {
    anchorMarks()
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    if (pts.size < 3) { handle(0, a); handle(1, b); line(a, b); return }
    val length = distance(a, b)
    if (length < 1f) { anchorHandles(); return }
    val normal = Offset(-(b.y - a.y) / length, (b.x - a.x) / length)
    val half = (pts[2] - a).let { it.x * normal.x + it.y * normal.y }
    polyline(listOf(a + normal * half, b + normal * half, b - normal * half, a - normal * half), stroke, area, closed = true)
    handle(0, a); handle(1, b); handle(2, mid(a, b) + normal * half); handle(3, mid(a, b) - normal * half)
}

internal fun SceneBuilder.pathShape() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    polyline(pts)
    if (s.rightArrow) arrowHead(pts.last(), pts[pts.lastIndex - 1])
    if (s.leftArrow) arrowHead(pts[0], pts[1])
}

internal fun SceneBuilder.circleShape() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val radius = distance(pts[0], pts[1])
    polyline(ellipsePoints(pts[0], radius, radius), stroke, area, closed = true)
    captionIn(Rect(pts[0], radius))
}

internal fun SceneBuilder.ellipseShape() {
    anchorMarks()
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    handle(0, a); handle(1, b)
    if (pts.size < 3) { line(a, b); return }
    val center = mid(a, b)
    val major = distance(a, b) / 2
    val rotation = angleOf(a, b)
    val normal = Offset(-sin(rotation).toFloat(), cos(rotation).toFloat())
    val minor = abs((pts[2] - center).let { it.x * normal.x + it.y * normal.y })
    polyline(ellipsePoints(center, major, minor, rotation), stroke, area, closed = true)
    handle(2, center + normal * minor); handle(3, center - normal * minor)
    captionIn(Rect(center, min(major, minor)))
}

internal fun SceneBuilder.polylineShape() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val closed = d.param("closed") != 0.0
    polyline(pts, stroke, area?.takeIf { closed || pts.size >= 3 }, closed = closed)
}

internal fun SceneBuilder.triangleShape() {
    anchorHandles(); anchorMarks()
    if (pts.size >= 3) polyline(pts, stroke, area, closed = true) else polyline(pts)
}

/** Circle through three points; null when they are in a line. */
internal fun circumcircle(a: Offset, b: Offset, c: Offset): Pair<Offset, Float>? {
    val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
    if (abs(d) < 1e-3f) return null
    val a2 = a.x * a.x + a.y * a.y
    val b2 = b.x * b.x + b.y * b.y
    val c2 = c.x * c.x + c.y * c.y
    val center = Offset((a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d, (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d)
    return center to distance(center, a)
}

internal fun SceneBuilder.arcShape() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    if (pts.size < 3) { line(pts[0], pts[1]); return }
    val circle = circumcircle(pts[0], pts[1], pts[2])
    if (circle == null) { line(pts[0], pts[1]); return }
    val (center, radius) = circle
    val start = angleOf(center, pts[0])
    val end = angleOf(center, pts[1])
    val through = angleOf(center, pts[2])
    fun normalize(angle: Double): Double { var v = angle; while (v < 0) v += 2 * PI; while (v >= 2 * PI) v -= 2 * PI; return v }
    val forward = normalize(end - start)
    val sweep = if (normalize(through - start) <= forward) forward else forward - 2 * PI
    polyline(ellipsePoints(center, radius, radius, from = start, sweep = sweep, steps = 96), stroke, area, closed = false)
}

internal fun SceneBuilder.curveShape() {
    anchorMarks()
    if (pts.size < 2) { anchorHandles(); return }
    handle(0, pts[0]); handle(1, pts[1])
    if (pts.size < 3) { line(pts[0], pts[1]); return }
    handle(2, pts[2])
    polyline(quadPoints(pts[0], pts[2], pts[1], 64), stroke, area)
}

internal fun SceneBuilder.doubleCurveShape() {
    anchorMarks()
    if (pts.size < 2) { anchorHandles(); return }
    handle(0, pts[0]); handle(1, pts[1])
    if (pts.size < 4) { line(pts[0], pts[1]); return }
    handle(2, pts[2]); handle(3, pts[3])
    polyline(cubicPoints(pts[0], pts[2], pts[3], pts[1], 72), stroke, area)
}

internal fun SceneBuilder.textNote() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val fill = area
    label(d.text.ifBlank { "文字" }, pts[0], color = base, h = HAlign.LEFT, v = VAlign.TOP, sizeSp = textSize, bold = s.bold, italic = s.italic,
        background = fill, padding = if (fill != null) 4f * px else 0f, maxWidth = 320f * px)
}

/** A small round marker; its text opens beside it while hovered or selected. */
internal fun SceneBuilder.markerNote() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val at = pts[0]
    val radius = 9f * px
    val center = Offset(at.x, at.y - radius * 1.8f)
    polyline(listOf(at, Offset(center.x - radius * .75f, center.y + radius * .55f), Offset(center.x + radius * .75f, center.y + radius * .55f)), null, base, closed = true)
    polyline(ellipsePoints(center, radius, radius, steps = 32), null, base, closed = true)
    if (expanded && d.text.isNotBlank()) {
        label(d.text, Offset(center.x + radius + 4f * px, center.y), color = ink, h = HAlign.LEFT, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic,
            background = area, padding = 6f * px, corner = 4f * px, maxWidth = 280f * px, hit = false)
    }
}

internal fun SceneBuilder.priceNote() {
    anchorHandles(); anchorMarks()
    if (pts.isEmpty()) return
    val at = pts[0]
    if (s.showPrices) label(ctx.price(d.points[0].price), Offset(at.x, at.y), color = Color.White, h = HAlign.CENTER, v = VAlign.BOTTOM, sizeSp = 12f, background = base, padding = 3f * px, hit = true)
    if (pts.size < 2) return
    line(at, pts[1])
    dot(at, 3f * px)
    if (d.text.isNotBlank()) label(d.text, Offset(pts[1].x + 4f * px, pts[1].y), color = ink, h = HAlign.LEFT, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic)
}

internal fun SceneBuilder.tableShape() {
    val origin = Offset(plot.left + plot.width * d.param("screenX", .25).toFloat(), plot.top + plot.height * d.param("screenY", .25).toFloat())
    handle(0, origin)
    val columns = d.table.firstOrNull()?.size ?: return
    val cellPadding = 5f * px
    val widths = FloatArray(columns) { 96f * px }
    val heights = FloatArray(d.table.size) { textSize * px * 1.5f }
    d.table.forEachIndexed { r, row ->
        row.forEachIndexed { col, text ->
            if (text.isEmpty()) return@forEachIndexed
            val size = ctx.measure(text, textSize, s.bold, s.italic, null)
            widths[col] = max(widths[col], size.width)
            heights[r] = max(heights[r], size.height)
        }
    }
    val totalWidth = widths.sum() + cellPadding * 2 * columns
    val totalHeight = heights.sum() + cellPadding * 2 * d.table.size
    val fill = area
    polyline(listOf(origin, origin + Offset(totalWidth, 0f), origin + Offset(totalWidth, totalHeight), origin + Offset(0f, totalHeight)), stroke, fill, closed = true)
    var y = origin.y
    d.table.forEachIndexed { r, row ->
        var x = origin.x
        row.forEachIndexed { col, text ->
            if (text.isNotEmpty()) label(text, Offset(x + cellPadding, y + cellPadding), color = ink, h = HAlign.LEFT, v = VAlign.TOP, sizeSp = textSize, bold = s.bold, italic = s.italic, hit = false)
            x += widths[col] + cellPadding * 2
            if (col < columns - 1) line(Offset(x, origin.y), Offset(x, origin.y + totalHeight), stroke.copy(width = px), hit = false)
        }
        y += heights[r] + cellPadding * 2
        if (r < d.table.lastIndex) line(Offset(origin.x, y), Offset(origin.x + totalWidth, y), stroke.copy(width = px), hit = false)
    }
}

internal fun SceneBuilder.callout() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val at = pts[0]
    val text = d.text.ifBlank { "文字" }
    val box = label(text, pts[1], color = ink, h = HAlign.CENTER, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic, background = area, border = base, padding = 8f * px, corner = 6f * px, maxWidth = 280f * px)
    val center = box.center
    val direction = at - center
    val length = direction.getDistance()
    if (length < 1f) return
    val normal = Offset(-direction.y / length, direction.x / length)
    val width = min(box.width, box.height) * .3f
    polyline(listOf(center + normal * width, at, center - normal * width), null, area, closed = true, hit = false)
    line(at, center + normal * width, stroke.copy(width = stroke.width / 2), hit = false)
    line(at, center - normal * width, stroke.copy(width = stroke.width / 2), hit = false)
}

internal fun SceneBuilder.comment() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val at = pts[0]
    val gap = 12f * px
    val box = label(d.text.ifBlank { "评论" }, Offset(at.x + gap * .5f, at.y - gap), color = ink, h = HAlign.LEFT, v = VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic,
        background = area, padding = 8f * px, corner = 8f * px, maxWidth = 280f * px)
    polyline(listOf(at, Offset(box.left + 10f * px, box.bottom - 1f), Offset(box.left + 24f * px, box.bottom - 1f)), null, area, closed = true)
}

internal fun SceneBuilder.priceLabel() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val at = pts[0]
    val tail = 8f * px
    val box = label(ctx.price(d.points[0].price), Offset(at.x + tail, at.y - tail), color = ink, h = HAlign.LEFT, v = VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic, background = area, padding = 5f * px, corner = 3f * px)
    polyline(listOf(at, Offset(box.left, box.bottom - 12f * px), Offset(box.left + 12f * px, box.bottom)), null, area, closed = true)
}

/** A stick up from the bar with a plate on it; posts and ideas show their link there. */
internal fun SceneBuilder.signpost() {
    if (pts.isEmpty()) return
    anchorMarks()
    val at = pts[0]
    val plateY = (at.y - plot.height * d.param("position", 27.0).toFloat() / 100).coerceAtLeast(plot.top + 12f * px)
    line(at, Offset(at.x, plateY), stroke.copy(width = stroke.width / 2))
    val prefix = when (d.tool) { DrawingTool.POST -> "帖子"; DrawingTool.IDEA -> "观点"; else -> null }
    val body = listOfNotNull(prefix, d.glyph, d.text.ifBlank { d.link.ifBlank { null } }).joinToString(" ").ifEmpty { "指示牌" }
    val box = label(body, Offset(at.x, plateY), color = ink, h = HAlign.CENTER, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic, background = area ?: base, padding = 5f * px, corner = 4f * px, maxWidth = 240f * px)
    handle(0, at)
    handle(1, box.center)
}

internal fun SceneBuilder.flag() {
    if (pts.isEmpty()) return
    handle(0, pts[0]); anchorMarks()
    val at = pts[0]
    val top = Offset(at.x, at.y - 22f * px)
    line(at, top, stroke.copy(width = 2f * px))
    polyline(listOf(top, Offset(top.x + 15f * px, top.y + 5f * px), Offset(top.x, top.y + 11f * px)), null, base, closed = true)
}

internal fun SceneBuilder.imageShape() {
    if (pts.isEmpty()) return
    anchorMarks()
    val at = pts[0]
    val size = ctx.images[d.imageId]
    val scale = d.param("width", 200.0).toFloat() * px
    val width = scale
    val height = if (size != null && size.width > 0f) scale * size.height / size.width else d.param("height", 150.0).toFloat() * px
    val box = Rect(at.x, at.y, at.x + width, at.y + height)
    if (size != null && d.imageId != null) image(d.imageId, box)
    else polyline(rectPoints(box.topLeft, box.bottomRight), stroke, Color(0xFF808080).copy(alpha = .2f), closed = true)
    handle(0, at)
    handle(1, box.bottomRight)
}

internal fun SceneBuilder.iconShape() {
    if (pts.isEmpty()) return
    anchorMarks()
    val at = pts[0]
    val size = d.param("size", 72.0).toFloat()
    val angle = d.param("angle").toFloat()
    val box = label(d.glyph ?: "😀", at, color = base, h = HAlign.CENTER, v = VAlign.MIDDLE, sizeSp = size, angle = angle)
    handle(0, at)
    handle(1, Offset(box.right, box.bottom))
    handle(2, Offset(at.x, box.top - 14f * px))
}
