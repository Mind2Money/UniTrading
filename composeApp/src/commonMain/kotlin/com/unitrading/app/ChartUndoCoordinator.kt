package com.unitrading.app

import com.unitrading.chart.drawing.ChartDrawings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Combines the two histories in the order of edits, without crossing into another chart. */
internal class ChartUndoCoordinator(
    private val indicators: IndicatorController,
    private val drawings: ChartDrawings,
    internal var scope: CoroutineScope,
) {
    private enum class Step { INDICATOR, DRAWING }
    private class Entry(val step: Step)
    private val undone = ArrayDeque<Entry>()
    private val redone = ArrayDeque<Entry>()
    private val mutex = Mutex()

    init {
        indicators.onRecorded = { record(Step.INDICATOR) }
        drawings.onRecorded = { record(Step.DRAWING) }
        // Drawing histories are symbol-specific while indicator settings are chart-specific. Reset the
        // combined timeline on a symbol change so an undo cannot restore another symbol's drawings.
        drawings.onSymbolChanged = { undone.clear(); redone.clear() }
        drawings.onUndoRequested = { scope.launch { undo() }; Unit }
        drawings.onRedoRequested = { scope.launch { redo() }; Unit }
    }

    private fun record(step: Step) {
        undone.addLast(Entry(step))
        if (undone.size > 100) undone.removeFirst()
        redone.clear()
    }

    suspend fun undo(): Boolean = mutex.withLock {
        val entry = undone.lastOrNull() ?: return@withLock false
        val restored = when (entry.step) {
            Step.INDICATOR -> indicators.undo()
            Step.DRAWING -> if (drawings.canUndo) { drawings.undo(); true } else false
        }
        if (restored && undone.lastOrNull() === entry) {
            undone.removeLast()
            redone.addLast(entry)
        } else if (restored) {
            // An edit or symbol change arrived during persistence; never remove a newer action.
            undone.remove(entry)
            redone.clear()
        }
        restored
    }

    suspend fun redo(): Boolean = mutex.withLock {
        val entry = redone.lastOrNull() ?: return@withLock false
        val restored = when (entry.step) {
            Step.INDICATOR -> indicators.redo()
            Step.DRAWING -> if (drawings.canRedo) { drawings.redo(); true } else false
        }
        if (restored && redone.lastOrNull() === entry) {
            redone.removeLast()
            undone.addLast(entry)
        } else if (restored) {
            redone.remove(entry)
        }
        restored
    }
}
