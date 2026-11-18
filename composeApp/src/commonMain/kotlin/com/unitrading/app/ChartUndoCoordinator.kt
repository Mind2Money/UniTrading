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
    private class Entry(val step: Step, val symbol: String)
    private val undone = ArrayDeque<Entry>()
    private val redone = ArrayDeque<Entry>()
    private val mutex = Mutex()

    init {
        indicators.onRecorded = { record(Step.INDICATOR) }
        drawings.onRecorded = { record(Step.DRAWING) }
        drawings.onUndoRequested = { scope.launch { undo() }; Unit }
        drawings.onRedoRequested = { scope.launch { redo() }; Unit }
    }

    private fun record(step: Step) {
        undone.addLast(Entry(step, drawings.symbol))
        if (undone.size > 100) undone.removeFirst()
        redone.clear()
    }

    suspend fun undo(): Boolean = mutex.withLock {
        val entry = undone.lastOrNull() ?: return@withLock false
        val restored = when (entry.step) {
            Step.INDICATOR -> indicators.undo()
            Step.DRAWING -> drawings.undoForSymbol(entry.symbol)
        }
        if (restored && undone.lastOrNull() === entry) {
            undone.removeLast()
            redone.addLast(entry)
        } else if (restored) {
            // An edit arrived during persistence; never remove a newer action.
            undone.remove(entry)
            redone.clear()
        }
        restored
    }

    suspend fun redo(): Boolean = mutex.withLock {
        val entry = redone.lastOrNull() ?: return@withLock false
        val restored = when (entry.step) {
            Step.INDICATOR -> indicators.redo()
            Step.DRAWING -> drawings.redoForSymbol(entry.symbol)
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
