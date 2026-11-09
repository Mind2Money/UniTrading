package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.unitrading.broker.Candle
import com.unitrading.chart.ChartViewport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.*

class DrawingGeometryTest {
    private val ctx = testContext()
    private val c = ctx.coords

    private fun pricePoints(vararg bars: Int) = bars.map { DrawingPoint(c.timeAt(it.toDouble()), 39_000.0 + it * 20) }

    @Test fun coordinatesRoundTripThroughBarsAndContinuePastTheLoadedBars() {
        val index = c.index(START + 40 * HOUR)
        assertEquals(40.0, index)
        assertEquals(START + 40 * HOUR, c.timeAt(index))
        assertEquals(99.0 + 2.5, c.index(START + 99 * HOUR + 2 * HOUR + HOUR / 2))
        assertEquals(-3.0, c.index(START - 3 * HOUR))
        assertEquals(START - 3 * HOUR, c.timeAt(-3.0))
        assertEquals(START + 101 * HOUR, c.timeAt(101.0))
        val point = DrawingPoint(START + 30 * HOUR, 40_200.0)
        val pixel = c.offset(point)
        assertEquals(point, c.pointAt(pixel))
    }

    @Test fun timeInsideASessionGapLandsOnTheNextBarCenterAtMost() {
        val gapped = listOf(Candle(START, 1, 1, 1, 1), Candle(START + HOUR, 1, 1, 1, 1), Candle(START + 72 * HOUR, 1, 1, 1, 1))
        val coords = testContext(gapped).coords
        assertEquals(1.0, coords.index(START + HOUR))
        assertEquals(2.0, coords.index(START + 60 * HOUR), "inside the gap")
        assertEquals(1.5, coords.index(START + HOUR + HOUR / 2))
        assertEquals(START + HOUR + HOUR / 2, coords.timeAt(1.5))
    }

    @Test fun snappingCentersAnchorsOnBarsAndFreehandKeepsTheFraction() {
        val snapped = c.pointAt(Offset(c.xOfIndex(10.4), 100f))
        assertEquals(START + 10 * HOUR, snapped.timeMs)
        val free = c.pointAt(Offset(c.xOfIndex(10.5), 100f), snapToBar = false)
        assertEquals(START + 10 * HOUR + HOUR / 2, free.timeMs)
    }

    @Test fun magnetSnapsNearAValueInWeakModeAndAlwaysInStrongMode() {
        val bar = c.candles[10]
        val time = bar.timeMs
        val near = DrawingPoint(time, bar.high + 10.0)
        assertEquals(bar.high.toDouble(), c.magnetize(near, MagnetMode.WEAK, 20f).price)
        val far = DrawingPoint(time, bar.high + 900.0)
        assertEquals(far.price, c.magnetize(far, MagnetMode.WEAK, 5f).price)
        assertEquals(bar.high.toDouble(), c.magnetize(far, MagnetMode.STRONG, 5f).price)
        assertEquals(far, c.magnetize(far, MagnetMode.OFF, 5f))
        assertEquals(far.price + 1, c.magnetize(far, MagnetMode.STRONG, 5f, listOf(far.price + 1)).price, "indicator values take part")
    }

    @Test fun extendSegmentRunsToTheViewOnTheRequestedSides() {
        val view = Rect(0f, 0f, 100f, 100f)
        val (a, b) = extendSegment(Offset(20f, 20f), Offset(40f, 40f), view, left = true, right = true)
        assertEquals(Offset(0f, 0f), a)
        assertEquals(Offset(100f, 100f), b)
        val (c1, d1) = extendSegment(Offset(20f, 20f), Offset(40f, 40f), view, left = false, right = true)
        assertEquals(Offset(20f, 20f), c1)
        assertEquals(Offset(100f, 100f), d1)
        assertEquals(Offset(5f, 5f) to Offset(5f, 5f), extendSegment(Offset(5f, 5f), Offset(5f, 5f), view, left = true, right = true))
        val (e, _) = extendSegment(Offset(50f, 10f), Offset(50f, 20f), view, left = true, right = false)
        assertEquals(Offset(50f, 0f), e, "vertical lines work")
    }

    @Test fun everyToolBuildsForEveryPlacementStepAndAfterCompletion() {
        DrawingTool.entries.forEach { tool ->
            val max = when (tool.anchors) { OPEN_ANCHORS -> 5; FREEHAND -> 12; else -> tool.anchors }
            for (count in 1..max) {
                val drawing = newDrawing(tool, "t", pricePoints(*IntArray(count) { 20 + it * 6 }))
                val scene = buildScene(drawing, ctx)
                assertNotNull(scene, "$tool with $count points")
                buildScene(drawing, ctx, expanded = true)
            }
            val complete = completeAnchors(newDrawing(tool, "t", pricePoints(*IntArray(max) { 20 + it * 6 })), ctx)
            assertTrue(buildScene(complete, ctx).parts.isNotEmpty() || tool == DrawingTool.IMAGE, "$tool draws something")
        }
    }

    @Test fun drawingOutsideTheLoadedBarsDoesNotFail() {
        DrawingTool.entries.forEach { tool ->
            val far = listOf(DrawingPoint(START - 1000 * HOUR, 10.0), DrawingPoint(START + 5000 * HOUR, 90_000.0), DrawingPoint(START + 6000 * HOUR, 200.0), DrawingPoint(START + 7000 * HOUR, 20.0),
                DrawingPoint(START + 8000 * HOUR, 40.0), DrawingPoint(START + 9000 * HOUR, 50.0), DrawingPoint(START + 9500 * HOUR, 60.0))
            val points = when (tool.anchors) { OPEN_ANCHORS, FREEHAND -> far; else -> far.take(tool.anchors) }
            buildScene(completeAnchors(newDrawing(tool, "t", points), ctx), ctx)
        }
    }

    @Test fun drawingsSurviveJson() {
        val json = Json { ignoreUnknownKeys = true }
        val list = DrawingTool.entries.map { newDrawing(it, it.name, pricePoints(1, 2)).copy(text = "x", hiddenIntervals = setOf(IntervalBucket.DAYS)) }
        assertEquals(list, json.decodeFromString<List<Drawing>>(json.encodeToString(list)))
    }

    @Test fun fibRetracementPutsLevelZeroAtTheSecondAnchor() {
        val low = DrawingPoint(c.timeAt(10.0), 39_000.0)
        val high = DrawingPoint(c.timeAt(30.0), 41_000.0)
        val scene = buildScene(newDrawing(DrawingTool.FIB_RETRACEMENT, "f", listOf(low, high)), ctx)
        val lines = scene.parts.filterIsInstance<PolyPart>().filter { it.stroke != null && it.points.size == 2 && it.points[0].y == it.points[1].y }.map { it.points[0].y }
        assertTrue(lines.any { abs(it - c.y(41_000.0)) < .5f }, "level 0 at the high")
        assertTrue(lines.any { abs(it - c.y(39_000.0 + 2000 * (1 - .618))) < .5f }, "level 0.618 retraced from the high")
        val labels = scene.parts.filterIsInstance<TextPart>().map { it.text }
        assertTrue(labels.any { it.startsWith("0.618") && it.contains("(397.64)".take(4)) || it.startsWith("0.618") })
    }

    @Test fun parallelChannelKeepsTheThirdAnchorsDistance() {
        val a = at(10, 39_000)
        val b = at(30, 40_000)
        val third = at(20, 39_000)
        assertEquals(c.y(39_000.0) - (a.y + (b.y - a.y) / 2), channelOffset(a, b, third), .01f)
    }

    @Test fun regressionOfAStraightLineFitsExactly() {
        val fit = assertNotNull(regression(List(11) { 100.0 + it * 3 }, 5))
        assertEquals(3.0, fit.slope, 1e-9)
        assertEquals(100.0, fit.value(5), 1e-9)
        assertEquals(0.0, fit.deviation, 1e-9)
        assertEquals(1.0, fit.pearson, 1e-9)
        assertNull(regression(listOf(1.0), 0))
    }

    @Test fun volumeProfileKeepsTheTotalAndFindsThePointOfControl() {
        val bars = listOf(Candle(0, 100, 200, 100, 200, 1000), Candle(1, 200, 200, 100, 100, 1000), Candle(2, 150, 150, 150, 150, 5000))
        val profile = assertNotNull(computeVolumeProfile(bars, 0, 2, 10, 70.0))
        assertEquals(7000.0, profile.rows.sumOf { it.total }, 1e-6)
        assertEquals(5, profile.pocIndex, "the bar that traded in one spot holds the most")
        assertEquals(6000.0, profile.rows.sumOf { it.up }, 1e-6, "a bar that did not fall counts as up")
        assertTrue(profile.rows.slice(profile.valueArea).sumOf { it.total } >= 7000 * .7)
        assertNull(computeVolumeProfile(listOf(Candle(0, 1, 2, 1, 2, null)), 0, 0, 5, 70.0), "no volume, no profile")
    }

    @Test fun positionOutcomeFollowsTheBarsFromTheEntry() {
        val bars = listOf(Candle(0, 100, 110, 95, 105, 1), Candle(1, 105, 130, 100, 125, 1), Candle(2, 125, 126, 90, 95, 1))
        val target = evaluatePosition(bars, 0, 2, long = true, entry = 100.0, target = 120.0, stop = 85.0)
        assertEquals(120.0, target.exitPrice)
        assertTrue(target.closed)
        assertEquals(1, target.exitIndex)
        val bothInOneBar = evaluatePosition(listOf(Candle(0, 100, 130, 80, 100, 1)), 0, 0, long = true, entry = 100.0, target = 120.0, stop = 90.0)
        assertEquals(90.0, bothInOneBar.exitPrice, "the stop counts first")
        val open = evaluatePosition(bars, 0, 1, long = true, entry = 100.0, target = 500.0, stop = 10.0)
        assertFalse(open.closed)
        assertEquals(125.0, open.exitPrice)
        assertFalse(evaluatePosition(bars, 0, 2, long = true, entry = 500.0, target = 600.0, stop = 400.0).filled, "price never reached the entry")
        val short = evaluatePosition(bars, 1, 2, long = false, entry = 120.0, target = 100.0, stop = 140.0)
        assertEquals(100.0, short.exitPrice)
    }

    @Test fun forecastSucceedsWhenTheTargetIsReachedInTime() {
        val bars = listOf(Candle(0, 100, 110, 95, 105, 1), Candle(10, 105, 130, 100, 125, 1))
        assertEquals(ForecastStatus.SUCCESS, forecastStatus(bars, 0, 1, 100.0, 120.0, 10))
        assertEquals(ForecastStatus.FAILURE, forecastStatus(bars, 0, 1, 100.0, 200.0, 10))
        assertEquals(ForecastStatus.WAITING, forecastStatus(bars, 0, 1, 100.0, 200.0, 99))
        assertEquals(ForecastStatus.SUCCESS, forecastStatus(bars, 0, 1, 100.0, 96.0, 10), "a falling target looks at the lows")
    }

    @Test fun ghostBarsWalkThroughTheAnchorsAndRepeatFrameToFrame() {
        val bars = ghostBars(listOf(0.0 to 100.0, 4.0 to 140.0), 10.0, 50.0, 7)
        assertEquals(4, bars.size)
        assertEquals(100.0, bars.first().open)
        assertEquals(140.0, bars.last().close, 1e-9)
        bars.zipWithNext().forEach { (one, two) -> assertEquals(one.close, two.open, 1e-9) }
        assertTrue(bars.all { it.high >= maxOf(it.open, it.close) && it.low <= minOf(it.open, it.close) })
        assertEquals(bars.map { it.high }, ghostBars(listOf(0.0 to 100.0, 4.0 to 140.0), 10.0, 50.0, 7).map { it.high })
    }

    @Test fun hitTestingFollowsWhatIsPainted() {
        val line = buildScene(newDrawing(DrawingTool.TREND_LINE, "l", pricePoints(10, 60)), ctx)
        val middle = mid(line.handles[0].position, line.handles[1].position)
        assertTrue(line.hits(middle, 4f))
        assertTrue(line.hits(middle + Offset(0f, 3f), 4f))
        assertFalse(line.hits(middle + Offset(0f, 30f), 4f))
        val rect = buildScene(newDrawing(DrawingTool.RECTANGLE, "r", listOf(DrawingPoint(c.timeAt(10.0), 39_000.0), DrawingPoint(c.timeAt(30.0), 40_000.0))), ctx)
        assertTrue(rect.hits(at(20, 39_500), 4f), "a filled shape is grabbable inside")
        assertFalse(rect.hits(at(40, 39_500), 4f))
        assertEquals(rect.handles[0], rect.handleAt(at(10, 39_000) + Offset(3f, 0f), 6f))
        assertNull(rect.handleAt(at(20, 39_500), 6f))
    }

    @Test fun rectangleHandlesEditOneSideAtATime() {
        val a = DrawingPoint(c.timeAt(10.0), 39_000.0)
        val b = DrawingPoint(c.timeAt(30.0), 40_000.0)
        val d = newDrawing(DrawingTool.RECTANGLE, "r", listOf(a, b))
        val target = DrawingPoint(c.timeAt(5.0), 38_500.0)
        assertEquals(listOf(a.copy(price = target.price), b), moveHandle(d, 4, target, ctx).points, "top edge")
        assertEquals(listOf(a, b.copy(timeMs = target.timeMs)), moveHandle(d, 7, target, ctx).points, "right edge")
        assertEquals(listOf(a.copy(price = target.price), b.copy(timeMs = target.timeMs)), moveHandle(d, 2, target, ctx).points, "corner")
    }

    @Test fun positionEntryDragCarriesItsLevelsAndLevelsStayOnTheirSide() {
        val placed = completeAnchors(newDrawing(DrawingTool.LONG_POSITION, "p", listOf(DrawingPoint(c.timeAt(10.0), 40_000.0))), ctx)
        assertEquals(4, placed.points.size)
        assertTrue(placed.points[2].price > 40_000 && placed.points[3].price < 40_000)
        assertEquals(c.timeAt(34.0), placed.points[1].timeMs)
        val moved = moveHandle(placed, 0, DrawingPoint(c.timeAt(12.0), 40_500.0), ctx)
        assertEquals(placed.points[2].price + 500, moved.points[2].price)
        assertEquals(placed.points[3].price + 500, moved.points[3].price)
        assertEquals(placed.points[0].price, moveHandle(placed, 2, DrawingPoint(c.timeAt(12.0), 30_000.0), ctx).points[2].price, "the target cannot cross the entry")
        assertEquals(placed.points[0].price, moveHandle(placed, 3, DrawingPoint(c.timeAt(12.0), 50_000.0), ctx).points[3].price, "nor the stop")
        val short = completeAnchors(newDrawing(DrawingTool.SHORT_POSITION, "s", listOf(DrawingPoint(c.timeAt(10.0), 40_000.0))), ctx)
        assertTrue(short.points[2].price < 40_000 && short.points[3].price > 40_000)
    }

    @Test fun translateMovesByWholeBarsAndPrice() {
        val d = newDrawing(DrawingTool.TREND_LINE, "l", listOf(DrawingPoint(c.timeAt(10.0), 39_000.0), DrawingPoint(c.timeAt(30.0), 40_000.0)))
        assertEquals(listOf(DrawingPoint(c.timeAt(13.0), 39_100.0), DrawingPoint(c.timeAt(33.0), 40_100.0)), translate(d, 3.0, 100.0, Offset.Zero, ctx).points)
        val table = newDrawing(DrawingTool.TABLE, "t", emptyList())
        assertEquals(.35, translate(table, 0.0, 0.0, Offset(100f, 0f), ctx).param("screenX"), 1e-6)
    }

    @Test fun curvesGetControlPointsAndTheGannSquareItsRatio() {
        assertEquals(3, completeAnchors(newDrawing(DrawingTool.CURVE, "c", pricePoints(10, 30)), ctx).points.size)
        assertEquals(4, completeAnchors(newDrawing(DrawingTool.DOUBLE_CURVE, "c", pricePoints(10, 30)), ctx).points.size)
        val bars = completeAnchors(newDrawing(DrawingTool.BARS_PATTERN, "b", pricePoints(10, 30)), ctx)
        assertEquals(c.timeAt(31.0), bars.points[2].timeMs)
        val square = completeAnchors(newDrawing(DrawingTool.GANN_SQUARE, "g", listOf(DrawingPoint(c.timeAt(10.0), 39_000.0), DrawingPoint(c.timeAt(30.0), 40_000.0))), ctx)
        assertEquals(50.0, square.param("scaleRatio"), 1e-9)
        val dragged = moveHandle(square, 1, DrawingPoint(c.timeAt(40.0), 39_500.0), ctx)
        assertEquals(39_000.0 + 30 * 50, dragged.points[1].price, 1e-9, "the far corner stays on the ratio")
    }

    @Test fun elliottDegreesWriteTheirLabels() {
        assertEquals("(1)", elliottLabel("1", ElliottDegree.INTERMEDIATE.style))
        assertEquals("(A)", elliottLabel("A", ElliottDegree.INTERMEDIATE.style))
        assertEquals("I", elliottLabel("1", ElliottDegree.CYCLE.style))
        assertEquals("①", elliottLabel("1", ElliottDegree.PRIMARY.style))
        assertEquals("((II))", elliottLabel("2", ElliottDegree.GRAND_SUPERCYCLE.style))
        assertEquals("(iii)", elliottLabel("3", ElliottDegree.MINUETTE.style))
    }

    @Test fun zoomedViewportShowsTheAskedBars() {
        val view = com.unitrading.chart.drawing.zoomedViewport(20.0, 49.0, 100)
        assertEquals(30.0, view.count)
        assertEquals(19.5, view.start(100), 1e-9)
    }

    @Test fun ratiosPrintWithoutTrailingZeros() {
        assertEquals("0.236", formatRatio(.236))
        assertEquals("1", formatRatio(1.0))
        assertEquals("2.5", formatRatio(2.5))
        assertEquals("−0.5", formatRatio(-.5))
        assertEquals("3天 4小时", formatSpan(76 * HOUR))
        assertEquals("45分钟", formatSpan(45 * 60_000))
        assertEquals("0.5", ChartViewport().let { formatRatio(.5) })
    }
}
