package com.unitrading.chart

import com.unitrading.broker.*
import kotlin.test.*

class HistoricalReplayTest {
    private val spec = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, commissionMicrosPerLot = 500_000)
    private val start = Candle(1, 10_000, 10_025, 9_975, 10_000)

    @Test fun simulatedStopAndTargetUseWorstCaseWhenBothTouch() {
        val state = ReplayState(0).open(spec, start, Side.BUY, 100, 9_950, 10_050)
        val done = state.advance(spec, Candle(2, 10_000, 10_075, 9_925, 10_000))
        assertTrue(done.positions.isEmpty())
        assertEquals("止损", done.trades.single().reason)
        assertEquals(9_950L, done.trades.single().exit)
        assertEquals(-3_500_000L, done.trades.single().pnlMicros)
    }

    @Test fun stopGapUsesAdverseOpenAndShortTargetUsesLimit() {
        val long = ReplayState(0).open(spec, start, Side.BUY, 100, 9_950, null)
        assertEquals(9_900L, long.advance(spec, Candle(2, 9_900, 9_925, 9_875, 9_900)).trades.single().exit)
        val short = ReplayState(0).open(spec, start, Side.SELL, 100, 10_050, 9_950)
        assertEquals(9_950L, short.advance(spec, Candle(2, 10_000, 10_025, 9_925, 9_950)).trades.single().exit)
        val gapTarget = ReplayState(0).open(spec, start, Side.BUY, 100, 9_950, 10_050)
        val first = gapTarget.advance(spec, Candle(2, 10_075, 10_100, 9_925, 10_000)).trades.single()
        assertEquals("止盈", first.reason)
        assertEquals(10_050L, first.exit)
    }

    @Test fun rejectsInvalidQuantitiesAndProtectionDirections() {
        assertFailsWith<IllegalArgumentException> { ReplayState(0).open(spec, start, Side.BUY, 0, null, null) }
        assertFailsWith<IllegalArgumentException> { ReplayState(0).open(spec, start, Side.BUY, 100, 10_025, null) }
        assertFailsWith<IllegalArgumentException> { ReplayState(0).open(spec, start, Side.SELL, 100, null, 10_025) }
    }

    @Test fun protectionCanMoveToBreakEvenAndManualCloseAccountsForFees() {
        val opened = ReplayState(0).open(spec, start, Side.BUY, 100, 9_950, null)
        val raised = opened.protect(spec, opened.positions.single(), 10_050, 10_000, 10_100)
        assertEquals(10_000L, raised.positions.single().stopLoss)
        val closed = raised.close(spec, raised.positions.single(), 10_050, 2, "手动平仓")
        assertEquals(1_500_000L, closed.trades.single().pnlMicros)
        assertTrue(closed.positions.isEmpty())
    }
}
