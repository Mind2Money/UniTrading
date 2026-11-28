package com.unitrading.chart.drawing

import com.unitrading.broker.*
import kotlin.test.*

class PositionDrawingOrderTest {
    private val spec = Instrument("MES", "MES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, maxLotsHundredths = 10_000, quantityDecimals = 0, minStopTicks = 1)
    private val quote = Quote(550_000, 550_025, 1)
    private fun drawing(long: Boolean, entry: Long): Drawing {
        val direction = if (long) 1 else -1
        return Drawing("d", if (long) DrawingTool.LONG_POSITION else DrawingTool.SHORT_POSITION,
            listOf(DrawingPoint(1, entry.toDouble()), DrawingPoint(2, entry.toDouble()), DrawingPoint(1, (entry + direction * 200).toDouble()),
                DrawingPoint(1, (entry - direction * 100).toDouble())), params = mapOf("accountSize" to 1000.0, "riskPercent" to 1.0))
    }
    @Test fun longUsesAskForLimitOrStopAndKeepsBothProtectionPrices() {
        val stop = positionDrawingOrder(drawing(true, 550_050), spec, quote)
        assertEquals(OrderKind.STOP, stop.kind)
        assertEquals(Side.BUY, stop.side)
        assertEquals(200L, stop.lotsHundredths)
        assertEquals(549_950L, stop.stopLoss)
        assertEquals(550_250L, stop.takeProfit)
        assertTrue(stop.fromPositionDrawing)
        assertEquals(OrderKind.LIMIT, positionDrawingOrder(drawing(true, 550_025), spec, quote).kind)
        assertEquals(OrderKind.LIMIT, positionDrawingOrder(drawing(true, 550_000), spec, quote).kind)
    }
    @Test fun shortUsesBidForLimitOrStopIncludingEqualPrice() {
        assertEquals(OrderKind.STOP, positionDrawingOrder(drawing(false, 549_975), spec, quote).kind)
        assertEquals(OrderKind.LIMIT, positionDrawingOrder(drawing(false, 550_000), spec, quote).kind)
        assertEquals(OrderKind.LIMIT, positionDrawingOrder(drawing(false, 550_025), spec, quote).kind)
    }
    @Test fun invalidRiskPriceOrUnavailableContractNeverProducesAnOrder() {
        assertFailsWith<IllegalArgumentException> { positionDrawingOrder(drawing(true, 550_001), spec, quote) }
        assertFailsWith<IllegalArgumentException> { positionDrawingOrder(drawing(true, 550_000).copy(params = mapOf("accountSize" to 1.0, "riskPercent" to 1.0)), spec, quote) }
        assertFailsWith<IllegalArgumentException> { positionDrawingOrder(drawing(true, 550_000), spec.copy(isContinuous = true), quote) }
    }
    @Test fun visibilityPresetsUseExactTimeframeAndPreserveOldDefaults() {
        val d = drawing(true, 550_000)
        assertTrue(d.visibleOn(Timeframe.M1))
        val only = d.copy(timeVisibility = DrawingTimeVisibility.CURRENT, visibilityReferenceSeconds = Timeframe.ofMinutes(8).seconds)
        assertTrue(only.visibleOn(Timeframe.ofMinutes(8)))
        assertFalse(only.visibleOn(Timeframe.M5))
        assertFalse(only.visibleOn(Timeframe.ofMinutes(10)))
        val above = only.copy(timeVisibility = DrawingTimeVisibility.CURRENT_AND_ABOVE)
        assertFalse(above.visibleOn(Timeframe.M5)); assertTrue(above.visibleOn(Timeframe.ofMinutes(8))); assertTrue(above.visibleOn(Timeframe.M15))
        val below = only.copy(timeVisibility = DrawingTimeVisibility.CURRENT_AND_BELOW)
        assertTrue(below.visibleOn(Timeframe.M5)); assertTrue(below.visibleOn(Timeframe.ofMinutes(8))); assertFalse(below.visibleOn(Timeframe.M15))
        assertFalse(d.copy(hiddenIntervals = setOf(IntervalBucket.MINUTES)).visibleOn(Timeframe.M1))
    }
}
