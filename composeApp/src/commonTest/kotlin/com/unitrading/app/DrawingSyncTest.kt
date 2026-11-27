package com.unitrading.app

import com.unitrading.chart.drawing.DrawingPoint
import com.unitrading.chart.drawing.DrawingSyncScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DrawingSyncTest {
    @Test fun existingDrawingCanMoveBetweenScopesAndSurviveRestart() = runTest {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = false)
        val main = runtime.drawingsForChart("main").apply { bind("MES") }
        val split = runtime.drawingsForChart("split1").apply { bind("MES") }
        val other = runtime.drawingsForChart("c1").apply { bind("MES") }
        val different = runtime.drawingsForChart("c2").apply { bind("MNQ") }
        main.addHorizontalLine(DrawingPoint(0, 100.0))
        val id = main.drawings.single().id
        assertTrue(main.changeSyncScope(id, DrawingSyncScope.LAYOUT))
        assertEquals(id, split.drawings.single().id)
        assertTrue(other.drawings.isEmpty())
        assertTrue(main.changeSyncScope(id, DrawingSyncScope.GLOBAL))
        assertEquals(id, other.drawings.single().id)
        assertTrue(different.drawings.isEmpty())
        other.undo() // its own history cannot undo a change made in main
        main.undo()
        assertEquals(DrawingSyncScope.LAYOUT, split.drawings.single().syncScope)
        assertTrue(other.drawings.isEmpty())
        main.redo()
        assertTrue(other.changeSyncScope(id, DrawingSyncScope.LOCAL))
        assertTrue(main.drawings.isEmpty())
        assertEquals(DrawingSyncScope.LOCAL, other.drawings.single().syncScope)
        assertTrue(runtime.flushChartDrawings())
        val restored = TradingRuntime(store, includeDemoBrokers = false)
        assertTrue(restored.drawingsForChart("main").apply { bind("MES") }.drawings.isEmpty())
        assertEquals(id, restored.drawingsForChart("c1").apply { bind("MES") }.drawings.single().id)
    }
    @Test fun favoriteToolbarPositionPersistsThroughExitAndRestart() = runTest {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = false)
        val drawings = runtime.drawingsForChart("main")
        drawings.setFavoriteWorkspacePosition(220f, 140f)
        assertTrue(runtime.flushChartDrawings())
        val restored = TradingRuntime(store, includeDemoBrokers = false).drawingsForChart("split1")
        assertEquals(220f, restored.favoriteWorkspacePosition?.x)
        assertEquals(140f, restored.favoriteWorkspacePosition?.y)
    }
    @Test fun onlyNewDrawingsShareWithinTheirChosenScopeAndRestore() = runTest {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = false)
        val main = runtime.drawingsForChart("main").apply { bind("MES") }
        val split = runtime.drawingsForChart("split1").apply { bind("MES") }
        val other = runtime.drawingsForChart("c1").apply { bind("MES") }
        val otherSplit = runtime.drawingsForChart("c1s1").apply { bind("MES") }
        val different = runtime.drawingsForChart("c2").apply { bind("MNQ") }
        main.addHorizontalLine(DrawingPoint(0, 100.0))
        val local = main.drawings.single()
        assertEquals(DrawingSyncScope.LOCAL, local.syncScope)
        assertTrue(split.drawings.isEmpty())
        main.newDrawingSyncScope = DrawingSyncScope.LAYOUT
        main.addHorizontalLine(DrawingPoint(0, 200.0))
        val layoutLine = main.drawings.last()
        assertEquals(listOf(layoutLine), split.drawings)
        assertTrue(other.drawings.isEmpty())
        main.newDrawingSyncScope = DrawingSyncScope.GLOBAL
        main.addHorizontalLine(DrawingPoint(0, 300.0))
        val global = main.drawings.last()
        assertEquals(listOf(global), other.drawings)
        assertEquals(listOf(global), otherSplit.drawings)
        assertTrue(different.drawings.isEmpty())
        assertEquals(listOf(layoutLine, global), split.drawings)
        val edited = global.copy(points = listOf(DrawingPoint(0, 350.0)))
        other.update(edited)
        assertEquals(edited, main.drawings.first { it.id == global.id })
        other.remove(global.id)
        assertTrue(main.drawings.none { it.id == global.id })
        assertEquals(listOf(layoutLine), split.drawings)
        other.undo()
        assertEquals(edited, main.drawings.first { it.id == global.id })
        assertTrue(runtime.flushChartDrawings())
        val restored = TradingRuntime(store, includeDemoBrokers = false)
        val newMain = restored.drawingsForChart("main").apply { bind("MES") }
        val newSplit = restored.drawingsForChart("split1").apply { bind("MES") }
        val newWorkspace = restored.drawingsForChart("c3").apply { bind("MES") }
        assertEquals(listOf(local, layoutLine, edited), newMain.drawings)
        assertEquals(listOf(layoutLine, edited), newSplit.drawings)
        assertEquals(listOf(edited), newWorkspace.drawings)
        assertEquals(DrawingSyncScope.GLOBAL, newWorkspace.newDrawingSyncScope)
    }

    @Test fun undoOfSharedDrawingKeepsUnrelatedEditsAndRejectsConflicts() = runTest {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = false)
        val a = runtime.drawingsForChart("main").apply { bind("MES"); newDrawingSyncScope = DrawingSyncScope.GLOBAL }
        val b = runtime.drawingsForChart("c1").apply { bind("MES") }
        a.addHorizontalLine(DrawingPoint(0, 100.0))
        val first = a.drawings.single()
        b.addHorizontalLine(DrawingPoint(0, 200.0))
        val second = b.drawings.last()
        a.undo()
        assertEquals(listOf(second), b.drawings, "Undo must preserve a drawing added in another chart")
        a.redo()
        assertEquals(setOf(first.id, second.id), b.drawings.map { it.id }.toSet())
        b.update(first.copy(points = listOf(DrawingPoint(0, 110.0))))
        a.undo()
        assertEquals(110.0, b.drawings.first { it.id == first.id }.points.single().price, "Undo must not overwrite a later edit")
    }

    @Test fun reusingAClosedWorkspaceIdStartsAnIndependentLayoutWithoutLosingGlobalDrawings() = runTest {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = false)
        assertTrue(runtime.prepareNewDrawingLayout("c1"))
        val old = runtime.drawingsForChart("c1").apply { bind("MES") }
        old.newDrawingSyncScope = DrawingSyncScope.LAYOUT
        old.addHorizontalLine(DrawingPoint(0, 100.0))
        old.newDrawingSyncScope = DrawingSyncScope.GLOBAL
        old.addHorizontalLine(DrawingPoint(0, 200.0))
        val global = old.drawings.last()
        assertTrue(runtime.prepareNewDrawingLayout("c1"))
        val fresh = runtime.drawingsForChart("c1").apply { bind("MES") }
        assertEquals(listOf(global), fresh.drawings)
        assertTrue(runtime.flushChartDrawings())
        val restored = TradingRuntime(store, includeDemoBrokers = false).drawingsForChart("c1").apply { bind("MES") }
        assertEquals(listOf(global), restored.drawings)
    }
}
