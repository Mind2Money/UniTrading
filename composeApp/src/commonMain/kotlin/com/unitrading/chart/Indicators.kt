package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.formatFixed
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Overlay indicators share the price axis; separate ones get their own pane under the candles. */
enum class IndicatorPane { OVERLAY, SEPARATE }

/** One editable number. Bar counts are integers; multipliers accept two decimals. */
data class IndicatorParam(val key: String, val label: String, val default: Double, val min: Double, val max: Double, val integer: Boolean = true)

/** One drawn series. Histograms colour by sign with the theme's up/down colours instead of a configured colour. */
data class IndicatorLine(val name: String, val histogram: Boolean = false)

/** Largest bar count any indicator parameter accepts. The Tradovate plugin keeps twice as many bars, so the longest period still has values. */
const val MAX_INDICATOR_PERIOD = 1000

private fun bars(key: String, label: String, default: Double) = IndicatorParam(key, label, default, 1.0, MAX_INDICATOR_PERIOD.toDouble())

/**
 * Built-in indicator formulas. Values stay in the candles' fixed-point price units (MA, EXPMA, BOLL, MACD, ATR)
 * or on a 0–100 scale (RSI, KDJ); `NaN` marks the warm-up bars before a value exists.
 */
@Serializable
enum class IndicatorKind(
    val label: String,
    val title: String,
    val pane: IndicatorPane,
    val params: List<IndicatorParam>,
    val lines: List<IndicatorLine>,
    /** A separate pane always shows at least this range and widens when values leave it. */
    val baseRange: ClosedFloatingPointRange<Double>? = null,
) {
    MA("MA", "简单移动平均", IndicatorPane.OVERLAY, listOf(bars("period", "周期", 20.0)), listOf(IndicatorLine("MA"))),
    EXPMA("EXPMA", "指数移动平均", IndicatorPane.OVERLAY, listOf(bars("period", "周期", 20.0)), listOf(IndicatorLine("EXPMA"))),
    BOLL("BOLL", "布林带", IndicatorPane.OVERLAY, listOf(bars("period", "周期", 20.0), IndicatorParam("width", "带宽倍数", 2.0, 0.1, 10.0, integer = false)),
        listOf(IndicatorLine("中轨"), IndicatorLine("上轨"), IndicatorLine("下轨"))),
    RSI("RSI", "相对强弱指标", IndicatorPane.SEPARATE, listOf(bars("period", "周期", 14.0)), listOf(IndicatorLine("RSI")), 0.0..100.0),
    MACD("MACD", "指数平滑异同平均", IndicatorPane.SEPARATE, listOf(bars("fast", "快线", 12.0), bars("slow", "慢线", 26.0), bars("signal", "信号", 9.0)),
        listOf(IndicatorLine("DIF"), IndicatorLine("DEA"), IndicatorLine("MACD", histogram = true))),
    KDJ("KDJ", "随机指标", IndicatorPane.SEPARATE, listOf(bars("period", "RSV 周期", 9.0), bars("k", "K 平滑", 3.0), bars("d", "D 平滑", 3.0)),
        listOf(IndicatorLine("K"), IndicatorLine("D"), IndicatorLine("J")), 0.0..100.0),
    ATR("ATR", "平均真实波幅", IndicatorPane.SEPARATE, listOf(bars("period", "周期", 14.0)), listOf(IndicatorLine("ATR")));

    fun param(key: String): IndicatorParam = params.first { it.key == key }
    /** Dashed reference levels drawn in a separate pane. */
    val guides: List<Double> get() = when (this) { RSI -> listOf(30.0, 70.0); KDJ -> listOf(20.0, 80.0); MACD -> listOf(0.0); else -> emptyList() }
    /** Price-unit values are formatted with the instrument; oscillators keep two decimals. */
    val priceUnits: Boolean get() = this != RSI && this != KDJ
}

/** Default line colours by line index; a config overrides them per line with `#RRGGBB`. */
val indicatorPalette = listOf("#F5B942", "#7FD1AE", "#C98BFF", "#4FC3F7", "#FF8A65", "#F06292")

@Serializable
data class IndicatorConfig(
    val id: String,
    val kind: IndicatorKind,
    val params: Map<String, Double> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val visible: Boolean = true,
) {
    fun param(key: String): Double = params[key] ?: kind.param(key).default
    fun color(line: Int): String = colors[kind.lines[line].name] ?: indicatorPalette[line % indicatorPalette.size]
    /** `EXPMA(20)`, `BOLL(20,2)`, `MACD(12,26,9)` */
    val label: String get() = kind.label + "(" + kind.params.joinToString(",") { formatParam(param(it.key)) } + ")"

    fun validate() {
        require(id.isNotBlank()) { "指标缺少 ID" }
        params.forEach { (key, value) ->
            val spec = kind.params.firstOrNull { it.key == key } ?: throw IllegalArgumentException("${kind.label} 没有参数 $key")
            require(value.isFinite() && value >= spec.min && value <= spec.max) { "${kind.label} ${spec.label} 须在 ${formatParam(spec.min)}–${formatParam(spec.max)}" }
            require(!spec.integer || value == floor(value)) { "${kind.label} ${spec.label} 须为整数" }
        }
        colors.forEach { (line, value) ->
            require(kind.lines.any { it.name == line && !it.histogram }) { "${kind.label} 没有可配色的线 $line" }
            require(value.matches(Regex("#[0-9a-fA-F]{6}"))) { "颜色须为 #RRGGBB" }
        }
        if (kind == IndicatorKind.MACD) require(param("fast") < param("slow")) { "MACD 快线周期须小于慢线" }
    }
}

fun formatParam(value: Double): String = formatFixed((value * 100).roundToLong(), 2).trimEnd('0').trimEnd('.')

class IndicatorSeries(val line: IndicatorLine, val values: DoubleArray, val color: String)

/** Legend text for one indicator: its label and one formatted value (with colour) per line. */
data class IndicatorReadout(val title: String, val values: List<Pair<String, String>>)

fun computeIndicator(config: IndicatorConfig, candles: List<Candle>): List<IndicatorSeries> {
    val closes = DoubleArray(candles.size) { candles[it].close.toDouble() }
    fun count(key: String) = config.param(key).toInt()
    val lines = when (config.kind) {
        IndicatorKind.MA -> listOf(sma(closes, count("period")))
        IndicatorKind.EXPMA -> listOf(ema(closes, count("period")))
        IndicatorKind.BOLL -> bollinger(closes, count("period"), config.param("width"))
        IndicatorKind.RSI -> listOf(rsi(closes, count("period")))
        IndicatorKind.MACD -> macd(closes, count("fast"), count("slow"), count("signal"))
        IndicatorKind.KDJ -> kdj(candles, count("period"), count("k"), count("d"))
        IndicatorKind.ATR -> listOf(atr(candles, count("period")))
    }
    return lines.mapIndexed { index, values -> IndicatorSeries(config.kind.lines[index], values, config.color(index)) }
}

fun formatIndicatorValue(kind: IndicatorKind, value: Double, instrument: Instrument): String = when {
    value.isNaN() -> "—"
    kind.priceUnits -> instrument.formatPrice(value.roundToLong())
    else -> formatFixed((value * 100).roundToLong(), 2)
}

/** Vertical range of a separate pane over [range]: the kind's base range widened to the data, MACD kept symmetric, then padded. */
fun paneRange(kind: IndicatorKind, series: List<IndicatorSeries>, range: IntRange): ClosedFloatingPointRange<Double> {
    var low = kind.baseRange?.start ?: Double.POSITIVE_INFINITY
    var high = kind.baseRange?.endInclusive ?: Double.NEGATIVE_INFINITY
    for (s in series) for (i in range) {
        val v = s.values.getOrNull(i) ?: continue
        if (!v.isNaN()) { low = min(low, v); high = max(high, v) }
    }
    if (low > high) return -1.0..1.0
    if (kind == IndicatorKind.MACD) { val m = max(abs(low), abs(high)).takeIf { it > 0 } ?: 1.0; low = -m; high = m }
    if (high == low) return (low - 1)..(high + 1)
    val pad = (high - low) * .06
    return (low - pad)..(high + pad)
}

/** Simple average over a window of consecutive non-NaN values; NaN input restarts the window. */
fun sma(values: DoubleArray, period: Int): DoubleArray {
    require(period >= 1)
    val out = DoubleArray(values.size) { Double.NaN }
    var sum = 0.0
    var count = 0
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) { sum = 0.0; count = 0; continue }
        sum += v
        count++
        if (count > period) sum -= values[i - period]
        if (count >= period) out[i] = sum / period
    }
    return out
}

/** EMA seeded with the first full window's simple average, then `alpha = 2 / (period + 1)`. */
fun ema(values: DoubleArray, period: Int): DoubleArray {
    require(period >= 1)
    val out = DoubleArray(values.size) { Double.NaN }
    val alpha = 2.0 / (period + 1)
    var previous = Double.NaN
    var sum = 0.0
    var count = 0
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) { previous = Double.NaN; sum = 0.0; count = 0; continue }
        if (previous.isNaN()) {
            sum += v
            count++
            if (count == period) { previous = sum / period; out[i] = previous }
        } else {
            previous = alpha * v + (1 - alpha) * previous
            out[i] = previous
        }
    }
    return out
}

/** Middle band is the SMA; the outer bands sit `width` population standard deviations away. */
fun bollinger(values: DoubleArray, period: Int, width: Double): List<DoubleArray> {
    val middle = sma(values, period)
    val upper = DoubleArray(values.size) { Double.NaN }
    val lower = DoubleArray(values.size) { Double.NaN }
    for (i in values.indices) {
        val m = middle[i]
        if (m.isNaN()) continue
        var squares = 0.0
        for (j in i - period + 1..i) { val d = values[j] - m; squares += d * d }
        val deviation = sqrt(squares / period)
        upper[i] = m + width * deviation
        lower[i] = m - width * deviation
    }
    return listOf(middle, upper, lower)
}

/** Wilder RSI: the first averages are plain means of the first [period] changes; later ones are smoothed by `(period - 1) / period`. */
fun rsi(closes: DoubleArray, period: Int): DoubleArray {
    require(period >= 1)
    val out = DoubleArray(closes.size) { Double.NaN }
    if (closes.size <= period) return out
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) { val d = closes[i] - closes[i - 1]; if (d > 0) gain += d else loss -= d }
    gain /= period
    loss /= period
    out[period] = rsiValue(gain, loss)
    for (i in period + 1 until closes.size) {
        val d = closes[i] - closes[i - 1]
        gain = (gain * (period - 1) + max(d, 0.0)) / period
        loss = (loss * (period - 1) + max(-d, 0.0)) / period
        out[i] = rsiValue(gain, loss)
    }
    return out
}

/** Equivalent to `100 - 100 / (1 + RS)`; a window without any movement reads 50. */
private fun rsiValue(gain: Double, loss: Double): Double = if (gain + loss == 0.0) 50.0 else 100.0 * gain / (gain + loss)

/** DIF = EMA(fast) - EMA(slow), DEA = EMA(DIF, signal), MACD bar = (DIF - DEA) * 2. */
fun macd(closes: DoubleArray, fast: Int, slow: Int, signal: Int): List<DoubleArray> {
    val quick = ema(closes, fast)
    val lazy = ema(closes, slow)
    val dif = DoubleArray(closes.size) { if (quick[it].isNaN() || lazy[it].isNaN()) Double.NaN else quick[it] - lazy[it] }
    val dea = ema(dif, signal)
    val bar = DoubleArray(closes.size) { if (dea[it].isNaN()) Double.NaN else (dif[it] - dea[it]) * 2 }
    return listOf(dif, dea, bar)
}

/**
 * RSV = (close - lowest low) / (highest high - lowest low) * 100 over [period] bars (50 when the window is flat);
 * K and D smooth with weights `1/k` and `1/d` from a 50/50 seed; J = 3K - 2D.
 */
fun kdj(candles: List<Candle>, period: Int, kSmooth: Int, dSmooth: Int): List<DoubleArray> {
    require(period >= 1 && kSmooth >= 1 && dSmooth >= 1)
    val k = DoubleArray(candles.size) { Double.NaN }
    val d = DoubleArray(candles.size) { Double.NaN }
    val j = DoubleArray(candles.size) { Double.NaN }
    var previousK = 50.0
    var previousD = 50.0
    for (i in period - 1 until candles.size) {
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (x in i - period + 1..i) { high = max(high, candles[x].high.toDouble()); low = min(low, candles[x].low.toDouble()) }
        val rsv = if (high == low) 50.0 else (candles[i].close - low) / (high - low) * 100
        previousK = ((kSmooth - 1) * previousK + rsv) / kSmooth
        previousD = ((dSmooth - 1) * previousD + previousK) / dSmooth
        k[i] = previousK
        d[i] = previousD
        j[i] = 3 * previousK - 2 * previousD
    }
    return listOf(k, d, j)
}

/** True range uses the previous close from the second bar on; ATR seeds with the mean of the first [period] ranges, then Wilder smoothing. */
fun atr(candles: List<Candle>, period: Int): DoubleArray {
    require(period >= 1)
    val out = DoubleArray(candles.size) { Double.NaN }
    var value = Double.NaN
    var sum = 0.0
    for (i in candles.indices) {
        val c = candles[i]
        val range = if (i == 0) (c.high - c.low).toDouble() else {
            val previous = candles[i - 1].close
            maxOf((c.high - c.low).toDouble(), abs((c.high - previous).toDouble()), abs((c.low - previous).toDouble()))
        }
        if (value.isNaN()) {
            sum += range
            if (i == period - 1) { value = sum / period; out[i] = value }
        } else {
            value = (value * (period - 1) + range) / period
            out[i] = value
        }
    }
    return out
}
