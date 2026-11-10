package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.DesktopLayoutController
import com.unitrading.app.DesktopLayoutPreferences
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.app.WindowBounds
import com.unitrading.app.WorkspacePanel
import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.Side
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Any panel of the desktop workspace can move into a window of its own and back; the fake windows stand under the screen. */
@OptIn(ExperimentalTestApi::class)
class DesktopWindowsUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val broker = OfflineOrderBroker()
    private var runtime by mutableStateOf(newRuntime())
    private var windows by mutableStateOf(FakePanelWindows())

    private fun newRuntime() = TradingRuntime(store, listOf(broker), includeDemoBrokers = true).apply { paused.value = true }

    /**
     * The screen above, 760 dp of it, and the open windows in a row of 440 dp below. Without [windowed] the platform has
     * no windows to offer. A new [runtime] starts the screen afresh on the same store, as a restart does.
     */
    private fun render(windowed: Boolean = true) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) {
            val current = runtime
            val fake = windows
            key(current, fake) {
                CompositionLocalProvider(LocalPanelWindowHost provides fake.takeIf { windowed }) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(760.dp)) { TradingScreen(current) }
                        fake.WindowRow(Modifier.fillMaxWidth().height(440.dp))
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun restart() {
        compose.runOnIdle { windows = FakePanelWindows(); runtime = newRuntime() }
        compose.waitForIdle()
    }
    private fun layout() = runtime.desktopLayout.preferences.value
    private fun awaitSaved() = compose.waitUntil(5_000) { DesktopLayoutController(store).preferences.value == layout() }
    private fun inWindow(panel: WorkspacePanel, tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("fake-window-${panel.name}")))
    private fun toggle(vararg panels: WorkspacePanel) {
        compose.onNodeWithTag("rail-windows").performClick()
        panels.forEach { compose.onNodeWithTag("window-menu-${it.name}").performClick() }
        // The menu stays open for several choices; the rail entry under it closes it again.
        compose.onNodeWithTag("rail-windows").performClick()
        compose.waitForIdle()
    }

    @Test fun theChartLeavesForItsOwnWindowAndComesBackByTheBarOrByClosingTheWindow() {
        render()
        compose.onNodeWithTag("desktop-chart").assertExists()
        compose.onNodeWithTag("rail-windows").performClick()
        for (panel in WorkspacePanel.entries.filter { it != WorkspacePanel.ORDER_FLOW }) compose.onNodeWithTag("window-menu-${panel.name}").assertExists()
        compose.onNodeWithTag("window-menu-ORDER_FLOW").assertDoesNotExist() // the demo broker has no order flow
        compose.onNodeWithTag("window-menu-CHART").performClick()
        // The menu stays open for further choices and takes the next press outside it to close.
        compose.onNodeWithTag("rail-windows").performClick()
        compose.waitForIdle()
        assertEquals(setOf(WorkspacePanel.CHART), layout().detached)
        compose.onNodeWithTag("desktop-chart").assertDoesNotExist()
        compose.onNodeWithTag("detached-CHART").assertExists()
        inWindow(WorkspacePanel.CHART, "trading-chart").assertExists()
        compose.onAllNodesWithTag("trading-chart").assertCountEquals(1)
        assertEquals("UniTrading · 图表", windows.open.getValue("CHART").title)
        val terminal = compose.onNodeWithTag("desktop-terminal").fetchSemanticsNode().boundsInRoot
        assertTrue(terminal.height > 300f, "the trade list takes the chart's room: ${terminal.height}")
        awaitSaved()
        val folder = File("../artifacts").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "desktop-window-chart.png"))

        compose.onNodeWithTag("dock-CHART").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("desktop-chart").assertExists()
        compose.onNodeWithTag("fake-window-CHART").assertDoesNotExist()
        assertTrue(layout().detached.isEmpty())

        toggle(WorkspacePanel.CHART)
        compose.onNodeWithTag("fake-window-CHART").assertExists()
        compose.runOnIdle { windows.open.getValue("CHART").onClose() }
        compose.waitForIdle()
        compose.onNodeWithTag("desktop-chart").assertExists()
        assertTrue(layout().detached.isEmpty(), "closing the window brings the panel back")
    }

    @Test fun everySidePanelMovesOutAndTheRailStillFoldsIt() {
        render()
        toggle(WorkspacePanel.ORDER, WorkspacePanel.TERMINAL, WorkspacePanel.MARKET)
        for (tag in listOf("desktop-order", "desktop-terminal", "desktop-market")) compose.onNodeWithTag(tag).assertDoesNotExist()
        inWindow(WorkspacePanel.ORDER, "order-quantity").assertExists()
        inWindow(WorkspacePanel.TERMINAL, "trade-terminal").assertExists()
        compose.onNodeWithTag("fake-window-MARKET").assertExists()
        val accounts = compose.onNodeWithTag("desktop-accounts").fetchSemanticsNode().boundsInRoot
        val sidebar = compose.onNodeWithTag("desktop-sidebar").fetchSemanticsNode().boundsInRoot
        assertEquals(sidebar.height, accounts.height, 1f, "the accounts take the whole column while the market list is out")
        compose.onNodeWithTag("resize-accounts").assertDoesNotExist()

        // The rail folds a panel wherever it lives: its window goes, and comes back on the next press.
        compose.onNodeWithTag("rail-order").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fake-window-ORDER").assertDoesNotExist()
        assertEquals(setOf(WorkspacePanel.ORDER, WorkspacePanel.TERMINAL, WorkspacePanel.MARKET), layout().detached, "folded, not brought back")
        compose.onNodeWithTag("rail-order").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fake-window-ORDER").assertExists()
        compose.onNodeWithTag("rail-terminal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fake-window-TERMINAL").assertDoesNotExist()

        toggle(WorkspacePanel.ACCOUNTS)
        compose.onNodeWithTag("desktop-sidebar").assertDoesNotExist() // nothing is left for the side column
        compose.onNodeWithTag("fake-window-ACCOUNTS").assertExists()
        compose.onNodeWithTag("rail-market").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fake-window-ACCOUNTS").assertDoesNotExist()
        compose.onNodeWithTag("fake-window-MARKET").assertDoesNotExist()
    }

    @Test fun detachedPanelsAndTheirWindowBoundsComeBackAfterARestartAndResetBringsEverythingHome() {
        render()
        toggle(WorkspacePanel.CHART, WorkspacePanel.ORDER)
        val moved = WindowBounds(1700f, 120f, 900f, 640f)
        compose.runOnIdle { windows.open.getValue("CHART").onBounds(moved) }
        awaitSaved()
        assertEquals(moved, DesktopLayoutController(store).preferences.value.windows["CHART"])

        restart()
        assertEquals(setOf(WorkspacePanel.CHART, WorkspacePanel.ORDER), layout().detached)
        assertEquals(moved, windows.open.getValue("CHART").bounds, "the window opens where it was")
        assertNull(windows.open.getValue("ORDER").bounds, "a window that was never moved opens at its default size")
        inWindow(WorkspacePanel.CHART, "trading-chart").assertExists()
        compose.onNodeWithTag("reset-desktop-layout").performClick()
        compose.waitForIdle()
        assertEquals(DesktopLayoutPreferences(), layout())
        assertTrue(windows.open.isEmpty())
        compose.onNodeWithTag("desktop-chart").assertExists()
    }

    @Test fun aPlatformWithoutWindowsOffersNoneAndKeepsEveryPanelInPlace() {
        runtime.desktopLayout.update(DesktopLayoutPreferences(detached = setOf(WorkspacePanel.CHART, WorkspacePanel.ORDER)))
        render(windowed = false)
        compose.onNodeWithTag("rail-windows").assertDoesNotExist()
        compose.onNodeWithTag("desktop-chart").assertExists()
        compose.onNodeWithTag("desktop-order").assertExists()
        compose.onNodeWithTag("detached-CHART").assertDoesNotExist()
        assertTrue(windows.open.isEmpty())
    }

    @Test fun tradingFromADetachedChartAsksAndConfirmsLikeTheMainWindow() {
        render()
        compose.onNodeWithTag("broker-picker").performClick()
        compose.onNodeWithText(broker.descriptor.name).performClick()
        toggle(WorkspacePanel.CHART, WorkspacePanel.ORDER)
        val chart = inWindow(WorkspacePanel.CHART, "trading-chart")
        val size = chart.fetchSemanticsNode().size
        chart.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .8f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.waitForIdle()
        compose.onNodeWithTag("plot-menu-BUY-LIMIT").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认 Tradovate Demo 下单").assertExists()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        val order = broker.placed.single()
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.LIMIT, 100, order.entryPrice), order)
        compose.onNodeWithTag("trade-notice").assertExists("the result is reported")
    }
}
