package com.unitrading.chart

import com.unitrading.broker.Candle
import kotlin.math.abs
import kotlin.test.*

class ChartGeometryTest {
    @Test fun coordinateRoundTripAndTickSnapping() {
        val scale = PriceScale(100.0, 500.0, 20f, 420f)
        for (price in 105L..495L step 5) assertTrue(abs(scale.price(scale.y(price), 5) - price) <= 5)
        assertEquals(500L, scale.price(-100f, 5))
        assertEquals(100L, scale.price(999f, 5))
        assertEquals(0L, scale.price(113.2f, 5) % 5)
    }
    @Test fun emptyAndConstantChartsHaveRange() {
        for (candles in listOf(emptyList(), listOf(Candle(0, 100, 100, 100, 100)))) {
            val scale = PriceScale.from(candles, emptyList(), 0f, 100f)
            assertTrue(scale.high > scale.low)
            assertTrue(scale.y(100).isFinite())
        }
    }
    @Test fun nearbyPriceLabelsStaySeparateAndInsideBounds() {
        assertEquals(listOf(100f, 125f, 150f), spreadLabelCenters(listOf(100f, 105f, 110f), 20f, 200f, 25f))
        assertEquals(listOf(150f, 175f, 200f), spreadLabelCenters(listOf(180f, 190f, 199f), 20f, 200f, 25f))
        assertEquals(listOf(20f), spreadLabelCenters(listOf(-5f), 20f, 200f, 25f))
        assertEquals(emptyList(), spreadLabelCenters(emptyList(), 20f, 200f, 25f))
        assertEquals(listOf(20f, 25f, 30f), spreadLabelCenters(listOf(20f, 20f, 20f), 20f, 30f, 25f))
    }
    @Test fun viewportCannotEscapeHistory() {
        assertEquals(IntRange.EMPTY, visibleRange(0, 100, 0))
        assertEquals(0..9, visibleRange(10, 100, 10000))
        assertEquals(80..99, visibleRange(100, 20, 0))
        assertEquals(0..19, visibleRange(100, 20, 10000))
        assertEquals(0, candleIndex(-99f, 0f, 100f, 10))
        assertEquals(9, candleIndex(999f, 0f, 100f, 10))
    }
}
