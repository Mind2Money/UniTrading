package com.unitrading.broker

/** Contract volume at an occupied price level. Unknown aggressors contribute to volume, never delta. */
data class ProfileLevel(
    val price: Long,
    val buyVolume: Long,
    val sellVolume: Long,
    val unknownVolume: Long,
) {
    val totalVolume: Long get() = checkedAdd(checkedAdd(buyVolume, sellVolume), unknownVolume)
    val delta: Long get() = buyVolume - sellVolume
}

data class FootprintBar(
    val timeMs: Long,
    val levels: List<ProfileLevel>,
    val buyVolume: Long,
    val sellVolume: Long,
    val unknownVolume: Long,
    val cumulativeDelta: Long,
) {
    val totalVolume: Long get() = checkedAdd(checkedAdd(buyVolume, sellVolume), unknownVolume)
    val delta: Long get() = buyVolume - sellVolume
}

data class OrderFlowAnalysis(
    val levels: List<ProfileLevel> = emptyList(),
    val bars: List<FootprintBar> = emptyList(),
    val totalBuy: Long = 0,
    val totalSell: Long = 0,
    val totalUnknown: Long = 0,
    val poc: Long? = null,
    val valueAreaLow: Long? = null,
    val valueAreaHigh: Long? = null,
) {
    val totalVolume: Long get() = checkedAdd(checkedAdd(totalBuy, totalSell), totalUnknown)
    val delta: Long get() = totalBuy - totalSell
}

private class FlowVolume {
    var buy: Long = 0
    var sell: Long = 0
    var unknown: Long = 0

    fun add(trade: MarketTrade) {
        when (trade.aggressor) {
            Side.BUY -> buy = checkedAdd(buy, trade.size)
            Side.SELL -> sell = checkedAdd(sell, trade.size)
            null -> unknown = checkedAdd(unknown, trade.size)
        }
    }

    fun level(price: Long) = ProfileLevel(price, buy, sell, unknown)
}

private fun floorBucket(value: Long, width: Long): Long {
    val quotient = value / width - if (value % width < 0) 1 else 0
    return checkedMultiply(quotient, width)
}

/**
 * Exact aggregation of at most the most recently received 20,000 prints; no candle-volume allocation or
 * quote-based aggressor estimation. Zero/negative sizes are ignored. Levels contain only occupied prices,
 * sorted ascending, so large gaps do not allocate empty rows. Bars are sorted by time and limited to the
 * latest 120; their CVD still starts at the beginning of the full retained window.
 *
 * POC ties select the lowest price. The 70% value area expands from POC toward the larger adjacent
 * occupied level, including both sides on a tie. Unknown-side volume is included in POC and value area.
 */
fun analyzeOrderFlow(
    trades: List<MarketTrade>,
    timeframe: Timeframe,
    ticksPerLevel: Long,
    tickSize: Long,
): OrderFlowAnalysis {
    require(ticksPerLevel > 0 && tickSize > 0) { "价格分组与最小跳动必须为正数" }
    val levelWidth = checkedMultiply(ticksPerLevel, tickSize)
    val barWidth = timeframe.millis
    val profile = mutableMapOf<Long, FlowVolume>()
    val footprints = mutableMapOf<Long, MutableMap<Long, FlowVolume>>()
    val totals = FlowVolume()
    for (index in (trades.size - 20_000).coerceAtLeast(0) until trades.size) {
        val trade = trades[index]
        if (trade.size <= 0) continue
        val price = floorBucket(trade.price, levelWidth)
        val time = floorBucket(trade.timeMs, barWidth)
        profile.getOrPut(price, ::FlowVolume).add(trade)
        footprints.getOrPut(time) { mutableMapOf() }.getOrPut(price, ::FlowVolume).add(trade)
        totals.add(trade)
    }
    if (profile.isEmpty()) return OrderFlowAnalysis()
    val levels = profile.entries.sortedBy { it.key }.map { (price, volume) -> volume.level(price) }
    val totalVolume = checkedAdd(checkedAdd(totals.buy, totals.sell), totals.unknown)
    val pocIndex = levels.indices.maxBy { levels[it].totalVolume }
    val valueArea = valueArea(levels, pocIndex, totalVolume)
    var cumulativeDelta = 0L
    val bars = footprints.entries.sortedBy { it.key }.map { (time, prices) ->
        val barLevels = prices.entries.sortedBy { it.key }.map { (price, volume) -> volume.level(price) }
        var buy = 0L
        var sell = 0L
        var unknown = 0L
        for (level in barLevels) {
            buy = checkedAdd(buy, level.buyVolume)
            sell = checkedAdd(sell, level.sellVolume)
            unknown = checkedAdd(unknown, level.unknownVolume)
        }
        cumulativeDelta = checkedAdd(cumulativeDelta, buy - sell)
        FootprintBar(time, barLevels, buy, sell, unknown, cumulativeDelta)
    }.takeLast(120)
    return OrderFlowAnalysis(
        levels, bars, totals.buy, totals.sell, totals.unknown,
        levels[pocIndex].price, valueArea.first, valueArea.second,
    )
}

private fun valueArea(levels: List<ProfileLevel>, pocIndex: Int, totalVolume: Long): Pair<Long, Long> {
    // ceil(total * .7) without multiplying the full total, which could overflow.
    val target = totalVolume / 10 * 7 + (totalVolume % 10 * 7 + 9) / 10
    var low = pocIndex
    var high = pocIndex
    var included = levels[pocIndex].totalVolume
    while (included < target) {
        val left = levels.getOrNull(low - 1)?.totalVolume
        val right = levels.getOrNull(high + 1)?.totalVolume
        when {
            left == null && right == null -> break
            left != null && right != null && left == right -> {
                included = checkedAdd(checkedAdd(included, left), right)
                low--; high++
            }
            right == null || left != null && left > right -> {
                included = checkedAdd(included, requireNotNull(left))
                low--
            }
            else -> {
                included = checkedAdd(included, requireNotNull(right))
                high++
            }
        }
    }
    return levels[low].price to levels[high].price
}
