package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopChartFullscreenUiTest {
    @get:Rule val compose = createComposeRule()
    private val broker = OfflineOrderBroker()
    private val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
    private fun render() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f), LocalChartPlatform provides ChartPlatform.DESKTOP,
            LocalPanelWindowHost provides FakePanelWindows()) {
            Box(Modifier.requiredSize(1500.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun enter() = compose.onNodeWithTag("chart-fullscreen").performScrollTo().performClick()
    @Test fun fullscreenHidesPanelsAndEscapeRestoresThePreviousLayout() {
        render()
        val before = runtime.desktopLayout.preferences.value
        compose.onNodeWithTag("desktop-panel-rail").assertExists()
        compose.onNodeWithTag("drawing-toolbar").assertExists()
        enter()
        compose.onNodeWithTag("fullscreen-chart-workspace").assertExists()
        compose.onNodeWithTag("desktop-panel-rail").assertDoesNotExist()
        compose.onNodeWithTag("desktop-terminal").assertDoesNotExist()
        compose.onNodeWithTag("desktop-order").assertDoesNotExist()
        compose.onNodeWithTag("desktop-sidebar").assertDoesNotExist()
        compose.onNodeWithTag("drawing-toolbar").assertDoesNotExist()
        compose.onNodeWithTag("chart-fullscreen").assertTextContains("退出全屏")
        compose.onNodeWithTag("trading-chart").performMouseInput { click(center) }
        compose.onNodeWithTag("trading-chart").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        compose.onNodeWithTag("fullscreen-chart-workspace").assertDoesNotExist()
        compose.onNodeWithTag("desktop-panel-rail").assertExists()
        compose.onNodeWithTag("drawing-toolbar").assertExists()
        assertEquals(before, runtime.desktopLayout.preferences.value)
    }

    @Test fun legendOpensMovableNonmodalTicketAndChartStopLineRemainsDraggable() {
        render(); enter()
        compose.onNodeWithTag("legend-buy").performClick()
        compose.onNodeWithTag("fullscreen-order-ticket").assertExists()
        compose.onNodeWithTag("order-kind-MARKET").assertExists()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        val stop = 549_800L
        compose.onNodeWithTag("order-sl").performTextReplacement("5498.00")
        val chart = compose.onNodeWithTag("trading-chart")
        val size = chart.fetchSemanticsNode().size
        val scale = chart.chartAxis(.6f)
        val y = scale.y(stop)
        chart.performTouchInput {
            down(Offset(size.width * .38f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)); up()
        }
        val edited = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertNotEquals("5498.00", edited, "Dragging a stop on the chart should update the open ticket")
        assertTrue(broker.placed.isEmpty(), "Dragging a draft must not place an order")
        val before = compose.onNodeWithTag("fullscreen-order-ticket").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("fullscreen-ticket-drag").performTouchInput { down(center); moveBy(Offset(-50f, 20f)); up() }
        val after = compose.onNodeWithTag("fullscreen-order-ticket").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left < before.left - 10f, "The floating ticket should move without changing the chart")
        val image = File("../artifacts/fullscreen/fullscreen-order.png").also { it.parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", image)
        compose.onNodeWithTag("fullscreen-ticket-close").performClick()
        compose.onNodeWithTag("fullscreen-order-ticket").assertDoesNotExist()
        compose.onNodeWithTag("fullscreen-chart-workspace").assertExists()
    }

    @Test fun rightClickPreparesAnOrderAndKeyboardTradingStillUsesItsSettings() {
        runBlocking { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true)); assertTrue(runtime.tradingPreferences.setSkipConfirmation(true)) }
        render(); enter()
        val chart = compose.onNodeWithTag("trading-chart")
        chart.performMouseInput { moveTo(Offset(size.width * .4f, size.height * .6f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-menu-SELL-MARKET").performClick()
        compose.onNodeWithTag("fullscreen-order-ticket").assertExists()
        assertTrue(broker.placed.isEmpty(), "The right-click menu opens an editor rather than sending an order")
        chart.performMouseInput { click(Offset(size.width * .4f, size.height * .6f)) }
        chart.performKeyInput { keyDown(Key.W); keyUp(Key.W) }
        compose.waitUntil(5_000) { broker.placed.size == 1 }
    }
}
