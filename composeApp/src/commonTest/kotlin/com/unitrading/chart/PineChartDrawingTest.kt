package com.unitrading.chart

import com.unitrading.broker.Candle
import com.unitrading.chart.pine.PineDrawing
import com.unitrading.chart.pine.PineExtend
import com.unitrading.chart.pine.PineFill
import com.unitrading.chart.pine.PineLevel
import com.unitrading.chart.pine.PineLocation
import com.unitrading.chart.pine.PineMark
import com.unitrading.chart.pine.PinePlot
import com.unitrading.chart.pine.PinePlotStyle
import com.unitrading.chart.pine.PineResult
import com.unitrading.chart.pine.PineShape
import com.unitrading.chart.pine.PineStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PineChartDrawingTest {
    private val blue = 0xFF3684FA.toInt()
    private val transparent = 0x003684FA
    private val period = 60_000L
    private val start = 1_700_000_000_000L
    private val bars = listOf(0L, 1L, 2880L).mapIndexed { i, offset ->
        Candle(start + offset * period, 10_000, 10_324L + 50L * i, 9_876L - 25L * i, 10_001)
    }

    @Test fun timeCoordinatesCollapseSessionGapsAndExtrapolateByPeriod() {
        fun at(minutes: Double) = pineBarIndex(start + minutes * period, true, bars, period)
        assertEquals(-1.25, at(-1.25))
        assertEquals(0.0, at(0.0))
        assertEquals(0.5, at(.5))
        assertEquals(1.5, at(1.5))
        assertEquals(2.0, at(2000.0))
        assertEquals(2.0, at(2880.0))
        assertEquals(4.25, at(2882.25))
        assertEquals(-5.25, pineBarIndex(-5.25, false, emptyList(), 0L))
        assertNull(pineBarIndex(start.toDouble(), true, bars, 0L))
        assertNull(pineBarIndex(start.toDouble(), true, emptyList(), period))
        assertNull(pineBarIndex(Double.POSITIVE_INFINITY, false, bars, period))
        assertNull(pineBarIndex(1e40, true, bars, period))
    }

    @Test fun shiftingUsesTargetSlotsEvenWhenTheSourceIsOffScreen() {
        val plots = listOf(
            plot(1, doubleArrayOf(90.0, 17.25, 25.5), offset = 4),
            plot(2, doubleArrayOf(50.0, 39.0, -2.75), offset = -4),
        )
        val result = result(plots = plots)
        assertEquals(17.25..25.5, bounds(result, IntRange.EMPTY, 5..6))
        assertEquals(-2.75..-2.75, bounds(result, IntRange.EMPTY, -2..-2))
        assertNull(bounds(result, 0..2, 0..2))
        val extremeOffsets = result.copy(plots = listOf(plots[0].copy(offset = Int.MAX_VALUE), plots[1].copy(offset = Int.MIN_VALUE)))
        assertEquals(90.0..90.0, bounds(extremeOffsets, IntRange.EMPTY, Int.MAX_VALUE..Int.MAX_VALUE))
        assertEquals(50.0..50.0, bounds(extremeOffsets, IntRange.EMPTY, Int.MIN_VALUE..Int.MIN_VALUE))
    }

    @Test fun onlyPaintedValuesAndTheirColumnBaselinesAffectPaneBounds() {
        val histogram = plot(1, doubleArrayOf(1.25, -500.0, Double.NaN), style = PinePlotStyle.HISTOGRAM, baseline = -.75,
            colors = arrayOf(blue, transparent, blue))
        val columns = plot(2, doubleArrayOf(.75, 800.0, 900.0), style = PinePlotStyle.COLUMNS, baseline = 3.5,
            colors = arrayOf(blue, null, transparent))
        val clipped = plot(3, doubleArrayOf(Double.POSITIVE_INFINITY, 3_000.0), colors = arrayOf(blue, null))
        val hidden = plot(4, doubleArrayOf(2_000.0), displayed = false)
        val result = result(plots = listOf(histogram, columns, clipped, hidden), levels = listOf(
            level(7, -100.0, null), level(8, -200.0, transparent), level(9, -.25, blue), level(10, 500.0, blue, displayed = false),
        ))
        assertEquals(-.75..3.5, bounds(result, 0..2, 0..2))
        assertEquals(-.25..-.25, bounds(result, 1..2, 1..2))
        assertNull(bounds(result.copy(levels = emptyList()), 1..2, 1..2))
    }

    @Test fun visibleLineSegmentDoesNotScaleToOutsidePricesAndLineBreakDoesNotBridgeNa() {
        val continuous = plot(2, doubleArrayOf(0.0, Double.NaN, 20.0), style = PinePlotStyle.LINE)
        assertEquals(5.0..15.0, bounds(result(plots = listOf(continuous)), 1..1, 1..1))
        assertNull(bounds(result(plots = listOf(continuous.copy(style = PinePlotStyle.LINE_BREAK))), 1..1, 1..1))
        val transparentMiddle = continuous.copy(values = doubleArrayOf(0.0, 10.0, 20.0), colors = arrayOf(blue, null, blue))
        assertNull(bounds(result(plots = listOf(transparentMiddle)), 1..1, 1..1))
    }

    @Test fun fillUsesIndependentPlotOffsetsAndTargetColorWithoutCrossingNa() {
        val a = plot(1, doubleArrayOf(10.0, 20.0, Double.NaN), offset = 1, displayed = false)
        val b = plot(2, doubleArrayOf(-500.0, -5.0, 0.0, 9.0), displayed = false, colors = arrayOfNulls(4))
        val fill = PineFill(1, 2, arrayOf(null, blue, blue, blue), false)
        val result = result(plots = listOf(a, b), fills = listOf(fill))
        assertEquals(-5.0..20.0, bounds(result, 0..2, 1..2))
        assertEquals(-2.5..20.0, bounds(result, 2..2, 2..2))
        assertNull(bounds(result, IntRange.EMPTY, 3..3))
        assertNull(bounds(result.copy(fills = listOf(fill.copy(colors = arrayOf(blue, null, transparent, blue)))), 0..2, 1..2))
        val levelBand = result(plots = emptyList(), levels = listOf(level(3, -9.0, null), level(4, 7.5, null)),
            fills = listOf(PineFill(3, 4, arrayOf(blue, blue, blue), false)))
        assertEquals(-9.0..7.5, bounds(levelBand, 1..1, 1..1))
    }

    @Test fun crossingObjectsAreNotCulledByAnchorsAndExtensionsRespectDirection() {
        val cross = line(-100.0, -100.0, 100.0, 100.0)
        val future = line(4.0, 4.0, 10.0, 10.0)
        val vertical = line(1.0, -6.0, 1.0, 4.0, PineExtend.BOTH)
        val viewport = 0..2
        fun lineBounds(value: PineDrawing.Line) = bounds(result(drawings = listOf(value)), viewport, viewport)
        assertBounds(-.5, 2.5, lineBounds(cross))
        assertNull(lineBounds(future))
        assertNull(lineBounds(future.copy(extend = PineExtend.RIGHT)))
        assertBounds(-.5, 2.5, lineBounds(future.copy(extend = PineExtend.LEFT)))
        assertBounds(-.5, 2.5, lineBounds(line(10.0, 10.0, 4.0, 4.0, PineExtend.LEFT)))
        assertEquals(-6.0..4.0, lineBounds(vertical))
        val box = PineDrawing.Box(-99.0, 9.0, 99.0, -3.0, false, PineExtend.NONE, null, PineStroke.DASHED, 0f, blue, "", null, false)
        assertEquals(-3.0..9.0, bounds(result(drawings = listOf(box)), viewport, viewport))
        val offscreen = box.copy(left = 4.0, right = 7.0, extend = PineExtend.LEFT)
        assertNull(bounds(result(drawings = listOf(offscreen)), viewport, viewport), "only borders extend")
        assertEquals(-3.0..9.0, bounds(result(drawings = listOf(offscreen.copy(border = blue, borderWidth = 1f))), viewport, viewport))
    }

    @Test fun labelsAndMarksContributeOnlyIfAbsoluteAndHorizontallyVisible() {
        val mark = PineMark(1, -1_000.0, PineLocation.TOP, PineShape.CIRCLE, blue, "", null, 10f, false)
        val label = PineDrawing.Label(start + 1.5 * period, 103.24, true, PineLocation.ABSOLUTE, PineShape.LABEL_UP, blue, "P", null, 10f, true)
        val result = result(marks = listOf(mark, mark.copy(location = PineLocation.ABSOLUTE, value = 17.75)), drawings = listOf(
            label, label.copy(x = start + 2900.0 * period, y = 5_000.0), label.copy(location = PineLocation.BELOW_BAR, y = -2_000.0),
            label.copy(color = transparent, y = -3_000.0),
        ))
        assertEquals(17.75..103.24, bounds(result, 0..2, 0..2))
        assertNull(bounds(result, 0..0, 0..0))
        assertEquals(5_000.0..5_000.0, bounds(result, IntRange.EMPTY, 22..22))
        assertEquals(103.24, pineLabelValue(label, 1.0, bars, 100.0), "absolute labels are already in raw price units")
        assertEquals(103.74, pineLabelValue(label.copy(location = PineLocation.ABOVE_BAR), 1.0, bars, 100.0))
        assertEquals(98.51, pineLabelValue(label.copy(location = PineLocation.BELOW_BAR), 1.0, bars, 100.0))
        assertEquals(103.24, pineLabelValue(label.copy(location = PineLocation.BELOW_BAR, onPrice = false), 1.0, bars, 100.0))
        assertTrue(pineLabelValue(label.copy(location = PineLocation.ABOVE_BAR), 10.0, bars, 100.0).isNaN())
        assertTrue(pineLabelValue(label.copy(location = PineLocation.ABOVE_BAR), 0.0, bars, 0.0).isNaN())
    }

    @Test fun timeAnchorsUseTheFullCandlesBeforeEnteringReplaySpace() {
        val label = PineDrawing.Label(start + 2.0 * period, 15.25, true, PineLocation.ABSOLUTE, PineShape.LABEL_UP, blue, "", null, 10f, true)
        assertEquals(15.25..15.25, pineValueBounds(result(drawings = listOf(label)), 0..0, 0..0, bars, period, barOrigin = 2))
        assertNull(pineValueBounds(result(drawings = listOf(label)), 0..0, 0..0, bars.drop(2), period))
        assertEquals(104.24, pineLabelValue(label.copy(location = PineLocation.ABOVE_BAR), 0.0, bars, 100.0, barOrigin = 2))
        assertEquals(103.74, pineLabelValue(label.copy(location = PineLocation.ABOVE_BAR, byTime = false), -1.0, bars, 100.0, barOrigin = 2))
    }

    @Test fun sortedMarksIncludeEveryDuplicateAtTheVisibleWindowEdges() {
        fun mark(index: Int, value: Double) = PineMark(index, value, PineLocation.ABSOLUTE, PineShape.CIRCLE, blue, "", null, 10f, false)
        val marks = listOf(mark(Int.MIN_VALUE, 80.0), mark(-2, 200.0), mark(-1, -5.0), mark(-1, -6.0),
            mark(1, 2.0), mark(1, 3.0), mark(2, 100.0), mark(Int.MAX_VALUE, 25.0))
        assertEquals(-6.0..3.0, bounds(result(marks = marks), 0..1, -1..1))
        assertEquals(25.0..25.0, bounds(result(marks = marks), IntRange.EMPTY, Int.MAX_VALUE..Int.MAX_VALUE))
    }

    @Test fun sharedTextBudgetStopsBeforeAdditionalLayoutWork() {
        val budget = PineTextBudget(2)
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertTrue(budget.exhausted)
        assertEquals(false, budget.take())
        assertEquals(false, PineTextBudget(-1).take())
    }

    @Test fun geometryBudgetChargesCandidateWorkWithoutChangingRawBounds() {
        val budget = PineGeometryBudget(5)
        assertTrue(budget.take(3))
        assertFalse(budget.take(3), "compound items do not partly consume the remaining work")
        assertEquals(2, budget.remaining)
        assertFalse(budget.take(-10))
        assertTrue(budget.take(2))
        assertTrue(budget.exhausted)
        assertFalse(budget.take())
        assertTrue(PineGeometryBudget(-1).exhausted)
        val result = result(plots = listOf(plot(1, doubleArrayOf(1.25, 30.0), style = PinePlotStyle.COLUMNS, baseline = 0.0)))
        assertEquals(0.0..30.0, bounds(result, 0..1, 0..1), "the paint cap does not shrink the raw range")
    }

    @Test fun onlyFiniteNonpositiveBaselinesOutsideTheScaleUseTheProvidedLogEdge() {
        val logY: (Double) -> Float = { if (it > 0) 70f else Float.NaN }
        assertEquals(75f, pineBaselineY(0.0, 75f, logY))
        assertEquals(5f, pineBaselineY(-10.0, 5f, logY), "an inverted scale can put the low edge at the top")
        assertEquals(70f, pineBaselineY(10.0, 75f, logY))
        assertEquals(15f, pineBaselineY(0.0, 75f) { 15f }, "prefer a valid projection over the fallback")
        assertTrue(pineBaselineY(0.0, Float.NaN, logY).isNaN())
        assertTrue(pineBaselineY(10.0, 75f) { Float.POSITIVE_INFINITY }.isNaN())
        assertTrue(pineBaselineY(Double.NaN, 75f, logY).isNaN())
        assertTrue(pineBaselineY(Double.NEGATIVE_INFINITY, 75f, logY).isNaN())
    }

    private fun plot(
        id: Int, values: DoubleArray, offset: Int = 0, style: PinePlotStyle = PinePlotStyle.CIRCLES, baseline: Double = 0.0,
        colors: Array<Int?> = Array(values.size) { blue }, displayed: Boolean = true,
    ) = PinePlot(id, "", values, colors, style, 1f, offset, baseline, false, displayed)

    private fun level(id: Int, value: Double, color: Int?, displayed: Boolean = true) =
        PineLevel(id, "", value, color, PineStroke.SOLID, 1f, false, displayed)

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, extend: PineExtend = PineExtend.NONE) =
        PineDrawing.Line(x1, y1, x2, y2, false, extend, blue, PineStroke.SOLID, 1f, false)

    private fun result(
        plots: List<PinePlot> = emptyList(), levels: List<PineLevel> = emptyList(), fills: List<PineFill> = emptyList(),
        drawings: List<PineDrawing> = emptyList(), marks: List<PineMark> = emptyList(),
    ) = PineResult("", false, plots, levels, fills, marks, drawings, emptyArray(), emptyArray())

    private fun bounds(result: PineResult, range: IntRange, slots: IntRange) = pineValueBounds(result, range, slots, bars, period)

    private fun assertBounds(low: Double, high: Double, bounds: ClosedFloatingPointRange<Double>?) {
        val actual = assertNotNull(bounds)
        assertEquals(low, actual.start, 1e-9)
        assertEquals(high, actual.endInclusive, 1e-9)
    }
}
