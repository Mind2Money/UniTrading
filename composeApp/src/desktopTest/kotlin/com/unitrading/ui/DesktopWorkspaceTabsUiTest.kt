package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopWorkspaceTabsUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemorySnapshotStore()
    private val broker = OfflineOrderBroker()
    private var runtime by mutableStateOf(newRuntime())
    private var windows by mutableStateOf(FakePanelWindows())
    private fun newRuntime() = TradingRuntime(store, listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
    private fun render() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f), LocalChartPlatform provides ChartPlatform.DESKTOP) {
            val current = runtime; val host = windows
            key(current, host) { CompositionLocalProvider(LocalPanelWindowHost provides host) {
                Box(Modifier.requiredSize(1500.dp, 1000.dp)) { TradingScreen(current) }
            } }
        } }
        compose.waitForIdle()
    }
    private fun addTab(tag: String = "chart-new-workspace") {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun layout(name: String) {
        compose.onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
        compose.onNodeWithTag("chart-layout-$name").performClick()
        compose.waitForIdle()
    }
    @Test fun tabsKeepIndependentLayoutsCanDetachAndRestoreOnRestart() {
        render()
        compose.onNodeWithTag("rail-windows").assertDoesNotExist()
        compose.onNodeWithTag("rail-new-chart").assertDoesNotExist()
        addTab()
        assertEquals(2, runtime.desktopWorkspaceCount.value)
        layout("TWO_COLUMNS")
        compose.onNodeWithTag("split-cell-c1s1").assertExists()
        val other = compose.onNode(hasTestTag("trading-chart") and hasAnyAncestor(hasTestTag("split-cell-c1s1")))
        val size = other.fetchSemanticsNode().size
        other.performMouseInput { moveTo(Offset(size.width * .65f, size.height * .65f)); press(); release() }
        compose.onNodeWithTag("chart-symbol").performClick()
        compose.onNodeWithTag("symbol-option-OTHRZ6").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-tab-main").performClick()
        compose.onNodeWithTag("split-cell-c1s1").assertDoesNotExist()
        compose.onNodeWithTag("chart-symbol").assertTextContains("TESTZ6", substring = true)
        compose.onNodeWithTag("workspace-tab-c1").performClick()
        compose.onNodeWithTag("split-cell-c1s1").assertExists()
        compose.onNodeWithTag("chart-symbol").assertTextContains("OTHRZ6", substring = true)
        // The tab's detach button is also a keyboard-accessible alternative to dragging it out.
        compose.onNodeWithTag("detach-workspace-c1").performClick()
        compose.waitUntil(10_000) { "chart-c1" in windows.open }
        compose.onNodeWithTag("fake-window-chart-c1").assertExists()
        compose.onNodeWithTag("split-cell-c1s1").assertExists()
        val detachedToolbar = compose.onNode(hasTestTag("chart-layout-menu") and hasAnyAncestor(hasTestTag("fake-window-chart-c1")))
        detachedToolbar.performClick()
        compose.onNodeWithTag("chart-layout-THREE_LEFT").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("split-cell-c1s2").assertExists()
        assertTrue(runBlocking { runtime.desktopWorkspaceSaver!!.invoke() })
        assertEquals("c1s1", runtime.chartPreferences.preferences.value.workspaceSelections["c1"])
        compose.runOnIdle { windows = FakePanelWindows(); runtime = newRuntime() }
        compose.waitUntil(10_000) { "chart-c1" in windows.open }
        compose.onNodeWithTag("split-cell-c1s2").assertExists()
        compose.onNodeWithTag("dock-workspace-c1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-tab-c1").assertExists()
        compose.onNodeWithTag("split-cell-c1s2").assertExists()
        val out = File("../artifacts/desktop-tabs/workspaces.png").also { it.parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
    }
    @Test fun rightNewWindowRemainsAndResetNeedsConfirmation() {
        render()
        compose.onNodeWithTag("chart-new-workspace").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("chart-c1" !in windows.open, "The add button creates a tab rather than a detached window")
        compose.onNodeWithTag("chart-new-workspace").assertExists()
        compose.waitForIdle()
        compose.runOnIdle { runtime.desktopLayout.update(runtime.desktopLayout.preferences.value.copy(sidebarWidth = 410f)) }
        compose.onNodeWithTag("reset-desktop-layout").performClick()
        compose.onNodeWithTag("cancel-reset-desktop-layout").performClick()
        assertEquals(410f, runtime.desktopLayout.preferences.value.sidebarWidth)
        compose.onNodeWithTag("reset-desktop-layout").performClick()
        compose.onNodeWithTag("confirm-reset-desktop-layout").performClick()
        assertEquals(320f, runtime.desktopLayout.preferences.value.sidebarWidth)
        compose.onNodeWithTag("workspace-tab-c1").assertExists()
        val header = compose.onNodeWithTag("desktop-title-toolbar").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("chart-workspace-tabs").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
        val accounts = compose.onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
        assertTrue(tabs.top >= header.top && tabs.bottom <= header.bottom + 1f)
        assertTrue(accounts.top >= toolbar.top && accounts.bottom <= toolbar.bottom + 1f)
        assertTrue(accounts.width <= 340f * .6f + 1f)
    }
    @Test fun foldedTradeBarKeepsAccountPickersAndMenusWorking() {
        render(); addTab()
        compose.onNodeWithTag("desktop-terminal-toggle").performClick()
        compose.onNodeWithTag("desktop-trade-accounts").assertIsDisplayed()
        val out = File("../artifacts/desktop-tabs/folded-accounts.png").also { it.parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
        compose.runOnIdle { runBlocking { runtime.theme.apply(com.unitrading.app.ThemeSettings(com.unitrading.app.ThemePreset.LIGHT)) } }
        compose.waitForIdle()
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(out.parentFile, "folded-accounts-light.png"))
        compose.onNodeWithTag("quick-login-picker").assertIsDisplayed().performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").assertExists().performClick()
        compose.onNodeWithTag("quick-account-picker").assertIsDisplayed().performClick()
        compose.onNodeWithTag("quick-account-${broker.accounts.first().id}").assertExists().performClick()
        compose.onNodeWithTag("flatten-all-accounts").assertIsDisplayed().performClick()
        compose.onNodeWithText("平仓全部子账户？").assertExists()
        compose.onNodeWithTag("cancel-bulk-flatten").performClick()
        assertTrue(broker.placed.isEmpty(), "Cancelling the flatten confirmation never submits a trade")
        compose.onNodeWithTag("desktop-terminal-toggle").performClick()
        compose.onNodeWithTag("desktop-trade-accounts").assertIsDisplayed()
    }
    @Test fun eachTabShowsItsOwnLiveLastTradeAndChange() {
        render()
        compose.runOnIdle {
            val first = broker.market.value.quotes.getValue("TESTZ6").copy(lastTrade = 550000, settlement = 555000)
            val second = broker.market.value.quotes.getValue("OTHRZ6").copy(lastTrade = 700000, settlement = 700000)
            broker.market.value = broker.market.value.copy(quotes = mapOf("TESTZ6" to first, "OTHRZ6" to second), revision = 1)
        }
        compose.onNodeWithTag("workspace-price-main", useUnmergedTree = true).assertTextEquals("5,500.00")
        compose.onNodeWithTag("workspace-change-main", useUnmergedTree = true).assertTextContains("−0.90%", substring = true)
        addTab()
        compose.onNodeWithTag("chart-symbol").performClick()
        compose.onNodeWithTag("symbol-option-OTHRZ6").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-symbol-c1", useUnmergedTree = true).assertTextEquals("OTHRZ6")
        compose.onNodeWithTag("workspace-price-c1", useUnmergedTree = true).assertTextEquals("7,000.00")
        compose.runOnIdle { runtime.marketWatch.update(broker.descriptor.id, listOf("TESTZ6")) }
        compose.onNodeWithTag("workspace-tab-main").performClick()
        compose.waitUntil(5_000) { broker.quoteWatches.lastOrNull()?.contains("OTHRZ6") == true }
        compose.runOnIdle {
            val updated = broker.market.value.quotes.getValue("OTHRZ6").copy(lastTrade = 701000)
            broker.market.value = broker.market.value.copy(quotes = broker.market.value.quotes + ("OTHRZ6" to updated), revision = 2)
        }
        compose.onNodeWithTag("workspace-price-c1", useUnmergedTree = true).assertTextEquals("7,010.00")
        compose.onNodeWithTag("workspace-change-c1", useUnmergedTree = true).assertTextContains("+0.14%", substring = true)
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun windowsFrameHostsLiveTabsWithoutASecondToolbar() {
        val slot = DesktopTitleBarSlot()
        lateinit var frame: ComposeWindow
        compose.runOnIdle { frame = ComposeWindow() }
        val windowScope = object : FrameWindowScope { override val window: ComposeWindow = frame }
        val windowState = WindowState()
        broker.market.value = broker.market.value.copy(quotes = broker.market.value.quotes.mapValues { (_, q) ->
            q.copy(lastTrade = 550000, settlement = 555000)
        })
        try {
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f), LocalChartPlatform provides ChartPlatform.DESKTOP,
                LocalPanelWindowHost provides windows, LocalDesktopTitleBarSlot provides slot) {
                Column(Modifier.requiredSize(1500.dp, 1000.dp)) {
                    TradingTheme { with(windowScope) { WindowsTitleBar(slot, windowState, "UniTrading · 交易终端") { } } }
                    Box(Modifier.weight(1f).fillMaxWidth()) { TradingScreen(runtime) }
                }
            } }
            compose.waitForIdle()
            compose.onNode(hasTestTag("workspace-tab-main") and hasAnyAncestor(hasTestTag("desktop-title-toolbar"))).assertExists()
            compose.onNodeWithTag("chart-new-workspace").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("workspace-tab-c1") and hasAnyAncestor(hasTestTag("desktop-title-toolbar"))).assertExists()
            compose.onNodeWithTag("window-minimize").assertExists()
            compose.onNodeWithTag("window-maximize").assertExists()
            compose.onNodeWithTag("window-close").assertExists()
            val toolbar = compose.onNodeWithTag("app-toolbar").fetchSemanticsNode().boundsInRoot
            val accounts = compose.onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
            assertTrue(accounts.top >= toolbar.top && accounts.bottom <= toolbar.bottom + 1f)
            val out = File("../artifacts/desktop-tabs/windows-title-tabs.png").also { it.parentFile.mkdirs() }
            ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
        } finally { compose.runOnIdle { frame.dispose() } }
    }

    @Test fun restoredWindowsTitleUpdatesAllQuotesWithoutSelectingEachTab() {
        val slot = DesktopTitleBarSlot()
        lateinit var frame: ComposeWindow
        compose.runOnIdle { frame = ComposeWindow() }
        val windowScope = object : FrameWindowScope { override val window: ComposeWindow = frame }
        val state = WindowState()
        runBlocking {
            assertTrue(runtime.chartPreferences.setLastChart(broker.descriptor.id, "TESTZ6", Timeframe.M5))
            assertTrue(runtime.chartPreferences.setExtraCharts(broker.descriptor.id, listOf(Triple("c1", "OTHRZ6", Timeframe.M5))))
            assertTrue(runtime.chartPreferences.setWorkspaces(broker.descriptor.id, emptyMap(), emptyMap(), emptyList(),
                emptyMap(), emptySet(), "main", listOf("main", "c1")))
        }
        runtime = newRuntime()
        try {
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f), LocalChartPlatform provides ChartPlatform.DESKTOP,
                LocalPanelWindowHost provides windows, LocalDesktopTitleBarSlot provides slot) {
                Column(Modifier.requiredSize(1500.dp, 1000.dp)) {
                    TradingTheme { with(windowScope) { WindowsTitleBar(slot, state, "UniTrading · 交易终端") { } } }
                    Box(Modifier.weight(1f).fillMaxWidth()) { TradingScreen(runtime) }
                }
            } }
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-price-main", useUnmergedTree = true).assertTextEquals("—")
            compose.onNodeWithTag("workspace-price-c1", useUnmergedTree = true).assertTextEquals("—")
            compose.waitUntil(5_000) { broker.quoteWatches.lastOrNull()?.containsAll(listOf("TESTZ6", "OTHRZ6")) == true }
            compose.runOnIdle {
                val first = broker.market.value.quotes.getValue("TESTZ6").copy(lastTrade = 550000, settlement = 555000)
                val second = broker.market.value.quotes.getValue("OTHRZ6").copy(lastTrade = 701000, settlement = 700000)
                broker.market.value = broker.market.value.copy(quotes = mapOf("TESTZ6" to first, "OTHRZ6" to second), revision = 7)
            }
            // No tab or chart click: restored title data must follow the next market snapshot.
            compose.onNodeWithTag("workspace-price-main", useUnmergedTree = true).assertTextEquals("5,500.00")
            compose.onNodeWithTag("workspace-change-main", useUnmergedTree = true).assertTextContains("−0.90%", substring = true)
            compose.onNodeWithTag("workspace-price-c1", useUnmergedTree = true).assertTextEquals("7,010.00")
            compose.onNodeWithTag("workspace-change-c1", useUnmergedTree = true).assertTextContains("+0.14%", substring = true)
            val restored = compose.onNodeWithTag("workspace-tab-c1")
            val size = restored.fetchSemanticsNode().size
            restored.performMouseInput { moveTo(Offset(size.width * .15f, size.height * .5f)); press() }
            compose.runOnIdle {
                broker.market.value = broker.market.value.copy(revision = 8,
                    quotes = broker.market.value.quotes.mapValues { (_, q) -> q.copy(lastTrade = q.lastTrade?.plus(100)) })
            }
            restored.performMouseInput { release() }
            compose.onNodeWithTag("chart-symbol").assertTextContains("OTHRZ6", substring = true)
            compose.onNodeWithTag("detach-workspace-c1").performMouseInput { click() }
            compose.waitUntil(10_000) { "chart-c1" in windows.open }
            compose.onNodeWithTag("dock-workspace-c1").performClick()
            compose.onNodeWithTag("close-workspace-c1").performMouseInput { click() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isEmpty() }
        } finally { compose.runOnIdle { frame.dispose() } }
    }

    @Test fun windowsTitleTabsRespondToMouseAfterQuotesUpdate() {
        val slot = DesktopTitleBarSlot()
        lateinit var frame: ComposeWindow
        compose.runOnIdle { frame = ComposeWindow() }
        val windowScope = object : FrameWindowScope { override val window: ComposeWindow = frame }
        try {
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f), LocalChartPlatform provides ChartPlatform.DESKTOP,
                LocalPanelWindowHost provides windows, LocalDesktopTitleBarSlot provides slot) {
                Column(Modifier.requiredSize(1500.dp, 1000.dp)) {
                    TradingTheme { with(windowScope) { WindowsTitleBar(slot, WindowState(), "UniTrading · 交易终端") { } } }
                    Box(Modifier.weight(1f).fillMaxWidth()) { TradingScreen(runtime) }
                }
            } }
            compose.onNodeWithTag("chart-new-workspace").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chart-symbol").performClick()
            compose.onNodeWithTag("symbol-option-OTHRZ6").performClick()
            compose.onNodeWithTag("chart-new-workspace").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c2").fetchSemanticsNodes().isNotEmpty() }
            var revision = 8L
            fun quoteTick() = compose.runOnIdle {
                revision++
                broker.market.value = broker.market.value.copy(quotes = broker.market.value.quotes.mapValues { (_, q) ->
                    q.copy(lastTrade = 550000 + revision, settlement = 555000)
                }, revision = revision)
            }
            quoteTick()
            fun clickTab(id: String) {
                val tab = compose.onNodeWithTag("workspace-tab-$id")
                val size = tab.fetchSemanticsNode().size
                tab.performMouseInput { moveTo(Offset(size.width * .15f, size.height * .5f)); press() }
                quoteTick()
                tab.performMouseInput { release() }
                compose.waitForIdle()
            }
            fun left(id: String) = compose.onNodeWithTag("workspace-tab-$id").fetchSemanticsNode().boundsInRoot.left
            clickTab("main")
            compose.onNodeWithTag("chart-symbol").assertTextContains("TESTZ6", substring = true)
            clickTab("c1")
            compose.onNodeWithTag("chart-symbol").assertTextContains("OTHRZ6", substring = true)
            val source = compose.onNodeWithTag("workspace-tab-c2")
            val from = source.fetchSemanticsNode().boundsInRoot
            val to = compose.onNodeWithTag("workspace-tab-main").fetchSemanticsNode().boundsInRoot
            val delta = to.center.x - from.center.x - 20f
            source.performMouseInput {
                val start = Offset(from.width * .35f, from.height * .5f)
                moveTo(start); press(); moveTo(start + Offset(delta * .5f, 0f))
            }
            quoteTick()
            source.performMouseInput { moveBy(Offset(delta * .5f, 0f)); release() }
            compose.waitForIdle()
            assertTrue(left("c2") < left("main"), "Dragging a Windows title tab changes its order")
            val detach = compose.onNodeWithTag("detach-workspace-c1")
            detach.performMouseInput { moveTo(center); press() }
            quoteTick()
            detach.performMouseInput { release() }
            compose.waitUntil(10_000) { "chart-c1" in windows.open }
            compose.onNodeWithTag("dock-workspace-c1").performClick()
            compose.waitUntil(10_000) { "chart-c1" !in windows.open }
            val close = compose.onNodeWithTag("close-workspace-c1")
            close.performMouseInput { moveTo(center); press() }
            quoteTick()
            close.performMouseInput { release() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isEmpty() }
            assertEquals(2, runtime.desktopWorkspaceCount.value)
            assertTrue(broker.placed.isEmpty())
        } finally { compose.runOnIdle { frame.dispose() } }
    }

    @Test fun tabsDragHorizontallyAndKeepTheirOrderAfterRestart() {
        render(); addTab()
        compose.onNodeWithTag("chart-new-workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c2").fetchSemanticsNodes().isNotEmpty() }
        fun left(id: String) = compose.onNodeWithTag("workspace-tab-$id").fetchSemanticsNode().boundsInRoot.left
        fun dragTo(from: String, to: String, extra: Float) {
            val source = compose.onNodeWithTag("workspace-tab-$from")
            val sourceBounds = source.fetchSemanticsNode().boundsInRoot
            val targetBounds = compose.onNodeWithTag("workspace-tab-$to").fetchSemanticsNode().boundsInRoot
            val delta = targetBounds.center.x - sourceBounds.center.x + extra
            source.performMouseInput {
                val start = Offset(sourceBounds.width * .35f, sourceBounds.height * .5f)
                moveTo(start); press(); moveTo(start + Offset(delta * .5f, 0f)); moveTo(start + Offset(delta, 0f)); release()
            }
            compose.waitForIdle()
        }
        dragTo("c2", "main", -20f)
        assertTrue(left("c2") < left("main") && left("main") < left("c1"), "A new tab can move before the main tab")
        assertTrue("chart-c2" !in windows.open, "A horizontal drag must not detach the tab")
        compose.onNodeWithTag("detach-workspace-c1").performClick()
        compose.waitUntil(10_000) { "chart-c1" in windows.open }
        dragTo("main", "c2", -20f)
        compose.onNodeWithTag("dock-workspace-c1").performClick()
        compose.waitUntil(10_000) { "chart-c1" !in windows.open }
        assertTrue(left("main") < left("c2") && left("c2") < left("c1"), "Docking retains the detached tab's place")
        assertTrue(runBlocking { runtime.desktopWorkspaceSaver!!.invoke() })
        assertEquals(listOf("main", "c2", "c1"), runtime.chartPreferences.preferences.value.workspaceOrder)
        compose.runOnIdle { windows = FakePanelWindows(); runtime = newRuntime() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-tab-c2").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(left("main") < left("c2") && left("c2") < left("c1"), "Saved ordering is restored after restart")
        assertTrue(broker.placed.isEmpty(), "Reordering workspaces does not send trades")
        val out = File("../artifacts/desktop-tabs/reordered-tabs.png").also { it.parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
    }

    @Test fun detachedTabCanBeDraggedBackOnlyOverTheMainTabStrip() {
        render(); addTab()
        compose.onNodeWithTag("detach-workspace-c1").performClick()
        compose.waitUntil(10_000) { "chart-c1" in windows.open }
        compose.runOnIdle { windows.dockAllowed = false }
        compose.onNodeWithTag("drag-workspace-c1").performTouchInput { swipe(center, center + Offset(0f, -60f)) }
        compose.onNodeWithTag("fake-window-chart-c1").assertExists()
        compose.runOnIdle { windows.dockAllowed = true }
        compose.onNodeWithTag("drag-workspace-c1").performTouchInput { swipe(center, center + Offset(0f, -60f)) }
        compose.waitUntil(10_000) { "chart-c1" !in windows.open }
        compose.onNodeWithTag("workspace-tab-c1").assertExists()
    }
    @Test fun desktopPlotMenuKeepsTradingAndRemovesRequestedEntries() {
        render()
        val plot = compose.onNode(hasTestTag("trading-chart") and hasAnyAncestor(hasTestTag("desktop-chart")))
        val size = plot.fetchSemanticsNode().size
        plot.performMouseInput { moveTo(Offset(size.width * .7f, size.height * .65f)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.onNodeWithTag("plot-reset").assertExists()
        compose.onNodeWithTag("plot-open-order").assertExists()
        compose.onNodeWithTag("plot-objects").assertExists()
        compose.onNodeWithTag("plot-copy-price").assertDoesNotExist()
        compose.onNodeWithTag("plot-paste-drawing").assertDoesNotExist()
        compose.onNodeWithTag("plot-menu-horizontal-line").assertDoesNotExist()
        compose.onNodeWithTag("plot-time-lock").assertDoesNotExist()
        compose.onNodeWithTag("plot-table").assertDoesNotExist()
    }
    @Test fun tabCanBeDraggedOutToASeparateWindow() {
        render(); addTab()
        compose.onNodeWithTag("workspace-tab-c1").performTouchInput { swipe(center, center + Offset(0f, 80f)) }
        compose.waitUntil(10_000) { "chart-c1" in windows.open }
        compose.onNodeWithTag("fake-window-chart-c1").assertExists()
    }
}
