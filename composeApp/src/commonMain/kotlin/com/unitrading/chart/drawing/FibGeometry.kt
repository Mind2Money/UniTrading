package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fill tint of one band; the level above the band gives it its colour. */
private fun SceneBuilder.band(level: DrawingLevel): Color? = area?.let { colorOf(level.color, base).copy(alpha = s.fillAlpha) }
private fun SceneBuilder.levelStroke(level: DrawingLevel, width: Float = s.width) = StrokeSpec(colorOf(level.color, base), width * px)
private fun visibleLevels(levels: List<DrawingLevel>) = levels.filter { it.visible }.sortedBy { it.value }

/** Horizontal price levels between [x1] and [x2], with their bands and labels; [priceOf] maps a level to its price. */
private fun SceneBuilder.horizontalBands(x1: Float, x2: Float, priceOf: (Double) -> Double) {
    val left = if (s.extendLeft) view.left else min(x1, x2)
    val right = if (s.extendRight) view.right else max(x1, x2)
    val levels = visibleLevels(d.levels)
    levels.zipWithNext().forEach { (lower, upper) ->
        val y1 = c.y(priceOf(lower.value))
        val y2 = c.y(priceOf(upper.value))
        fillArea(listOf(Offset(left, y1), Offset(right, y1), Offset(right, y2), Offset(left, y2)), band(upper))
    }
    levels.forEach { level ->
        val price = priceOf(level.value)
        val y = c.y(price)
        line(Offset(left, y), Offset(right, y), levelStroke(level))
        val text = listOfNotNull(formatRatio(level.value).takeIf { s.showLabels }, ctx.price(price).takeIf { s.showPrices }?.let { if (s.showLabels) "($it)" else it }).joinToString(" ")
        if (text.isNotEmpty()) label(text, Offset(left - 4f * px, y), color = colorOf(level.color, base), h = HAlign.RIGHT, v = VAlign.MIDDLE, hit = false)
    }
}

internal fun SceneBuilder.fibRetracement() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val first = d.points[0].price
    val second = d.points[1].price
    val (zero, one) = if (s.reverse) first to second else second to first
    horizontalBands(pts[0].x, pts[1].x) { zero + (one - zero) * it }
    line(pts[0], pts[1], stroke)
}

internal fun SceneBuilder.fibExtension() {
    anchorHandles(); anchorMarks()
    if (pts.size >= 2) line(pts[0], pts[1], stroke)
    if (pts.size < 3) return
    line(pts[1], pts[2], stroke)
    val move = d.points[1].price - d.points[0].price
    val base = d.points[2].price
    horizontalBands(pts[1].x, pts[2].x) { base + move * it }
}

internal fun SceneBuilder.fibChannel() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    if (pts.size < 3) { line(a, b); return }
    val offset = channelOffset(a, b, pts[2])
    val levels = visibleLevels(d.levels)
    fun segment(level: Double) = extendSegment(Offset(a.x, a.y + (offset * level).toFloat()), Offset(b.x, b.y + (offset * level).toFloat()), view, s.extendLeft, s.extendRight)
    levels.zipWithNext().forEach { (lower, upper) ->
        val (l1, r1) = segment(lower.value)
        val (l2, r2) = segment(upper.value)
        fillArea(listOf(l1, r1, r2, l2), band(upper))
    }
    levels.forEach { level ->
        val (from, to) = segment(level.value)
        line(from, to, levelStroke(level))
        if (s.showLabels) label(formatRatio(level.value), Offset(from.x - 4f * px, from.y), color = colorOf(level.color, base), h = HAlign.RIGHT, v = VAlign.MIDDLE, hit = false)
    }
}

/** Vertical lines at bar positions; [position] maps a level to a fractional bar. */
private fun SceneBuilder.verticalLevels(position: (Double) -> Double) {
    val levels = visibleLevels(d.levels)
    levels.zipWithNext().forEach { (left, right) ->
        val x1 = c.xOfIndex(position(left.value))
        val x2 = c.xOfIndex(position(right.value))
        fillArea(listOf(Offset(x1, view.top), Offset(x2, view.top), Offset(x2, view.bottom), Offset(x1, view.bottom)), band(right))
    }
    levels.forEach { level ->
        val x = c.xOfIndex(position(level.value))
        if (x < plot.left - 4f || x > plot.right + 4f) return@forEach
        fullHeightLine(x, levelStroke(level))
        if (s.showLabels) label(formatRatio(level.value), Offset(x + 3f * px, plot.bottom - 3f * px), color = colorOf(level.color, base), h = HAlign.LEFT, v = VAlign.BOTTOM, hit = false)
    }
}

internal fun SceneBuilder.fibTimeZone() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val i1 = c.index(d.points[0].timeMs)
    val unit = c.index(d.points[1].timeMs) - i1
    line(pts[0], pts[1], stroke)
    verticalLevels { i1 + it * unit }
}

internal fun SceneBuilder.fibTrendTime() {
    anchorHandles(); anchorMarks()
    if (pts.size >= 2) line(pts[0], pts[1], stroke)
    if (pts.size < 3) return
    line(pts[1], pts[2], stroke)
    val unit = c.index(d.points[1].timeMs) - c.index(d.points[0].timeMs)
    val origin = c.index(d.points[2].timeMs)
    verticalLevels { origin + it * unit }
}

/** Rays from the first anchor through the far edges of its box, with the box's grid and ratio labels on each side. */
internal fun SceneBuilder.fibSpeedFan() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val grid = stroke.copy(width = px)
    val horizontal = visibleLevels(d.levels)
    val vertical = visibleLevels(d.levels2)
    horizontal.forEach { level ->
        val y = a.y + (b.y - a.y) * level.value.toFloat()
        line(Offset(a.x, y), Offset(b.x, y), grid, hit = false)
        if (s.showLabels) {
            label(formatRatio(level.value), Offset(min(a.x, b.x) - 3f * px, y), color = colorOf(level.color, base), h = HAlign.RIGHT, v = VAlign.MIDDLE, hit = false)
            label(formatRatio(level.value), Offset(max(a.x, b.x) + 3f * px, y), color = colorOf(level.color, base), h = HAlign.LEFT, v = VAlign.MIDDLE, hit = false)
        }
    }
    vertical.forEach { level ->
        val x = a.x + (b.x - a.x) * level.value.toFloat()
        line(Offset(x, a.y), Offset(x, b.y), grid, hit = false)
        if (s.showLabels) {
            label(formatRatio(level.value), Offset(x, min(a.y, b.y) - 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.BOTTOM, hit = false)
            label(formatRatio(level.value), Offset(x, max(a.y, b.y) + 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.TOP, hit = false)
        }
    }
    val rays = horizontal.map { level -> level to Offset(b.x, a.y + (b.y - a.y) * level.value.toFloat()) } +
        vertical.map { level -> level to Offset(a.x + (b.x - a.x) * level.value.toFloat(), b.y) }
    rays.zipWithNext().forEach { (one, two) ->
        val far1 = extendSegment(a, one.second, view, left = false, right = true).second
        val far2 = extendSegment(a, two.second, view, left = false, right = true).second
        fillArea(listOf(a, far1, far2), band(two.first))
    }
    rays.forEach { (level, through) ->
        if (through != a) line(a, extendSegment(a, through, view, left = false, right = true).second, levelStroke(level, 2f))
    }
}

private fun SceneBuilder.ringBands(center: Offset, radius: Float, from: Double, sweep: Double, levels: List<DrawingLevel>) {
    levels.forEachIndexed { index, level ->
        val outer = ellipsePoints(center, radius * level.value.toFloat(), radius * level.value.toFloat(), from = from, sweep = sweep, steps = 72)
        val previous = levels.getOrNull(index - 1)?.value?.toFloat() ?: 0f
        val inner = if (previous == 0f) listOf(center) else ellipsePoints(center, radius * previous, radius * previous, from = from, sweep = sweep, steps = 72).reversed()
        fillArea(outer + inner, band(level))
    }
    levels.forEach { level ->
        val r = radius * level.value.toFloat()
        polyline(ellipsePoints(center, r, r, from = from, sweep = sweep, steps = 96), levelStroke(level))
    }
}

internal fun SceneBuilder.fibCircles() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val center = pts[0]
    val radius = distance(center, pts[1])
    line(center, pts[1], stroke)
    val levels = visibleLevels(d.levels)
    ringBands(center, radius, 0.0, 2 * PI, levels)
    if (s.showLabels) levels.forEach { level ->
        label(formatRatio(level.value), Offset(center.x, center.y - radius * level.value.toFloat() - 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.BOTTOM, hit = false)
    }
}

internal fun SceneBuilder.fibSpeedArcs() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val center = pts[0]
    val radius = distance(center, pts[1])
    line(center, pts[1], stroke)
    val direction = angleOf(center, pts[1])
    val levels = visibleLevels(d.levels)
    val full = d.param("fullCircles") != 0.0
    ringBands(center, radius, if (full) 0.0 else direction - PI / 2, if (full) 2 * PI else PI, levels)
    if (s.showLabels) levels.forEach { level ->
        val r = radius * level.value.toFloat() + 10f * px
        label(formatRatio(level.value), Offset((center.x + r * cos(direction)).toFloat(), (center.y + r * sin(direction)).toFloat()), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.MIDDLE, hit = false)
    }
}

private const val GOLDEN = 1.618033988749895

internal fun SceneBuilder.fibSpiral() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val center = pts[0]
    val startRadius = distance(center, pts[1])
    line(center, pts[1], stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
    if (startRadius < 1f) return
    val startAngle = angleOf(center, pts[1])
    val direction = if (d.param("counterclockwise") != 0.0) -1.0 else 1.0
    val limit = hypot(plot.width, plot.height) * 2f
    val points = ArrayList<Offset>()
    var theta = -4 * PI
    while (theta < 12 * PI) {
        val radius = startRadius * GOLDEN.pow(theta * 2 / PI)
        if (radius in .5..limit.toDouble()) {
            val angle = startAngle + direction * theta
            points += Offset((center.x + radius * cos(angle)).toFloat(), (center.y + radius * sin(angle)).toFloat())
        }
        theta += PI / 32
    }
    polyline(points)
}

internal fun SceneBuilder.fibWedge() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val apex = pts[0]
    val trend = stroke.copy(dash = DrawingDash.SOLID)
    line(apex, pts[1], trend)
    if (pts.size < 3) return
    line(apex, pts[2], trend)
    val radius = distance(apex, pts[1])
    val from = angleOf(apex, pts[1])
    var sweep = angleOf(apex, pts[2]) - from
    if (sweep > PI) sweep -= 2 * PI else if (sweep < -PI) sweep += 2 * PI
    val levels = visibleLevels(d.levels)
    ringBands(apex, radius, from, sweep, levels)
    if (s.showLabels) levels.forEach { level ->
        val r = radius * level.value.toFloat()
        label(formatRatio(level.value), Offset((apex.x + r * cos(from)).toFloat(), (apex.y + r * sin(from)).toFloat() - 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.BOTTOM, hit = false)
    }
}

internal fun SceneBuilder.pitchfan() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val apex = pts[0]
    if (pts.size < 3) { line(apex, pts[1], stroke.copy(dash = DrawingDash.DASHED)); return }
    val b = pts[1]
    val e = pts[2]
    val tips = mid(b, e)
    fun ray(through: Offset) = extendSegment(apex, through, view, left = false, right = true).second
    line(apex, ray(tips))
    val half = (e - b) * .5f
    var previousUpper = ray(tips)
    var previousLower = previousUpper
    visibleLevels(d.levels).forEach { level ->
        val offset = half * level.value.toFloat()
        val upper = ray(tips + offset)
        val lower = ray(tips - offset)
        val color = colorOf(level.color, base)
        val spec = StrokeSpec(color, s.width * px)
        fillArea(listOf(apex, previousUpper, upper), area?.let { color.copy(alpha = s.fillAlpha) })
        fillArea(listOf(apex, previousLower, lower), area?.let { color.copy(alpha = s.fillAlpha) })
        line(apex, upper, spec); line(apex, lower, spec)
        previousUpper = upper
        previousLower = lower
    }
    line(b, e, stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
}

internal fun SceneBuilder.gannBox() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val horizontal = visibleLevels(d.levels)
    val vertical = visibleLevels(d.levels2)
    fun x(level: Double) = a.x + (b.x - a.x) * level.toFloat()
    fun y(level: Double) = a.y + (b.y - a.y) * level.toFloat()
    horizontal.zipWithNext().forEach { (one, two) -> fillArea(rectPoints(Offset(a.x, y(one.value)), Offset(b.x, y(two.value))), band(two)) }
    vertical.zipWithNext().forEach { (one, two) -> fillArea(rectPoints(Offset(x(one.value), a.y), Offset(x(two.value), b.y)), band(two)) }
    horizontal.forEach { level ->
        line(Offset(a.x, y(level.value)), Offset(b.x, y(level.value)), levelStroke(level))
        if (s.showLabels) {
            label(formatRatio(level.value), Offset(min(a.x, b.x) - 3f * px, y(level.value)), color = colorOf(level.color, base), h = HAlign.RIGHT, v = VAlign.MIDDLE, hit = false)
            label(formatRatio(level.value), Offset(max(a.x, b.x) + 3f * px, y(level.value)), color = colorOf(level.color, base), h = HAlign.LEFT, v = VAlign.MIDDLE, hit = false)
        }
    }
    vertical.forEach { level ->
        line(Offset(x(level.value), a.y), Offset(x(level.value), b.y), levelStroke(level))
        if (s.showLabels) {
            label(formatRatio(level.value), Offset(x(level.value), min(a.y, b.y) - 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.BOTTOM, hit = false)
            label(formatRatio(level.value), Offset(x(level.value), max(a.y, b.y) + 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.TOP, hit = false)
        }
    }
    val fanColor = StrokeSpec(Color(0xFF9C9C9C), px)
    if (d.param("showFans") != 0.0) listOf(Offset(b.x, b.y), Offset(b.x, mid(a, b).y), Offset(mid(a, b).x, b.y)).forEach { line(a, it, fanColor, hit = false) }
}

/** Five by five grid with quarter arcs and fan lines from the first anchor, clipped to the box by the arcs' own sweep. */
internal fun SceneBuilder.gannSquare() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val unitX = (b.x - a.x) / 5
    val unitY = (b.y - a.y) / 5
    val box = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    fun inBox(point: Offset) = point.x in box.left - .5f..box.right + .5f && point.y in box.top - .5f..box.bottom + .5f
    val arcs = d.levels3.filter { it.visible }.sortedBy { hypot(it.value, it.value2 ?: 0.0) }
    var previous: List<Offset>? = null
    arcs.forEach { level ->
        val scale = hypot(level.value, level.value2 ?: 0.0)
        val arc = List(49) { i ->
            val t = PI / 2 * i / 48
            Offset((a.x + unitX * scale * cos(t)).toFloat(), (a.y + unitY * scale * sin(t)).toFloat())
        }.filter(::inBox)
        val color = colorOf(level.color, base)
        val spec = StrokeSpec(color, s.width * px)
        polyline(arc, spec)
        val inner = previous
        if (area != null && arc.size >= 2) fillArea(if (inner == null) listOf(a) + arc else arc + inner.reversed(), color.copy(alpha = s.fillAlpha))
        previous = arc
    }
    d.levels.filter { it.visible }.forEach { level ->
        val spec = StrokeSpec(colorOf(level.color, base), s.width * px)
        val k = level.value.toFloat()
        line(Offset(a.x + unitX * k, a.y), Offset(a.x + unitX * k, b.y), spec, hit = k == 0f || k == 5f)
        line(Offset(a.x, a.y + unitY * k), Offset(b.x, a.y + unitY * k), spec, hit = k == 0f || k == 5f)
        if (s.showLabels) {
            label(formatRatio(level.value), Offset(a.x + unitX * k, min(a.y, b.y) - 2f * px), color = colorOf(level.color, base), h = HAlign.CENTER, v = VAlign.BOTTOM, hit = false)
            label(formatRatio(level.value), Offset(min(a.x, b.x) - 3f * px, a.y + unitY * k), color = colorOf(level.color, base), h = HAlign.RIGHT, v = VAlign.MIDDLE, hit = false)
        }
    }
    d.levels2.filter { it.visible }.forEach { level ->
        val x = level.value2 ?: 1.0
        val y = level.value
        val ratio = max(x, y)
        val end = Offset(a.x + (b.x - a.x) * (x / ratio).toFloat(), a.y + (b.y - a.y) * (y / ratio).toFloat())
        line(a, end, StrokeSpec(colorOf(level.color, base), s.width * px))
    }
}

internal fun SceneBuilder.gannFan() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val dx = pts[1].x - a.x
    val dy = pts[1].y - a.y
    val levels = d.levels.filter { it.visible }
    val ends = levels.map { level ->
        val priceRatio = level.value
        val timeRatio = level.value2 ?: 1.0
        level to extendSegment(a, Offset(a.x + dx * timeRatio.toFloat(), a.y + dy * priceRatio.toFloat()), view, left = false, right = true).second
    }
    ends.zipWithNext().forEach { (one, two) -> fillArea(listOf(a, one.second, two.second), band(two.first)) }
    ends.forEach { (level, end) ->
        line(a, end, levelStroke(level))
        if (s.showLabels) {
            val text = "${formatRatio(level.value2 ?: 1.0)}/${formatRatio(level.value)}"
            val along = Offset(a.x + (end.x - a.x) * .6f, a.y + (end.y - a.y) * .6f)
            label(text, along, color = colorOf(level.color, base), h = HAlign.LEFT, v = VAlign.BOTTOM, hit = false)
        }
    }
}
