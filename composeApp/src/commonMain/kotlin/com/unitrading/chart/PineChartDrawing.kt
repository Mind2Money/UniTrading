package com.unitrading.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.Candle
import com.unitrading.chart.pine.PineDrawing
import com.unitrading.chart.pine.PineExtend
import com.unitrading.chart.pine.PineLevel
import com.unitrading.chart.pine.PineLocation
import com.unitrading.chart.pine.PineMark
import com.unitrading.chart.pine.PinePlot
import com.unitrading.chart.pine.PinePlotStyle
import com.unitrading.chart.pine.PineResult
import com.unitrading.chart.pine.PineShape
import com.unitrading.chart.pine.PineStroke
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Like DrawingCoordinates.index: time advances one bar per period, collapsing the untraded part of a session gap. */
internal fun pineBarIndex(value: Double, byTime: Boolean, candles: List<Candle>, periodMs: Long): Double? {
    if (!value.isFinite()) return null
    if (!byTime) return value
    if (candles.isEmpty() || periodMs <= 0L || value < Long.MIN_VALUE.toDouble() || value >= Long.MAX_VALUE.toDouble()) return null
    val time = value.roundToLong()
    fun periodsSince(earlier: Long): Double {
        val delta = time - earlier
        // Prefer exact integer subtraction for neighboring timestamps; fall back if it overflowed.
        val ms = if ((time xor earlier) and (time xor delta) < 0L) time.toDouble() - earlier.toDouble() else delta.toDouble()
        return ms / periodMs
    }
    if (time < candles.first().timeMs) return periodsSince(candles.first().timeMs)
    if (time >= candles.last().timeMs) return candles.lastIndex + periodsSince(candles.last().timeMs)
    val found = candles.binarySearchBy(time) { it.timeMs }
    if (found >= 0) return found.toDouble()
    val bar = -found - 2
    return bar + periodsSince(candles[bar].timeMs).coerceAtMost(1.0)
}

private fun Int?.visibleColor(): Int? = this?.takeIf { it ushr 24 != 0 }
private fun IntRange.intersect(other: IntRange) = maxOf(first, other.first)..minOf(last, other.last)
private fun PineExtend.leftward() = this == PineExtend.LEFT || this == PineExtend.BOTH
private fun PineExtend.rightward() = this == PineExtend.RIGHT || this == PineExtend.BOTH
private fun Float.validSize() = isFinite() && this > 0f
private fun PinePlot.isLine() = style == PinePlotStyle.LINE || style == PinePlotStyle.LINE_BREAK
private fun PinePlot.hasBaseline() = style == PinePlotStyle.COLUMNS || style == PinePlotStyle.HISTOGRAM

/** Invert the offset without wrapping at the ends of Int. The source index may be outside the visible loaded range. */
private fun PinePlot.sourceSlots(targets: IntRange): IntRange {
    if (targets.isEmpty() || values.isEmpty()) return IntRange.EMPTY
    val first = maxOf(0L, targets.first.toLong() - offset)
    val last = minOf(values.lastIndex.toLong(), targets.last.toLong() - offset)
    return if (first > last) IntRange.EMPTY else first.toInt()..last.toInt()
}

private inline fun PinePlot.forEachSegment(slots: IntRange, work: PineGeometryBudget? = null, emit: (Double, Double, Double, Double, Int) -> Unit) {
    if (slots.isEmpty() || values.size < 2 || slots.last < offset || slots.first.toLong() > values.lastIndex.toLong() + offset) return
    val first = (slots.first.toLong() - offset).coerceIn(0, values.size.toLong()).toInt()
    val last = (slots.last.toLong() - offset).coerceIn(-1, values.lastIndex.toLong()).toInt()
    val bridge = style == PinePlotStyle.LINE
    // Take a neighbor on either side of the visible window, even if a LINE gap straddles that edge.
    var before = first - 1
    while (bridge && before >= 0 && !values[before].isFinite()) {
        if (work?.take() == false) return
        before--
    }
    var after = last + 1
    while (bridge && after < values.size && !values[after].isFinite()) {
        if (work?.take() == false) return
        after++
    }
    var previous = -1
    for (index in maxOf(0, before)..minOf(values.lastIndex, after)) {
        if (work?.take() == false) return
        val value = values[index]
        if (!value.isFinite()) {
            if (!bridge) previous = -1
            continue
        }
        val color = colors.getOrNull(index).visibleColor()
        if (color == null) {
            previous = -1
            continue
        }
        if (previous >= 0) emit(previous.toDouble() + offset, values[previous], index.toDouble() + offset, value, color)
        previous = index
    }
}

private fun mix(a: Double, b: Double, t: Double): Double = when {
    t == 0.0 || a == b -> a
    t == 1.0 -> b
    t in 0.0..1.0 -> a * (1.0 - t) + b * t
    else -> a + (b - a) * t
}

/** Fill colors belong to destination bars; plot values are independently unshifted. Two neighboring
 * samples are needed so a fill never bridges an NA, even if its boundary is an ordinary LINE plot. */
private inline fun PineResult.forEachBand(
    slots: IntRange, work: PineGeometryBudget? = null,
    emit: (Int, Double, Double, Double, Double, Double, Double, Int) -> Unit,
) {
    if (fills.isEmpty() || slots.isEmpty() || work?.exhausted == true) return
    val plotsById = plots.associateBy { it.id }
    val levelsById = levels.associateBy { it.id }
    for ((fillIndex, fill) in fills.withIndex()) {
        if (work?.exhausted == true) break
        val first = fillEndpoint(plotsById[fill.first], levelsById[fill.first]) ?: continue
        val second = fillEndpoint(plotsById[fill.second], levelsById[fill.second]) ?: continue
        val from = maxOf(-1L, slots.first.toLong() - 1).toInt()
        val until = minOf(fill.colors.lastIndex, slots.last)
        for (index in from..until) {
            if (work?.take() == false) return
            val leftColor = fill.colors.getOrNull(index).visibleColor()
            val rightColor = fill.colors.getOrNull(index + 1).visibleColor()
            if (leftColor == null && rightColor == null) continue
            val a1 = first(index)
            val a2 = first(index + 1)
            val b1 = second(index)
            val b2 = second(index + 1)
            if (!a1.isFinite() || !a2.isFinite() || !b1.isFinite() || !b2.isFinite()) continue
            val a = index.toDouble()
            val b = a + 1.0
            if (leftColor != null && leftColor == rightColor) {
                if (work?.take() == false) return
                emit(fillIndex, a, a1, b1, b, a2, b2, leftColor)
            } else {
                val midA = mix(a1, a2, .5)
                val midB = mix(b1, b2, .5)
                if (leftColor != null) {
                    if (work?.take() == false) return
                    emit(fillIndex, a, a1, b1, a + .5, midA, midB, leftColor)
                }
                if (rightColor != null) {
                    if (work?.take() == false) return
                    emit(fillIndex, a + .5, midA, midB, b, a2, b2, rightColor)
                }
            }
        }
    }
}

private fun fillEndpoint(plot: PinePlot?, level: PineLevel?): ((Int) -> Double)? = when {
    plot != null -> { target ->
        val source = target.toLong() - plot.offset
        if (source >= 0 && source < plot.values.size) plot.values[source.toInt()] else Double.NaN
    }
    level != null -> { _ -> level.value }
    else -> null
}

private data class IndexWindow(val left: Double, val right: Double) {
    fun contains(index: Double) = index in left..right
    fun overlaps(start: Double, end: Double) = start <= right && end >= left
}

/** Return the values of the visible part, not the values at offscreen anchors. */
private fun visibleLineValues(
    x1: Double, y1: Double, x2: Double, y2: Double, extend: PineExtend, window: IndexWindow,
): Pair<Double, Double>? {
    if (!y1.isFinite() || !y2.isFinite()) return null
    if (x1 == x2) return if (window.contains(x1)) y1 to y2 else null
    val start = minOf(x1, x2)
    val end = maxOf(x1, x2)
    val startValue = if (x1 < x2) y1 else y2
    val endValue = if (x1 < x2) y2 else y1
    val lo = if (extend.leftward()) window.left else maxOf(start, window.left)
    val hi = if (extend.rightward()) window.right else minOf(end, window.right)
    if (lo > hi) return null
    val distance = end - start
    if (!distance.isFinite()) return null
    return mix(startValue, endValue, (lo - start) / distance) to mix(startValue, endValue, (hi - start) / distance)
}

private fun hasMarker(shape: PineShape, color: Int?) = shape != PineShape.TEXT && color.visibleColor() != null
private fun hasText(text: String, color: Int?) = text.isNotEmpty() && color.visibleColor() != null

private fun pineLocalIndex(value: Double, byTime: Boolean, candles: List<Candle>, periodMs: Long, barOrigin: Int): Double? =
    pineBarIndex(value, byTime, candles, periodMs)?.let { if (byTime) it - barOrigin else it }

/** Only price-pane relative labels derive a value from a broker candle. All other values are already Pine units. */
internal fun pineLabelValue(
    label: PineDrawing.Label, index: Double, candles: List<Candle>, priceFactor: Double, barOrigin: Int = 0,
): Double {
    val above = label.location == PineLocation.ABOVE_BAR
    if (!label.onPrice || !above && label.location != PineLocation.BELOW_BAR) return label.y
    val anchor = index + barOrigin
    if (!anchor.isFinite() || !priceFactor.isFinite() || priceFactor <= 0.0) return Double.NaN
    val candle = candles.getOrNull(anchor.roundToInt()) ?: return Double.NaN
    return (if (above) candle.high else candle.low).toDouble() / priceFactor
}

/** The interpreter emits marks sorted by target index. Binary search avoids scanning older offscreen marks. */
private fun List<PineMark>.markSlots(slots: IntRange): IntRange {
    fun bound(target: Long): Int {
        var left = 0
        var right = size
        while (left < right) {
            val mid = left + (right - left) / 2
            if (this[mid].index < target) left = mid + 1 else right = mid
        }
        return left
    }
    return bound(slots.first.toLong()) until bound(slots.last.toLong() + 1L)
}

/** Raw Pine values, independent of pixel scale and broker price units. Fractional scrolling is approximated by
 * the outer half-slot edges; rendering below uses the exact pixel viewport. */
internal fun pineValueBounds(
    result: PineResult, range: IntRange, slots: IntRange, candles: List<Candle>, periodMs: Long, barOrigin: Int = 0,
): ClosedFloatingPointRange<Double>? {
    if (slots.isEmpty()) return null
    val window = IndexWindow(slots.first - .5, slots.last + .5)
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    fun include(value: Double) {
        if (value.isFinite()) {
            low = minOf(low, value)
            high = maxOf(high, value)
        }
    }
    fun includeLine(x1: Double, y1: Double, x2: Double, y2: Double, extend: PineExtend = PineExtend.NONE) {
        visibleLineValues(x1, y1, x2, y2, extend, window)?.let { (a, b) -> include(a); include(b) }
    }
    for (plot in result.plots) {
        if (!plot.displayed || !plot.width.validSize() || plot.hasBaseline() && !plot.baseline.isFinite()) continue
        val targets = if (plot.offset == 0) slots.intersect(range) else slots
        for (index in plot.sourceSlots(targets)) {
            val value = plot.values[index]
            if (!value.isFinite() || plot.colors.getOrNull(index).visibleColor() == null) continue
            include(value)
            if (plot.hasBaseline()) include(plot.baseline)
        }
        if (plot.isLine()) plot.forEachSegment(slots) { x1, y1, x2, y2, _ -> includeLine(x1, y1, x2, y2) }
    }
    for (level in result.levels) {
        if (level.displayed && level.color.visibleColor() != null && level.width.validSize()) include(level.value)
    }
    result.forEachBand(slots) { _, x1, a1, b1, x2, a2, b2, _ ->
        if (x1 < window.right && x2 > window.left) {
            includeLine(x1, a1, x2, a2)
            includeLine(x1, b1, x2, b2)
        }
    }
    for (i in result.marks.markSlots(slots)) {
        val mark = result.marks[i]
        if (mark.location == PineLocation.ABSOLUTE && mark.size.validSize() &&
            (hasMarker(mark.shape, mark.color) || hasText(mark.text, mark.textColor))) include(mark.value)
    }
    for (drawing in result.drawings) {
        fun index(x: Double, byTime: Boolean) = pineLocalIndex(x, byTime, candles, periodMs, barOrigin)
        when (drawing) {
            is PineDrawing.Line -> {
                if (drawing.color.visibleColor() == null || !drawing.width.validSize()) continue
                val a = index(drawing.x1, drawing.byTime) ?: continue
                val b = index(drawing.x2, drawing.byTime) ?: continue
                if (a != b || drawing.y1 != drawing.y2) includeLine(a, drawing.y1, b, drawing.y2, drawing.extend)
            }
            is PineDrawing.Box -> {
                if (!drawing.top.isFinite() || !drawing.bottom.isFinite()) continue
                val a = index(drawing.left, drawing.byTime) ?: continue
                val b = index(drawing.right, drawing.byTime) ?: continue
                val start = minOf(a, b)
                val end = maxOf(a, b)
                val border = drawing.border.visibleColor() != null && drawing.borderWidth.validSize()
                val body = start < end && drawing.top != drawing.bottom &&
                    (drawing.background.visibleColor() != null || hasText(drawing.text, drawing.textColor))
                if (body && window.overlaps(start, end) || border && window.overlaps(
                        if (drawing.extend.leftward()) window.left else start,
                        if (drawing.extend.rightward()) window.right else end,
                    )) {
                    include(drawing.top)
                    include(drawing.bottom)
                }
            }
            is PineDrawing.Label -> {
                if (drawing.location != PineLocation.ABSOLUTE || !drawing.size.validSize() ||
                    !hasMarker(drawing.shape, drawing.color) && !hasText(drawing.text, drawing.textColor)) continue
                val anchor = index(drawing.x, drawing.byTime) ?: continue
                if (window.contains(anchor)) include(drawing.y)
            }
        }
    }
    return if (low <= high) low..high else null
}

private class PineWindow(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    val valid = left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite() &&
        (right - left).validSize() && (bottom - top).validSize()

    fun DrawScope.rect(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!x1.isFinite() || !x2.isFinite() || !y1.isFinite() || !y2.isFinite()) return
        val a = maxOf(left, minOf(x1, x2))
        val b = minOf(right, maxOf(x1, x2))
        val c = maxOf(top, minOf(y1, y2))
        val d = minOf(bottom, maxOf(y1, y2))
        if (a < b && c < d) drawRect(color, Offset(a, c), Size(b - a, d - c))
    }

    /** Bound actual coordinates before submitting paths/strokes to Skia, including vertical rays. */
    fun DrawScope.segment(
        x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float,
        effect: PathEffect? = null, extend: PineExtend = PineExtend.NONE,
    ) {
        if (!x1.isFinite() || !x2.isFinite() || !y1.isFinite() || !y2.isFinite() || !width.validSize()) return
        val a = minOf(x1, x2).toDouble()
        val c = (if (x1 <= x2) y1 else y2).toDouble()
        val dx = maxOf(x1, x2).toDouble() - a
        val dy = (if (x1 <= x2) y2 else y1).toDouble() - c
        if (dx == 0.0 && dy == 0.0) return
        var enter = if (extend.leftward()) Double.NEGATIVE_INFINITY else 0.0
        var exit = if (extend.rightward()) Double.POSITIVE_INFINITY else 1.0
        fun clip(p: Double, delta: Double, min: Double, max: Double): Boolean {
            if (delta == 0.0) return p in min..max
            val t1 = (min - p) / delta
            val t2 = (max - p) / delta
            enter = maxOf(enter, minOf(t1, t2))
            exit = minOf(exit, maxOf(t1, t2))
            return enter < exit
        }
        val pad = width.toDouble() / 2
        if (!clip(a, dx, left - pad, right + pad) || !clip(c, dy, top - pad, bottom + pad)) return
        val start = Offset((a + dx * enter).toFloat(), (c + dy * enter).toFloat())
        val end = Offset((a + dx * exit).toFloat(), (c + dy * exit).toFloat())
        if (start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite()) {
            drawLine(color, start, end, strokeWidth = width, pathEffect = effect)
        }
    }
}

/** One full-height color strip per loaded bar; backgrounds are intentionally a separate paint pass. */
internal fun DrawScope.drawPineBackground(
    result: PineResult, range: IntRange, left: Float, right: Float, top: Float, bottom: Float, step: Float, x: (Double) -> Float,
) {
    val window = PineWindow(left, right, top, bottom)
    if (!window.valid || !step.validSize()) return
    val indices = range.intersect(result.backgrounds.indices)
    var start = indices.first
    var current: Int? = null
    fun DrawScope.paint(first: Int, last: Int, argb: Int?) {
        if (argb == null) return
        with(window) { rect(Color(argb), x(first.toDouble()) - step / 2f, top, x(last.toDouble()) + step / 2f, bottom) }
    }
    for (index in indices) {
        val color = result.backgrounds[index].visibleColor()
        if (current != color) {
            paint(start, index - 1, current)
            start = index
            current = color
        }
    }
    paint(start, indices.last, current)
}

private fun DrawScope.pineStrokeWidth(width: Float): Float =
    if (width.validSize()) width.coerceAtMost(100f).dp.toPx().takeIf { it.validSize() } ?: 0f else 0f

private fun DrawScope.pineEffect(pattern: PineStroke, width: Float): PathEffect? = when (pattern) {
    PineStroke.SOLID -> null
    PineStroke.DASHED -> PathEffect.dashPathEffect(floatArrayOf(maxOf(width * 4, 6.dp.toPx()), maxOf(width * 3, 4.dp.toPx())))
    PineStroke.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(width, width * 3))
}

/** Share one of these across scripts in a pane on each paint pass. Shapes do not consume this text-only budget. */
internal class PineTextBudget(maxLayouts: Int = 80) {
    private var remaining = maxLayouts.coerceAtLeast(0)
    val exhausted: Boolean get() = remaining == 0

    fun take(): Boolean {
        if (exhausted) return false
        remaining--
        return true
    }
}

/** Per-paint candidate budget: even invalid/offscreen output must not permit an unbounded amount of work.
 * A simple plot point takes one unit; compound marks/objects reserve their maximum strokes in advance. */
internal class PineGeometryBudget(limit: Int = 16_000) {
    var remaining: Int = limit.coerceAtLeast(0)
        private set
    val exhausted: Boolean get() = remaining == 0

    fun take(units: Int = 1): Boolean {
        if (units <= 0 || units > remaining) return false
        remaining -= units
        return true
    }
}

private fun PineShape.geometryCost() = when (this) {
    PineShape.ARROW_UP, PineShape.ARROW_DOWN -> 4
    PineShape.CROSS, PineShape.X_CROSS, PineShape.LABEL_UP, PineShape.LABEL_DOWN, PineShape.FLAG -> 3
    PineShape.TEXT -> 1
    else -> 2
}

/** A finite nonpositive baseline outside a log scale maps to its low edge, not necessarily the pixel bottom. */
internal fun pineBaselineY(baseline: Double, nonPositiveBaselineY: Float, y: (Double) -> Float): Float {
    if (!baseline.isFinite()) return Float.NaN
    val row = y(baseline)
    return when {
        row.isFinite() -> row
        baseline <= 0.0 && nonPositiveBaselineY.isFinite() -> nonPositiveBaselineY
        else -> Float.NaN
    }
}

/** The caller selects one panel and owns its clip, axes and legend. x and y are the replay's bar index and raw Pine value.
 * [barOrigin] is the global index of replay bar zero in the full [candles] list. */
internal fun DrawScope.drawPineVisuals(
    result: PineResult, range: IntRange, slots: IntRange, candles: List<Candle>, periodMs: Long,
    left: Float, right: Float, top: Float, bottom: Float, step: Float, textMeasurer: TextMeasurer,
    priceFactor: Double = 1.0, barOrigin: Int = 0, textBudget: PineTextBudget = PineTextBudget(),
    geometryBudget: PineGeometryBudget = PineGeometryBudget(), nonPositiveBaselineY: Float = Float.NaN,
    x: (Double) -> Float, y: (Double) -> Float,
) {
    val window = PineWindow(left, right, top, bottom)
    if (!window.valid || !step.validSize() || slots.isEmpty() || geometryBudget.exhausted) return
    with(window) {
        // Reserve recent objects before spending the rest on dense data, but preserve the original z-order.
        val drawings = result.drawings
        var firstDrawing = drawings.size
        while (firstDrawing > 0) {
            val cost = when (val drawing = drawings[firstDrawing - 1]) {
                is PineDrawing.Line -> 1
                is PineDrawing.Box -> 6 // four edges, fill, text
                is PineDrawing.Label -> drawing.shape.geometryCost()
            }
            if (!geometryBudget.take(cost)) break
            firstDrawing--
        }
        val levels = result.levels
        var levelCount = 0
        while (levelCount < levels.size && geometryBudget.take()) levelCount++

        val visibleMarks = result.marks.markSlots(slots)
        var firstMark = visibleMarks.last + 1
        // Do not let a run of markers crowd out all plot data. The newest marks get the available space.
        val hasPlots = result.plots.any { it.displayed }
        val markLimit = if (!hasPlots && result.fills.isEmpty()) geometryBudget.remaining else geometryBudget.remaining / 3
        var markWork = 0
        while (firstMark > visibleMarks.first) {
            val cost = result.marks[firstMark - 1].shape.geometryCost()
            if (cost > markLimit - markWork || !geometryBudget.take(cost)) break
            markWork += cost
            firstMark--
        }
        // Dense fills are optional when the cap is hit; leave at least half of the remaining work for plot strokes.
        if (result.fills.isNotEmpty() && !geometryBudget.exhausted) {
            val fillLimit = if (hasPlots) geometryBudget.remaining / 2 else geometryBudget.remaining
            val fillBudget = PineGeometryBudget(fillLimit)
            drawPineBands(this@drawPineVisuals, result, slots, fillBudget, x, y)
            geometryBudget.take(fillLimit - fillBudget.remaining)
        }
        for (i in 0 until levelCount) {
            val level = levels[i]
            val color = level.color.visibleColor() ?: continue
            val width = pineStrokeWidth(level.width)
            if (!level.displayed || !width.validSize() || !level.value.isFinite()) continue
            val row = y(level.value)
            segment(left, row, right, row, Color(color), width, pineEffect(level.pattern, width))
        }
        for (plot in result.plots) {
            if (geometryBudget.exhausted) break
            if (!plot.displayed) continue
            val width = pineStrokeWidth(plot.width)
            if (!width.validSize()) continue
            if (plot.isLine()) {
                plot.forEachSegment(slots, geometryBudget) { x1, y1, x2, y2, color ->
                    segment(x(x1), y(y1), x(x2), y(y2), Color(color), width)
                }
                continue
            }
            val baseline = if (plot.hasBaseline()) pineBaselineY(plot.baseline, nonPositiveBaselineY, y) else Float.NaN
            if (plot.hasBaseline() && !baseline.isFinite()) continue
            val targets = if (plot.offset == 0) slots.intersect(range) else slots
            val cost = if (plot.style == PinePlotStyle.CROSS) 2 else 1
            for (index in plot.sourceSlots(targets)) {
                if (!geometryBudget.take(cost)) break
                val color = Color(plot.colors.getOrNull(index).visibleColor() ?: continue)
                val value = plot.values[index]
                if (!value.isFinite()) continue
                val px = x(index.toDouble() + plot.offset)
                val py = y(value)
                if (!px.isFinite() || !py.isFinite()) continue
                when (plot.style) {
                    PinePlotStyle.HISTOGRAM, PinePlotStyle.COLUMNS -> {
                        val half = (if (plot.style == PinePlotStyle.COLUMNS) step * .4f else minOf(step, width) / 2f)
                        rect(color, px - half, py, px + half, if (py == baseline) py + 1f else baseline)
                    }
                    PinePlotStyle.CIRCLES -> if (inMarkerWindow(px, py, width)) drawCircle(color, width / 2f, Offset(px, py))
                    PinePlotStyle.CROSS -> {
                        segment(px - width, py, px + width, py, color, width / 2f)
                        segment(px, py - width, px, py + width, color, width / 2f)
                    }
                    else -> Unit
                }
            }
        }
        // Measure newest visible object text first, then newest plot marks. Replay the original paint order below.
        fun index(value: Double, byTime: Boolean) = pineLocalIndex(value, byTime, candles, periodMs, barOrigin)
        val layouts = HashMap<Int, TextLayoutResult>()
        for (i in drawings.lastIndex downTo firstDrawing) {
            if (textBudget.exhausted) break
            val drawing = drawings[i]
            val layout = when (drawing) {
                is PineDrawing.Box -> {
                    if (!hasText(drawing.text, drawing.textColor) || !drawing.top.isFinite() || !drawing.bottom.isFinite()) continue
                    val a = index(drawing.left, drawing.byTime) ?: continue
                    val b = index(drawing.right, drawing.byTime) ?: continue
                    measureBoxText(this@drawPineVisuals, x(a), y(drawing.top), x(b), y(drawing.bottom), drawing, textMeasurer, textBudget)
                }
                is PineDrawing.Label -> {
                    if (!hasText(drawing.text, drawing.textColor)) continue
                    val anchor = index(drawing.x, drawing.byTime) ?: continue
                    val value = pineLabelValue(drawing, anchor, candles, priceFactor, barOrigin)
                    measureMarkerText(this@drawPineVisuals, x(anchor), value, drawing.location, drawing.size, drawing.onPrice,
                        drawing.text, drawing.textColor, textMeasurer, textBudget, y)
                }
                else -> null
            }
            if (layout != null) layouts[i] = layout
            if (textBudget.exhausted) break
        }
        val markLayouts = HashMap<Int, TextLayoutResult>()
        for (i in visibleMarks.last downTo firstMark) {
            if (textBudget.exhausted) break
            val mark = result.marks[i]
            val layout = measureMarkerText(this@drawPineVisuals, x(mark.index.toDouble()), mark.value, mark.location, mark.size,
                mark.onPrice, mark.text, mark.textColor, textMeasurer, textBudget, y)
            if (layout != null) markLayouts[i] = layout
        }
        for (i in firstMark..visibleMarks.last) {
            val mark = result.marks[i]
            drawMarkerIn(this@drawPineVisuals, x(mark.index.toDouble()), mark.value, mark.location, mark.shape,
                mark.color, mark.size, mark.onPrice, markLayouts[i], y)
        }
        for (i in firstDrawing..drawings.lastIndex) {
            when (val drawing = drawings[i]) {
                is PineDrawing.Line -> {
                    val color = drawing.color.visibleColor() ?: continue
                    val width = pineStrokeWidth(drawing.width)
                    if (!width.validSize() || !drawing.y1.isFinite() || !drawing.y2.isFinite()) continue
                    val a = index(drawing.x1, drawing.byTime) ?: continue
                    val b = index(drawing.x2, drawing.byTime) ?: continue
                    segment(x(a), y(drawing.y1), x(b), y(drawing.y2), Color(color), width,
                        pineEffect(drawing.pattern, width), drawing.extend)
                }
                is PineDrawing.Box -> {
                    val a = index(drawing.left, drawing.byTime) ?: continue
                    val b = index(drawing.right, drawing.byTime) ?: continue
                    if (!drawing.top.isFinite() || !drawing.bottom.isFinite()) continue
                    drawBoxIn(this@drawPineVisuals, x(a), y(drawing.top), x(b), y(drawing.bottom), drawing, layouts[i])
                }
                is PineDrawing.Label -> {
                    val anchor = index(drawing.x, drawing.byTime) ?: continue
                    val value = pineLabelValue(drawing, anchor, candles, priceFactor, barOrigin)
                    drawMarkerIn(this@drawPineVisuals, x(anchor), value, drawing.location, drawing.shape, drawing.color,
                        drawing.size, drawing.onPrice, layouts[i], y)
                }
            }
        }
    }
}

/** The normal chart clip does the final clipping. Clamp only extreme rows before feeding a polygon to the backend. */
private fun Path.addPinePolygon(a: Offset, b: Offset, c: Offset, d: Offset = Offset.Unspecified) {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    if (d != Offset.Unspecified) lineTo(d.x, d.y)
    close()
}

private fun DrawScope.drawPinePolygon(color: Color, a: Offset, b: Offset, c: Offset, d: Offset = Offset.Unspecified) {
    drawPath(Path().apply { addPinePolygon(a, b, c, d) }, color)
}

private fun PineWindow.inMarkerWindow(x: Float, y: Float, pad: Float) =
    x.isFinite() && y.isFinite() && x in left - pad..right + pad && y in top - pad..bottom + pad

private fun pineText(textMeasurer: TextMeasurer, text: String, color: Int, size: Float, maxWidth: Int): TextLayoutResult =
    textMeasurer.measure(text.take(512), TextStyle(color = Color(color), fontSize = size.sp), overflow = TextOverflow.Ellipsis,
        maxLines = 4, constraints = Constraints(maxWidth = maxWidth.coerceIn(1, 2048)))

private fun PineWindow.measureBoxText(
    scope: DrawScope, x1: Float, y1: Float, x2: Float, y2: Float, box: PineDrawing.Box,
    textMeasurer: TextMeasurer, budget: PineTextBudget,
): TextLayoutResult? {
    val ink = box.textColor.visibleColor() ?: return null
    if (!x1.isFinite() || !x2.isFinite() || !y1.isFinite() || !y2.isFinite()) return null
    val a = minOf(x1, x2)
    val b = maxOf(x1, x2)
    val c = minOf(y1, y2)
    val d = maxOf(y1, y2)
    if (box.text.isEmpty() || a >= b || c >= d || b <= left || a >= right || d <= top || c >= bottom) return null
    val padding = with(scope) { 3.dp.toPx() }
    val available = (b.toDouble() - a - 2 * padding).coerceAtMost(2048.0).toInt()
    if (available < 1 || !inMarkerWindow(a / 2f + b / 2f, c / 2f + d / 2f, maxOf(available / 2f, padding * 30)) || !budget.take()) return null
    return pineText(textMeasurer, box.text, ink, 12f, available)
}

private fun PineWindow.markerRow(
    value: Double, location: PineLocation, radius: Float, gap: Float, onPrice: Boolean, y: (Double) -> Float,
): Float = when {
    location == PineLocation.TOP || location == PineLocation.ABOVE_BAR && !onPrice -> minOf(bottom, top + radius + gap)
    location == PineLocation.BOTTOM || location == PineLocation.BELOW_BAR && !onPrice -> maxOf(top, bottom - radius - gap)
    !value.isFinite() -> Float.NaN
    location == PineLocation.ABOVE_BAR -> y(value) - radius - gap
    location == PineLocation.BELOW_BAR -> y(value) + radius + gap
    else -> y(value)
}

private fun PineWindow.measureMarkerText(
    scope: DrawScope, px: Float, value: Double, location: PineLocation, size: Float, onPrice: Boolean,
    text: String, textColor: Int?, textMeasurer: TextMeasurer, budget: PineTextBudget, y: (Double) -> Float,
): TextLayoutResult? {
    val ink = textColor.visibleColor() ?: return null
    if (text.isEmpty() || !size.validSize()) return null
    val unit = size.coerceAtMost(100f)
    val radius = with(scope) { unit.dp.toPx() / 2f }
    val gap = with(scope) { 2.dp.toPx() }
    if (!radius.validSize()) return null
    val row = markerRow(value, location, radius, gap, onPrice, y)
    val available = (right - left).coerceAtMost(512f).toInt()
    if (!inMarkerWindow(px, row, maxOf(radius * 6, available / 2f)) || !budget.take()) return null
    return pineText(textMeasurer, text, ink, unit, available)
}

private fun PineWindow.drawPineBands(
    scope: DrawScope, result: PineResult, slots: IntRange, budget: PineGeometryBudget, x: (Double) -> Float, y: (Double) -> Float,
) = with(scope) {
    val paths = linkedMapOf<Int, Path>()
    var activeFill = -1
    var contours = 0
    fun flush() {
        for ((argb, path) in paths) drawPath(path, Color(argb))
        paths.clear()
        contours = 0
    }
    val lo = top - 1_000_000f
    val hi = bottom + 1_000_000f
    result.forEachBand(slots, budget) { fill, i1, v1, w1, i2, v2, w2, argb ->
        val x1 = x(i1)
        val x2 = x(i2)
        if (!x1.isFinite() || !x2.isFinite() || x1 == x2 || maxOf(x1, x2) <= left || minOf(x1, x2) >= right) return@forEachBand
        val a1 = y(v1)
        val b1 = y(w1)
        val a2 = y(v2)
        val b2 = y(w2)
        if (!a1.isFinite() || !b1.isFinite() || !a2.isFinite() || !b2.isFinite() ||
            maxOf(a1, b1, a2, b2) <= top || minOf(a1, b1, a2, b2) >= bottom) return@forEachBand
        // One fill's slices occupy disjoint x intervals. Batch their colors, but never reorder different fills.
        if (fill != activeFill || contours == 128 || paths.size == 8 && argb !in paths) flush()
        activeFill = fill
        contours++
        paths.getOrPut(argb) { Path() }.addPinePolygon(Offset(x1, a1.coerceIn(lo, hi)), Offset(x2, a2.coerceIn(lo, hi)),
            Offset(x2, b2.coerceIn(lo, hi)), Offset(x1, b1.coerceIn(lo, hi)))
    }
    flush()
}

private fun PineWindow.drawBoxIn(
    scope: DrawScope, x1: Float, y1: Float, x2: Float, y2: Float, box: PineDrawing.Box, text: TextLayoutResult?,
) = with(scope) {
    if (!x1.isFinite() || !x2.isFinite() || !y1.isFinite() || !y2.isFinite()) return@with
    val a = minOf(x1, x2)
    val b = maxOf(x1, x2)
    val c = minOf(y1, y2)
    val d = maxOf(y1, y2)
    box.background.visibleColor()?.let { rect(Color(it), a, c, b, d) }
    val edge = box.border.visibleColor()
    val width = pineStrokeWidth(box.borderWidth)
    if (edge != null && width.validSize()) {
        val color = Color(edge)
        val effect = pineEffect(box.borderPattern, width)
        // Pine extends the horizontal borders only, rather than stretching the box or moving its vertical edges.
        val from = if (box.extend.leftward()) minOf(a, left) else a
        val to = if (box.extend.rightward()) maxOf(b, right) else b
        segment(from, c, to, c, color, width, effect)
        if (d != c) segment(from, d, to, d, color, width, effect)
        segment(a, c, a, d, color, width, effect)
        if (a != b) segment(b, c, b, d, color, width, effect)
    }
    if (text == null || a >= b || c >= d || b <= left || a >= right || d <= top || c >= bottom) return@with
    val centerX = a / 2f + b / 2f
    val centerY = c / 2f + d / 2f
    val origin = Offset(centerX - text.size.width / 2f, centerY - text.size.height / 2f)
    if (!inMarkerWindow(centerX, centerY, maxOf(text.size.width, text.size.height).toFloat())) return@with
    clipRect(maxOf(left, a), maxOf(top, c), minOf(right, b), minOf(bottom, d)) { drawText(text, topLeft = origin) }
}

private fun PineWindow.drawMarkerIn(
    scope: DrawScope, px: Float, value: Double, location: PineLocation, shape: PineShape, argb: Int?,
    size: Float, onPrice: Boolean, layout: TextLayoutResult?, y: (Double) -> Float,
) = with(scope) {
    val color = argb.visibleColor()?.let { Color(it) }?.takeUnless { shape == PineShape.TEXT }
    if (!size.validSize() || color == null && layout == null || !px.isFinite()) return@with
    val radius = size.coerceAtMost(100f).dp.toPx() / 2f
    val gap = 2.dp.toPx()
    if (!radius.validSize()) return@with
    val above = location == PineLocation.ABOVE_BAR
    val below = location == PineLocation.BELOW_BAR
    val row = markerRow(value, location, radius, gap, onPrice, y)
    val label = shape == PineShape.LABEL_UP || shape == PineShape.LABEL_DOWN
    val pad = maxOf(radius * 4, (layout?.size?.width ?: 0) / 2f, (layout?.size?.height ?: 0) + radius)
    if (!inMarkerWindow(px, row, pad)) return@with
    if (label) {
        val up = shape == PineShape.LABEL_UP
        val height = maxOf(radius * 2f, (layout?.size?.height ?: 0) + gap * 2f)
        val half = maxOf(radius, (layout?.size?.width ?: 0) / 2f + gap)
        // Preserve the orientation of the pointer but keep a relative marker's body away from its bar or pane edge.
        val center = when (location) {
            PineLocation.TOP -> row + height / 2f
            PineLocation.BOTTOM -> row - height / 2f
            PineLocation.ABOVE_BAR -> row + if (onPrice) -height / 2f else height / 2f
            PineLocation.BELOW_BAR -> row + if (onPrice) height / 2f else -height / 2f
            PineLocation.ABSOLUTE -> row + (if (up) 1f else -1f) * (height / 2f + radius * .65f)
        }
        if (color != null) {
            val topEdge = center - height / 2f
            val bottomEdge = center + height / 2f
            rect(color, px - half, topEdge, px + half, bottomEdge)
            val edge = if (up) topEdge else bottomEdge
            drawPinePolygon(color, Offset(px - radius / 2f, edge), Offset(px + radius / 2f, edge),
                Offset(px, edge + (if (up) -1f else 1f) * radius * .65f))
        }
        if (layout != null) drawText(layout, topLeft = Offset(px - layout.size.width / 2f, center - layout.size.height / 2f))
        return@with
    }
    if (color != null && inMarkerWindow(px, row, radius * 2)) drawGlyph(px, row, radius, shape, color)
    if (layout != null) {
        val dy = when {
            color == null -> -layout.size.height / 2f
            above && onPrice || location == PineLocation.BOTTOM || below && !onPrice -> -radius - gap - layout.size.height
            else -> radius + gap
        }
        drawText(layout, topLeft = Offset(px - layout.size.width / 2f, row + dy))
    }
}

private fun DrawScope.drawGlyph(x: Float, y: Float, r: Float, shape: PineShape, color: Color) {
    val stroke = maxOf(1f, r / 3f)
    fun point(dx: Float, dy: Float) = Offset(x + r * dx, y + r * dy)
    fun line(dx1: Float, dy1: Float, dx2: Float, dy2: Float) = drawLine(color, point(dx1, dy1), point(dx2, dy2), stroke)
    when (shape) {
        PineShape.CIRCLE -> drawCircle(color, r, Offset(x, y))
        PineShape.SQUARE -> drawRect(color, point(-1f, -1f), Size(r * 2, r * 2))
        PineShape.DIAMOND -> drawPinePolygon(color, point(0f, -1f), point(1f, 0f), point(0f, 1f), point(-1f, 0f))
        PineShape.TRIANGLE_UP, PineShape.TRIANGLE_DOWN -> {
            val dir = if (shape == PineShape.TRIANGLE_UP) 1f else -1f
            drawPinePolygon(color, point(0f, -dir), point(1f, dir), point(-1f, dir))
        }
        PineShape.ARROW_UP, PineShape.ARROW_DOWN -> {
            val dir = if (shape == PineShape.ARROW_UP) 1f else -1f
            line(0f, -.85f * dir, 0f, dir)
            line(-.72f, -.15f * dir, 0f, -.85f * dir)
            line(.72f, -.15f * dir, 0f, -.85f * dir)
        }
        PineShape.CROSS -> { line(-1f, 0f, 1f, 0f); line(0f, -1f, 0f, 1f) }
        PineShape.X_CROSS -> { line(-.75f, -.75f, .75f, .75f); line(-.75f, .75f, .75f, -.75f) }
        PineShape.FLAG -> {
            line(-.75f, 1f, -.75f, -1f)
            drawPinePolygon(color, point(-.75f, -1f), point(.95f, -.7f), point(.35f, -.33f), point(-.75f, -.18f))
        }
        PineShape.LABEL_UP, PineShape.LABEL_DOWN, PineShape.TEXT -> Unit
    }
}
