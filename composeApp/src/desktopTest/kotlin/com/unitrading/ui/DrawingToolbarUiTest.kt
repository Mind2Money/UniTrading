package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.chart.drawing.ChartCursor
import com.unitrading.chart.drawing.DrawingTool
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** The left toolbar arms tools; drawings are placed, selected, edited and removed on the real chart. */
class DrawingToolbarUiTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
    private val drawings get() = runtime.drawings

    private fun show() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { Box(Modifier.fillMaxSize()) { TradingScreen(runtime) } } }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun clickChart(fx: Float, fy: Float) {
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * fx, size.height * fy)); press(); release() }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun hoverChart(fx: Float, fy: Float) {
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * fx, size.height * fy)); moveBy(Offset(1f, 0f)) }
        compose.waitForIdle()
    }

    @Test fun drawsATrendLineFromTheToolbarAndRemovesIt() {
        show()
        compose.onNodeWithTag("drawing-group-TREND").performClick()
        compose.waitForIdle()
        assertEquals(DrawingTool.TREND_LINE, drawings.tool)
        clickChart(.3f, .6f)
        hoverChart(.5f, .4f)
        clickChart(.6f, .3f)
        assertEquals(1, drawings.drawings.size)
        assertNull(drawings.tool)
        compose.onNodeWithTag("drawing-bar").assertExists("the quick bar opens on the new drawing")
        compose.onNodeWithTag("drawing-bar-remove").performClick()
        compose.waitForIdle()
        assertTrue(drawings.drawings.isEmpty())
        compose.onNodeWithTag("drawing-bar").assertDoesNotExist()
    }

    @Test fun flyoutChoosesFibonacciAndDeleteKeyRemovesIt() {
        show()
        compose.onNodeWithTag("drawing-group-FIB-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawing-tool-FIB_RETRACEMENT").performClick()
        compose.waitForIdle()
        assertEquals(DrawingTool.FIB_RETRACEMENT, drawings.tool)
        clickChart(.3f, .7f)
        clickChart(.6f, .25f)
        assertEquals(DrawingTool.FIB_RETRACEMENT, drawings.drawings.single().tool)
        compose.onNodeWithTag("trading-chart").requestFocus()
        compose.onNodeWithTag("trading-chart").performKeyInput { pressKey(Key.Delete) }
        compose.waitForIdle()
        assertTrue(drawings.drawings.isEmpty())
    }

    @Test fun settingsDialogEditsStyleAndCancelRestores() {
        show()
        compose.onNodeWithTag("drawing-group-TREND").performClick()
        compose.waitForIdle()
        clickChart(.3f, .6f)
        clickChart(.6f, .3f)
        val original = drawings.drawings.single()
        compose.onNodeWithTag("drawing-bar-settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-width-4").performClick()
        compose.waitForIdle()
        assertEquals(4f, drawings.drawings.single().style.width, "preview applies at once")
        compose.onNodeWithTag("drawing-settings-cancel").performClick()
        compose.waitForIdle()
        assertEquals(original, drawings.drawings.single())
        compose.onNodeWithTag("drawing-bar-settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-width-1").performClick()
        compose.onNodeWithTag("drawing-settings-save").performClick()
        compose.waitForIdle()
        assertEquals(1f, drawings.drawings.single().style.width)
    }

    @Test fun textToolAsksForItsText() {
        show()
        compose.onNodeWithTag("drawing-group-ANNOTATION").performClick()
        compose.waitForIdle()
        assertEquals(DrawingTool.TEXT, drawings.tool)
        clickChart(.5f, .5f)
        compose.onNodeWithTag("drawing-text").performTextInput("突破位")
        compose.onNodeWithTag("drawing-text-save").performClick()
        compose.waitForIdle()
        assertEquals("突破位", drawings.drawings.single().text)
    }

    @Test fun hideAndCursorControlsReachTheChart() {
        show()
        compose.onNodeWithTag("drawing-cursor-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawing-cursor-ARROW").performClick()
        compose.waitForIdle()
        assertEquals(ChartCursor.ARROW, drawings.cursor)
        compose.onNodeWithTag("drawing-hide-menu").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawing-hide-trading").performClick()
        compose.waitForIdle()
        assertTrue(drawings.hideTrading)
    }

    @Test fun takesAScreenshotOfTheToolbarAndAFlyout() {
        show()
        compose.onNodeWithTag("drawing-group-FIB-menu").performClick()
        compose.waitForIdle()
        val folder = File("../artifacts/drawing-gallery").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "toolbar.png"))
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "flyout.png"))
    }
}
