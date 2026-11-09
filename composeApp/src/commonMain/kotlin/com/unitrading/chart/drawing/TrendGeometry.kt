package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Price, percent and tick change between two anchors, then bars, duration and angle; one group of lines per stats option. */
internal fun SceneBuilder.statsLines(first: DrawingPoint, second: DrawingPoint, a: Offset, b: Offset, withAngle: Boolean): List<String> {
    val diff = second.price - first.price
    val percent = if (first.price != 0.0) diff / first.price * 100 else 0.0
    val bars = (c.index(second.timeMs) - c.index(first.timeMs)).roundToInt()
    return buildList {
        add("${ctx.priceDiff(diff)} (${signed(percent)}%) ${ctx.ticks(diff)}")
        add("$bars 根K线, ${formatSpan(second.timeMs - first.timeMs)}")
        if (withAngle) add("∠ ${formatRatio(-angleOf(a, b) * 180 / PI, 2)}°")
    }
}

internal fun SceneBuilder.statsBox(lines: List<String>, at: Offset, h: HAlign = HAlign.LEFT, v: VAlign = VAlign.TOP) {
    label(lines.joinToString("\n"), at, color = ctx.ink, h = h, v = v, background = ctx.surface.copy(alpha = .86f), padding = 4f * px, corner = 3f * px, hit = false)
}

internal fun SceneBuilder.trendLine() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val (from, to) = extendSegment(a, b, view, s.extendLeft, s.extendRight)
    line(from, to)
    if (s.leftArrow) arrowHead(a, b)
    if (s.rightArrow) arrowHead(b, a)
    if (s.showMiddle) dot(mid(a, b), 3f * px)
    if (s.showPrices) {
        label(ctx.price(d.points[0].price), a, h = HAlign.CENTER, v = VAlign.BOTTOM)
        label(ctx.price(d.points[1].price), b, h = HAlign.CENTER, v = VAlign.BOTTOM)
    }
    captionAlong(a, b)
    if (s.showStats) statsBox(statsLines(d.points[0], d.points[1], a, b, withAngle = true), Offset(b.x + 8f * px, b.y + 8f * px))
}

internal fun SceneBuilder.trendAngle() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val (from, to) = extendSegment(a, b, view, s.extendLeft, s.extendRight)
    line(from, to)
    val angle = angleOf(a, b)
    val radius = minOf(distance(a, b), 60f * px)
    line(a, Offset(a.x + radius * 1.4f, a.y), stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
    polyline(ellipsePoints(a, radius, radius, from = 0.0, sweep = angle, steps = 32), stroke.copy(width = stroke.width / 2), hit = false)
    label("${formatRatio(-angle * 180 / PI, 2)}°", Offset(a.x + radius + 6f * px, a.y + (if (angle < 0) -2f else 2f) * px), h = HAlign.LEFT, v = if (angle < 0) VAlign.BOTTOM else VAlign.TOP, color = base, sizeSp = textSize, hit = false)
}

internal fun SceneBuilder.horizontalLine() {
    handle(0, Offset(pts.getOrNull(0)?.x ?: return, pts[0].y))
    val y = pts[0].y
    line(Offset(view.left, y), Offset(view.right, y))
    if (d.text.isNotBlank()) label(d.text, Offset(plot.center.x, y - 2f * px), h = HAlign.CENTER, v = VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic)
    if (s.showPrices) markPrice(d.points[0].price, always = true)
}

internal fun SceneBuilder.horizontalRay() {
    if (pts.isEmpty()) return
    handle(0, pts[0])
    val a = pts[0]
    line(a, Offset(view.right, a.y))
    if (d.text.isNotBlank()) label(d.text, Offset(a.x + 8f * px, a.y - 2f * px), h = HAlign.LEFT, v = VAlign.BOTTOM, sizeSp = textSize, bold = s.bold, italic = s.italic)
    if (s.showPrices) markPrice(d.points[0].price, always = true)
    markTime(d.points[0].timeMs)
}

internal fun SceneBuilder.verticalLine() {
    if (pts.isEmpty()) return
    handle(0, Offset(pts[0].x, plot.center.y))
    fullHeightLine(pts[0].x)
    if (d.text.isNotBlank()) label(d.text, Offset(pts[0].x, plot.center.y), h = HAlign.LEFT, v = VAlign.MIDDLE, sizeSp = textSize, bold = s.bold, italic = s.italic, angle = -90f)
    markTime(d.points[0].timeMs, always = true)
}

internal fun SceneBuilder.crossLine() {
    if (pts.isEmpty()) return
    handle(0, pts[0])
    line(Offset(view.left, pts[0].y), Offset(view.right, pts[0].y))
    fullHeightLine(pts[0].x)
    markPrice(d.points[0].price, always = true)
    markTime(d.points[0].timeMs, always = true)
}

/** Vertical pixel offset of the third anchor from the a→b line, the channel's width. */
internal fun channelOffset(a: Offset, b: Offset, third: Offset): Float {
    val span = b.x - a.x
    val lineY = if (span == 0f) a.y else a.y + (third.x - a.x) / span * (b.y - a.y)
    return third.y - lineY
}

internal fun SceneBuilder.parallelChannel() {
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    handle(0, a); handle(1, b)
    anchorMarks()
    if (pts.size < 3) { line(a, b); return }
    val offset = channelOffset(a, b, pts[2])
    fun at(level: Double, p: Offset) = Offset(p.x, p.y + (offset * level).toFloat())
    val visible = d.levels.filter { it.visible }.sortedBy { it.value }
    if (area != null && visible.size >= 2) {
        val (l1, r1) = extendSegment(at(visible.first().value, a), at(visible.first().value, b), view, s.extendLeft, s.extendRight)
        val (l2, r2) = extendSegment(at(visible.last().value, a), at(visible.last().value, b), view, s.extendLeft, s.extendRight)
        fillArea(listOf(l1, r1, r2, l2), area, hit = true)
    }
    visible.forEach { level ->
        val (from, to) = extendSegment(at(level.value, a), at(level.value, b), view, s.extendLeft, s.extendRight)
        val edge = level.value == 0.0 || level.value == 1.0
        val spec = StrokeSpec(colorOf(level.color, base), (if (edge) s.width else 1f) * px, if (level.value == .5) DrawingDash.DASHED else s.dash)
        line(from, to, spec)
    }
    handle(2, at(1.0, a)); handle(3, at(1.0, b))
    handle(4, mid(a, b)); handle(5, mid(at(1.0, a), at(1.0, b)))
    captionAlong(a, b)
}

/** Least-squares fit of closes over a bar range: `value(bar) = intercept + slope * (bar - first)`. */
internal class Regression(val first: Int, val last: Int, val intercept: Double, val slope: Double, val deviation: Double, val pearson: Double) {
    fun value(bar: Int) = intercept + slope * (bar - first)
}

internal fun regression(values: List<Double>, first: Int): Regression? {
    val n = values.size
    if (n < 2) return null
    val meanX = (n - 1) / 2.0
    val meanY = values.sum() / n
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    values.forEachIndexed { x, y ->
        sxy += (x - meanX) * (y - meanY)
        sxx += (x - meanX) * (x - meanX)
        syy += (y - meanY) * (y - meanY)
    }
    if (sxx == 0.0) return null
    val slope = sxy / sxx
    val intercept = meanY - slope * meanX
    val residual = values.withIndex().sumOf { (x, y) -> val e = y - (intercept + slope * x); e * e }
    return Regression(first, first + n - 1, intercept, slope, sqrt(residual / n), if (syy == 0.0) 0.0 else sxy / sqrt(sxx * syy))
}

internal fun SceneBuilder.regressionTrend() {
    if (pts.size < 2) { anchorHandles(); return }
    val i1 = c.index(d.points[0].timeMs).roundToInt()
    val i2 = c.index(d.points[1].timeMs).roundToInt()
    val first = minOf(i1, i2).coerceIn(0, c.total - 1)
    val last = maxOf(i1, i2).coerceIn(0, c.total - 1)
    val fit = regression(c.candles.subList(first, last + 1).map { it.close.toDouble() }, first)
    if (fit == null) { line(pts[0], pts[1]); anchorHandles(); return }
    val x1 = c.xOfIndex(first.toDouble())
    val x2 = c.xOfIndex(last.toDouble())
    val startY = c.y(fit.value(first))
    val endY = c.y(fit.value(last))
    fun extended(a: Offset, b: Offset) = extendSegment(a, b, view, s.extendLeft, s.extendRight)
    val up = d.param("upper", 2.0)
    val down = d.param("lower", 2.0)
    val upperStart = c.y(fit.value(first) + up * fit.deviation)
    val upperEnd = c.y(fit.value(last) + up * fit.deviation)
    val lowerStart = c.y(fit.value(first) - down * fit.deviation)
    val lowerEnd = c.y(fit.value(last) - down * fit.deviation)
    val (u1, u2) = extended(Offset(x1, upperStart), Offset(x2, upperEnd))
    val (l1, l2) = extended(Offset(x1, lowerStart), Offset(x2, lowerEnd))
    val (m1, m2) = extended(Offset(x1, startY), Offset(x2, endY))
    fillArea(listOf(u1, u2, m2, m1), area, hit = true)
    fillArea(listOf(m1, m2, l2, l1), area?.copy(alpha = (s.fillAlpha * .66f)), hit = true)
    line(u1, u2, StrokeSpec(base.copy(alpha = .7f), s.width * px))
    line(l1, l2, StrokeSpec(base.copy(alpha = .7f), s.width * px))
    line(m1, m2, StrokeSpec(Color(0xFFF23645).copy(alpha = .8f), px, DrawingDash.DASHED))
    if (s.showLabels) label(formatRatio(fit.pearson, 3), Offset(x1, lowerStart + 4f * px), color = base, h = HAlign.LEFT, v = VAlign.TOP, hit = false)
    handle(0, Offset(x1, startY)); handle(1, Offset(x2, endY))
    markTime(c.timeAt(first.toDouble())); markTime(c.timeAt(last.toDouble()))
}

internal fun SceneBuilder.flatTopBottom() {
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    handle(0, a); handle(1, b)
    anchorMarks()
    if (pts.size < 3) { line(a, b); return }
    val flatY = pts[2].y
    val (l1, r1) = extendSegment(a, b, view, s.extendLeft, s.extendRight)
    val (l2, r2) = extendSegment(Offset(a.x, flatY), Offset(b.x, flatY), view, s.extendLeft, s.extendRight)
    fillArea(listOf(l1, r1, r2, l2), area, hit = true)
    line(l1, r1); line(l2, r2)
    handle(2, Offset(a.x, flatY)); handle(3, Offset(b.x, flatY))
    captionAlong(a, b)
}

/** The second line starts level with a at the third anchor's height and runs with the opposite slope, so the two lines fan apart. */
internal fun SceneBuilder.disjointChannel() {
    if (pts.size < 2) { anchorHandles(); return }
    val a = pts[0]
    val b = pts[1]
    handle(0, a); handle(1, b)
    anchorMarks()
    if (pts.size < 3) { line(a, b); return }
    val start = Offset(a.x, pts[2].y)
    val end = Offset(b.x, pts[2].y + a.y - b.y)
    val (l1, r1) = extendSegment(a, b, view, s.extendLeft, s.extendRight)
    val (l2, r2) = extendSegment(start, end, view, s.extendLeft, s.extendRight)
    fillArea(listOf(l1, r1, r2, l2), area, hit = true)
    line(l1, r1); line(l2, r2)
    handle(2, start); handle(3, end)
    captionAlong(a, b)
}

internal fun SceneBuilder.pitchfork() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    if (pts.size < 3) { line(pts[0], pts[1], stroke.copy(dash = DrawingDash.DASHED)); return }
    val a = pts[0]
    val b = pts[1]
    val cc = pts[2]
    val tips = mid(b, cc)
    val inside = d.tool == DrawingTool.INSIDE_PITCHFORK
    val origin = when (d.tool) {
        DrawingTool.SCHIFF_PITCHFORK -> Offset(a.x, (a.y + b.y) / 2)
        DrawingTool.MODIFIED_SCHIFF_PITCHFORK -> mid(a, b)
        else -> a
    }
    val head = if (inside) mid(a, tips) else tips
    val halfWidth = (cc - b) * (if (inside) .25f else .5f)
    val direction = tips - origin
    fun ending(start: Offset) = if (s.extendRight) extendSegment(start, start + direction, view, left = false, right = true).second else start + direction
    val medianEnd = ending(head)
    line(origin, medianEnd)
    line(a, b, stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
    line(a, cc, stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
    val visible = d.levels.filter { it.visible }.sortedBy { it.value }
    var previousUpper = head
    var previousLower = head
    visible.forEach { level ->
        val upper = head + halfWidth * level.value.toFloat()
        val lower = head - halfWidth * level.value.toFloat()
        val color = colorOf(level.color, base)
        val spec = StrokeSpec(color, s.width * px)
        if (area != null) {
            val tint = color.copy(alpha = s.fillAlpha)
            fillArea(listOf(previousUpper, ending(previousUpper), ending(upper), upper), tint)
            fillArea(listOf(previousLower, ending(previousLower), ending(lower), lower), tint)
        }
        line(upper, ending(upper), spec)
        line(lower, ending(lower), spec)
        previousUpper = upper
        previousLower = lower
    }
    if (visible.isNotEmpty()) line(previousUpper, previousLower, StrokeSpec(colorOf(visible.last().color, base), s.width * px))
}
