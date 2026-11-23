package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
internal fun chartSessionCandles(candles: List<Candle>, symbol: String, timeframe: Timeframe, regular: Boolean): Pair<List<Candle>, String?> {
    if (!regular) return candles to null
    if (timeframe.seconds >= Timeframe.DAY) return candles to null
    if (!supportedRegularSymbols.matches(symbol.uppercase())) return emptyList<Candle>() to "该品种暂无常规时段模板，请切换电子交易时间。"
    val seconds = timeframe.seconds
    if (seconds < Timeframe.MINUTE || seconds % Timeframe.MINUTE != 0)
        return emptyList<Candle>() to "秒线暂不支持常规时段，请切换分钟周期或电子交易时间。"
    if (candles.isEmpty()) return emptyList<Candle>() to null
    val zone = TimeZone.of("America/Chicago")
    val sorted = candles.sortedBy { it.timeMs }
    val first = sorted.first().timeMs
    val result = mutableListOf<Candle>()
    for (bar in sorted) {
        val local = Instant.fromEpochMilliseconds(bar.timeMs).toLocalDateTime(zone)
        val minute = local.hour * 60 + local.minute
        if (minute < 510 || minute >= 915 || local.second != 0) continue
        val start = LocalDateTime.parse("${local.date}T08:30:00").toInstant(zone).toEpochMilliseconds()
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
