package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe

/**
 * Builds a chart solely from completed source bars through [through]. The last displayed candle is deliberately
 * partial: taking a broker's finished higher-timeframe candle would reveal its future high, low and close.
 */
internal fun replayChartBars(source: List<Candle>, through: Int, sourceFrame: Timeframe, chartFrame: Timeframe,
    symbol: String, regular: Boolean): List<Candle> {
    require(through in source.indices)
    require(chartFrame.seconds >= sourceFrame.seconds && chartFrame.seconds % sourceFrame.seconds == 0) { "图表周期必须是回放数据周期的整数倍" }
    val visible = source.subList(0, through + 1)
    if (regular && chartFrame.seconds < Timeframe.DAY) {
        require(sourceFrame == Timeframe.M1) { "常规时段需要券商 1 分钟历史数据" }
        return chartSessionCandles(visible, symbol, chartFrame, true).first
    }
    if (sourceFrame == chartFrame) return visible
    require(chartFrame.seconds < Timeframe.DAY) { "日线及以上需券商结算数据，暂不能从分钟线安全合成" }
    val result = ArrayList<Candle>()
    for (bar in visible) {
        val bucket = MathFloorDiv.floor(bar.timeMs, chartFrame.millis) * chartFrame.millis
        val previous = result.lastOrNull()
        if (previous == null || previous.timeMs != bucket) result.add(bar.copy(timeMs = bucket))
        else {
            val a = previous.volume
            val b = bar.volume
            val volume = if (a == null || b == null) null else (a + b).takeIf { it >= a && it >= b }
            result[result.lastIndex] = previous.copy(high = maxOf(previous.high, bar.high), low = minOf(previous.low, bar.low), close = bar.close, volume = volume)
        }
    }
    return result
}

/** Broker candles become visible only after their entire time interval has elapsed in the replay. */
internal fun replayCompletedBrokerBars(bars: List<Candle>, frame: Timeframe, replayEndMs: Long): List<Candle> =
    bars.asSequence().filter { bar -> bar.timeMs <= replayEndMs && frame.millis <= replayEndMs - bar.timeMs }
        .sortedBy(Candle::timeMs).toList()

private object MathFloorDiv {
    fun floor(value: Long, divisor: Long): Long = value / divisor - if (value % divisor < 0) 1 else 0
}
