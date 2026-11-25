package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import kotlin.test.*

class ReplaySoftStopTest {
    private val spec = Instrument("MES", "MES", "期货", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0)
    private val config = ReplayRiskConfig(50_000_000_000, 2_000_000_000, 50_000_000, ReplayDrawdown.STATIC,
        maxDailyLossMicros = 5_000_000, dailyLossMode = ReplayDailyLoss.SOFT, consistencyPercent = 40)
    private val start = Candle(1_795_470_000_000, 10_000, 10_025, 9_975, 10_000)
    private val risk get() = ReplayRiskState.initial(config).observe(config, start.timeMs, config.initialMicros, config.initialMicros)
    private fun open(side: Side = Side.BUY, instrument: Instrument = spec, stop: Long? = null, target: Long? = null) =
        ReplayState(0).open(instrument, start, side, 100, stop, target)

    @Test fun softStopClosesALongAtTheFirstLossTickEvenIfTheBarLaterRises() {
        val before = open()
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_600, 9_700, 10_500)
        assertEquals(9_900L, replaySoftStopBeforeAdvance(spec, config, risk, before, bar))
    }

    @Test fun softStopFindsTheFirstLossTickForShortsAndIncludesCommissions() {
        val short = open(Side.SELL)
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_300, 9_500, 9_500)
        assertEquals(10_100L, replaySoftStopBeforeAdvance(spec, config, risk, short, bar))
        val withFees = spec.copy(commissionMicrosPerLot = 1_000_000)
        val long = open(instrument = withFees)
        val losing = Candle(start.timeMs + 60_000, 10_000, 10_025, 9_700, 9_700)
        assertEquals(9_925L, replaySoftStopBeforeAdvance(withFees, config, risk, long, losing))
    }

    @Test fun aGapUsesTheOpenInsteadOfPretendingTheLimitWasFilled() {
        val bar = Candle(start.timeMs + 60_000, 9_800, 10_500, 9_700, 10_500)
        assertEquals(9_800L, replaySoftStopBeforeAdvance(spec, config, risk, open(), bar))
    }

    @Test fun aCloserProtectiveStopRunsBeforeTheAccountLimit() {
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_600, 9_700, 10_500)
        assertNull(replaySoftStopBeforeAdvance(spec, config, risk, open(stop = 9_950), bar))
        assertEquals(9_900L, replaySoftStopBeforeAdvance(spec, config, risk, open(stop = 9_900), bar))
        assertEquals(9_900L, replaySoftStopBeforeAdvance(spec, config, risk, open(stop = 9_800), bar))
    }

    @Test fun aPendingOrderOnTheAdversePathIsConsideredBeforeTheSoftStop() {
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_600, 9_700, 10_500)
        val before = open().enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_950, null, null, start.close)
        assertNull(replaySoftStopBeforeAdvance(spec, config, risk, before, bar))
        val favorable = open().enqueue(spec, Side.SELL, OrderKind.LIMIT, 100, 10_100, null, null, start.close)
        assertEquals(9_900L, replaySoftStopBeforeAdvance(spec, config, risk, favorable, bar))
        val atLimit = open().enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_900, null, null, start.close)
        assertEquals(9_900L, replaySoftStopBeforeAdvance(spec, config, risk, atLimit, bar))
    }

    @Test fun newlyFilledPendingPositionCanStillHitTheSoftStop() {
        val before = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_950, null, null, start.close)
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_025, 9_800, 9_800)
        val after = before.advance(spec, bar)
        assertEquals(9_850L, replaySoftStopPrice(spec, config, risk, before, after, bar))
    }

    @Test fun aPendingEntryAtTheOpenCanHitTheSoftStopBeforeItsLaterTakeProfit() {
        val before = ReplayState(0).enqueue(spec, Side.BUY, OrderKind.LIMIT, 100, 9_900, null, 10_350, 10_000)
        val bar = Candle(start.timeMs + 60_000, 9_900, 10_400, 9_800, 10_400)
        val opened = assertNotNull(replaySinglePendingAtOpen(spec, before, bar))
        assertEquals(9_800L, replaySoftStopBeforeAdvance(spec, config, risk, opened, bar))
    }

    @Test fun hardAndDisabledDailyStopsDoNotUseTheSoftFlatteningPath() {
        val bar = Candle(start.timeMs + 60_000, 10_000, 10_025, 9_700, 9_700)
        assertNull(replaySoftStopBeforeAdvance(spec, config.copy(dailyLossMode = ReplayDailyLoss.HARD), risk, open(), bar))
        assertNull(replaySoftStopBeforeAdvance(spec, config.copy(dailyLossMode = ReplayDailyLoss.NONE), risk, open(), bar))
    }
}
