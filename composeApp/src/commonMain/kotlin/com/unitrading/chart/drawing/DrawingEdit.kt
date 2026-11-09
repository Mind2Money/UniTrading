package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private fun DrawingPoint.withPrice(price: Double) = copy(price = price)
private fun DrawingPoint.withTime(timeMs: Long) = copy(timeMs = timeMs)

private fun Drawing.setPoint(index: Int, value: DrawingPoint): Drawing =
    if (index in points.indices) copy(points = points.toMutableList().also { it[index] = value }) else this

/** Keeps a Gann square's second anchor on the price-per-bar ratio it was drawn with. */
private fun Drawing.squared(ctx: DrawingContext): Drawing {
    if (tool != DrawingTool.GANN_SQUARE || points.size < 2) return this
    val ratio = param("scaleRatio", 0.0)
    if (ratio == 0.0) return this
    val c = ctx.coords
    val bars = c.index(points[1].timeMs) - c.index(points[0].timeMs)
    val direction = if (points[1].price >= points[0].price) 1 else -1
    return setPoint(1, points[1].withPrice(points[0].price + direction * abs(bars) * ratio))
}

/**
 * Adds the points a tool works out by itself once its clicks are done: a position's width and levels, the control points
 * of curves, where a copied bar pattern lands, and the fixed ratio of a Gann square.
 */
internal fun completeAnchors(d: Drawing, ctx: DrawingContext): Drawing {
    val c = ctx.coords
    return when (d.tool) {
        DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION -> {
            if (d.points.size != 1) return d
            val entry = d.points[0]
            val distance = ctx.snapToTick(((c.scale.high - c.scale.low) * .08).coerceAtLeast(ctx.instrument.tickSize.toDouble()))
            val sign = if (d.tool == DrawingTool.LONG_POSITION) 1 else -1
            val right = c.timeAt(c.index(entry.timeMs) + 24)
            d.copy(points = listOf(entry, DrawingPoint(right, entry.price), DrawingPoint(entry.timeMs, entry.price + sign * distance), DrawingPoint(entry.timeMs, entry.price - sign * distance)))
        }
        DrawingTool.CURVE -> {
            if (d.points.size != 2) return d
            val a = c.offset(d.points[0])
            val b = c.offset(d.points[1])
            val normal = Offset(-(b.y - a.y), b.x - a.x) * .35f
            d.copy(points = d.points + c.pointAt(mid(a, b) + normal, snapToBar = false))
        }
        DrawingTool.DOUBLE_CURVE -> {
            if (d.points.size != 2) return d
            val a = c.offset(d.points[0])
            val b = c.offset(d.points[1])
            val normal = Offset(-(b.y - a.y), b.x - a.x) * .35f
            val one = a + (b - a) * .33f + normal
            val two = a + (b - a) * .66f - normal
            d.copy(points = d.points + c.pointAt(one, snapToBar = false) + c.pointAt(two, snapToBar = false))
        }
        DrawingTool.BARS_PATTERN -> {
            if (d.points.size != 2 || c.candles.isEmpty()) return d
            val end = maxOf(c.index(d.points[0].timeMs), c.index(d.points[1].timeMs)).roundToInt()
            val bar = c.candles[end.coerceIn(0, c.candles.lastIndex)]
            d.copy(points = d.points + DrawingPoint(c.timeAt(end + 1.0), bar.close.toDouble()))
        }
        DrawingTool.GANN_SQUARE -> {
            if (d.points.size != 2) return d
            val bars = abs(c.index(d.points[1].timeMs) - c.index(d.points[0].timeMs)).coerceAtLeast(1.0)
            val ratio = abs(d.points[1].price - d.points[0].price) / bars
            d.copy(params = d.params + ("scaleRatio" to ratio.coerceAtLeast(1e-9)))
        }
        else -> d
    }
}

/** [d] after handle [id] was dragged to [target]. */
internal fun moveHandle(d: Drawing, id: Int, target: DrawingPoint, ctx: DrawingContext): Drawing {
    val p = d.points
    val c = ctx.coords
    return when (d.tool) {
        DrawingTool.PARALLEL_CHANNEL, DrawingTool.FIB_CHANNEL -> when (id) {
            0, 1 -> d.setPoint(id, target)
            4 -> {
                val shift = target.price - (p[0].price + p[1].price) / 2
                d.copy(points = listOf(p[0].withPrice(p[0].price + shift), p[1].withPrice(p[1].price + shift)) + p.drop(2))
            }
            else -> d.setPoint(2, target)
        }
        DrawingTool.FLAT_TOP_BOTTOM -> if (id <= 1) d.setPoint(id, target) else d.setPoint(2, p[2].withPrice(target.price))
        DrawingTool.DISJOINT_CHANNEL -> when (id) {
            0, 1 -> d.setPoint(id, target)
            3 -> d.setPoint(2, p[2].withPrice(target.price - (p[0].price - p[1].price)))
            else -> d.setPoint(2, target)
        }
        DrawingTool.RECTANGLE -> when (id) {
            0, 1 -> d.setPoint(id, target)
            2 -> d.copy(points = listOf(p[0].withPrice(target.price), p[1].withTime(target.timeMs)))
            3 -> d.copy(points = listOf(p[0].withTime(target.timeMs), p[1].withPrice(target.price)))
            4 -> d.setPoint(0, p[0].withPrice(target.price))
            5 -> d.setPoint(1, p[1].withPrice(target.price))
            6 -> d.setPoint(0, p[0].withTime(target.timeMs))
            else -> d.setPoint(1, p[1].withTime(target.timeMs))
        }
        DrawingTool.ROTATED_RECTANGLE, DrawingTool.ELLIPSE -> if (id <= 1) d.setPoint(id, target) else d.setPoint(2, target)
        DrawingTool.LONG_POSITION, DrawingTool.SHORT_POSITION -> {
            val long = d.tool == DrawingTool.LONG_POSITION
            when (id) {
                0 -> {
                    val shift = target.price - p[0].price
                    d.copy(points = listOf(target, p[1], p[2].copy(timeMs = target.timeMs, price = p[2].price + shift), p[3].copy(timeMs = target.timeMs, price = p[3].price + shift)))
                }
                1 -> d.setPoint(1, p[1].withTime(target.timeMs))
                2 -> d.setPoint(2, p[2].withPrice(if (long) maxOf(target.price, p[0].price) else minOf(target.price, p[0].price)))
                else -> d.setPoint(3, p[3].withPrice(if (long) minOf(target.price, p[0].price) else maxOf(target.price, p[0].price)))
            }
        }
        DrawingTool.SIGNPOST, DrawingTool.POST, DrawingTool.IDEA -> if (id == 0) d.setPoint(0, target) else {
            val percent = (c.y(p[0].price) - c.y(target.price)) / c.plot.height * 100
            d.copy(params = d.params + ("position" to percent.toDouble().coerceAtLeast(2.0)))
        }
        DrawingTool.IMAGE -> if (id == 0) d.setPoint(0, target) else d.copy(params = d.params + ("width" to ((c.x(target.timeMs) - c.x(p[0].timeMs)) / ctx.density).toDouble().coerceAtLeast(24.0)))
        DrawingTool.ICON -> when (id) {
            0 -> d.setPoint(0, target)
            1 -> {
                val center = c.offset(p[0])
                val reach = distance(center, c.offset(target)) / ctx.density
                d.copy(params = d.params + ("size" to (reach * 1.2f).toDouble().coerceIn(16.0, 320.0)))
            }
            else -> {
                val center = c.offset(p[0])
                val at = c.offset(target)
                d.copy(params = d.params + ("angle" to atan2((at.x - center.x).toDouble(), (center.y - at.y).toDouble()) * 180 / kotlin.math.PI))
            }
        }
        DrawingTool.TABLE -> d.copy(params = d.params + ("screenX" to ((c.x(target.timeMs) - c.plot.left) / c.plot.width).toDouble().coerceIn(0.0, .95)) +
            ("screenY" to ((c.y(target.price) - c.plot.top) / c.plot.height).toDouble().coerceIn(0.0, .95)))
        DrawingTool.GANN_SQUARE -> d.setPoint(id, target).squared(ctx)
        DrawingTool.BRUSH, DrawingTool.HIGHLIGHTER, DrawingTool.PATH, DrawingTool.POLYLINE, DrawingTool.GHOST_FEED -> d.setPoint(id.coerceIn(0, p.lastIndex), target)
        else -> d.setPoint(id, target)
    }
}

/** [d] moved as a whole by whole [bars] and [price] units; a table moves by screen pixels instead. */
internal fun translate(d: Drawing, bars: Double, price: Double, pixels: Offset, ctx: DrawingContext): Drawing {
    val c = ctx.coords
    if (d.tool.screenAnchored) return d.copy(params = d.params +
        ("screenX" to (d.param("screenX", .25) + pixels.x / c.plot.width).coerceIn(0.0, .95)) +
        ("screenY" to (d.param("screenY", .25) + pixels.y / c.plot.height).coerceIn(0.0, .95)))
    return d.copy(points = d.points.map { DrawingPoint(c.timeAt(c.index(it.timeMs) + bars), it.price + price) })
}
