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

    @Test fun pendingLimitAssumesStopFirstWhenSameBarPathIsUnknown() {
        val queued = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_950, 9_900, 10_050, 10_000)
        assertTrue(queued.positions.isEmpty())
        val untouched = queued.advance(spec, Candle(2, 10_000, 10_025, 9_975, 10_000))
        assertEquals(1, untouched.pending.size)
        val filled = untouched.advance(spec, Candle(3, 10_000, 10_050, 9_875, 9_950))
        assertTrue(filled.pending.isEmpty())
        assertTrue(filled.positions.isEmpty())
        assertEquals(9_950L, filled.trades.single().position.entryPrice)
        assertEquals("止损", filled.trades.single().reason)
    }

    @Test fun stopEntryGapsAtOpenAndInvalidBracketIsCancelled() {
        val queued = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.STOP, 100, 10_050, 9_950, 10_100, 10_000)
        val result = queued.advance(spec, Candle(2, 10_125, 10_150, 10_100, 10_125))
        assertTrue(result.pending.isEmpty())
        assertTrue(result.positions.isEmpty())
        assertNotNull(result.lastNotice)
    }

    @Test fun oppositeOrdersReduceThenReverseTheNetPosition() {
        val long = ReplayState(0).open(spec, start, Side.BUY, 200, null, null)
        val reduced = long.open(spec, start.copy(close = 10_050), Side.SELL, 100, 9_950, 10_100)
        assertEquals(100L, reduced.positions.single().lotsHundredths)
        assertEquals(Side.BUY, reduced.positions.single().side)
        assertEquals(1_500_000L, reduced.trades.single().pnlMicros)
        val reversed = reduced.open(spec, start.copy(close = 10_050), Side.SELL, 200, null, null)
        assertEquals(Side.SELL, reversed.positions.single().side)
        assertEquals(100L, reversed.positions.single().lotsHundredths)
        assertEquals(2, reversed.trades.size)
    }

    @Test fun pendingEditsValidateWithoutChangingPositionOrOrderIdentity() {
        val queued = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_950, 9_900, null, 10_000)
        val revised = queued.modifyPending(spec, queued.pending.single().id, 9_925, 9_875, null, 10_000)
        assertEquals(queued.pending.single().id, revised.pending.single().id)
        assertEquals(9_925L, revised.pending.single().price)
        assertTrue(revised.positions.isEmpty())
        assertFailsWith<IllegalArgumentException> { revised.modifyPending(spec, revised.pending.single().id, 10_025, null, null, 10_000) }
    }
}
