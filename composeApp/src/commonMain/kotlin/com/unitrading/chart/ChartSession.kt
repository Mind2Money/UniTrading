package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** TradingView-style CME session, not a trading restriction or an exchange open/closed signal. */
internal fun chartSessionCandles(candles: List<Candle>, symbol: String, timeframe: Timeframe, regular: Boolean): Pair<List<Candle>, String?> {
    if (!regular) return candles to null
    val supported = Regex("^@?(MES|MNQ|MCL|MGC|ES|NQ|CL|GC)([FGHJKMNQUVXZ][0-9]{1,4})?$").matches(symbol.uppercase())
    if (!supported) return emptyList<Candle>() to "该品种暂无常规时段模板，请切换电子交易时间。"
    if (timeframe.seconds >= Timeframe.DAY) return candles to null
    val seconds = timeframe.seconds
    if (seconds < 60 || seconds % 60 != 0 || 900 % seconds != 0)
        return emptyList<Candle>() to "当前周期跨越常规时段边界，请选 1、3、5 或 15 分钟，或切换电子交易时间。"
    val zone = TimeZone.of("America/Chicago")
    return candles.filter { candle ->
        val local = Instant.fromEpochMilliseconds(candle.timeMs).toLocalDateTime(zone)
        val minute = local.hour * 60 + local.minute
        val end = minute * 60 + local.second + seconds
        minute >= 8 * 60 + 30 && end <= (15 * 60 + 15) * 60
    } to null
}
