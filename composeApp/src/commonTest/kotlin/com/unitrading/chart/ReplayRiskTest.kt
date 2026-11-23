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
