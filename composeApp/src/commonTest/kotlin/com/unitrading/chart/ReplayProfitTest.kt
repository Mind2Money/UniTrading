package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import kotlin.test.*

class ReplayProfitTest {
    private val spec = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
    private val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 50_000_000, ReplayDrawdown.STATIC, consistencyPercent = 40)
    private val start = Candle(1_700_000_000_000, 10_000, 10_025, 9_975, 10_000)
    private val risk get() = ReplayRiskState.initial(config).observe(config, start.timeMs, config.initialMicros, config.initialMicros)
    private fun opening(side: Side = Side.BUY, instrument: Instrument = spec) = ReplayState(0).open(instrument, start, side, 100, null, null)

    @Test fun consistencyClosesAtItsThresholdBeforeTheProfitTarget() {
        val before = opening()
        val bar = Candle(start.timeMs + 60_000, 10_000, 11_100, 9_975, 10_000)
        val after = before.advance(spec, bar)
        val trigger = assertNotNull(replayProfitTrigger(spec, config, risk, before, after, bar))
        assertEquals(ReplayProfitKind.CONSISTENCY, trigger.kind)
        assertEquals(10_400L, trigger.price)
        val closed = after.close(spec, after.positions.single(), trigger.price, bar.timeMs, "一致性自动平仓")
        assertEquals(20_000_000L, closed.trades.single().pnlMicros)
    }

    @Test fun softDailyLockDoesNotHideAConsistencyTriggerForAnExistingPosition() {
        val before = opening()
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_500, 9_975, 10_500)
        val after = before.advance(spec, bar)
        val trigger = assertNotNull(replayProfitTrigger(spec, config, risk.copy(dailyLossHit = true), before, after, bar))
        assertEquals(ReplayProfitKind.CONSISTENCY, trigger.kind)
        assertEquals(10_400L, trigger.price)
    }

    @Test fun zeroDisablesConsistencyAndTheTargetClosesAShortPosition() {
        val cfg = config.copy(consistencyPercent = 0)
        val before = opening(Side.SELL)
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_025, 8_900, 10_000)
        val after = before.advance(spec, bar)
        val trigger = assertNotNull(replayProfitTrigger(spec, cfg, ReplayRiskState.initial(cfg), before, after, bar))
        assertEquals(ReplayProfitKind.TARGET, trigger.kind)
        assertEquals(9_000L, trigger.price)
    }

    @Test fun thresholdAccountsForCommissionAndRoundsToATradableTick() {
        val instrument = spec.copy(commissionMicrosPerLot = 1_000_000)
        val cfg = config.copy(targetMicros = 25_000_000, consistencyPercent = 20)
        val before = opening(instrument = instrument)
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_200, 9_975, 10_000)
        val after = before.advance(instrument, bar)
        val trigger = assertNotNull(replayProfitTrigger(instrument, cfg, ReplayRiskState.initial(cfg), before, after, bar))
        assertEquals(10_150L, trigger.price)
        assertEquals(5_500_000L, after.close(instrument, after.positions.single(), trigger.price, bar.timeMs, "一致性").trades.single().pnlMicros)
    }

    @Test fun aGapThroughTheTargetExecutesAtTheOpen() {
        val cfg = config.copy(consistencyPercent = 0)
        val before = opening()
        val bar = Candle(start.timeMs + 60_000, 11_200, 11_250, 11_000, 11_200)
        val after = before.advance(spec, bar)
        val trigger = assertNotNull(replayProfitTrigger(spec, cfg, ReplayRiskState.initial(cfg), before, after, bar))
        assertEquals(ReplayProfitKind.TARGET, trigger.kind)
        assertEquals(11_200L, trigger.price)
    }

    @Test fun aPendingEntryCannotUseAnExtremeThatMayHaveOccurredBeforeEntry() {
        val before = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_900, null, null, 10_000)
        val bar = Candle(start.timeMs + 60_000, 10_000, 11_000, 9_900, 9_900)
        val after = before.advance(spec, bar)
        assertEquals(1, after.positions.size)
        assertNull(replayProfitTrigger(spec, config, risk, before, after, bar))
    }

    @Test fun aPendingEntryKnownToFillAtTheOpenCanReachConsistencyInsideThatBar() {
        val before = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_900, null, null, 10_000)
        val bar = Candle(start.timeMs + 60_000, 9_900, 10_400, 9_875, 9_900)
        val after = before.advance(spec, bar)
        val trigger = assertNotNull(replayProfitTrigger(spec, config, risk, before, after, bar))
        assertEquals(ReplayProfitKind.CONSISTENCY, trigger.kind)
        assertEquals(10_300L, trigger.price)
    }

    @Test fun accountCapBeforeTakeProfitIsNotSkipped() {
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_800, 9_975, 10_000)
        val laterTp = ReplayState(0).open(spec, start, Side.BUY, 100, null, 10_600)
        assertTrue(replayProfitPrecedesTakeProfit(spec, config, risk, laterTp, bar))
        val earlierTp = ReplayState(0).open(spec, start, Side.BUY, 100, null, 10_200)
        assertFalse(replayProfitPrecedesTakeProfit(spec, config, risk, earlierTp, bar))
        val sameBarStop = ReplayState(0).open(spec, start, Side.BUY, 100, 9_975, 10_600)
        assertFalse(replayProfitPrecedesTakeProfit(spec, config, risk, sameBarStop, bar))
    }

    @Test fun consistencyLockResetsOnTheNextChicagoTradingDay() {
        val initial = ReplayRiskState.initial(config).observe(config, 1_795_470_000_000, config.initialMicros, config.initialMicros).copy(consistencyHit = true)
        val next = initial.observe(config, 1_795_556_400_000, config.initialMicros, config.initialMicros)
        assertFalse(next.consistencyHit)
        assertNull(config.copy(consistencyPercent = 0).consistencyLimitMicros)
        assertEquals(20_000_000L, config.consistencyLimitMicros)
        assertFailsWith<IllegalArgumentException> { config.copy(consistencyPercent = 10) }
    }
}
