package com.unitrading.ui

import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MarketSessionStateTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilliseconds()
    private val instrument = Instrument("@MCL", "Micro WTI", "连续合约", 2, tickValueMicrosPerLot = 1000)

    @Test fun cmeScheduleUsesChicagoTimeInSummerAndWinter() {
        assertFalse(scheduledCmeClosed("@MCL", at("2026-07-10T20:59:00Z"))!!)
        assertTrue(scheduledCmeClosed("@MCL", at("2026-07-10T21:01:00Z"))!!)
        assertTrue(scheduledCmeClosed("MESZ6", at("2026-12-11T22:01:00Z"))!!)
        assertTrue(scheduledCmeClosed("@NQ", at("2026-07-12T21:59:00Z"))!!)
        assertFalse(scheduledCmeClosed("@NQ", at("2026-07-12T22:00:00Z"))!!)
        assertTrue(scheduledCmeClosed("@GC", at("2026-07-08T21:30:00Z"))!!)
        assertNull(scheduledCmeClosed("EURUSD", at("2026-07-08T21:30:00Z")))
    }

    @Test fun staleOrMissingQuotesDoNotMistakeAnOpenSessionOrDisconnectedFeedForClosed() {
        val open = at("2026-07-08T19:00:00Z")
        val closed = at("2026-07-10T21:01:00Z")
        val fresh = Quote(8000, 8001, open - 1_000)
        assertEquals(MarketSessionState.OPEN, marketSessionState(instrument, fresh, true, open, open - 60_000))
        assertEquals(MarketSessionState.DELAYED, marketSessionState(instrument, fresh.copy(timeMs = open - 15_001), true, open, open - 60_000))
        assertEquals(MarketSessionState.WAITING, marketSessionState(instrument, null, true, closed, closed - 14_999))
        assertEquals(MarketSessionState.CLOSED, marketSessionState(instrument, null, true, closed, closed - 15_000))
        assertEquals(MarketSessionState.CLOSED, marketSessionState(instrument, fresh, true, closed, closed - 60_000))
        assertEquals(MarketSessionState.DISCONNECTED, marketSessionState(instrument, fresh, false, closed, closed - 60_000))
        assertEquals(MarketSessionState.DELAYED, marketSessionState(instrument.copy(symbol = "EURUSD"), null, true, closed, closed - 60_000))
    }
}
