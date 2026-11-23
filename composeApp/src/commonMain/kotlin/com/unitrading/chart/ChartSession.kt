package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val supportedRegularSymbols = Regex("^@?(MES|MNQ|MCL|MGC|ES|NQ|CL|GC)([FGHJKMNQUVXZ][0-9]{1,4})?$")

/** The chart template's local session boundary, independent of the selected display time zone. */
internal fun isRegularChartTime(timeMs: Long): Boolean {
    val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.of("America/Chicago"))
    val day = local.date.dayOfWeek
    val minute = local.hour * 60 + local.minute
    return day.name != "SATURDAY" && day.name != "SUNDAY" && minute in 510 until 915
}

/** Regular intraday charts need one-minute source bars, because a broker's hourly ETH bars straddle the RTH boundary. */
internal fun chartSessionSource(symbol: String, timeframe: Timeframe, regular: Boolean): Timeframe =
    if (regular && supportedRegularSymbols.matches(symbol.uppercase()) && timeframe.seconds < Timeframe.DAY &&
        timeframe.seconds >= Timeframe.MINUTE && timeframe.seconds % Timeframe.MINUTE == 0) Timeframe.M1 else timeframe

/** TradingView-style CME regular session. This never constrains orders or indicates market status. */
internal fun chartSessionCandles(candles: List<Candle>, symbol: String, timeframe: Timeframe, regular: Boolean,
    checkActive: () -> Unit = {}): Pair<List<Candle>, String?> {
    if (!regular) return candles to null
    if (timeframe.seconds >= Timeframe.DAY) return candles to null
    if (!supportedRegularSymbols.matches(symbol.uppercase())) return emptyList<Candle>() to "该品种暂无常规时段模板，请切换电子交易时间。"
    val seconds = timeframe.seconds
    if (seconds < Timeframe.MINUTE || seconds % Timeframe.MINUTE != 0)
        return emptyList<Candle>() to "秒线暂不支持常规时段，请切换分钟周期或电子交易时间。"
    if (candles.isEmpty()) return emptyList<Candle>() to null
    val zone = TimeZone.of("America/Chicago")
    var inOrder = true
    for (i in 1 until candles.size) {
        if (i % 1024 == 0) checkActive()
        if (candles[i - 1].timeMs > candles[i].timeMs) { inOrder = false; break }
    }
    val sorted = if (inOrder) candles else candles.sortedBy { it.timeMs }
    val first = sorted.first().timeMs
    val result = mutableListOf<Candle>()
    var dayStart = Long.MIN_VALUE
    var dayEnd = Long.MIN_VALUE
    var start = Long.MIN_VALUE
    for ((index, bar) in sorted.withIndex()) {
        if (index % 1024 == 0) checkActive()
        if (bar.timeMs < dayStart || bar.timeMs >= dayEnd) {
            val date = Instant.fromEpochMilliseconds(bar.timeMs).toLocalDateTime(zone).date
            dayStart = LocalDateTime.parse("${date}T00:00:00").toInstant(zone).toEpochMilliseconds()
            dayEnd = LocalDateTime.parse("${date.plus(1, DateTimeUnit.DAY)}T00:00:00").toInstant(zone).toEpochMilliseconds()
            start = LocalDateTime.parse("${date}T08:30:00").toInstant(zone).toEpochMilliseconds()
        }
        val minuteOffset = (bar.timeMs % 60_000L + 60_000L) % 60_000L
        if (bar.timeMs < start || bar.timeMs >= start + 405 * 60_000L || minuteOffset >= 1_000L) continue
        // Do not fabricate a session's opening bar when the loaded history starts inside it.
        if (first > start) continue
        val bucket = start + ((bar.timeMs - start) / timeframe.millis) * timeframe.millis
        val previous = result.lastOrNull()
        if (previous == null || previous.timeMs != bucket) {
            result.add(bar.copy(timeMs = bucket))
        } else {
            val previousVolume = previous.volume
            val barVolume = bar.volume
            val volume = if (previousVolume == null || barVolume == null) null else {
                val sum = previousVolume + barVolume
                sum.takeIf { it >= previousVolume && it >= barVolume }
            }
            result[result.lastIndex] = previous.copy(high = maxOf(previous.high, bar.high), low = minOf(previous.low, bar.low), close = bar.close, volume = volume)
        }
    }
    return result to if (result.isEmpty() && first > 0) "常规时段历史尚未完整加载，请稍候或切换电子交易时间。" else null
}
