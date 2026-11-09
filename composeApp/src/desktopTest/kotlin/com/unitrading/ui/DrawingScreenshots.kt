package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The left toolbar on the real chart at real density, desktop and phone, to artifacts/drawing-gallery for visual review. */
@OptIn(ExperimentalTestApi::class)
class DrawingScreenshots {
    private val folder = File("../artifacts/drawing-gallery").also { it.mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        mainClock.advanceTimeBy(400)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }

    private fun ComposeUiTest.place(points: List<Pair<Float, Float>>) {
        val chart = onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        points.forEach { (fx, fy) ->
            chart.performMouseInput { moveTo(Offset(size.width * fx, size.height * fy)); press(); release() }
            waitForIdle()
        }
    }

    private fun ComposeUiTest.tool(group: String, tool: String) {
        onNodeWithTag("drawing-group-$group-menu").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("drawing-tool-$tool").performClick()
        waitForIdle()
    }

    @Test fun desktopChart() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) } } }
        waitForIdle()
        tool("FIB", "FIB_RETRACEMENT")
        place(listOf(.12f to .72f, .34f to .32f))
        tool("SHAPE", "RECTANGLE")
        place(listOf(.42f to .3f, .55f to .52f))
        tool("FORECAST", "LONG_POSITION")
        place(listOf(.62f to .55f))
        tool("TREND", "PARALLEL_CHANNEL")
        place(listOf(.2f to .78f, .5f to .6f, .3f to .86f))
        shot("desktop-chart")
        onNodeWithTag("drawing-bar-settings").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        shot("desktop-settings")
    }

    @Test fun phoneChart() = runDesktopComposeUiTest(width = 824, height = 1830) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) { Box(Modifier.requiredSize(412.dp, 915.dp)) { TradingScreen(runtime) } } }
        waitForIdle()
        onNodeWithTag("chart-drawing-tools").performClick()
        waitForIdle()
        onNodeWithTag("drawing-group-TREND").performClick()
        waitForIdle()
        val chart = onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { click(Offset(size.width * .2f, size.height * .6f)) }
        waitForIdle()
        chart.performTouchInput { click(Offset(size.width * .7f, size.height * .3f)) }
        waitForIdle()
        shot("phone-chart")
    }
}
