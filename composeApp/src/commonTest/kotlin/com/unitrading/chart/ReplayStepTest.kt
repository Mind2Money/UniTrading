package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlin.test.*

class ReplayStepTest {
    @Test fun choicesAdvanceOnlyOverLoadedSourceBars() {
        assertEquals(listOf(1, 3, 5, 15).map(Timeframe::ofMinutes), replayStepOptions(Timeframe.M15, Timeframe.M1))
        assertEquals(listOf(Timeframe.M1), replayStepOptions(Timeframe.M1, Timeframe.M1))
        assertEquals(listOf(Timeframe.M5), replayStepOptions(Timeframe.M5, Timeframe.M5))
        assertTrue(Timeframe.ofSeconds(1) in replayStepOptions(Timeframe.M1, Timeframe.ofSeconds(1)))
        assertFalse(Timeframe.D1 in replayStepOptions(Timeframe.M1, Timeframe.ofSeconds(1)))
        assertTrue(replayStepOptions(Timeframe.M1, Timeframe.M5).isEmpty())
    }
    @Test fun visibleChoicesFollowChartAndDivisibility() {
        val second = Timeframe.ofSeconds(1)
        assertEquals(listOf(second, Timeframe.M1, Timeframe.ofMinutes(8)), replayStepCandidates(Timeframe.ofMinutes(8)))
        assertEquals(listOf(second) + listOf(1, 3, 5, 15).map(Timeframe::ofMinutes), replayStepCandidates(Timeframe.M15))
        assertEquals(listOf(second) + listOf(1, 5, 10).map(Timeframe::ofMinutes), replayStepCandidates(Timeframe.ofMinutes(10)))
        assertEquals(listOf(second) + listOf(1, 3, 5, 10, 15, 30, 60).map(Timeframe::ofMinutes), replayStepCandidates(Timeframe.H1))
    }
    @Test fun advancingUsesTimeAndSkipsMissingBars() {
        val bars = listOf(0L, 60_000L, 300_000L, 360_000L).map { Candle(it, 100, 100, 100, 100) }
        assertEquals(2, replayStepEnd(bars, 0, Timeframe.M5))
        assertEquals(2, replayStepEnd(bars, 1, Timeframe.M1))
        assertEquals(3, replayStepEnd(bars, 2, Timeframe.M5))
    }
}
