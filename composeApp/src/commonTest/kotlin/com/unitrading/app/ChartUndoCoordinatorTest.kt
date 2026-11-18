package com.unitrading.app

import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.drawing.DrawingPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChartUndoCoordinatorTest {
    @Test fun undoAndRedoFollowEditOrderAndStayWithinTheSelectedChart() = runTest {
        val runtime = TradingRuntime(MemorySnapshotStore())
        val firstDrawings = runtime.drawingsForChart("split1").apply { bind("TESTZ6") }
        val otherDrawings = runtime.drawingsForChart("split2").apply { bind("TESTZ6") }
        val firstIndicators = runtime.indicatorsForChart("split1")
        val otherIndicators = runtime.indicatorsForChart("split2")
        val first = runtime.undoForChart("split1", this)
        runtime.undoForChart("split2", this)
        val ma = IndicatorSettings(listOf(IndicatorConfig("ma", IndicatorKind.MA)))
        val rsi = IndicatorSettings(listOf(IndicatorConfig("rsi", IndicatorKind.RSI)))
        assertTrue(firstIndicators.apply(ma))
        firstDrawings.addHorizontalLine(DrawingPoint(1000, 100.0))
        assertTrue(otherIndicators.apply(rsi))
        otherDrawings.addHorizontalLine(DrawingPoint(1000, 200.0))
        assertTrue(firstIndicators.apply(IndicatorSettings()))
        firstDrawings.removeAllDrawings()
        assertTrue(first.undo()); assertEquals(1, firstDrawings.drawingCount())
        assertTrue(first.undo()); assertEquals(ma, firstIndicators.settings.value)
        assertTrue(first.undo()); assertEquals(0, firstDrawings.drawingCount())
        assertTrue(first.undo()); assertEquals(IndicatorSettings(), firstIndicators.settings.value)
        assertEquals(rsi, otherIndicators.settings.value)
        assertEquals(1, otherDrawings.drawingCount())
        assertTrue(first.redo()); assertEquals(ma, firstIndicators.settings.value)
        assertTrue(first.redo()); assertEquals(1, firstDrawings.drawingCount())
        assertTrue(first.redo()); assertEquals(IndicatorSettings(), firstIndicators.settings.value)
        assertTrue(first.redo()); assertEquals(0, firstDrawings.drawingCount())
        assertFalse(first.redo())
        assertTrue(first.undo())
        firstDrawings.addHorizontalLine(DrawingPoint(2000, 120.0))
        assertFalse(first.redo(), "a new action discards the combined redo history")
    }

    @Test fun switchingSymbolsRetainsOrderedHistoryWithoutChangingTheSelectedInstrument() = runTest {
        val runtime = TradingRuntime(MemorySnapshotStore())
        val drawings = runtime.drawingsForChart("split1").apply { bind("A") }
        val indicators = runtime.indicatorsForChart("split1")
        val history = runtime.undoForChart("split1", this)
        drawings.addHorizontalLine(DrawingPoint(1, 100.0))
        drawings.removeAllDrawings()
        drawings.bind("B")
        val ma = IndicatorSettings(listOf(IndicatorConfig("ma", IndicatorKind.MA)))
        assertTrue(indicators.apply(ma))
        assertTrue(history.undo()); assertEquals(IndicatorSettings(), indicators.settings.value)
        assertTrue(history.undo()); assertEquals("B", drawings.symbol)
        assertEquals(0, drawings.drawingCount())
        drawings.bind("A")
        assertEquals(1, drawings.drawingCount())
        assertTrue(history.redo()); assertEquals(0, drawings.drawingCount())
        assertTrue(history.redo()); assertEquals(ma, indicators.settings.value)
    }
}
