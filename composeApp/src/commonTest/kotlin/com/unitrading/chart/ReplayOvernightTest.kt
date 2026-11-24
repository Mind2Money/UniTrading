package com.unitrading.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReplayOvernightTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilliseconds()

    @Test fun winterAndSummerCutoffsFollowChicagoTime() {
        val cutoff = parseReplayCutoff("15:59")!!
        assertFalse(replayOvernightBlocked(at("2026-01-05T21:58:00Z"), cutoff))
        assertTrue(replayOvernightBlocked(at("2026-01-05T21:59:00Z"), cutoff))
        assertFalse(replayOvernightBlocked(at("2026-01-05T23:00:00Z"), cutoff))
        assertTrue(replayOvernightBlocked(at("2026-07-06T20:59:00Z"), cutoff))
        assertFalse(replayOvernightBlocked(at("2026-07-06T22:00:00Z"), cutoff))
        assertNull(parseReplayCutoff("25:00"))
        assertNull(parseReplayCutoff("3:00"))
    }

    @Test fun closeOnBoundaryOrBeforeGapWithoutUsingFuturePrices() {
        val cutoff = 15 * 60 + 59
        val current = at("2026-01-05T21:58:00Z")
        assertEquals(ReplayCutoffAction.AFTER_NEXT, replayCutoffAction(current, current, at("2026-01-05T21:59:00Z"), cutoff))
        assertEquals(ReplayCutoffAction.BEFORE_NEXT, replayCutoffAction(current, at("2026-01-05T23:00:00Z"), at("2026-01-05T23:01:00Z"), cutoff))
        assertEquals(ReplayCutoffAction.NONE, replayCutoffAction(at("2026-01-05T21:57:00Z"), at("2026-01-05T21:57:00Z"), current, cutoff))
    }
}
