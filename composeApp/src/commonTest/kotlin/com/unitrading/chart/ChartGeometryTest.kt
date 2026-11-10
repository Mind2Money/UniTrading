package com.unitrading.chart

import com.unitrading.broker.Candle
import kotlin.math.abs
import kotlin.test.*

class ChartGeometryTest {
    @Test fun pinchKeepsTimeAndPriceAnchoredAndHonorsLimits() {
        val original = ChartViewport(80.0, 100.0)
        val zoomed = original.pinch(2.0, .3f, .6f, 1000)
        assertEquals(40.0, zoomed.count)
        assertEquals(original.start(1000) + .3 * original.count, zoomed.start(1000) + .6 * zoomed.count, .00001)
        val scale = PriceScale(100.0, 500.0, 0f, 400f)
        val prices = scale.pinch(2.0, .25f, .75f, 4.0)
        assertEquals(200.0, prices.high - prices.low)
        assertEquals(400.0, prices.high - .75 * (prices.high - prices.low))
        assertEquals(12.0, original.pinch(100000.0, .5f, .5f, 1000).count)
        assertEquals(600.0, original.pinch(.00001, .5f, .5f, 1000).count)
        assertEquals(original, original.pinch(Double.NaN, .5f, .5f, 1000))
        assertEquals(scale, scale.pinch(0.0, .5f, .5f, 4.0))
    }

    @Test fun axesZoomIndependentlyAndContinuousPanAllowsFutureSpace() {
        val original = ChartViewport()
        val zoomed = original.zoom(100f, 500f, 200)
        assertTrue(zoomed.count < original.count)
        assertEquals(original.start(200) + original.count / 2, zoomed.start(200) + zoomed.count / 2, .00001)
        val panned = original.pan(-.5f, 500f, 200)
        assertTrue(panned.offset < 0 && panned.offset > -1)
        assertEquals(120..199, original.range(200))
        val scale = PriceScale(100.0, 500.0, 0f, 400f)
        val expanded = scale.zoom(-100f, 4.0)
        assertTrue(expanded.high - expanded.low < scale.high - scale.low)
        assertEquals(300.0, (expanded.low + expanded.high) / 2)
        assertEquals(scale.y(300) + 50, scale.pan(50f).y(300), .001f)
        assertTrue(original.zoom(100000f, 500f, 200).count >= 12)
        assertTrue(original.zoom(-100000f, 500f, 200).count <= 600)
        assertTrue(original.pan(100000f, 500f, 200).range(200).first >= 0)
    }
    @Test fun wheelKeepsAFollowingChartOnTheNewestBar() {
        val following = ChartViewport(80.0, 0.0)
        val out = following.zoomAt(3f, .25f, 1_000)
        assertTrue(out.count > following.count, "Rolling towards the user shows more bars")
        assertEquals(0.0, out.offset)
        val into = following.zoomAt(-3f, .25f, 1_000)
        assertTrue(into.count < following.count)
        assertEquals(0.0, into.offset)
        assertEquals(-10.0, ChartViewport(80.0, -10.0).zoomAt(1f, .5f, 1_000).offset, "Space left after the last bar stays as it is")
    }
    @Test fun wheelKeepsTheBarUnderThePointerWhileLookingBack() {
        val before = ChartViewport(100.0, 200.0)
        for (ticks in listOf(-2f, 2f)) {
            val after = before.zoomAt(ticks, .25f, 1_000)
            assertEquals(before.start(1_000) + .25 * before.count, after.start(1_000) + .25 * after.count, .00001)
        }
    }
    @Test fun wheelStopsAtTheNewestBarAndItsLimits() {
        val after = ChartViewport(80.0, 2.0).zoomAt(10f, 0f, 1_000)
        assertEquals(0.0, after.offset, "Zooming out of the recent past does not open space after the newest bar")
        assertEquals(600.0, ChartViewport().zoomAt(1_000f, .5f, 1_000).count)
        assertEquals(12.0, ChartViewport().zoomAt(-1_000f, .5f, 1_000).count)
        val scale = PriceScale(100.0, 500.0, 0f, 400f)
        val wider = scale.zoomWheel(1f, 4.0)
        assertTrue(wider.high - wider.low > scale.high - scale.low)
        assertEquals(300.0, (wider.low + wider.high) / 2, .00001)
        assertEquals(scale.high - scale.low, scale.zoomWheel(1f, 4.0).zoomWheel(-1f, 4.0).let { it.high - it.low }, .001)
        assertEquals(4.0, scale.zoomWheel(-1_000f, 4.0).let { it.high - it.low }, .00001)
    }
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
    @Test fun reservedRoomKeepsStackedLinesClearOfNeighboursAndBounds() {
        // Middle label reserves 10px above and below: neighbours move away by the gap plus the reservation.
        assertEquals(listOf(100f, 135f, 170f), spreadLabelCenters(listOf(100f, 105f, 110f), 20f, 400f, 25f, listOf(0f, 10f, 0f), listOf(0f, 10f, 0f)))
        // Reservations also keep the stacked lines inside the axis.
        assertEquals(listOf(35f), spreadLabelCenters(listOf(10f), 20f, 400f, 25f, listOf(15f), listOf(0f)))
        assertEquals(listOf(385f), spreadLabelCenters(listOf(399f), 20f, 400f, 25f, listOf(0f), listOf(15f)))
        // Without room for the reservation the layout falls back to uniform spacing instead of overlapping.
        assertEquals(spreadLabelCenters(listOf(50f, 60f), 0f, 30f, 25f), spreadLabelCenters(listOf(50f, 60f), 0f, 30f, 25f, listOf(0f, 100f), listOf(0f, 0f)))
        assertEquals(spreadLabelCenters(listOf(100f, 105f, 110f), 20f, 200f, 25f), spreadLabelCenters(listOf(100f, 105f, 110f), 20f, 200f, 25f, listOf(0f, 0f, 0f), listOf(0f, 0f, 0f)))
    }
    @Test fun seriesChangesKeepTheBarsOnScreen() {
        fun bars(range: IntRange) = range.map { Candle(it.toLong(), 100, 100, 100, 100) }
        val moved = ChartViewport(offset = 30.0)
        assertEquals(moved, moved.following(bars(100..199), bars(0..199)), "older history lands off-screen")
        assertEquals(32.0, moved.following(bars(0..99), bars(0..101)).offset, "new bars push the view back")
        assertEquals(31.0, moved.following(bars(0..1999), bars(1..2000)).offset, "a full series drops its oldest bar")
        assertEquals(60.0, ChartViewport(offset = 20.0).following(bars(0..49) + bars(90..99), bars(0..99)).offset, "a filled gap right of the view counts")
        assertEquals(20.0, ChartViewport(offset = 20.0).following(bars(0..9) + bars(50..99), bars(0..99)).offset, "a filled gap left of the view does not")
        val latest = ChartViewport()
        assertEquals(latest, latest.following(bars(100..199), bars(0..199)))
        assertEquals(latest, latest.following(bars(0..99), bars(0..101)))
        val space = ChartViewport(offset = -5.0)
        assertEquals(space, space.following(bars(100..199), bars(0..199)))
        assertEquals(-4.0, space.following(bars(0..99), bars(0..100)).offset, "the next bar fills the space after the newest one")
        assertEquals(moved, moved.following(emptyList(), bars(0..199)))
        assertEquals(0.0, moved.following(bars(0..99), emptyList()).offset, "an emptied series cannot leave the view past its end")
    }
    @Test fun slotsContinuePastTheLoadedBars() {
        val following = ChartViewport(80.0, 0.0)
        assertEquals(120..199, following.slots(200), "a following view ends on the newest bar")
        assertEquals(160, following.slotAt(.5, 200))
        assertEquals(199, following.slotAt(1.0, 200), "the right edge belongs to the last slot")
        assertEquals(199, following.slotAt(5.0, 200), "right of the plot")
        assertEquals(120, following.slotAt(-.2, 200), "left of the plot")
        val space = ChartViewport(80.0, -10.0)
        assertEquals(130..199, space.range(200))
        assertEquals(130..209, space.slots(200), "ten empty slots follow the newest bar")
        assertEquals(199, space.slotAt(69.5 / 80, 200))
        assertEquals(200, space.slotAt(70.5 / 80, 200), "the first slot after the newest bar")
        assertEquals(209, space.slotAt(.999, 200))
        assertEquals(130..210, ChartViewport(80.0, -10.4).slots(200), "a slot cut by an edge of the plot counts")
        val short = ChartViewport(80.0, 0.0)
        assertEquals(0..29, short.range(30))
        assertEquals(-50..29, short.slots(30), "a short history leaves slots before its oldest bar")
        assertEquals(-50, short.slotAt(.001, 30))
        assertEquals(-1, short.slotAt(49.5 / 80, 30))
        assertEquals(0, short.slotAt(50.5 / 80, 30))
    }
    @Test fun slotTimesContinueOnePeriodPerSlotPastEitherEnd() {
        val bars = listOf(1_000L, 1_060L, 1_300L).map { Candle(it, 100, 100, 100, 100) }
        assertEquals(1_060L, slotTime(bars, 1, 60))
        assertEquals(1_300L, slotTime(bars, 2, 60), "a loaded bar keeps its own time after a gap")
        assertEquals(1_360L, slotTime(bars, 3, 60))
        assertEquals(1_480L, slotTime(bars, 5, 60))
        assertEquals(940L, slotTime(bars, -1, 60))
        assertNull(slotTime(emptyList(), 0, 60))
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
