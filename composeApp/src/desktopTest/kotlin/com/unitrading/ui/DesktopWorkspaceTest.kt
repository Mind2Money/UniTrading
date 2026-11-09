package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private var width by mutableStateOf(1500.dp)
    private var height by mutableStateOf(1000.dp)

    private fun render() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) {
            Box(Modifier.requiredSize(width, height)) { TradingScreen(runtime) }
        } }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun drag(tag: String, delta: Offset) = compose.onNodeWithTag(tag).performTouchInput { swipe(center, center + delta) }
    private fun awaitSaved() = compose.waitUntil(5_000) {
        DesktopLayoutController(store).preferences.value == runtime.desktopLayout.preferences.value
    }

    @Test fun allFourDividersResizeTheirPanelsAndPersistWithoutTrading() {
        render()
        val plugin = runtime.registry.plugins.first()
        val session = plugin.openSession(plugin.accounts.first().id)
        val account = session.state.value
        val sidebar = bounds("desktop-sidebar").width
        val order = bounds("desktop-order").width
        val accounts = bounds("desktop-accounts").height
        val terminal = bounds("desktop-terminal").height
        drag("resize-sidebar", Offset(-54f, 0f))
        assertTrue(bounds("desktop-sidebar").width > sidebar + 30f)
        drag("resize-order", Offset(-54f, 0f))
        assertTrue(bounds("desktop-order").width > order + 30f)
        drag("resize-accounts", Offset(0f, 42f))
        assertTrue(bounds("desktop-accounts").height > accounts + 20f)
        drag("resize-terminal", Offset(0f, -54f))
        assertTrue(bounds("desktop-terminal").height > terminal + 30f)
        assertTrue(bounds("desktop-chart").width >= 320f * .6f - 1f)
        awaitSaved()
        assertEquals(account, session.state.value)
        val target = File("../artifacts/desktop-panels-resized.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
        compose.onNodeWithTag("reset-desktop-layout").performClick()
        assertEquals(DesktopLayoutPreferences(), runtime.desktopLayout.preferences.value)
        awaitSaved()
        assertEquals(sidebar, bounds("desktop-sidebar").width)
        assertEquals(order, bounds("desktop-order").width)
    }

    @Test fun panelsFollowTradingViewsArrangement() {
        render()
        val chart = bounds("desktop-chart")
        val terminal = bounds("desktop-terminal")
        val order = bounds("desktop-order")
        val sidebar = bounds("desktop-sidebar")
        val rail = bounds("desktop-panel-rail")
        assertTrue(terminal.top >= chart.bottom, "The trade list sits under the chart")
        assertEquals(chart.left, terminal.left)
        assertEquals(chart.right, terminal.right, "and only as wide as the chart")
        assertTrue(chart.right <= order.left && order.right <= sidebar.left && sidebar.right <= rail.left, "Then the order panel, the market panel and the icons")
        assertEquals(chart.top, order.top)
        assertEquals(terminal.bottom, order.bottom, "The order panel runs the full height")
        assertEquals(order.bottom, sidebar.bottom)
        compose.onNodeWithTag("broker-picker").assertExists()
        assertTrue(bounds("desktop-market-minimize").right > sidebar.center.x, "Its fold button is in the panel's top right corner")
        val target = File("../artifacts/desktop-panels-open.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }

    private fun assertFolds(toggle: String, gone: String, chartGrows: (androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect) -> Boolean) {
        val before = bounds("desktop-chart")
        compose.onNodeWithTag(toggle).performClick()
        compose.onNodeWithTag(gone).assertDoesNotExist()
        assertTrue(chartGrows(before, bounds("desktop-chart")), "$toggle gives the chart its room")
    }

    @Test fun eachPanelFoldsFromTheIconColumnAndItsOwnButton() {
        render()
        val wider = { a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect -> b.width > a.width }
        val taller = { a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect -> b.height > a.height }
        assertFolds("rail-market", "desktop-sidebar", wider)
        compose.onNodeWithTag("rail-market").performClick()
        compose.onNodeWithTag("desktop-sidebar").assertExists()
        assertFolds("desktop-market-minimize", "desktop-sidebar", wider)
        assertFolds("rail-order", "desktop-order", wider)
        compose.onNodeWithTag("rail-order").performClick()
        assertFolds("desktop-order-minimize", "desktop-order", wider)
        assertFolds("rail-terminal", "trade-terminal", taller)
        compose.onNodeWithTag("desktop-terminal-bar").assertExists()
        compose.onNodeWithTag("resize-terminal").assertDoesNotExist()
        compose.onNodeWithTag("desktop-terminal-toggle").performClick()
        compose.onNodeWithTag("trade-terminal").assertExists()
        assertFolds("desktop-terminal-toggle", "trade-terminal", taller)
        awaitSaved()
        val saved = DesktopLayoutController(store).preferences.value
        assertFalse(saved.marketOpen || saved.orderOpen || saved.terminalOpen)
        val target = File("../artifacts/desktop-panels-folded.png")
        target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", target)
        compose.onNodeWithTag("reset-desktop-layout").performClick()
        compose.onNodeWithTag("desktop-sidebar").assertExists()
        compose.onNodeWithTag("desktop-order").assertExists()
        compose.onNodeWithTag("trade-terminal").assertExists()
    }

    @Test fun theLegendOpensAFoldedOrderPanelAndNarrowWindowsHaveNoOrderSwitch() {
        render()
        compose.onNodeWithTag("rail-order").performClick()
        compose.onNodeWithTag("desktop-order").assertDoesNotExist()
        compose.onNodeWithTag("legend-buy").performClick()
        compose.onNodeWithTag("desktop-order").assertExists()
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("rail-order").assertDoesNotExist()
        compose.onNodeWithTag("rail-market").assertExists()
    }

    @Test fun windowResizingAndMobileModeDoNotDestroyPreferredDimensions() {
        val custom = DesktopLayoutPreferences(400f, 480f, 370f, 340f)
        runtime.desktopLayout.update(custom)
        render()
        val original = bounds("desktop-order").width
        compose.runOnIdle { width = 1240.dp; height = 700.dp }
        assertTrue(bounds("desktop-chart").width >= 320f * .6f - 1f)
        assertEquals(custom, runtime.desktopLayout.preferences.value)
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("resize-order").assertDoesNotExist()
        compose.onNodeWithTag("open-order").assertExists()
        compose.runOnIdle { width = 500.dp }
        compose.onNodeWithTag("desktop-workspace").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.runOnIdle { width = 1500.dp; height = 1000.dp }
        assertEquals(original, bounds("desktop-order").width)
        assertEquals(custom, runtime.desktopLayout.preferences.value)
    }

    @Test fun keyboardAndDoubleClickAdjustAndResetTheFocusedDivider() {
        render()
        val handle = compose.onNodeWithTag("resize-sidebar")
        handle.performTouchInput { click() }
        handle.performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        assertEquals(DesktopLayoutPreferences().sidebarWidth + 8f, runtime.desktopLayout.preferences.value.sidebarWidth)
        awaitSaved()
        handle.performTouchInput { doubleClick() }
        assertEquals(DesktopLayoutPreferences().sidebarWidth, runtime.desktopLayout.preferences.value.sidebarWidth)
        awaitSaved()
    }
}
