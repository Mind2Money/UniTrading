package com.unitrading.chart.pine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val MAX_PERIOD = 1_000
private const val MAX_TA_SITES = 48
private val aggregateParameters = List(16) { "number$it" }
private val unaryMath: Map<String, (Double) -> Double> = mapOf(
    "math.abs" to ::abs, "math.ceil" to ::ceil, "math.floor" to ::floor,
    "math.exp" to ::exp, "math.log" to ::ln, "math.log10" to ::log10,
    "math.sign" to ::sign, "math.sqrt" to ::sqrt,
    "math.sin" to ::sin, "math.cos" to ::cos, "math.tan" to ::tan,
    "math.asin" to ::asin, "math.acos" to ::acos, "math.atan" to ::atan,
    "math.todegrees" to { it * (180.0 / PI) }, "math.toradians" to { it * (PI / 180.0) },
)
private fun mathParameter(name: String) = when (name) {
    "math.sin", "math.cos", "math.tan" -> "angle"
    "math.todegrees" -> "radians"
    "math.toradians" -> "degrees"
    else -> "number"
}

internal val pineFunctionSignatures: Map<String, PineSignature> = buildMap {
    for (name in listOf("ta.sma", "ta.wma", "ta.ema", "ta.rma", "ta.rsi", "ta.roc", "math.sum")) {
        put(name, pineSignature(2, "source", "length"))
    }
    put("ta.stdev", pineSignature(2, "source", "length", "biased"))
    put("ta.highest", pineSignature(1, "source", "length"))
    put("ta.lowest", pineSignature(1, "source", "length"))
    put("ta.change", pineSignature(1, "source", "length"))
    for (name in listOf("ta.crossover", "ta.crossunder", "ta.cross")) {
        put(name, pineSignature(2, "source1", "source2"))
    }
    put("ta.atr", pineSignature(1, "length"))
    put("ta.tr", pineSignature(1, "handle_na"))
    put("ta.barssince", pineSignature(1, "condition"))
    put("ta.cum", pineSignature(1, "source"))
    for (name in unaryMath.keys) put(name, pineSignature(1, mathParameter(name)))
    put("math.pow", pineSignature(2, "base", "exponent"))
    put("math.round", pineSignature(1, "number", "precision"))
    for (name in listOf("math.min", "math.max", "math.avg")) put(name, PineSignature(2, aggregateParameters))
    for (name in listOf("na", "float", "int", "bool", "color")) put(name, pineSignature(1, "x"))
    put("nz", pineSignature(1, "source", "replacement"))
    put("color.new", pineSignature(2, "color", "transp"))
    put("color.rgb", pineSignature(3, "red", "green", "blue", "transp"))
    for (name in listOf("color.r", "color.g", "color.b", "color.t")) put(name, pineSignature(1, "color"))
    put("str.tostring", pineSignature(1, "value", "format"))
    put("str.tonumber", pineSignature(1, "string"))
    put("str.length", pineSignature(1, "string"))
}

/**
 * One instance per run. Bars advance monotonically at each call site. Repeated executions on the
 * same bar each see the previously committed bars, and the last execution supplies the value for
 * future bars. Iterations are not independent series. Skipped calls cannot reconstruct their input.
 *
 * Windows for SMA, WMA, STDEV and SUM count finite samples; RSI compares successive finite samples.
 * In contrast, extrema, lag and cross functions use the actual bar offsets, treating unexecuted
 * bars as missing. Extrema ignore missing values even in a partial initial window. EMA starts at the
 * first valid input; RMA/ATR/RSI use Wilder seeds. Changing a normally simple Wilder length reseeds
 * from recent inputs. An explicit `na` length masks the result without discarding the source history.
 */
internal class PineFunctions() {
    private val sites = mutableMapOf<Int, Site>()

    fun call(name: String, id: Int, args: PineArgs, bar: Int, trueRange: Double, high: Double, low: Double, charge: (Int) -> Unit): Any? = when (name) {
        "ta.sma", "ta.wma", "ta.stdev", "math.sum" -> site(id, args, ::RollingSite).run {
            advance(bar, charge)
            evaluate(name, args.number("source"), args.period(), name != "ta.stdev" || args.bool("biased", true), charge)
        }
        "ta.ema" -> site(id, args, ::EmaSite).run {
            advance(bar, charge)
            evaluate(args.number("source"), args.period())
        }
        "ta.rma", "ta.atr", "ta.rsi" -> site(id, args) { WilderSite(name == "ta.rsi") }.run {
            advance(bar, charge)
            val source = if (name == "ta.atr") fallbackRange(trueRange, high, low) else args.number("source")
            evaluate(source, args.period(), charge)
        }
        "ta.highest", "ta.lowest" -> {
            val explicit = args.has("source") && args.has("length")
            val highest = name == "ta.highest"
            val source = if (explicit) args.number("source") else if (highest) high else low
            val length = args.period(if (args.has("length")) "length" else "source")
            site(id, args) { ExtremeSite(highest) }.run {
                advance(bar, charge)
                evaluate(source, length, charge)
            }
        }
        "ta.change", "ta.roc" -> {
            val source = args["source"]
            val roc = name == "ta.roc"
            if (!isMissing(source) && source !is Double && (roc || source !is Boolean)) args.error("参数 source 类型不支持")
            val length = args.period(default = if (roc) Double.NaN else 1.0)
            val previous = site(id, args, ::LagSite).run {
                put(bar, source)
                length?.let { get(bar - it) }
            }
            if (length == null) Double.NaN else if (!roc && (source is Boolean || previous is Boolean)) {
                source is Boolean && previous is Boolean && source != previous
            } else {
                val current = source as? Double ?: Double.NaN
                val past = previous as? Double ?: Double.NaN
                if (roc) if (past == 0.0) Double.NaN else 100.0 * ((current - past) / past) else current - past
            }
        }
        "ta.crossover", "ta.crossunder", "ta.cross" -> site(id, args, ::CrossSite).run {
            advance(bar, charge)
            evaluate(name, args.number("source1"), args.number("source2"))
        }
        "ta.tr" -> if (args.bool("handle_na")) fallbackRange(trueRange, high, low) else trueRange
        "ta.barssince" -> site(id, args, ::BarsSinceSite).run {
            advance(bar, charge)
            evaluate(args.bool("condition"))
        }
        "ta.cum" -> site(id, args, ::CumSite).run {
            advance(bar, charge)
            evaluate(args.number("source"))
        }
        "math.pow" -> args.number("base").pow(args.number("exponent"))
        "math.round" -> {
            val number = args.number("number")
            val precision = args.number("precision", 0.0)
            if (precision.isNaN()) Double.NaN else {
                if (precision !in -15.0..15.0 || precision != floor(precision)) args.error("参数 precision 必须是 -15–15 的整数")
                val scale = 10.0.pow(precision)
                val scaled = number * scale
                if (scaled.isInfinite() && number.isFinite()) number else roundUp(scaled) / scale
            }
        }
        "math.min", "math.max", "math.avg" -> aggregate(name, args, charge)
        "na" -> isMissing(args["x"])
        "nz" -> replaceNa(args)
        "float" -> args.number("x")
        "int" -> args.number("x").let { if (it < 0) ceil(it) else floor(it) }
        "bool" -> args.bool("x")
        "color" -> args.color("x")?.let(::PineColor)
        "color.new" -> {
            val rgb = args.color("color")
            val alpha = alpha(args, 0.0)
            if (rgb == null || alpha == null) null else PineColor((rgb and 0xFFFFFF) or (alpha shl 24))
        }
        "color.rgb" -> {
            val red = component(args, "red")
            val green = component(args, "green")
            val blue = component(args, "blue")
            val alpha = alpha(args, 0.0)
            if (red == null || green == null || blue == null || alpha == null) null
            else PineColor((alpha shl 24) or (red shl 16) or (green shl 8) or blue)
        }
        "color.r", "color.g", "color.b", "color.t" -> args.color("color")?.let { argb ->
            when (name) {
                "color.r" -> ((argb ushr 16) and 255).toDouble()
                "color.g" -> ((argb ushr 8) and 255).toDouble()
                "color.b" -> (argb and 255).toDouble()
                else -> (255 - (argb ushr 24)) * (100.0 / 255.0)
            }
        } ?: Double.NaN
        "str.tostring" -> stringify(args, charge)
        "str.tonumber" -> args.text("string").let { text ->
            charge(text.length)
            if (text.length <= 64 && numberText.matches(text)) text.toDoubleOrNull() ?: Double.NaN else Double.NaN
        }
        "str.length" -> args.text("string").length.toDouble()
        else -> unaryMath[name]?.invoke(args.number(mathParameter(name))) ?: args.error("不支持函数 $name")
    }

    private inline fun <reified S : Site> site(id: Int, args: PineArgs, create: () -> S): S {
        val existing = sites[id]
        if (existing != null) return existing as? S ?: args.error("Pine 函数位置重复")
        if (sites.size >= MAX_TA_SITES) args.error("有状态 Pine 函数位置不能超过 $MAX_TA_SITES 个")
        return create().also { sites[id] = it }
    }
}

private fun isMissing(value: Any?) = value == null || (value is Double && value.isNaN())

private fun PineArgs.period(name: String = "length", default: Double = Double.NaN): Int? {
    val value = number(name, default)
    if (value.isNaN()) return null
    if (value < 1.0 || value > MAX_PERIOD || value != floor(value)) error("周期必须是 1–$MAX_PERIOD 的整数")
    return value.toInt()
}

private fun fallbackRange(strict: Double, high: Double, low: Double): Double = if (strict.isNaN()) high - low else strict

private abstract class Site {
    var bar = -1
        private set

    fun advance(current: Int, charge: (Int) -> Unit) {
        if (bar == current) return
        if (bar >= 0) commit(charge)
        bar = current
    }

    protected open fun commit(charge: (Int) -> Unit) = Unit
}

private class ValidRing {
    private val values = DoubleArray(MAX_PERIOD)
    private var next = 0
    var size = 0
        private set
    var sum = 0.0
        private set

    /** Offset 0 is the newest committed sample. */
    operator fun get(offset: Int): Double = values[(next - 1 - offset + MAX_PERIOD) % MAX_PERIOD]

    fun append(value: Double) {
        if (!value.isFinite()) return
        if (size == MAX_PERIOD) sum -= values[next] else size++
        sum += value
        values[next] = value
        next = (next + 1) % MAX_PERIOD
    }

    fun tailSum(count: Int, charge: (Int) -> Unit): Double {
        if (count == 0) return 0.0
        if (count == size && sum.isFinite()) return sum
        charge(count)
        var total = 0.0
        for (offset in count - 1 downTo 0) total += this[offset]
        return total
    }
}

private class RollingSite : Site() {
    private val history = ValidRing()
    private var length = 0
    private var pending = Double.NaN
    private var committed = Moments()
    private var sinceRebuild = 0

    override fun commit(charge: (Int) -> Unit) {
        if (!pending.isFinite()) return
        if (length != 0) committed = withSample(committed, pending)
        history.append(pending)
        sinceRebuild++
    }

    fun evaluate(name: String, source: Double, period: Int?, biased: Boolean, charge: (Int) -> Unit): Double {
        pending = source
        if (period == null) return Double.NaN
        if (period != length || sinceRebuild >= MAX_PERIOD) {
            length = period
            val count = minOf(history.size, length)
            charge(count)
            committed = Moments()
            for (offset in count - 1 downTo 0) committed = committed.add(history[offset])
            sinceRebuild = 0
        }
        val current = if (source.isFinite()) withSample(committed, source) else committed
        if (current.count < length) return Double.NaN
        return when (name) {
            "math.sum" -> current.sum
            "ta.sma" -> current.sum / length
            "ta.wma" -> current.weighted / (length * (length + 1.0) / 2.0)
            else -> {
                val divisor = if (biased) length else length - 1
                if (divisor == 0) Double.NaN else sqrt(maxOf(0.0, current.m2) / divisor)
            }
        }
    }

    private fun withSample(base: Moments, value: Double): Moments =
        if (base.count < length) base.add(value) else base.slide(history[length - 1], value)
}

/** Welford's centered moments avoid subtracting two large sums of squares for STDEV. */
private data class Moments(val count: Int = 0, val sum: Double = 0.0, val weighted: Double = 0.0, val mean: Double = 0.0, val m2: Double = 0.0) {
    fun add(value: Double): Moments {
        if (count == 0) return Moments(1, value, value, value, 0.0)
        val diff = value - mean
        val nextMean = mean + diff / (count + 1)
        return Moments(count + 1, sum + value, weighted + (count + 1) * value, nextMean, m2 + diff * (value - nextMean))
    }

    fun slide(old: Double, value: Double): Moments {
        if (count == 1) return Moments(1, value, value, value, 0.0)
        val difference = value - old
        val nextMean = mean + difference / count
        val nextM2 = m2 + difference * ((value - nextMean) + (old - mean))
        return Moments(count, (sum - old) + value, (weighted - sum) + count * value, nextMean, nextM2)
    }
}

private class EmaSite : Site() {
    private var previous = Double.NaN
    private var pending = Double.NaN
    private var previousLength: Int? = null
    private var pendingLength: Int? = null

    override fun commit(charge: (Int) -> Unit) {
        previous = pending
        previousLength = pendingLength
    }

    fun evaluate(source: Double, length: Int?): Double {
        // Leading unknown lengths have no defined alpha; keep the most recent finite seed.
        val activeLength = length ?: previousLength
        pendingLength = activeLength
        pending = when {
            !source.isFinite() -> previous
            !previous.isFinite() || activeLength == null -> source
            else -> blend(previous, source, 2.0 / (activeLength + 1.0))
        }
        return if (length == null) Double.NaN else pending
    }
}

private class WilderSite(rsi: Boolean) : Site() {
    private val average = WilderAverage()
    private val loss = if (rsi) WilderAverage() else null
    private var previous = Double.NaN
    private var pending = Double.NaN

    override fun commit(charge: (Int) -> Unit) {
        previous = pending
        average.commit()
        loss?.commit()
    }

    fun evaluate(source: Double, length: Int?, charge: (Int) -> Unit): Double {
        if (loss == null) return average.evaluate(source, length, charge)
        pending = if (source.isFinite()) source else previous
        val change = if (source.isFinite()) source - previous else Double.NaN
        val up = average.evaluate(maxOf(change, 0.0), length, charge)
        val down = loss.evaluate(maxOf(-change, 0.0), length, charge)
        return when {
            up.isNaN() || down.isNaN() -> Double.NaN
            down == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + up / down)
        }
    }
}

private class WilderAverage {
    private val history = ValidRing()
    private var previousLength: Int? = null
    private var nextLength: Int? = null
    private var previous = Double.NaN
    private var next = Double.NaN
    private var input = Double.NaN

    fun commit() {
        history.append(input)
        previous = next
        previousLength = nextLength
    }

    fun evaluate(value: Double, length: Int?, charge: (Int) -> Unit): Double {
        input = value
        val period = length ?: previousLength
        nextLength = period
        val hasValue = value.isFinite()
        next = when {
            period == null -> Double.NaN
            period == previousLength && previous.isFinite() -> if (hasValue) blend(previous, value, 1.0 / period) else previous
            else -> {
                val count = period - if (hasValue) 1 else 0
                if (history.size < count) Double.NaN
                else (history.tailSum(count, charge) + if (hasValue) value else 0.0) / period
            }
        }
        return if (length == null) Double.NaN else next
    }
}

private fun blend(previous: Double, current: Double, alpha: Double) = alpha * current + (1 - alpha) * previous

/** Segment tree over a ring of valid samples; their bar indexes keep the window bar-based. */
private class ExtremeSite(private val highest: Boolean) : Site() {
    private val neutral = if (highest) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    // Dummy zero leaves never affect a query: its ranges contain only committed samples.
    private val tree = DoubleArray(2 * MAX_PERIOD)
    private val bars = IntArray(MAX_PERIOD)
    private var next = 0
    private var size = 0
    private var pending = Double.NaN

    override fun commit(charge: (Int) -> Unit) {
        if (!pending.isFinite()) return
        bars[next] = bar
        var node = next + MAX_PERIOD
        tree[node] = pending
        while (node > 1) {
            charge(1)
            node /= 2
            val combined = extreme(tree[node * 2], tree[node * 2 + 1])
            if (tree[node] == combined) break
            tree[node] = combined
        }
        next = (next + 1) % MAX_PERIOD
        size = minOf(size + 1, MAX_PERIOD)
    }

    fun evaluate(source: Double, period: Int?, charge: (Int) -> Unit): Double {
        pending = source
        if (period == null) return Double.NaN
        val cutoff = bar - period + 1
        var result = if (source.isFinite()) source else neutral
        val start = (next - size + MAX_PERIOD) % MAX_PERIOD
        if (size == 0 || bars[(next + MAX_PERIOD - 1) % MAX_PERIOD] < cutoff) return result.finiteOrNa()
        var low = 0
        var high = size
        if (bars[start] < cutoff) {
            while (low < high) {
                charge(1)
                val middle = (low + high) / 2
                if (bars[(start + middle) % MAX_PERIOD] < cutoff) low = middle + 1 else high = middle
            }
        }
        val first = (start + low) % MAX_PERIOD
        val count = size - low
        val end = first + count
        result = extreme(result, query(first, minOf(end, MAX_PERIOD), charge))
        if (end > MAX_PERIOD) result = extreme(result, query(0, end - MAX_PERIOD, charge))
        return result.finiteOrNa()
    }

    private fun query(start: Int, end: Int, charge: (Int) -> Unit): Double {
        var left = start + MAX_PERIOD
        var right = end + MAX_PERIOD
        var result = neutral
        while (left < right) {
            charge(1)
            if (left % 2 != 0) result = extreme(result, tree[left++])
            if (right % 2 != 0) result = extreme(result, tree[--right])
            left /= 2
            right /= 2
        }
        return result
    }

    private fun extreme(a: Double, b: Double) = if (highest) maxOf(a, b) else minOf(a, b)
}

private fun Double.finiteOrNa(): Double = if (isFinite()) this else Double.NaN

private class LagSite : Site() {
    private val history = arrayOfNulls<Any>(MAX_PERIOD + 1)
    private val stamps = IntArray(history.size)

    fun put(bar: Int, value: Any?) {
        val index = bar % history.size
        stamps[index] = bar
        history[index] = value
    }

    operator fun get(bar: Int): Any? {
        if (bar < 0) return null
        val index = bar % history.size
        return if (stamps[index] == bar) history[index] else null
    }
}

private class CrossSite : Site() {
    private var previousBar = -2
    private var previousA = Double.NaN
    private var previousB = Double.NaN
    private var pendingA = Double.NaN
    private var pendingB = Double.NaN

    override fun commit(charge: (Int) -> Unit) {
        previousBar = bar
        previousA = pendingA
        previousB = pendingB
    }

    fun evaluate(name: String, a: Double, b: Double): Boolean {
        pendingA = a.finiteOrNa()
        pendingB = b.finiteOrNa()
        if (bar - previousBar != 1) return false
        val over = pendingA > pendingB && previousA <= previousB
        val under = pendingA < pendingB && previousA >= previousB
        return when (name) {
            "ta.crossover" -> over
            "ta.crossunder" -> under
            else -> over || under
        }
    }
}

private class BarsSinceSite : Site() {
    private var previous = -1
    private var pending = -1

    override fun commit(charge: (Int) -> Unit) { previous = pending }

    fun evaluate(condition: Boolean): Double {
        pending = if (condition) bar else previous
        return if (pending >= 0) (bar - pending).toDouble() else Double.NaN
    }
}

private class CumSite : Site() {
    private var previous = 0.0
    private var pending = 0.0

    override fun commit(charge: (Int) -> Unit) { previous = pending }

    fun evaluate(source: Double): Double {
        pending = if (source.isFinite()) previous + source else previous
        return if (source.isFinite()) pending else Double.NaN
    }
}

private fun roundUp(number: Double): Double {
    val lower = floor(number)
    return if (number - lower >= 0.5) lower + 1.0 else lower
}

private fun aggregate(name: String, args: PineArgs, charge: (Int) -> Unit): Double {
    var result = args.number("number0")
    var count = 1
    charge(aggregateParameters.size - 1)
    for (i in 1 until aggregateParameters.size) {
        val parameter = aggregateParameters[i]
        if (!args.has(parameter)) continue
        val value = args.number(parameter)
        result = when (name) {
            "math.min" -> minOf(result, value)
            "math.max" -> maxOf(result, value)
            else -> result + value
        }
        count++
    }
    return if (name == "math.avg") result / count else result
}

private fun replaceNa(args: PineArgs): Any? {
    val source = args["source"]
    val replacement = args["replacement"]
    fun type(value: Any?): Int = when {
        isMissing(value) -> 0
        value is Double -> 1
        value is Boolean -> 2
        value is PineColor -> 3
        else -> -1
    }
    val sourceType = type(source)
    val replacementType = type(replacement)
    if (sourceType < 0 || replacementType < 0 || (sourceType != 0 && replacementType != 0 && sourceType != replacementType)) {
        args.error("nz() 仅支持类型相同的数值、布尔值或颜色")
    }
    // A missing value has no type in this runtime, so the omitted replacement is numeric zero.
    return if (sourceType != 0) source else if (args.has("replacement")) replacement else 0.0
}

private fun component(args: PineArgs, name: String): Int? {
    val value = args.number(name)
    if (value.isNaN()) return null
    if (value !in 0.0..255.0) args.error("参数 $name 必须在 0–255 之间")
    return roundUp(value).toInt()
}

private fun alpha(args: PineArgs, default: Double): Int? {
    val transparency = args.number("transp", default)
    if (transparency.isNaN()) return null
    if (transparency !in 0.0..100.0) args.error("参数 transp 必须在 0–100 之间")
    return roundUp((100.0 - transparency) * 255.0 / 100.0).toInt()
}

private val decimalFormat = Regex("[#0]{1,12}(?:\\.[#0]{1,10})?%?")
private val numberText = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

/** Locale-independent masks with `#`, `0`, a decimal point and optional trailing `%`. */
private fun stringify(args: PineArgs, charge: (Int) -> Unit): String {
    val format = args.text("format")
    charge(format.length)
    if (format == "mintick") args.error("str.tostring() 不支持 format.mintick；请使用小数格式")
    if (format.isNotEmpty() && format != "percent" && format != "volume" && (format.length > 24 || !decimalFormat.matches(format))) {
        args.error("str.tostring() 不支持该格式；可使用 0.00、#.##%、format.percent 或 format.volume")
    }
    val value = args["value"]
    return when {
        isMissing(value) -> "NaN"
        value is String -> value
        value is Boolean -> value.toString()
        value !is Double -> args.error("str.tostring() 仅支持数值、布尔值或字符串")
        format.isEmpty() -> formatDecimal(value, 0, 8, 1)
        format == "percent" -> formatDecimal(value, 2, 2, 1) + "%"
        format == "volume" -> {
            val magnitude = abs(value)
            when {
                magnitude >= 1e12 -> formatDecimal(value / 1e12, 0, 2, 1) + "T"
                magnitude >= 1e9 -> formatDecimal(value / 1e9, 0, 2, 1) + "B"
                magnitude >= 1e6 -> formatDecimal(value / 1e6, 0, 2, 1) + "M"
                magnitude >= 1e3 -> formatDecimal(value / 1e3, 0, 2, 1) + "K"
                else -> formatDecimal(value, 0, 2, 1)
            }
        }
        else -> {
            val percent = format.endsWith('%')
            val mask = format.removeSuffix("%")
            val whole = mask.substringBefore('.')
            val fraction = mask.substringAfter('.', "")
            val digits = fraction.lastIndexOf('0') + 1
            formatDecimal(if (percent) value * 100.0 else value, digits, fraction.length, whole.count { it == '0' }) +
                if (percent) "%" else ""
        }
    }
}

private fun formatDecimal(value: Double, minimum: Int, maximum: Int, wholeDigits: Int): String {
    val scale = 10.0.pow(maximum).toLong()
    val scaled = roundUp(abs(value) * scale)
    // Preserve large values rather than overflowing the integer used for the decimal mask.
    if (!scaled.isFinite() || scaled >= Long.MAX_VALUE.toDouble()) return value.toString()
    val digits = scaled.toLong()
    val prefix = if (value < 0 && digits != 0L) "-" else ""
    val whole = (digits / scale).toString().padStart(wholeDigits, '0')
    if (maximum == 0) return prefix + whole
    val fraction = (digits % scale).toString().padStart(maximum, '0').trimEnd('0').padEnd(minimum, '0')
    return if (fraction.isEmpty()) prefix + whole else "$prefix$whole.$fraction"
}
