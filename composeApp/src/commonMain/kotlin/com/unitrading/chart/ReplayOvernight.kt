package com.unitrading.chart

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun parseReplayCutoff(text: String): Int? {
    if (!text.matches(Regex("\\d{2}:\\d{2}"))) return null
    val h = text.substring(0, 2).toIntOrNull() ?: return null
    val m = text.substring(3).toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

internal fun replayCutoffText(minutes: Int): String = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

/** Restriction lasts until the simulation's next 17:00 Chicago trading-day boundary. */
internal fun replayOvernightBlocked(timeMs: Long, cutoff: Int): Boolean {
    val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.of("America/Chicago"))
    val minute = local.hour * 60 + local.minute
    return when {
        cutoff < 17 * 60 -> minute >= cutoff && minute < 17 * 60
        cutoff > 17 * 60 -> minute >= cutoff || minute < 17 * 60
        else -> false
    }
}

internal enum class ReplayCutoffAction { NONE, BEFORE_NEXT, AFTER_NEXT }

/** Never use the next bar's close if its interval extends past the configured cutoff. */
internal fun replayCutoffAction(currentEnd: Long, nextStart: Long, nextEnd: Long, cutoff: Int): ReplayCutoffAction {
    require(nextEnd > nextStart && nextStart >= currentEnd) { "K 线时间不连续或无效" }
    val zone = TimeZone.of("America/Chicago")
    val local = Instant.fromEpochMilliseconds(currentEnd).toLocalDateTime(zone)
    fun boundary(date: kotlinx.datetime.LocalDate): Long = requireNotNull(parseChartTime("$date ${replayCutoffText(cutoff)}", "America/Chicago")) {
        "平仓时间在该日不存在或不唯一，请修改模拟账户时间"
    }
    var deadline = boundary(local.date)
    if (deadline < currentEnd) deadline = boundary(local.date.plus(1, DateTimeUnit.DAY))
    if (replayOvernightBlocked(currentEnd, cutoff) || deadline == currentEnd) return ReplayCutoffAction.BEFORE_NEXT
    if (nextEnd < deadline) return ReplayCutoffAction.NONE
    return if (nextStart < deadline && nextEnd == deadline) ReplayCutoffAction.AFTER_NEXT else ReplayCutoffAction.BEFORE_NEXT
}
