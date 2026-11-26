package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabletChartFullscreenUiTest {
    private fun verifyFullscreen(platform: ChartPlatform, width: Int, height: Int) =
        runDesktopComposeUiTest(width = width, height = height) {
            val broker = OfflineOrderBroker()
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
            } }
            val before = runtime.desktopLayout.preferences.value
            onNodeWithTag("desktop-panel-rail").assertExists()
            onNodeWithTag("tablet-account-bar").assertExists()
            onNodeWithTag("chart-fullscreen").performScrollTo().performClick()
            onNodeWithTag("fullscreen-chart-workspace").assertExists()
            onNodeWithTag("desktop-panel-rail").assertDoesNotExist()
            onNodeWithTag("tablet-account-bar").assertDoesNotExist()
            onNodeWithTag("drawing-toolbar").assertDoesNotExist()
            onNodeWithTag("legend-buy").performClick()
            val workspace = onNodeWithTag("fullscreen-chart-workspace").fetchSemanticsNode().boundsInRoot
            val ticket = onNodeWithTag("fullscreen-order-ticket").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(workspace.center.x - ticket.center.x) <= 1f && abs(workspace.center.y - ticket.center.y) <= 1f)

            onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
            onNodeWithTag("order-sl").performTextReplacement("5498.00")
            val chart = onNodeWithTag("trading-chart")
            val size = chart.fetchSemanticsNode().size
            val y = chart.chartAxis(1f).y(549_800L)
            chart.performTouchInput {
                down(Offset(size.width * .15f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)); up()
            }
            val edited = onNodeWithTag("order-sl").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertNotEquals("5498.00", edited, "The unobscured chart must remain interactive")
            assertTrue(broker.placed.isEmpty())
            onNodeWithTag("fullscreen-ticket-drag").performTouchInput { down(center); moveBy(Offset(45f, 10f)); up() }
            val moved = onNodeWithTag("fullscreen-order-ticket").fetchSemanticsNode().boundsInRoot
            assertTrue(moved.left > ticket.left + 10f)
            onNodeWithTag("fullscreen-ticket-close").performClick()

            chart.performTouchInput { longClick(Offset(size.width * .15f, size.height * .6f)) }
            onNodeWithTag("plot-menu-SELL-MARKET").performClick()
            onNodeWithTag("fullscreen-order-ticket").assertExists()
            assertTrue(broker.placed.isEmpty(), "Preparing an order must not send it")
            onNodeWithTag("chart-fullscreen").performScrollTo().performClick()
            onNodeWithTag("fullscreen-order-ticket").assertDoesNotExist()
            onNodeWithTag("desktop-panel-rail").assertExists()
            onNodeWithTag("tablet-account-bar").assertExists()
            assertEquals(before, runtime.desktopLayout.preferences.value)
        }

    @Test fun androidTabletFullscreen() = verifyFullscreen(ChartPlatform.ANDROID, 1024, 768)
    @Test fun androidPortraitTabletFullscreen() = verifyFullscreen(ChartPlatform.ANDROID, 800, 1200)
    @Test fun ipadFullscreen() = verifyFullscreen(ChartPlatform.IOS, 1024, 768)

    @Test fun phonesKeepTheirExistingLayout() = runDesktopComposeUiTest(width = 412, height = 860) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(412.dp, 860.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("chart-fullscreen").assertDoesNotExist()
    }
}
