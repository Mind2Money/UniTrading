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
import kotlin.test.*

/** The crosshair follows hover, taps and arrow keys; its state is exposed as `cross:<bar>@<price|none>` or `cross:none`. */
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
}
