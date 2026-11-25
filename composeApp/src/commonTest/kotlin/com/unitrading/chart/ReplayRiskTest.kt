package com.unitrading.chart

import kotlin.test.*
import kotlin.time.Instant

class ReplayRiskTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilliseconds()
    @Test fun staticAndTrailingHaveDifferentThresholds() {
        val cfg = ReplayRiskConfig(50_000, 2_000, 3_000, ReplayDrawdown.TDD)
        val trailing = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T20:00:00Z"), 50_000, 51_000)
        assertEquals(49_000L, trailing.threshold)
        assertTrue(trailing.observe(cfg, at("2026-11-23T20:01:00Z"), 50_000, 49_000).failed)
        val fixed = cfg.copy(mode = ReplayDrawdown.STATIC)
        assertEquals(48_000L, ReplayRiskState.initial(fixed).observe(fixed, at("2026-11-23T20:00:00Z"), 50_000, 51_000).threshold)
    }
    @Test fun eodMovesOnlyOnNextChicagoTradingDay() {
        val cfg = ReplayRiskConfig(50_000, 2_000, 3_000, ReplayDrawdown.EOD)
        val day = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T22:50:00Z"), 51_000, 51_000)
        assertEquals(48_000L, day.threshold)
        val next = day.observe(cfg, at("2026-11-23T23:01:00Z"), 51_000, 51_000)
        assertEquals(49_000L, next.threshold)
    }
}

class ReplayDailyRiskTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilliseconds()
    @Test fun defaultsUseSoftStopAndEndOfDayTrailing() {
        val config = defaultReplayRiskConfig()
        assertEquals(ReplayDailyLoss.SOFT, config.dailyLossMode)
        assertEquals(ReplayDrawdown.EOD, config.mode)
        assertEquals(1_000_000_000L, config.maxDailyLossMicros)
    }
    @Test fun noDailyStopDoesNotLockOrFailBeforeTheTotalDrawdown() {
        val cfg = ReplayRiskConfig(50_000, 5_000, 10_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 5_000, dailyLossMode = ReplayDailyLoss.NONE)
        val prior = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T22:59:00Z"), 55_000, 55_000)
        val opening = prior.observe(cfg, at("2026-11-23T23:01:00Z"), 55_000, 55_000)
        val dailyLoss = opening.observe(cfg, at("2026-11-24T00:00:00Z"), 50_000, 50_000)
        assertFalse(dailyLoss.dailyLossHit)
        assertFalse(dailyLoss.dailyFailure)
        assertFalse(dailyLoss.failed)
        val totalLoss = dailyLoss.observe(cfg, at("2026-11-24T00:01:00Z"), 45_000, 45_000)
        assertTrue(totalLoss.failed)
        assertFalse(totalLoss.dailyFailure)
    }
    @Test fun hardDailyLossFailsAtThresholdAndStaysFailed() {
        val cfg = ReplayRiskConfig(50_000, 5_000, 3_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 1_000, dailyLossMode = ReplayDailyLoss.HARD)
        val state = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T20:00:00Z"), 50_000, 49_000)
        assertTrue(state.dailyLossHit)
        assertTrue(state.dailyFailure)
        assertTrue(state.failed)
        assertTrue(state.observe(cfg, at("2026-11-23T23:01:00Z"), 50_000, 50_000).failed)
    }
    @Test fun softDailyLossResetsAtChicagoTradingDayBoundary() {
        val cfg = ReplayRiskConfig(50_000, 5_000, 3_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 1_000, dailyLossMode = ReplayDailyLoss.SOFT)
        val state = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T22:30:00Z"), 49_000, 49_000)
        assertTrue(state.dailyLossHit)
        assertFalse(state.failed)
        assertTrue(state.observe(cfg, at("2026-11-23T22:59:00Z"), 49_500, 49_500).dailyLossHit)
        val next = state.observe(cfg, at("2026-11-23T23:01:00Z"), 49_000, 49_000)
        assertFalse(next.dailyLossHit)
        assertEquals(49_000L, next.dayOpeningBalance)
    }
    @Test fun overnightFloatingGainIsNotCountedAgainNextDay() {
        val cfg = ReplayRiskConfig(50_000, 5_000, 3_000, ReplayDrawdown.STATIC,
            maxDailyLossMicros = 1_000, dailyLossMode = ReplayDailyLoss.HARD)
        val before = ReplayRiskState.initial(cfg).observe(cfg, at("2026-11-23T22:59:00Z"), 50_000, 52_000)
        val after = before.observe(cfg, at("2026-11-23T23:01:00Z"), 50_000, 50_999)
        assertEquals(52_000L, after.dayOpeningBalance)
        assertTrue(after.failed)
    }

}
