package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*

class ReplayStepTest {
    @Test fun choicesAdvanceOnlyOverLoadedSourceBars() {
        val minutes = listOf(1, 3, 5, 15, 30, 60, 1440).map(Timeframe::ofMinutes)
        assertEquals(minutes, replayStepOptions(Timeframe.M15, Timeframe.M1))
        assertEquals(minutes, replayStepOptions(Timeframe.M1, Timeframe.M1))
        val fiveMinute = listOf(5, 15, 30, 60, 1440).map(Timeframe::ofMinutes)
        assertEquals(fiveMinute, replayStepOptions(Timeframe.M5, Timeframe.M5))
        assertTrue(Timeframe.ofSeconds(1) in replayStepOptions(Timeframe.M1, Timeframe.ofSeconds(1)))
        assertFalse(Timeframe.D1 in replayStepOptions(Timeframe.M1, Timeframe.ofSeconds(1)))
    }
    @Test fun advancingUsesTimeAndSkipsMissingBars() {
        val bars = listOf(0L, 60_000L, 300_000L, 360_000L).map { Candle(it, 100, 100, 100, 100) }
        assertEquals(2, replayStepEnd(bars, 0, Timeframe.M5))
        assertEquals(2, replayStepEnd(bars, 1, Timeframe.M1))
        assertEquals(3, replayStepEnd(bars, 2, Timeframe.M5))
    }
}
