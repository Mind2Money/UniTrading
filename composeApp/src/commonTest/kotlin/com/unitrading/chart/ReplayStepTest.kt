package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*

class ReplayStepTest {
    @Test fun choicesAreFinerThanTheChartAndSupportedBySource() {
        assertEquals(listOf(Timeframe.M1, Timeframe.M5), replayStepOptions(Timeframe.M15, Timeframe.M1))
        assertEquals(listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15), replayStepOptions(Timeframe.ofMinutes(30), Timeframe.M1))
        assertEquals(listOf(Timeframe.M1), replayStepOptions(Timeframe.M5, Timeframe.M1))
        assertEquals(listOf(Timeframe.M1), replayStepOptions(Timeframe.M1, Timeframe.M1))
        assertEquals(listOf(Timeframe.M5), replayStepOptions(Timeframe.M5, Timeframe.M5))
        assertEquals(listOf(Timeframe.M5, Timeframe.M15), replayStepOptions(Timeframe.ofMinutes(30), Timeframe.M5))
    }
    @Test fun advancingUsesTimeAndSkipsMissingBars() {
        val bars = listOf(0L, 60_000L, 300_000L, 360_000L).map { Candle(it, 100, 100, 100, 100) }
        assertEquals(2, replayStepEnd(bars, 0, Timeframe.M5))
        assertEquals(2, replayStepEnd(bars, 1, Timeframe.M1))
        assertEquals(3, replayStepEnd(bars, 2, Timeframe.M5))
    }
}
