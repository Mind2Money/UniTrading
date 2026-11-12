package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileSheetMotionTest {
    @Test fun openingAndClosingHaveIntermediateFramesAndNoChartHeightJump() = runDesktopComposeUiTest(width = 300, height = 600) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 800.dp).testTag("motion-host")) { TradingScreen(runtime) }
        } }
        waitForIdle()
        val closedChartHeight = onNodeWithTag("trading-chart").fetchSemanticsNode().size.height
        mainClock.autoAdvance = false
        fun capture(name: String) {
            val file = File("../artifacts/sheet-motion/$name.png"); file.parentFile.mkdirs()
            ImageIO.write(onNodeWithTag("motion-host").captureToImage().toAwtImage(), "png", file)
        }
        capture("open-00")
        onNodeWithTag("open-order").performClick()
        mainClock.advanceTimeByFrame()
        val firstChartHeight = onNodeWithTag("trading-chart").fetchSemanticsNode().size.height
        assertTrue(kotlin.math.abs(firstChartHeight - closedChartHeight) <= 4, "Chart must not jump on the first open frame")
        val opening = mutableListOf<Float>()
        repeat(16) { frame ->
            mainClock.advanceTimeBy(32)
            opening += onNodeWithTag("order-sheet").fetchSemanticsNode().positionInRoot.y
            capture("open-${(frame + 1).toString().padStart(2, '0')}")
        }
        assertTrue(opening.distinct().size >= 3, "Opening must move through several sheet positions: $opening")
        assertTrue(opening.first() > opening.last() + 20, "Sheet must rise from below the viewport")
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("order-quantity").performTextReplacement("0.23")
        onNodeWithTag("minimize-order").performClick()
        repeat(16) { frame -> mainClock.advanceTimeBy(32); capture("minimize-${frame.toString().padStart(2, '0')}") }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("restore-order").performClick()
        repeat(16) { frame -> mainClock.advanceTimeBy(32); capture("restore-${frame.toString().padStart(2, '0')}") }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("order-quantity").assertTextContains("0.23")
        onNodeWithTag("order-sheet-handle", useUnmergedTree = true).performTouchInput {
            swipeDown(endY = 300f, durationMillis = 300)
        }
        repeat(16) { frame -> mainClock.advanceTimeBy(32); capture("fling-${frame.toString().padStart(2, '0')}") }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("minimized-order").assertIsDisplayed()
        onNodeWithTag("restore-order").performClick()
        repeat(16) { frame -> mainClock.advanceTimeBy(32); capture("recover-${frame.toString().padStart(2, '0')}") }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("close-order").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("order-quantity").assertTextContains("0.23")
        repeat(16) { frame -> mainClock.advanceTimeBy(32); capture("close-${frame.toString().padStart(2, '0')}") }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("order-panel").assertDoesNotExist()
        assertEquals(closedChartHeight, onNodeWithTag("trading-chart").fetchSemanticsNode().size.height)
        mainClock.autoAdvance = true
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("order-quantity").assertTextContains("0.10")
    }

    @Test fun aNewOpenCancelsAnInFlightHideWithoutReportingClosed() = runDesktopComposeUiTest(width = 300, height = 600) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true)
        val plugin = runtime.registry.plugins.first()
        val spec = plugin.instruments.first()
        var state by mutableStateOf(MobileOrderPanelState.CLOSED)
        var hidden = 0
        setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            TradingTheme {
                MobileOrderSheet(spec, plugin.market.value.quotes[spec.symbol], plugin.capabilities, OrderDraft(), {},
                    state, { state = it }, { state = MobileOrderPanelState.CLOSED }, {}, false,
                    Modifier.requiredSize(400.dp, 800.dp), onHidden = { hidden++ },
                    content = { Text("Editor", Modifier.testTag("motion-editor")) }, body = {})
            }
        } }
        waitForIdle()
        mainClock.autoAdvance = false
        runOnUiThread { state = MobileOrderPanelState.EDITING }
        mainClock.advanceTimeBy(1000)
        val hiddenBefore = hidden
        runOnUiThread { state = MobileOrderPanelState.CLOSED }
        mainClock.advanceTimeBy(64)
        runOnUiThread { state = MobileOrderPanelState.EDITING }
        mainClock.advanceTimeBy(1000)
        assertEquals(MobileOrderPanelState.EDITING, state)
        assertEquals(hiddenBefore, hidden, "A cancelled exit must not clear the new draft")
        onNodeWithTag("motion-editor").assertIsDisplayed()
        mainClock.autoAdvance = true
    }
}
