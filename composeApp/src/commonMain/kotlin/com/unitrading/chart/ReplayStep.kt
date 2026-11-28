package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe

/** Visible choices depend on the active chart, even when finer data is not available. */
internal fun replayStepCandidates(chart: Timeframe): List<Timeframe> {
    val oneSecond = Timeframe.ofSeconds(1)
    val bases = if (chart.seconds < 10 * Timeframe.MINUTE) listOf(oneSecond, Timeframe.M1)
        else listOf(oneSecond) + listOf(1, 3, 5, 10, 15, 30).map(Timeframe::ofMinutes)
    return (bases.filter { it.seconds <= chart.seconds && chart.seconds % it.seconds == 0 } + chart)
        .distinct().sortedBy { it.seconds }
}

/** Only enable choices supported by the loaded source bars. */
internal fun replayStepOptions(chart: Timeframe, source: Timeframe): List<Timeframe> {
    return replayStepCandidates(chart).filter { it.seconds >= source.seconds && it.seconds % source.seconds == 0 }
}

internal fun replayStepLabel(frame: Timeframe): String = when {
    frame.seconds % Timeframe.DAY == 0 -> "${frame.seconds / Timeframe.DAY}d/步"
    frame.seconds % Timeframe.HOUR == 0 -> "${frame.seconds / Timeframe.HOUR}h/步"
    frame.seconds % Timeframe.MINUTE == 0 -> "${frame.seconds / Timeframe.MINUTE}m/步"
    else -> "${frame.seconds}s/步"
}

/** Index of the first source bar at or after the requested elapsed interval, skipping market gaps. */
internal fun replayStepEnd(bars: List<Candle>, start: Int, step: Timeframe): Int {
    require(start in bars.indices)
    if (start == bars.lastIndex) return start
    val target = if (bars[start].timeMs > Long.MAX_VALUE - step.millis) Long.MAX_VALUE else bars[start].timeMs + step.millis
    var lo = start + 1
    var hi = bars.lastIndex
    while (lo < hi) {
        val mid = lo + (hi - lo) / 2
        if (bars[mid].timeMs < target) lo = mid + 1 else hi = mid
    }
    return lo
}
