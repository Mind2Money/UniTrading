package com.unitrading.broker

import kotlin.test.*

class OrderFlowAnalyticsTest {
    private fun analyze(trades: List<MarketTrade>, ticks: Long = 1, tickSize: Long = 1) =
        analyzeOrderFlow(trades, Timeframe.M1, ticks, tickSize)

    @Test fun unknownDirectionAddsVolumeWithoutChangingDelta() {
        val result = analyze(listOf(
            MarketTrade(1, 100, 12, Side.BUY),
            MarketTrade(2, 100, 5, Side.SELL),
            MarketTrade(3, 100, 50),
        ))
        assertEquals(67L, result.totalVolume)
        assertEquals(7L, result.delta)
        assertEquals(50L, result.totalUnknown)
        assertEquals(ProfileLevel(100, 12, 5, 50), result.levels.single())
        assertEquals(7L, result.bars.single().cumulativeDelta)
    }

    @Test fun barsUseExchangeTimeAndCvdRemainsChronologicalForLatePrints() {
        val result = analyze(listOf(
            MarketTrade(60_010, 103, 2, Side.SELL),
            MarketTrade(59_999, 101, 8, Side.BUY),
            MarketTrade(60_000, 102, 3),
            MarketTrade(120_000, 104, 4, Side.BUY),
        ))
        assertEquals(listOf(0L, 60_000L, 120_000L), result.bars.map { it.timeMs })
        assertEquals(listOf(8L, -2L, 4L), result.bars.map { it.delta })
        assertEquals(listOf(8L, 6L, 10L), result.bars.map { it.cumulativeDelta })
        assertEquals(listOf(102L, 103L), result.bars[1].levels.map { it.price })
        assertEquals(5L, result.bars[1].totalVolume)
    }

    @Test fun pocTieIsStableAtLowestPriceRegardlessOfInputOrder() {
        val trades = listOf(MarketTrade(0, 200, 10), MarketTrade(0, 100, 10))
        assertEquals(100L, analyze(trades).poc)
        assertEquals(100L, analyze(trades.reversed()).poc)
    }

    @Test fun valueAreaAddsLargerNeighbourUntilSeventyPercent() {
        val result = analyze(listOf(
            MarketTrade(0, 99, 10), MarketTrade(0, 100, 40),
            MarketTrade(0, 101, 30), MarketTrade(0, 102, 20),
        ))
        assertEquals(100L, result.poc)
        assertEquals(100L, result.valueAreaLow)
        assertEquals(101L, result.valueAreaHigh)
    }

    @Test fun valueAreaIncludesBothSidesOnTieAndHandlesSingleLevel() {
        val result = analyze(listOf(
            MarketTrade(0, 99, 30), MarketTrade(0, 100, 40), MarketTrade(0, 101, 30),
        ))
        assertEquals(99L, result.valueAreaLow)
        assertEquals(101L, result.valueAreaHigh)
        val single = analyze(listOf(MarketTrade(0, 123, 1)))
        assertEquals(123L, single.valueAreaLow)
        assertEquals(123L, single.valueAreaHigh)
    }

    @Test fun priceRegroupingUsesFloorForNegativePricesAndNeverFillsGaps() {
        val result = analyze(listOf(
            MarketTrade(0, -1, 2, Side.BUY), MarketTrade(0, -10, 3, Side.SELL),
            MarketTrade(0, 9, 4), MarketTrade(0, 1_000_000, 5),
        ), ticks = 2, tickSize = 5)
        assertEquals(listOf(-10L, 0L, 1_000_000L), result.levels.map { it.price })
        assertEquals(ProfileLevel(-10, 2, 3, 0), result.levels.first())
        assertEquals(result.levels, result.bars.single().levels)
        assertEquals(14L, result.totalVolume)
    }

    @Test fun latestHundredTwentyBarsKeepCvdFromEarlierRetainedPrints() {
        val result = analyze((0..120).map { MarketTrade(it * 60_000L, 100, 1, Side.BUY) })
        assertEquals(120, result.bars.size)
        assertEquals(60_000L, result.bars.first().timeMs)
        assertEquals(2L, result.bars.first().cumulativeDelta)
        assertEquals(121L, result.bars.last().cumulativeDelta)
        assertEquals(121L, result.totalVolume)
    }

    @Test fun processingBoundCountsOnlyLatestTwentyThousandPrints() {
        val result = analyze(List(20_001) { index -> MarketTrade(index.toLong(), 100, 1, if (index == 0) Side.SELL else Side.BUY) })
        assertEquals(20_000L, result.totalVolume)
        assertEquals(20_000L, result.delta)
    }

    @Test fun emptyAndNonPositiveSizesDoNotCreateLevelsOrPoc() {
        assertEquals(OrderFlowAnalysis(), analyze(emptyList()))
        assertEquals(OrderFlowAnalysis(), analyze(listOf(MarketTrade(0, 100, 0), MarketTrade(0, 101, -1))))
        assertFailsWith<IllegalArgumentException> { analyze(emptyList(), ticks = 0) }
        assertFailsWith<IllegalArgumentException> { analyze(emptyList(), tickSize = -1) }
        assertFailsWith<IllegalArgumentException> { analyze(emptyList(), ticks = Long.MAX_VALUE, tickSize = 2) }
    }
}
