package com.unitrading.ui

import com.unitrading.broker.Instrument
import kotlin.test.*

class QuantitySliderScaleTest {
    private val spec = Instrument("TEST", "Test", "test", 2, tickValueMicrosPerLot = 100_000)

    @Test fun futuresAndFractionalLotsSnapToTheContractGrid() {
        val futures = QuantitySliderScale.from(spec.copy(minLotsHundredths = 100, maxLotsHundredths = 100_000, lotStepHundredths = 100))!!
        assertEquals(100, futures.quantity(0f))
        assertEquals(100_000, futures.quantity(1f))
        for (i in 0..1000) {
            val quantity = futures.quantity(i / 1000f)!!
            assertEquals(0, quantity % 100)
            assertTrue(quantity in 100..100_000)
        }
        val fx = QuantitySliderScale.from(spec.copy(minLotsHundredths = 5, maxLotsHundredths = 102, lotStepHundredths = 5))!!
        assertEquals(100, fx.maximum)
        assertEquals(5, fx.quantity(-1f))
        assertEquals(100, fx.quantity(2f))
        for (q in 5L..100L step 5) assertEquals(q, fx.quantity(fx.fraction(q)))
        assertNull(fx.quantity(Float.NaN))
    }

    @Test fun singleQuantityAndInvalidSpecsDoNotCreateAnInvalidRange() {
        val fixed = QuantitySliderScale.from(spec.copy(minLotsHundredths = 100, maxLotsHundredths = 100, lotStepHundredths = 100))!!
        assertEquals(0, fixed.intervals)
        assertEquals(100, fixed.quantity(.75f))
        assertEquals(0f, fixed.fraction(100))
        assertNull(QuantitySliderScale.from(spec.copy(lotStepHundredths = 0)))
        assertNull(QuantitySliderScale.from(spec.copy(minLotsHundredths = 100, maxLotsHundredths = 50)))
    }

    @Test fun accountCapacityBoundsTheSliderAndUnknownOrZeroDoesNotInventARange() {
        val futures = spec.copy(minLotsHundredths = 100, maxLotsHundredths = Int.MAX_VALUE.toLong() * 100, lotStepHundredths = 100, quantityDecimals = 0)
        val scale = QuantitySliderScale.from(futures, 550)!!
        assertEquals(500, scale.maximum)
        assertEquals(500, scale.quantity(1f))
        assertEquals(100, scale.quantity(0f))
        assertNull(QuantitySliderScale.from(futures, null))
        assertNull(QuantitySliderScale.from(futures, 0))
        assertEquals(100, QuantitySliderScale.from(futures, 100)!!.quantity(1f))
        assertEquals(250_000, QuantitySliderScale.from(futures, 250_000)!!.quantity(1f))
        assertNull(OrderDraft(quantity = "5").steppedQuantity(futures, true, 500))
        assertEquals("4", OrderDraft(quantity = "5").steppedQuantity(futures, false, 0))
        val request = com.unitrading.broker.OrderRequest("TEST", com.unitrading.broker.Side.BUY, com.unitrading.broker.OrderKind.MARKET, 600)
        assertFalse(com.unitrading.broker.OrderPreview(100).withCapacity(request, QuantityCapacityState(500)).valid)
        assertTrue(com.unitrading.broker.OrderPreview(100).withCapacity(request, QuantityCapacityState()).valid)
    }
}
