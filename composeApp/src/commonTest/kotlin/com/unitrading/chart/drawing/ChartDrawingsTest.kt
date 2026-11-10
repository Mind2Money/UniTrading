package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class ChartDrawingsTest {
    private val ctx = testContext()
    private val store = MemorySnapshotStore()
    private val drawings = ChartDrawings(store, Dispatchers.Unconfined).apply { bind("TEST") }
    private var clock = 10_000L

    private fun info(point: Offset, pressed: Boolean = true, ctrl: Boolean = false, shift: Boolean = false, touch: Boolean = false): PointerInfo {
        clock += 1000
        return PointerInfo(point, ctrl, shift, touch, pressed, clock)
    }
    private fun down(point: Offset, ctrl: Boolean = false, shift: Boolean = false, lines: Boolean = false) = drawings.pointerDown(info(point, ctrl = ctrl, shift = shift), ctx, emptyList()) { lines }
    private fun move(point: Offset, pressed: Boolean = false) = drawings.pointerMove(info(point, pressed = pressed), ctx, emptyList())
    private fun up(point: Offset, zoom: (Double, Double) -> Unit = { _, _ -> }) = drawings.pointerUp(info(point, pressed = false), ctx, emptyList(), zoom)
    private fun click(point: Offset, ctrl: Boolean = false) { down(point, ctrl = ctrl); up(point) }
    /** Renders a frame, so hit testing sees the current drawings. */
    private fun frame() = drawings.frame(ctx)

    private fun line(from: Offset, to: Offset, tool: DrawingTool = DrawingTool.TREND_LINE) { drawings.chooseTool(tool); click(from); move(to); click(to); frame() }

    @Test fun twoClicksPlaceATrendLineAndDisarmTheTool() {
        drawings.chooseTool(DrawingTool.TREND_LINE)
        val a = at(10, 39_000)
        val b = at(30, 40_000)
        assertTrue(down(a))
        up(a)
        assertEquals(0, drawings.drawings.size, "the first click only starts it")
        move(b)
        assertEquals(c(b), drawings.draft!!.points[1], "the preview follows the mouse")
        assertTrue(down(b))
        up(b)
        assertEquals(1, drawings.drawings.size)
        assertEquals(listOf(c(a), c(b)), drawings.drawings[0].points)
        assertNull(drawings.tool)
        assertEquals(drawings.drawings[0].id, drawings.selectedId)
    }
    private fun c(point: Offset) = ctx.coords.pointAt(point)

    @Test fun pressDragReleaseDrawsTwoAnchorsInOneMotion() {
        drawings.chooseTool(DrawingTool.RECTANGLE)
        val a = at(10, 39_000)
        val b = at(30, 40_000)
        down(a)
        move(b, pressed = true)
        up(b)
        assertEquals(1, drawings.drawings.size)
        assertEquals(c(b), drawings.drawings[0].points[1])
    }

    @Test fun threePointToolsTakeThreeClicks() {
        drawings.chooseTool(DrawingTool.PARALLEL_CHANNEL)
        click(at(10, 39_000)); click(at(30, 40_000))
        assertEquals(0, drawings.drawings.size)
        assertNotNull(drawings.draft)
        click(at(20, 39_000))
        assertEquals(3, drawings.drawings.single().points.size)
    }

    @Test fun oneClickToolsFinishAtOnceAndPositionsGetTheirLevels() {
        drawings.chooseTool(DrawingTool.HORIZONTAL_LINE)
        click(at(50, 39_500))
        assertEquals(1, drawings.drawings.single().points.size)
        drawings.chooseTool(DrawingTool.LONG_POSITION)
        click(at(50, 39_500))
        assertEquals(4, drawings.drawings.last().points.size)
    }

    @Test fun keepDrawingStaysArmed() {
        drawings.keepDrawing = true
        drawings.chooseTool(DrawingTool.VERTICAL_LINE)
        click(at(10, 39_000)); click(at(20, 39_000))
        assertEquals(2, drawings.drawings.size)
        assertEquals(DrawingTool.VERTICAL_LINE, drawings.tool)
        assertTrue(drawings.escape())
        assertNull(drawings.tool)
    }

    @Test fun anOpenPathEndsOnDoubleClickWithoutItsRubberBand() {
        drawings.chooseTool(DrawingTool.PATH)
        click(at(10, 39_000)); click(at(20, 40_000)); click(at(30, 39_000))
        assertEquals(0, drawings.drawings.size)
        val end = at(40, 40_000)
        drawings.pointerDown(PointerInfo(end, false, false, false, true, 50_000), ctx, emptyList()) { false }
        drawings.pointerUp(PointerInfo(end, false, false, false, false, 50_010), ctx, emptyList()) { _, _ -> }
        drawings.pointerDown(PointerInfo(end, false, false, false, true, 50_100), ctx, emptyList()) { false }
        assertEquals(4, drawings.drawings.single().points.size)
    }

    @Test fun aPolylineClosesOnItsFirstPoint() {
        drawings.chooseTool(DrawingTool.POLYLINE)
        val first = at(10, 39_000)
        click(first); click(at(20, 40_000)); click(at(30, 39_000))
        click(first + Offset(2f, 2f))
        val shape = drawings.drawings.single()
        assertEquals(3, shape.points.size)
        assertEquals(1.0, shape.param("closed"))
    }

    @Test fun enterFinishesAnOpenTool() {
        drawings.chooseTool(DrawingTool.GHOST_FEED)
        click(at(10, 39_000)); click(at(20, 40_000))
        assertTrue(drawings.finishOpen(ctx))
        assertEquals(2, drawings.drawings.single().points.size)
    }

    @Test fun aFreehandStrokeKeepsItsSamples() {
        drawings.chooseTool(DrawingTool.BRUSH)
        down(at(10, 39_000))
        (11..20).forEach { move(at(it, 39_000 + it * 10L), pressed = true) }
        up(at(20, 39_200))
        assertTrue(drawings.drawings.single().points.size >= 10)
    }

    @Test fun textToolsAskForTextAndADismissedEmptyNoteGoesAway() {
        drawings.chooseTool(DrawingTool.TEXT)
        click(at(10, 39_000))
        val id = assertNotNull(drawings.textEditId)
        assertEquals(id, drawings.drawings.single().id)
        drawings.closeText(cancelled = true)
        assertTrue(drawings.drawings.isEmpty())
    }

    @Test fun measureLeavesAReadoutThatTheNextClickClears() {
        drawings.chooseTool(DrawingTool.MEASURE)
        click(at(10, 39_000)); click(at(30, 40_000))
        assertTrue(drawings.drawings.isEmpty())
        assertNotNull(drawings.measured)
        assertFalse(down(at(60, 39_000)))
        assertNull(drawings.measured)
    }

    @Test fun shiftStartsAMeasurement() {
        val a = at(10, 39_000)
        assertTrue(down(a, shift = true))
        up(a)
        assertEquals(DrawingTool.MEASURE, drawings.tool)
    }

    @Test fun pressingEmptyChartPassesThroughAndDeselects() {
        line(at(10, 39_000), at(30, 40_000))
        assertNotNull(drawings.selectedId)
        assertFalse(down(at(80, 41_500)), "the chart keeps its pan")
        assertNull(drawings.selectedId)
    }

    @Test fun pressesOutsideThePlotAreIgnored() {
        drawings.chooseTool(DrawingTool.TREND_LINE)
        assertFalse(down(Offset(1500f, 100f)))
        assertNull(drawings.draft)
    }

    @Test fun draggingABodyMovesTheWholeDrawingWithUndoAndRedo() {
        val a = at(10, 39_000)
        val b = at(30, 40_000)
        line(a, b)
        val before = drawings.drawings.single().points
        val grab = at(20, 39_500)
        assertTrue(down(grab))
        assertTrue(move(grab + Offset(ctx.coords.step * 5, 0f), pressed = true))
        up(grab)
        val moved = drawings.drawings.single().points
        assertEquals(before.map { ctx.coords.timeAt(ctx.coords.index(it.timeMs) + 5) }, moved.map { it.timeMs })
        drawings.undo()
        assertEquals(before, drawings.drawings.single().points)
        drawings.redo()
        assertEquals(moved, drawings.drawings.single().points)
    }

    @Test fun draggingAHandleMovesOneAnchor() {
        val a = at(10, 39_000)
        val b = at(30, 40_000)
        line(a, b)
        down(b)
        val target = at(40, 39_000)
        move(target, pressed = true)
        up(target)
        assertEquals(listOf(c(a), c(target)), drawings.drawings.single().points)
    }

    @Test fun lockedDrawingsDoNotMoveAndLetTheChartPan() {
        line(at(10, 39_000), at(30, 40_000))
        drawings.lockDrawings = true
        frame()
        assertFalse(down(at(20, 39_500)))
        assertEquals(drawings.drawings.single().id, drawings.selectedId, "still selectable")
        drawings.lockDrawings = false
        drawings.toggleLock(drawings.drawings.single().id)
        frame()
        assertFalse(down(at(20, 39_500)))
    }

    @Test fun anOrderLineUnderADrawingStaysReachable() {
        line(at(10, 39_000), at(30, 40_000))
        assertFalse(down(at(20, 39_500), lines = true))
        assertTrue(down(at(30, 40_000), lines = true), "a selected drawing's handles win")
    }

    @Test fun ctrlDragClonesAndCtrlClickDoesNot() {
        line(at(10, 39_000), at(30, 40_000))
        val grab = at(20, 39_500)
        down(grab, ctrl = true)
        up(grab)
        assertEquals(1, drawings.drawings.size)
        frame()
        down(grab, ctrl = true)
        move(grab + Offset(50f, 0f), pressed = true)
        up(grab)
        assertEquals(2, drawings.drawings.size)
    }

    @Test fun theEraserRemovesWhatItTouches() {
        line(at(10, 39_000), at(30, 40_000))
        drawings.chooseCursor(ChartCursor.ERASER)
        frame()
        assertFalse(down(at(80, 41_500)))
        assertEquals(1, drawings.drawings.size)
        assertTrue(down(at(20, 39_500)))
        assertTrue(drawings.drawings.isEmpty())
    }

    @Test fun removeAllKeepsLockedDrawingsUnlessAskedNotTo() {
        line(at(10, 39_000), at(30, 40_000))
        line(at(40, 39_000), at(50, 40_000))
        drawings.toggleLock(drawings.drawings[0].id)
        drawings.removeAllDrawings()
        assertEquals(1, drawings.drawings.size)
        drawings.removeLockedToo = true
        drawings.removeAllDrawings()
        assertTrue(drawings.drawings.isEmpty())
    }

    @Test fun hiddenDrawingsAreNeitherPaintedNorHit() {
        line(at(10, 39_000), at(30, 40_000))
        drawings.hideDrawings = true
        assertTrue(frame().scenes.isEmpty())
        drawings.select(null)
        assertFalse(down(at(20, 39_500)))
        drawings.hideDrawings = false
        drawings.update(drawings.drawings.single().copy(hiddenIntervals = setOf(IntervalBucket.HOURS)))
        assertTrue(frame().scenes.isEmpty())
        assertEquals(1, drawings.frame(testContext(timeframe = Timeframe.ofMinutes(5))).scenes.size, "still on the minute charts")
        assertEquals(1, drawings.frame(testContext(timeframe = Timeframe.ofSeconds(30))).scenes.size, "and on the second charts")
        drawings.update(drawings.drawings.single().copy(hiddenIntervals = setOf(IntervalBucket.SECONDS)))
        assertTrue(drawings.frame(testContext(timeframe = Timeframe.ofSeconds(30))).scenes.isEmpty())
        assertTrue(drawings.frame(testContext(timeframe = Timeframe.ofSeconds(90))).scenes.isEmpty(), "ninety seconds are a second interval")
        assertEquals(1, drawings.frame(testContext(timeframe = Timeframe.M1)).scenes.size)
        assertEquals(listOf(IntervalBucket.MINUTES, IntervalBucket.HOURS, IntervalBucket.HOURS, IntervalBucket.DAYS),
            listOf(Timeframe.ofMinutes(59), Timeframe.H1, Timeframe.ofMinutes(1439), Timeframe.D1).map(IntervalBucket::of))
    }

    @Test fun strongMagnetPlacesAnchorsOnBarValuesAndCtrlFlipsIt() {
        drawings.magnet = MagnetMode.STRONG
        drawings.chooseTool(DrawingTool.HORIZONTAL_LINE)
        val bar = ctx.coords.candles[20]
        click(at(20, bar.high + 40))
        assertEquals(bar.high.toDouble(), drawings.drawings.last().points[0].price)
        drawings.chooseTool(DrawingTool.HORIZONTAL_LINE)
        click(at(20, 41_500), ctrl = true)
        assertEquals(41_500.0, drawings.drawings.last().points[0].price)
    }

    @Test fun theZoomToolReportsTheBoxedBars() {
        drawings.toggleZoom()
        val from = at(20, 39_000)
        val to = at(40, 40_000)
        down(from)
        move(to, pressed = true)
        var range: Pair<Double, Double>? = null
        assertTrue(up(to) { a, b -> range = a to b })
        assertEquals(20.0 to 40.0, range!!.let { (a, b) -> kotlin.math.round(a) to kotlin.math.round(b) })
        assertFalse(drawings.zooming)
    }

    @Test fun theDemonstrationPenFadesAway() {
        drawings.chooseCursor(ChartCursor.DEMONSTRATION)
        val a = at(10, 39_000)
        assertTrue(down(a))
        move(a + Offset(30f, 0f), pressed = true)
        drawings.pointerUp(PointerInfo(a, false, false, false, false, 1000), ctx, emptyList()) { _, _ -> }
        assertEquals(1, drawings.demoStrokes.size)
        drawings.tickDemo(1600)
        assertTrue(drawings.demoStrokes.single().opacity in 0f..1f)
        drawings.tickDemo(9000)
        assertTrue(drawings.demoStrokes.isEmpty())
    }

    @Test fun cloneRemoveOrderAndMenu() {
        line(at(10, 39_000), at(30, 40_000))
        val first = drawings.drawings.single().id
        drawings.clone(first)
        assertEquals(2, drawings.drawings.size)
        val copy = drawings.selectedId!!
        drawings.moveOrder(copy, -1)
        assertEquals(copy, drawings.drawings.first().id)
        frame()
        assertTrue(drawings.openMenuAt(at(20, 39_500), ctx) { false })
        assertNotNull(drawings.menuAt)
        assertFalse(drawings.openMenuAt(at(90, 41_500), ctx) { false }, "not over a drawing")
        drawings.hide(first)
        assertEquals(1, frame().scenes.size)
        drawings.remove(first)
        assertEquals(1, drawings.drawings.size)
    }

    @Test fun theContextMenuDrawsAHorizontalLineInOneStepAndItCanBeUndone() {
        drawings.hideDrawings = true
        drawings.addHorizontalLine(DrawingPoint(START + 20 * HOUR, 40_125.0))
        val line = drawings.drawings.single()
        assertEquals(DrawingTool.HORIZONTAL_LINE, line.tool)
        assertEquals(listOf(DrawingPoint(START + 20 * HOUR, 40_125.0)), line.points)
        assertTrue(line.style.showPrices, "the tool's default look, with its price on the axis")
        assertEquals(line.id, drawings.selectedId)
        assertFalse(drawings.hideDrawings, "hidden drawings come back, or the new line would not show")
        assertNull(drawings.tool, "no tool is armed by it")
        assertEquals(1, ChartDrawings(store, Dispatchers.Unconfined).apply { bind("TEST") }.drawings.size, "saved like any drawing")
        drawings.undo()
        assertTrue(drawings.drawings.isEmpty())
    }

    @Test fun theLayerAnchorsTheMenuLineOnTheBarUnderThePointerAtTheMenuPrice() {
        val layer = DrawingLayer(drawings).also { it.ctx = ctx }
        layer.addHorizontalLine(at(20, 39_990), 40_000)
        assertEquals(DrawingPoint(START + 20 * HOUR, 40_000.0), drawings.drawings.single().points.single(), "the tick price of the menu, not the raw pointer price")
    }

    @Test fun rightClickCancelsAPlacement() {
        drawings.chooseTool(DrawingTool.TREND_LINE)
        click(at(10, 39_000))
        assertTrue(drawings.openMenuAt(at(90, 41_500), ctx) { false })
        assertNull(drawings.draft)
        assertNull(drawings.tool)
    }

    @Test fun eachSymbolKeepsItsOwnDrawingsAndTheyComeBackAfterARestart() {
        line(at(10, 39_000), at(30, 40_000))
        drawings.bind("OTHER")
        assertTrue(drawings.drawings.isEmpty())
        assertNull(drawings.selectedId)
        line(at(40, 39_000), at(50, 40_000), DrawingTool.RECTANGLE)
        drawings.magnet = MagnetMode.WEAK
        drawings.toggleFavorite(DrawingTool.FIB_RETRACEMENT)
        val reopened = ChartDrawings(store, Dispatchers.Unconfined)
        reopened.bind("TEST")
        assertEquals(DrawingTool.TREND_LINE, reopened.drawings.single().tool)
        reopened.bind("OTHER")
        assertEquals(DrawingTool.RECTANGLE, reopened.drawings.single().tool)
        assertEquals(MagnetMode.WEAK, reopened.magnet)
        assertEquals(listOf(DrawingTool.FIB_RETRACEMENT), reopened.favorites)
    }

    /** Two chart windows: one library, a controller each. */
    @Test fun chartsOfOneLibraryShareASymbolsDrawingsAndKeepTheirOwnToolAndSelection() {
        val library = DrawingLibrary(store, Dispatchers.Unconfined)
        val first = ChartDrawings(library).apply { bind("TEST") }
        val second = ChartDrawings(library).apply { bind("TEST") }
        first.addHorizontalLine(DrawingPoint(START + 20 * HOUR, 40_125.0))
        val line = first.drawings.single()
        assertEquals(listOf(line), second.drawings, "the other chart of the symbol shows it at once")
        assertEquals(line.id, first.selectedId)
        assertNull(second.selectedId, "a selection belongs to the chart it was made in")
        second.chooseTool(DrawingTool.RECTANGLE)
        assertNull(first.tool, "so does the armed tool")
        second.cancelTool()

        // A chart of another symbol has that symbol's list.
        second.bind("OTHER")
        assertTrue(second.drawings.isEmpty())
        second.addHorizontalLine(DrawingPoint(START + 5 * HOUR, 39_500.0))
        assertEquals(listOf(line), first.drawings)
        second.bind("TEST")
        assertEquals(listOf(line), second.drawings)

        // A drawing removed in one chart is gone from the other, whose selection of it ends without a trace.
        second.remove(line.id)
        assertTrue(first.drawings.isEmpty())
        assertNull(first.selected)
        assertEquals(setOf("OTHER"), ChartDrawings(store, Dispatchers.Unconfined).let { reopened -> listOf("TEST", "OTHER").filter { reopened.bind(it); reopened.drawings.isNotEmpty() }.toSet() },
            "what is saved is the library, whichever chart changed it")
    }

    /** A symbol's history is one, so undoing in a chart never drops what another chart of the symbol drew since. */
    @Test fun chartsOfOneSymbolShareItsUndoHistory() {
        val library = DrawingLibrary(store, Dispatchers.Unconfined)
        val first = ChartDrawings(library).apply { bind("TEST") }
        val second = ChartDrawings(library).apply { bind("TEST") }
        val other = ChartDrawings(library).apply { bind("OTHER") }
        first.addHorizontalLine(DrawingPoint(START + 20 * HOUR, 40_125.0))
        second.addHorizontalLine(DrawingPoint(START + 30 * HOUR, 40_500.0))
        val (one, two) = first.drawings
        assertTrue(first.canUndo && second.canUndo)
        assertFalse(other.canUndo, "another symbol's history is its own")
        first.undo()
        assertEquals(listOf(one), second.drawings, "the last step on the symbol goes, not the first chart's own older one")
        assertNull(second.selected, "the chart that had drawn it no longer has it selected")
        assertTrue(second.canRedo)
        second.redo()
        assertEquals(listOf(one, two), first.drawings)
        first.undo(); second.undo()
        assertTrue(first.drawings.isEmpty())
        assertFalse(first.canUndo || second.canUndo)

        // The history of a symbol waits for the chart that comes back to it.
        first.redo()
        first.bind("OTHER")
        assertFalse(first.canUndo)
        first.bind("TEST")
        assertTrue(first.canUndo)
        first.undo()
        assertTrue(second.drawings.isEmpty())
    }

    /** A Fibonacci set up once is drawn that way again: the default keeps its levels, look, settings and intervals. */
    @Test fun aToolsSavedDefaultKeepsEverySettingAndNewDrawingsStartWithIt() {
        line(at(10, 39_000), at(30, 40_000), DrawingTool.FIB_RETRACEMENT)
        val fib = drawings.drawings.single()
        val levels = fib.levels.map { if (it.value == .5) it.copy(value = .55, color = "#FF0000") else it } + DrawingLevel(2.618, "#00FF00")
        val custom = fib.copy(style = fib.style.copy(color = "#123456", reverse = true, showPrices = false), levels = levels,
            hiddenIntervals = setOf(IntervalBucket.SECONDS), text = "own text")
        assertFalse(drawings.hasDefault(DrawingTool.FIB_RETRACEMENT))
        drawings.setAsDefault(custom)
        assertTrue(drawings.hasDefault(DrawingTool.FIB_RETRACEMENT))
        line(at(40, 39_000), at(50, 40_000), DrawingTool.FIB_RETRACEMENT)
        val next = drawings.drawings.last()
        assertEquals(Triple(custom.style, levels, setOf(IntervalBucket.SECONDS)), Triple(next.style, next.levels, next.hiddenIntervals))
        assertEquals("", next.text, "content is the drawing's own")
        assertNotEquals(custom.points, next.points)
        assertEquals(fib.style, drawings.drawings.first().style, "drawings made before keep their settings")

        // Settings kept as params go with it; a param that places one drawing does not.
        line(at(10, 39_000), at(30, 40_000), DrawingTool.REGRESSION_TREND)
        drawings.setAsDefault(drawings.drawings.last().let { it.copy(params = it.params + ("upper" to 3.5)) })
        line(at(40, 39_000), at(60, 40_000), DrawingTool.REGRESSION_TREND)
        assertEquals(3.5, drawings.drawings.last().param("upper"))
        val table = newDrawing(DrawingTool.TABLE, "t", listOf(DrawingPoint(START, 40_000.0)))
        assertEquals(emptyMap(), DrawingDefaults.of(table.copy(params = table.params + ("screenX" to .9))).params)

        // The built-in settings come back, for the tool and in the dialog, the drawing's own content kept.
        val factory = drawings.builtInSettings(custom)
        assertEquals(Triple(fib.style, fib.levels, emptySet<IntervalBucket>()), Triple(factory.style, factory.levels, factory.hiddenIntervals))
        assertEquals(Triple(custom.id, custom.points, "own text"), Triple(factory.id, factory.points, factory.text))
        drawings.clearDefault(DrawingTool.FIB_RETRACEMENT)
        assertFalse(drawings.hasDefault(DrawingTool.FIB_RETRACEMENT))
        line(at(70, 39_000), at(80, 40_000), DrawingTool.FIB_RETRACEMENT)
        assertEquals(fib.levels, drawings.drawings.last().levels)
    }

    @Test fun savedDefaultsComeBackAfterARestartAndOldStyleOnlyDefaultsStillApply() {
        drawings.addHorizontalLine(DrawingPoint(START + 20 * HOUR, 40_125.0))
        val line = drawings.drawings.single()
        drawings.setAsDefault(line.copy(style = line.style.copy(color = "#ABCDEF", width = 3f), hiddenIntervals = setOf(IntervalBucket.DAYS)))
        val reopened = ChartDrawings(store, Dispatchers.Unconfined).apply { bind("OTHER") }
        assertTrue(reopened.hasDefault(DrawingTool.HORIZONTAL_LINE))
        reopened.addHorizontalLine(DrawingPoint(START, 39_000.0))
        val restored = reopened.drawings.single()
        assertEquals(Triple("#ABCDEF", 3f, setOf(IntervalBucket.DAYS)), Triple(restored.style.color, restored.style.width, restored.hiddenIntervals))

        // A file saved before whole defaults existed holds a look per tool; it still applies until a new default replaces it.
        val legacy = MemorySnapshotStore().apply {
            write("chart-drawing-tools-v1", kotlinx.serialization.json.Json.encodeToString(DrawingToolPrefs(defaults = mapOf(DrawingTool.HORIZONTAL_LINE to DrawingStyle("#FEDCBA")))))
        }
        val old = ChartDrawings(legacy, Dispatchers.Unconfined).apply { bind("TEST") }
        assertTrue(old.hasDefault(DrawingTool.HORIZONTAL_LINE))
        old.addHorizontalLine(DrawingPoint(START, 39_000.0))
        assertEquals("#FEDCBA", old.drawings.single().style.color)
        old.clearDefault(DrawingTool.HORIZONTAL_LINE)
        assertFalse(old.hasDefault(DrawingTool.HORIZONTAL_LINE))
    }

    @Test fun anUnreadableFileIsSetAsideNotOverwritten() {
        store.write("chart-drawings-v1", "{broken")
        val reopened = ChartDrawings(store, Dispatchers.Unconfined)
        assertNotNull(reopened.notice)
        assertEquals("{broken", store.read("chart-drawings-v1-unreadable"))
        assertEquals("{broken", store.read("chart-drawings-v1"), "left alone until something is saved")
    }

    @Test fun defaultsApplyToNewDrawings() {
        line(at(10, 39_000), at(30, 40_000))
        val styled = drawings.drawings.single().let { it.copy(style = it.style.copy(color = "#FF0000", width = 4f)) }
        drawings.setAsDefault(styled)
        line(at(40, 39_000), at(50, 40_000))
        assertEquals("#FF0000", drawings.drawings.last().style.color)
    }

    @Test fun toolChoiceRemembersTheGroupAndTogglesOff() {
        assertEquals(DrawingTool.TREND_LINE, drawings.recentTool(ToolGroup.TREND))
        drawings.chooseTool(DrawingTool.PITCHFORK)
        assertEquals(DrawingTool.PITCHFORK, drawings.recentTool(ToolGroup.TREND))
        drawings.chooseTool(DrawingTool.PITCHFORK)
        assertNull(drawings.tool, "choosing the armed tool again disarms it")
    }

    @Test fun everyToolIsInTheToolbarOnce() {
        val listed = ToolGroup.entries.flatMap { it.tools } + DrawingTool.ICON + DrawingTool.MEASURE
        assertEquals(DrawingTool.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }
}
