package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/**
 * The crosshair follows hover, taps and arrow keys; its state is exposed as `cross:<slot>@<price|none>` or `cross:none`.
 * The slot counts from the first bar on screen and runs on into the empty space beside the bars.
 */
class CrosshairUiTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }

    private fun chartState(): Map<String, String> = compose.onNodeWithTag("trading-chart").fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun cross(): Pair<Int, Long?>? {
        val value = chartState().getValue("cross")
        if (value == "none") return null
        return value.substringBefore('@').toInt() to value.substringAfter('@').toLongOrNull()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun hoverTapAndArrowKeysPlaceTheCrosshairAndPanningOrEscapeHidesIt() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { Box(Modifier.fillMaxSize()) { TradingScreen(runtime) } } }
        compose.waitForIdle()
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        assertNull(cross())

        chart.performMouseInput { moveTo(Offset(size.width * .5f, size.height * .45f)); moveBy(Offset(1f, 0f)) }
        compose.waitForIdle()
        val hovered = assertNotNull(cross(), "hover places the crosshair")
        val price = assertNotNull(hovered.second, "hover inside the plot carries a price")
        val state = chartState()
        assertTrue(price >= state.getValue("low").toDouble() && price <= state.getValue("high").toDouble(), "price $price within ${state["low"]}..${state["high"]}")

        chart.performMouseInput { moveTo(Offset(size.width * .3f, size.height * .4f)) }
        compose.waitForIdle()
        val moved = assertNotNull(cross())
        assertTrue(moved.first < hovered.first, "the bar follows the pointer")

        chart.performTouchInput { click(Offset(size.width * .6f, size.height * .5f)) }
        compose.waitForIdle()
        val tapped = assertNotNull(cross(), "a tap places the crosshair")
        assertTrue(tapped.first > moved.first && tapped.second != null)
        val target = File("../artifacts/chart-crosshair.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)

        chart.requestFocus()
        chart.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        val keyed = assertNotNull(cross())
        assertEquals(tapped.first - 1, keyed.first)
        assertNull(keyed.second, "arrow keys keep only the vertical line")

        chart.performTouchInput { swipe(Offset(size.width * .4f, size.height * .4f), Offset(size.width * .6f, size.height * .6f)) }
        compose.waitForIdle()
        assertNull(cross(), "panning hides the crosshair")

        chart.performTouchInput { click(Offset(size.width * .5f, size.height * .5f)) }
        compose.waitForIdle()
        assertNotNull(cross())
        chart.performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertNull(cross(), "Escape hides the crosshair")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theCrosshairLeavesTheBarsForTheEmptySpaceAfterTheNewestOne() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { Box(Modifier.fillMaxSize()) { TradingScreen(runtime) } } }
        compose.waitForIdle()
        // Without position and order lines a drag on the plot always pans.
        compose.onNodeWithTag("chart-layers").performClick()
        compose.onNodeWithTag("layer-0").performClick()
        compose.onNodeWithTag("layer-1").performClick()
        compose.onNodeWithTag("chart-layers").performClick()
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { swipe(Offset(size.width * .7f, size.height * .4f), Offset(size.width * .4f, size.height * .4f)) }
        compose.waitForIdle()
        val count = chartState().getValue("bars").toFloat()
        val offset = chartState().getValue("offset").toFloat()
        assertTrue(offset < -10f, "dragging left opens empty slots after the newest bar, offset $offset")
        // The plot starts 12 dp from the left and ends 84 dp before the right edge of the chart.
        val left = 12 * .6f
        val right = size.width - 84 * .6f
        val step = (right - left) / count
        val row = size.height * .45f
        val newestX = right + (offset + .5f) * step
        val pointerX = right - 2 * step

        chart.performMouseInput { moveTo(Offset(newestX, row)) }
        compose.waitForIdle()
        val newest = assertNotNull(cross(), "hover on the newest bar").first
        chart.performMouseInput { moveTo(Offset(pointerX, row)) }
        compose.waitForIdle()
        val beyond = assertNotNull(cross(), "the crosshair shows in the empty space")
        assertTrue(beyond.first > newest + 3, "the bar slot leaves the newest bar: ${beyond.first} against $newest")
        assertNotNull(beyond.second, "the price still follows the pointer")
        val target = File("../artifacts/chart-crosshair-beyond.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)

        // The dashed vertical line is the only mark in the empty space that differs from the pixels on both of its sides.
        val image = chart.captureToImage().toAwtImage()
        val rows = (size.height * .2f).toInt() until (size.height * .7f).toInt()
        fun marked(x: Int) = rows.count { y -> image.getRGB(x, y) != image.getRGB(x - 4, y) && image.getRGB(x, y) != image.getRGB(x + 4, y) }
        val lineX = ((newestX + step).toInt()..(right.toInt() - 5)).maxBy(::marked)
        assertTrue(marked(lineX) > rows.count() * .3, "a dashed vertical line is drawn after the newest bar, ${marked(lineX)} of ${rows.count()} rows")
        assertTrue(abs(lineX - pointerX) <= step / 2 + 2, "the line stands under the pointer: $lineX against $pointerX")

        chart.performMouseInput { moveTo(Offset(newestX, row)) }
        chart.requestFocus()
        chart.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val keyed = assertNotNull(cross())
        assertEquals(newest + 1, keyed.first, "the right arrow steps past the newest bar")

        chart.performTouchInput { click(Offset(pointerX, row)) }
        compose.waitForIdle()
        assertEquals(beyond.first, assertNotNull(cross()).first, "a tap in the empty space places the crosshair there")
    }
}
