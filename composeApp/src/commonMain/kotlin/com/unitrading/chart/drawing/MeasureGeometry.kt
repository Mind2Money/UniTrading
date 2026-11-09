package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.unitrading.broker.Candle
import com.unitrading.chart.formatVolume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

private val PROFIT = Color(0xFF089981)
private val LOSS = Color(0xFFF23645)

internal class PositionOutcome(val filled: Boolean, val closed: Boolean, val exitPrice: Double, val exitIndex: Int)

/** Walks the bars from the entry: fills when price touches the entry, then closes at the first of stop or target. Both in one bar counts as the stop. */
internal fun evaluatePosition(candles: List<Candle>, firstBar: Int, lastBar: Int, long: Boolean, entry: Double, target: Double, stop: Double): PositionOutcome {
    val start = firstBar.coerceAtLeast(0)
    val end = lastBar.coerceAtMost(candles.lastIndex)
    var filled = false
    for (index in start..end) {
        val bar = candles[index]
        if (!filled && entry >= bar.low && entry <= bar.high) filled = true
        if (!filled) continue
        val stopped = if (long) bar.low <= stop else bar.high >= stop
        val reached = if (long) bar.high >= target else bar.low <= target
        if (stopped) return PositionOutcome(true, true, stop, index)
        if (reached) return PositionOutcome(true, true, target, index)
    }
    return PositionOutcome(filled, false, if (end >= start && filled) candles[end].close.toDouble() else entry, end.coerceAtLeast(start))
}

internal fun SceneBuilder.position() {
    if (pts.size < 4) { anchorHandles(); return }
    val long = d.tool == DrawingTool.LONG_POSITION
    val entry = d.points[0].price
    val target = d.points[2].price
    val stop = d.points[3].price
    val x1 = pts[0].x
    val x2 = pts[1].x
    val yEntry = pts[0].y
    val yTarget = pts[2].y
    val yStop = pts[3].y
    fillArea(rectPoints(Offset(x1, yEntry), Offset(x2, yTarget)), PROFIT.copy(alpha = .2f), hit = true)
    fillArea(rectPoints(Offset(x1, yEntry), Offset(x2, yStop)), LOSS.copy(alpha = .2f), hit = true)
    val firstBar = c.index(d.points[0].timeMs).roundToInt()
    val lastBar = c.index(d.points[1].timeMs).roundToInt()
    val outcome = evaluatePosition(c.candles, min(firstBar, lastBar), max(firstBar, lastBar), long, entry, target, stop)
    if (outcome.filled) {
        val exitX = c.xOfIndex(outcome.exitIndex.toDouble()).coerceIn(min(x1, x2), max(x1, x2))
        val exitY = c.y(outcome.exitPrice)
        val winning = if (long) outcome.exitPrice >= entry else outcome.exitPrice <= entry
        fillArea(rectPoints(Offset(x1, yEntry), Offset(exitX, exitY)), (if (winning) PROFIT else LOSS).copy(alpha = .25f))
        line(Offset(x1, yEntry), Offset(exitX, exitY), StrokeSpec(Color(0xFF808080), px, DrawingDash.DASHED), hit = false)
    }
    line(Offset(x1, yEntry), Offset(x2, yEntry), stroke)

    val tick = ctx.instrument.tickSize.toDouble()
    val profitTicks = abs(target - entry) / tick
    val stopTicks = abs(entry - stop) / tick
    val tickValue = ctx.instrument.tickValueMicrosPerLot / 1_000_000.0
    val riskAmount = d.param("accountSize", 1000.0) * d.param("riskPercent", 25.0) / 100
    val quantity = if (stopTicks > 0 && tickValue > 0) riskAmount / (stopTicks * tickValue) else 0.0
    val reward = if (stopTicks > 0) profitTicks / stopTicks else 0.0
    val pnl = (if (long) outcome.exitPrice - entry else entry - outcome.exitPrice) / tick * tickValue * quantity
    fun money(value: Double) = formatRatio(value, 2)
    fun offsetText(price: Double) = "${ctx.priceDiff(abs(price - entry))} (${formatRatio(abs(price - entry) / entry * 100, 2)}%) ${ctx.ticks(abs(price - entry))}"
    val center = (x1 + x2) / 2
    val font = textSize
    val above = yTarget < yStop
    label("目标: ${offsetText(target)}, 金额: ${money(quantity * profitTicks * tickValue)}", Offset(center, yTarget + (if (above) -3f else 3f) * px), color = ink,
        h = HAlign.CENTER, v = if (above) VAlign.BOTTOM else VAlign.TOP, sizeSp = font, background = PROFIT, padding = 3f * px, hit = false)
    val state = when { !outcome.filled -> "未成交"; outcome.closed -> "已平仓"; else -> "持仓中" }
    label("$state 盈亏: ${money(pnl)}, 数量: ${formatRatio(quantity, 3)}\n风险回报比: ${formatRatio(reward, 2)}", Offset(center, yEntry), color = ink,
        h = HAlign.CENTER, v = VAlign.MIDDLE, sizeSp = font, background = if (pnl >= 0) PROFIT else LOSS, padding = 3f * px, hit = false)
    label("止损: ${offsetText(stop)}, 金额: ${money(riskAmount)}", Offset(center, yStop + (if (above) 3f else -3f) * px), color = ink,
        h = HAlign.CENTER, v = if (above) VAlign.TOP else VAlign.BOTTOM, sizeSp = font, background = LOSS, padding = 3f * px, hit = false)
    handle(0, Offset(x1, yEntry)); handle(1, Offset(x2, yEntry)); handle(2, Offset(x1, yTarget)); handle(3, Offset(x1, yStop))
    if (s.showPrices) { markPrice(entry, Color(0xFF808080)); markPrice(target, PROFIT); markPrice(stop, LOSS) }
    markTime(d.points[0].timeMs); markTime(d.points[1].timeMs)
}

internal enum class ForecastStatus(val label: String, val color: Color, val text: Color) {
    WAITING("等待中", Color(0xFFEAD289), Color(0xFF6D4D22)), SUCCESS("成功", Color(0xFF4CAF50), Color.White), FAILURE("失败", Color(0xFFF23645), Color.White)
}

/** A forecast succeeds once price reaches the target by the target time, and fails when that time passes without it. */
internal fun forecastStatus(candles: List<Candle>, fromBar: Int, toBar: Int, source: Double, target: Double, targetTimeMs: Long): ForecastStatus {
    if (candles.isEmpty()) return ForecastStatus.WAITING
    val rising = target >= source
    for (index in fromBar.coerceAtLeast(0)..toBar.coerceAtMost(candles.lastIndex)) {
        val bar = candles[index]
        if (if (rising) bar.high >= target else bar.low <= target) return ForecastStatus.SUCCESS
    }
    return if (candles.last().timeMs >= targetTimeMs) ForecastStatus.FAILURE else ForecastStatus.WAITING
}

internal fun SceneBuilder.forecast() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    line(a, b)
    dot(a, 4f * px); dot(b, 4f * px)
    val source = d.points[0].price
    val target = d.points[1].price
    val diff = target - source
    val status = forecastStatus(c.candles, c.index(d.points[0].timeMs).roundToInt(), c.index(d.points[1].timeMs).roundToInt(), source, target, d.points[1].timeMs)
    val labelText = Color.White
    label("${ctx.price(source)}\n${formatDate(d.points[0].timeMs)}", Offset(a.x, a.y + 8f * px), color = labelText, h = HAlign.CENTER, v = VAlign.TOP, background = base, padding = 4f * px, hit = false)
    val percent = if (source != 0.0) diff / source * 100 else 0.0
    val up = b.y <= a.y
    val box = label("${ctx.priceDiff(diff)} (${signed(percent)}%) ${ctx.ticks(diff)}\n${formatDate(d.points[1].timeMs)}\n${(c.index(d.points[1].timeMs) - c.index(d.points[0].timeMs)).roundToInt()} 根K线",
        Offset(b.x, b.y + (if (up) -8f else 8f) * px), color = labelText, h = HAlign.CENTER, v = if (up) VAlign.BOTTOM else VAlign.TOP, background = base, padding = 4f * px, hit = false)
    label(status.label, Offset(box.center.x, if (up) box.top - 2f * px else box.bottom + 2f * px), color = status.text, h = HAlign.CENTER, v = if (up) VAlign.BOTTOM else VAlign.TOP, background = status.color, padding = 3f * px, hit = false)
}

internal fun SceneBuilder.barsPattern() {
    anchorHandles()
    if (pts.size < 2) return
    val i1 = c.index(d.points[0].timeMs).roundToInt().coerceIn(0, c.total - 1)
    val i2 = c.index(d.points[1].timeMs).roundToInt().coerceIn(0, c.total - 1)
    val first = min(i1, i2)
    val last = max(i1, i2)
    val x1 = c.xOfIndex(first.toDouble())
    val x2 = c.xOfIndex(last.toDouble())
    val bars = c.candles.subList(first, last + 1)
    val top = c.y(bars.maxOf { it.high }.toDouble())
    val bottom = c.y(bars.minOf { it.low }.toDouble())
    polyline(rectPoints(Offset(x1, top), Offset(x2, bottom)), stroke.copy(width = px, dash = DrawingDash.DASHED), closed = true, hit = false)
    if (pts.size < 3) return
    val start = c.index(d.points[2].timeMs)
    val anchorPrice = d.points[2].price
    val mirrored = d.param("mirrored") != 0.0
    val flipped = d.param("flipped") != 0.0
    val mode = d.param("mode").toInt()
    val pivot = bars.first().open.toDouble()
    fun project(value: Long): Double = anchorPrice + (value - pivot) * (if (flipped) -1 else 1)
    val width = (c.step * .62f).coerceIn(2f, 12f * px)
    val ordered = if (mirrored) bars.reversed() else bars
    val tint = base.copy(alpha = .55f)
    val line = ArrayList<Offset>()
    ordered.forEachIndexed { offset, bar ->
        val center = c.xOfIndex(start + offset)
        if (mode == 0) {
            val up = bar.close >= bar.open != flipped
            val wick = StrokeSpec(tint, px)
            line(Offset(center, c.y(project(bar.high))), Offset(center, c.y(project(bar.low))), wick, hit = false)
            val y1 = c.y(project(bar.open))
            val y2 = c.y(project(bar.close))
            val body = if (abs(y1 - y2) < 1.6f) y1 + 1.6f else y2
            polyline(rectPoints(Offset(center - width / 2, y1), Offset(center + width / 2, body)), if (up) wick else null, if (up) null else tint, closed = true)
        } else {
            val value = when (mode) { 1 -> bar.open; 2 -> bar.high; 3 -> bar.low; 4 -> bar.close; else -> (bar.high + bar.low) / 2 }
            line += Offset(center, c.y(project(value)))
        }
    }
    if (mode != 0) polyline(line, StrokeSpec(base.copy(alpha = .8f), s.width * px))
    if (pts.size >= 3) handle(2, pts[2])
}

internal class GhostBar(val index: Int, val open: Double, val high: Double, val low: Double, val close: Double)

/** Made-up bars walking through the anchors; [seed] keeps their wicks the same from one frame to the next. */
internal fun ghostBars(anchors: List<Pair<Double, Double>>, averageRange: Double, variance: Double, seed: Int): List<GhostBar> {
    if (anchors.size < 2) return emptyList()
    fun noise(index: Int): Double {
        val mixed = (index.toLong() * 2_654_435_761L + seed) and 0xFFFFFFFFL
        return (mixed % 1000) / 1000.0
    }
    val bars = ArrayList<GhostBar>()
    var open = anchors.first().second
    anchors.zipWithNext().forEach { (from, to) ->
        val start = from.first.roundToInt()
        val end = to.first.roundToInt()
        val span = (end - start).coerceAtLeast(1)
        for (index in start until end.coerceAtLeast(start + 1)) {
            val close = from.second + (to.second - from.second) * (index + 1 - start) / span
            val wick = averageRange * (1 + (noise(index) - .5) * variance / 50) / 2
            bars += GhostBar(index, open, max(open, close) + wick, min(open, close) - wick, close)
            open = close
        }
    }
    return bars
}

internal fun SceneBuilder.ghostFeed() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val anchors = d.points.map { c.index(it.timeMs) to it.price }
    val range = d.param("averageHl", 20.0) * ctx.instrument.tickSize
    val bars = ghostBars(anchors, range, d.param("variance", 50.0), d.id.hashCode())
    val width = (c.step * .62f).coerceIn(2f, 12f * px)
    val upFill = Color(0xFFACE5DC).copy(alpha = .5f)
    val downFill = Color(0xFFFAA1A4).copy(alpha = .5f)
    val upBorder = StrokeSpec(PROFIT, px)
    val downBorder = StrokeSpec(LOSS, px)
    bars.forEach { bar ->
        val x = c.xOfIndex(bar.index + 1.0)
        val up = bar.close >= bar.open
        val spec = if (up) upBorder else downBorder
        line(Offset(x, c.y(bar.high)), Offset(x, c.y(bar.low)), StrokeSpec(Color(0xFF808080), px), hit = false)
        polyline(rectPoints(Offset(x - width / 2, c.y(bar.open)), Offset(x + width / 2, c.y(bar.close))), spec, if (up) upFill else downFill, closed = true)
    }
}

/** A wedge around the first anchor from b to c, split in two colours where it crosses the level through the centre. */
internal fun SceneBuilder.projection() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val center = pts[0]
    val trend = StrokeSpec(base, s.width * px)
    line(center, pts[1], trend)
    if (pts.size < 3) return
    line(center, pts[2], trend)
    val radius = distance(center, pts[1])
    val from = angleOf(center, pts[1])
    var sweep = angleOf(center, pts[2]) - from
    if (sweep > PI) sweep -= 2 * PI else if (sweep < -PI) sweep += 2 * PI
    val tintA = area?.let { Color(0xFF2962FF).copy(alpha = s.fillAlpha) }
    val tintB = area?.let { Color(0xFF9C27B0).copy(alpha = s.fillAlpha) }
    fun sectorEnd(angle: Double) = (angle - from) / sweep
    val crossesLevel = (from < 0.0) != (from + sweep < 0.0) && sectorEnd(0.0) in 0.0..1.0
    if (crossesLevel) {
        fillArea(listOf(center) + ellipsePoints(center, radius, radius, from = from, sweep = -from, steps = 48), if (from < 0) tintA else tintB, hit = true)
        fillArea(listOf(center) + ellipsePoints(center, radius, radius, from = 0.0, sweep = from + sweep, steps = 48), if (from < 0) tintB else tintA, hit = true)
    } else fillArea(listOf(center) + ellipsePoints(center, radius, radius, from = from, sweep = sweep, steps = 72), if (from < 0) tintA else tintB, hit = true)
    polyline(ellipsePoints(center, radius, radius, from = from, sweep = sweep, steps = 72), StrokeSpec(colorOf(d.levels.firstOrNull()?.color, base), s.width * px))
    if (s.showLabels) label(formatRatio(sweep * 180 / PI, 1) + "°", Offset(center.x + radius + 4f * px, center.y), color = base, h = HAlign.LEFT, v = VAlign.MIDDLE, hit = false)
}

internal fun SceneBuilder.anchoredVwap() {
    if (pts.isEmpty() || c.candles.isEmpty()) { anchorHandles(); return }
    val startBar = c.index(d.points[0].timeMs).roundToInt().coerceIn(0, c.total - 1)
    var volumeSum = 0.0
    var priceVolume = 0.0
    var squareVolume = 0.0
    val vwap = ArrayList<Offset>()
    val bands = listOf(d.param("band1", 1.0), d.param("band2"), d.param("band3")).filter { it > 0.0 }
    val uppers = bands.map { ArrayList<Offset>() }
    val lowers = bands.map { ArrayList<Offset>() }
    var last = 0.0
    var startValue = c.candles[startBar].close.toDouble()
    for (index in startBar..c.candles.lastIndex) {
        val bar = c.candles[index]
        val typical = (bar.high + bar.low + bar.close) / 3.0
        val volume = (bar.volume ?: 1L).toDouble()
        volumeSum += volume
        priceVolume += typical * volume
        squareVolume += typical * typical * volume
        val value = priceVolume / volumeSum
        val deviation = sqrt(max(0.0, squareVolume / volumeSum - value * value))
        if (index == startBar) startValue = value
        last = value
        val x = c.xOfIndex(index.toDouble())
        if (x < view.left - c.step || x > view.right + c.step) continue
        vwap += Offset(x, c.y(value))
        bands.forEachIndexed { b, multiple ->
            uppers[b] += Offset(x, c.y(value + deviation * multiple))
            lowers[b] += Offset(x, c.y(value - deviation * multiple))
        }
    }
    if (area != null && uppers.isNotEmpty()) fillArea(uppers[0] + lowers[0].reversed(), area)
    val bandColor = StrokeSpec(Color(0xFF4CAF50), s.width * px)
    uppers.forEach { polyline(it, bandColor, hit = false) }
    lowers.forEach { polyline(it, bandColor, hit = false) }
    polyline(vwap)
    handle(0, Offset(c.xOfIndex(startBar.toDouble()), c.y(startValue)))
    markTime(c.timeAt(startBar.toDouble()))
    if (s.showPrices) markPrice(last, always = true)
}

internal class VolumeRow(val low: Double, val high: Double, val up: Double, val down: Double) { val total get() = up + down }
internal class VolumeProfile(val rows: List<VolumeRow>, val pocIndex: Int, val valueArea: IntRange) {
    val max: Double = rows.maxOfOrNull { it.total } ?: 0.0
    val poc: Double get() = (rows[pocIndex].low + rows[pocIndex].high) / 2
}

/** Volume by price of the bars in [first]..[last]; a bar's volume spreads evenly over the rows its range covers. */
internal fun computeVolumeProfile(candles: List<Candle>, first: Int, last: Int, rowCount: Int, valueAreaPercent: Double): VolumeProfile? {
    val bars = candles.subList(first.coerceAtLeast(0), (last + 1).coerceAtMost(candles.size)).filter { (it.volume ?: 0) > 0 }
    if (bars.isEmpty() || rowCount < 1) return null
    val low = bars.minOf { it.low }.toDouble()
    val high = bars.maxOf { it.high }.toDouble()
    val height = max((high - low) / rowCount, 1e-9)
    val up = DoubleArray(rowCount)
    val down = DoubleArray(rowCount)
    bars.forEach { bar ->
        val volume = bar.volume!!.toDouble()
        val barLow = bar.low.toDouble()
        val barHigh = bar.high.toDouble()
        val target = if (bar.close >= bar.open) up else down
        val firstRow = ((barLow - low) / height).toInt().coerceIn(0, rowCount - 1)
        val lastRow = ((barHigh - low) / height).toInt().coerceIn(0, rowCount - 1)
        if (firstRow == lastRow || barHigh <= barLow) { target[firstRow] += volume; return@forEach }
        for (row in firstRow..lastRow) {
            val rowLow = low + row * height
            val covered = min(barHigh, rowLow + height) - max(barLow, rowLow)
            target[row] += volume * max(0.0, covered) / (barHigh - barLow)
        }
    }
    val rows = List(rowCount) { VolumeRow(low + it * height, low + (it + 1) * height, up[it], down[it]) }
    val poc = rows.indices.maxByOrNull { rows[it].total } ?: 0
    val goal = rows.sumOf { it.total } * valueAreaPercent / 100
    var from = poc
    var to = poc
    var covered = rows[poc].total
    while (covered < goal && (from > 0 || to < rows.lastIndex)) {
        val below = rows.getOrNull(from - 1)?.total ?: -1.0
        val above = rows.getOrNull(to + 1)?.total ?: -1.0
        if (above >= below) { to++; covered += rows[to].total } else { from--; covered += rows[from].total }
    }
    return VolumeProfile(rows, poc, from..to)
}

internal fun SceneBuilder.volumeProfile() {
    if (pts.isEmpty() || c.candles.isEmpty()) { anchorHandles(); return }
    val fixed = d.tool == DrawingTool.FIXED_RANGE_VOLUME_PROFILE
    if (fixed && pts.size < 2) { anchorHandles(); return }
    val startBar = c.index(d.points[0].timeMs).roundToInt().coerceIn(0, c.total - 1)
    val endBar = if (fixed) c.index(d.points[1].timeMs).roundToInt().coerceIn(0, c.total - 1) else c.total - 1
    val first = min(startBar, endBar)
    val last = max(startBar, endBar)
    val profile = computeVolumeProfile(c.candles, first, last, d.param("rows", 24.0).toInt(), d.param("valueArea", 70.0))
    val x1 = c.xOfIndex(first.toDouble())
    val x2 = c.xOfIndex(last.toDouble())
    val mid = c.y(c.candles[first].close.toDouble())
    handle(0, Offset(x1, mid))
    if (fixed) handle(1, Offset(x2, mid))
    if (profile == null) {
        label("无成交量数据", Offset(x1, mid), color = ctx.ink, h = HAlign.LEFT, v = VAlign.BOTTOM, background = ctx.surface.copy(alpha = .86f), padding = 3f * px, hit = true)
        return
    }
    val top = c.y(profile.rows.last().high)
    val bottom = c.y(profile.rows.first().low)
    fillArea(rectPoints(Offset(x1, top), Offset(x2, bottom)), area, hit = false)
    val fromRight = !fixed
    val origin = if (fromRight) x2 else x1
    val fullWidth = abs(x2 - x1) * (d.param("widthPercent", 30.0) / 100).toFloat()
    val upColor = Color(0xFF26C6DA)
    val downColor = Color(0xFFEC407A)
    val sign = if (fromRight) -1f else 1f
    profile.rows.forEachIndexed { index, row ->
        if (row.total <= 0.0) return@forEachIndexed
        val inArea = index in profile.valueArea
        val alpha = if (inArea) .7f else .25f
        val yTop = c.y(row.high) + .5f
        val yBottom = c.y(row.low) - .5f
        val upWidth = (row.up / profile.max * fullWidth).toFloat() * sign
        val downWidth = (row.down / profile.max * fullWidth).toFloat() * sign
        fillArea(rectPoints(Offset(origin, yTop), Offset(origin + upWidth, yBottom)), upColor.copy(alpha = alpha), hit = true)
        fillArea(rectPoints(Offset(origin + upWidth, yTop), Offset(origin + upWidth + downWidth, yBottom)), downColor.copy(alpha = alpha), hit = true)
    }
    val pocY = c.y(profile.poc)
    line(Offset(x1, pocY), Offset(x2, pocY), StrokeSpec(base, 2f * px), hit = false)
    if (s.showPrices) markPrice(profile.poc, base, always = true)
    markTime(c.timeAt(first.toDouble())); markTime(c.timeAt(last.toDouble()))
}

internal fun SceneBuilder.measureRange() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val a = pts[0]
    val b = pts[1]
    val priceSide = d.tool != DrawingTool.DATE_RANGE
    val timeSide = d.tool != DrawingTool.PRICE_RANGE
    val falling = d.points[1].price < d.points[0].price
    val measuring = d.tool == DrawingTool.MEASURE
    val color = if (measuring && falling) Color(0xFFF23645) else base
    val spec = StrokeSpec(color, s.width * px)
    fillArea(rectPoints(a, b), color.copy(alpha = s.fillAlpha), hit = !measuring)
    if (priceSide) {
        line(Offset(a.x, a.y), Offset(b.x, a.y), spec); line(Offset(a.x, b.y), Offset(b.x, b.y), spec)
        val x = mid(a, b).x
        line(Offset(x, a.y), Offset(x, b.y), spec)
        arrowHead(Offset(x, b.y), Offset(x, a.y), spec)
    }
    if (timeSide) {
        if (!priceSide) { line(Offset(a.x, a.y), Offset(a.x, b.y), spec); line(Offset(b.x, a.y), Offset(b.x, b.y), spec) }
        val y = mid(a, b).y
        line(Offset(a.x, y), Offset(b.x, y), spec)
        arrowHead(Offset(b.x, y), Offset(a.x, y), spec)
    }
    if (!s.showStats) return
    val diff = d.points[1].price - d.points[0].price
    val percent = if (d.points[0].price != 0.0) diff / d.points[0].price * 100 else 0.0
    val i1 = c.index(d.points[0].timeMs).roundToInt()
    val i2 = c.index(d.points[1].timeMs).roundToInt()
    val volume = c.candles.subList(min(i1, i2).coerceIn(0, c.total), (max(i1, i2) + 1).coerceIn(0, c.total)).sumOf { it.volume ?: 0L }
    val lines = buildList {
        if (priceSide) add("${ctx.priceDiff(diff)} (${signed(percent)}%) ${ctx.ticks(diff)}")
        if (timeSide) add("${i2 - i1} 根K线, ${formatSpan(d.points[1].timeMs - d.points[0].timeMs)}")
        if (volume > 0) add("成交量 ${formatVolume(volume)}")
    }
    val center = mid(a, b).x
    val below = b.y >= a.y
    label(lines.joinToString("\n"), Offset(center, max(a.y, b.y).let { if (below) it + 6f * px else min(a.y, b.y) - 6f * px }), color = ink, h = HAlign.CENTER, v = if (below) VAlign.TOP else VAlign.BOTTOM,
        background = if (measuring) color else Color(0xFF222222), padding = 4f * px, corner = 3f * px, hit = false)
}

/** Rounds [value] to the instrument's tick, for prices computed when a tool is placed. */
internal fun DrawingContext.snapToTick(value: Double): Double {
    val tick = instrument.tickSize.toDouble()
    return (value / tick).roundToLong() * tick
}
