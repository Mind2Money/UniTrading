package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.ThemeSettings
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class WatchListEditorTest {
    @get:Rule val compose = createComposeRule()
    private val all = ('A'..'L').map { "S$it" }
    private var symbols by mutableStateOf(all)
    private val saves = mutableListOf<List<String>>()
    private val row = 48f
    /** Consumes pointer events before the row sees them, as a parent taking over the gesture does. */
    private var takeGestureAway = false

    /** Twelve rows of 48px, by default in a viewport of five. */
    private fun render(firstRow: Int = 0, viewport: Int = 240, onChange: (List<String>) -> Unit = { symbols = it; saves += it }) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            TradingTheme(ThemeSettings()) {
                Box(Modifier.requiredSize(320.dp, viewport.dp).testTag("host").pointerInput(Unit) {
                    awaitPointerEventScope { while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (takeGestureAway) event.changes.forEach { it.consume() }
                    } }
                }) {
                    WatchListEditor(symbols, onChange, Modifier.fillMaxSize(), firstRow)
                }
            }
        } }
    }
    private fun rowNode(symbol: String) = compose.onNodeWithTag("market-row-$symbol")
    private fun handle(symbol: String) = compose.onNodeWithTag("market-drag-$symbol", useUnmergedTree = true)
    private fun top(symbol: String) = rowNode(symbol).fetchSemanticsNode().boundsInRoot.top
    private fun moved(from: Int, to: Int): List<String> = all.toMutableList().apply { add(to, removeAt(from)) }

    @Test fun aMouseDragsFromAnywhereInTheRowAndSavesOnceOnRelease() {
        render()
        rowNode("SB").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row * 2)) }
        compose.runOnIdle { assertTrue(saves.isEmpty(), "Nothing is saved while the row is held") }
        assertEquals(row * 3, top("SB"), "The held row follows the pointer")
        assertEquals(row, top("SC"), "The rows it passed make room")
        assertEquals(row * 2, top("SD"))
        assertEquals(row * 4, top("SE"), "Rows it did not pass stay")
        val capture = File(System.getenv("UNITRADING_MARKET_ARTIFACTS") ?: "../artifacts/market-watch", "edit-watch-list-dragging.png")
        capture.parentFile.mkdirs()
        ImageIO.write(compose.onNodeWithTag("host").captureToImage().toAwtImage(), "png", capture)
        rowNode("SB").performMouseInput { release() }
        compose.runOnIdle { assertEquals(listOf(moved(1, 3)), saves) }
        assertEquals(row * 3, top("SB"))
        assertEquals(row, top("SC"))
    }

    @Test fun aRowCanBeDraggedAgainAfterADrop() {
        render()
        rowNode("SA").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row)); release() }
        rowNode("SA").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row)); release() }
        compose.runOnIdle { assertEquals(listOf(moved(0, 1), moved(0, 2)), saves) }
    }

    @Test fun aFingerReordersFromTheHandleAndScrollsFromAnywhereElse() {
        render()
        rowNode("SC").performTouchInput { down(center); moveBy(Offset(0f, -row * 2)); up() }
        compose.runOnIdle { assertTrue(saves.isEmpty()) }
        val scrolled = top("SD")
        assertTrue(scrolled < row * 3, "A finger on the row body scrolls the list")
        handle("SD").performTouchInput { down(center); moveBy(Offset(0f, row)); up() }
        compose.runOnIdle { assertEquals(listOf(moved(3, 4)), saves) }
        assertEquals(scrolled + row, top("SD"), "Reordering keeps the scroll position")
    }

    @Test fun holdingARowAtTheBottomEdgeScrollsAndDropsBeyondTheFirstScreen() {
        render()
        rowNode("SB").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row * 5)) }
        compose.waitForIdle()
        rowNode("SB").performMouseInput { release() }
        compose.runOnIdle { assertEquals(listOf(moved(1, 11)), saves) }
        assertEquals(row * 4, top("SB"), "The dropped row is still in view")
    }

    @Test fun holdingARowAtTheTopEdgeScrollsBackToTheStart() {
        render(firstRow = 7)
        rowNode("SK").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -row * 5)) }
        compose.waitForIdle()
        rowNode("SK").performMouseInput { release() }
        compose.runOnIdle { assertEquals(listOf(moved(10, 0)), saves) }
        assertEquals(0f, top("SK"))
    }

    @Test fun grabbingARowAtTheViewportEdgeDoesNotScrollUntilItMovesThatWay() {
        render(firstRow = 3)
        val before = top("SD")
        rowNode("SD").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row / 4)) }
        compose.waitForIdle()
        assertEquals(before + row / 4, top("SD"))
        rowNode("SD").performMouseInput { moveBy(Offset(0f, -row / 4)); release() }
        compose.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test fun aRowBroughtBackToTheEdgeItWasGrabbedAtScrollsThatWay() {
        render(firstRow = 3)
        rowNode("SD").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row * 5)) }
        compose.waitForIdle()
        rowNode("SD").performMouseInput { moveBy(Offset(0f, -row * 4.75f)) }
        compose.waitForIdle()
        rowNode("SD").performMouseInput { release() }
        compose.runOnIdle { assertEquals(listOf(moved(3, 0)), saves) }
    }

    @Test fun aRowGrabbedPartHiddenStaysPutUntilThePointerMoves() {
        render(viewport = 250)
        rowNode("SF").performMouseInput { moveTo(center); press(); moveBy(Offset(3f, 0f)) }
        assertEquals(row * 5, top("SF"))
        assertEquals(row * 4, top("SE"), "No row makes room yet")
        rowNode("SF").performMouseInput { release() }
        compose.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test fun aRowCannotLeaveTheListAndAnUnchangedOrderIsNotSaved() {
        render()
        rowNode("SA").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -row * 3)) }
        assertEquals(0f, top("SA"))
        rowNode("SA").performMouseInput { release() }
        compose.runOnIdle { assertTrue(saves.isEmpty()); assertEquals(all, symbols) }
    }

    @Test fun aGestureTakenAwayPutsTheRowBack() {
        render()
        handle("SB").performTouchInput { down(center); moveBy(Offset(0f, row * 2)) }
        assertEquals(row * 3, top("SB"))
        takeGestureAway = true
        handle("SB").performTouchInput { moveBy(Offset(0f, 5f)) }
        compose.runOnIdle { assertTrue(saves.isEmpty()); assertEquals(all, symbols) }
        assertEquals(row, top("SB"))
        assertEquals(row * 2, top("SC"))
    }

    @Test fun theDroppedOrderStaysUntilTheSavedListArrives() {
        render(onChange = { saves += it })
        rowNode("SB").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, row)); release() }
        compose.runOnIdle { assertEquals(listOf(moved(1, 2)), saves) }
        assertTrue(top("SB") > top("SC"))
    }

    @Test fun screenReaderActionsMoveARowOneStep() {
        render()
        val first = rowNode("SA").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("下移 SA"), first.map { it.label })
        compose.runOnIdle { first.single().action() }
        compose.runOnIdle { assertEquals(listOf(moved(0, 1)), saves) }
        val second = rowNode("SA").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("上移 SA", "下移 SA"), second.map { it.label })
    }

    @Test fun removeStillRemovesOneRow() {
        render()
        compose.onNodeWithTag("market-remove-SB").performClick()
        compose.runOnIdle { assertEquals(listOf(all - "SB"), saves) }
    }
}
