package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.BrokerPlugin
import com.unitrading.broker.AccountInfo
import com.unitrading.broker.OrderFlowSnapshot
import com.unitrading.broker.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopDetachedWorkspaceUiTest {
    @Test fun detachedWindowHasIndependentRailAndOrdersFollowItsChart() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val source = OfflineOrderBroker()
        val depthWatches = mutableListOf<String?>()
        val broker = object : BrokerPlugin by source {
            override val orderFlow = MutableStateFlow(OrderFlowSnapshot())
            override fun watchOrderFlow(symbol: String?) { depthWatches += symbol }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        val windows = FakePanelWindows(strip = 760.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP,
            LocalPanelWindowHost provides windows) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("chart-new-workspace").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("chart-symbol").performClick()
        onNodeWithTag("symbol-option-OTHRZ6").performClick()
        onNodeWithTag("detach-workspace-c1").performClick()
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open }
        val child = hasAnyAncestor(hasTestTag("fake-window-chart-c1"))
        onNode(hasTestTag("detached-account-toolbar-c1") and child).assertExists()
        onNode(hasTestTag("detached-account-strip-c1") and child).assertExists()
        onNode(hasTestTag("chart-new-workspace") and child).assertDoesNotExist()
        onNode(hasTestTag("quick-login-picker") and child).assertExists()
        onNode(hasTestTag("quick-account-picker") and child).assertExists()
        val bar = onNode(hasTestTag("detached-account-toolbar-c1") and child).fetchSemanticsNode().boundsInRoot
        val tools = onNode(hasTestTag("chart-toolbar") and child).fetchSemanticsNode().boundsInRoot
        val accounts = onNode(hasTestTag("desktop-trade-accounts") and child).fetchSemanticsNode().boundsInRoot
        assertTrue(tools.top >= bar.top && tools.bottom <= bar.bottom + 1f)
        assertTrue(accounts.top >= bar.top && accounts.bottom <= bar.bottom + 1f && accounts.left >= tools.right - 1f)
        onNode(hasTestTag("market-watch") and child).assertExists()

        val chart = onNode(hasTestTag("trading-chart") and child)
        val chartSize = chart.fetchSemanticsNode().size
        chart.performMouseInput { click(androidx.compose.ui.geometry.Offset(chartSize.width * .7f, chartSize.height * .7f)) }
        chart.performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(chartSize.width * .7f, chartSize.height * .7f))
            press(MouseButton.Secondary); release(MouseButton.Secondary)
        }
        onNodeWithTag("plot-menu-BUY-MARKET").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("order-fields") and child).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(source.placed.isEmpty(), "A child chart context menu only opens its own order form")

        onNodeWithTag("order-popup").assertDoesNotExist()
        onNode(hasText("OTHRZ6", substring = true) and hasAnyAncestor(hasTestTag("detached-side-panel-c1"))).assertExists()
        onNode(hasTestTag("review-order") and child).performClick()
        onNodeWithTag("desktop-order-confirmation").assertExists()
        onNode(hasText("OTHRZ6 ·", substring = true) and hasAnyAncestor(hasTestTag("desktop-order-confirmation"))).assertExists()
        onNodeWithTag("cancel-order-review").performClick()
        assertTrue(source.placed.isEmpty(), "Reviewing and cancelling a child-window order never submits it")

        onNodeWithTag("workspace-tab-main").performClick()
        onNode(hasTestTag("inactive-workspace-order-c1") and child).assertExists()
        onNode(hasTestTag("activate-workspace-order-c1") and child).performClick()
        onNode(hasTestTag("order-fields") and child).assertExists()
        assertTrue(source.placed.isEmpty(), "Switching focus between workspaces never submits the old ticket")

        onNodeWithTag("detached-rail-dom-c1").performScrollTo().performClick()
        onNode(hasTestTag("dom-panel") and child).assertExists()
        waitUntil(timeoutMillis = 5_000) { depthWatches.contains("OTHRZ6") }
        onNodeWithTag("detached-rail-terminal-c1").performScrollTo().performClick()
        onNode(hasTestTag("desktop-trade-tab-POSITIONS") and child).assertExists()
        onNode(hasTestTag("desktop-trade-tab-ORDERS") and child).assertExists()
        onNode(hasTestTag("desktop-trade-tab-EXECUTIONS") and child).assertExists()
        onNode(hasTestTag("desktop-trade-tab-CLOSED_TRADES") and child).assertExists()
        val screenshot = File("../artifacts/detached-workspace/detached-workspace.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("fake-window-chart-c1").captureToImage().toAwtImage(), "png", screenshot)
        assertTrue(source.placed.isEmpty())
    }

    @Test fun detachedSubaccountStaysIndependentAndTradesItsOwnAccount() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val first = AccountInfo("100", "主窗口账户")
        val second = AccountInfo("200", "子窗口账户")
        val broker = RiskFixture(children = listOf(first, second))
        broker.hold(first.id, listOf(riskPosition(Side.BUY, 100, "main-position")))
        broker.hold(second.id, listOf(riskPosition(Side.BUY, 100, "child-position")))
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        val windows = FakePanelWindows(strip = 760.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP,
            LocalPanelWindowHost provides windows) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("chart-new-workspace").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").performClick()
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open }
        val child = hasAnyAncestor(hasTestTag("fake-window-chart-c1"))
        val main = hasAnyAncestor(hasTestTag("app-toolbar"))
        onNode(hasTestTag("quick-login-picker") and child).assertIsNotEnabled()
        onNode(hasContentDescription("主账户由主窗口切换") and child, useUnmergedTree = true).assertExists()
        onNode(hasTestTag("quick-account-picker") and child).performClick()
        onNodeWithTag("rename-account-${second.id}").assertDoesNotExist()
        onNodeWithTag("quick-account-${second.id}").performClick()
        onNode(hasTestTag("quick-account-picker") and child).assertTextContains("子窗口账户", substring = true)
        onNode(hasTestTag("quick-account-picker") and main).assertTextContains("主窗口账户", substring = true)
        onNode(hasTestTag("flatten-selected-account") and child).assertIsEnabled().performMouseInput { click() }
        onNodeWithText("平仓此子账户？").assertExists()
        onNodeWithText(second.id, substring = true).assertExists()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        onNode(hasTestTag("flatten-all-accounts") and child).performMouseInput { click() }
        onNodeWithText("平仓全部子账户？").assertExists()
        onNodeWithTag("cancel-bulk-flatten").performClick()
        onNodeWithTag("detached-rail-terminal-c1").performScrollTo().performClick()
        onNode(hasTestTag("position-child-position") and child).assertExists()
        onNode(hasTestTag("position-main-position") and child).assertDoesNotExist()
        val childChart = onNode(hasTestTag("trading-chart") and child)
        val childSize = childChart.fetchSemanticsNode().size
        childChart.performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(childSize.width * .70f, childSize.height * .70f))
            press(MouseButton.Secondary); release(MouseButton.Secondary)
        }
        onNodeWithTag("plot-menu-BUY-MARKET").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("review-order") and child).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(broker.events.none { it.startsWith("place:") }, "A chart menu must not place an order")
        onNodeWithTag("order-popup").assertDoesNotExist()
        onNode(hasTestTag("review-order") and child).performClick()
        onNode(hasText("子窗口账户", substring = true) and hasAnyAncestor(hasTestTag("desktop-order-confirmation"))).assertExists()
        onNode(hasTestTag("quick-account-picker") and child).assertIsNotEnabled()
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { "place:200" in broker.events }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNode(hasTestTag("quick-account-picker") and child).assertIsEnabled() }.isSuccess }
        onNodeWithTag("workspace-tab-main").performClick()
        onNode(hasTestTag("quick-account-picker") and main).assertTextContains("主窗口账户", substring = true)
        onNode(hasTestTag("quick-account-picker") and child).assertTextContains("子窗口账户", substring = true)
        onNode(hasText("主窗口账户", substring = true) and hasAnyAncestor(hasTestTag("desktop-order"))).assertExists()
        // Fake windows cover the main test surface. Invoke the main button's action directly so
        // this test exercises its callback rather than clicking the child popup layered over it.
        onNode(hasTestTag("review-order") and hasAnyAncestor(hasTestTag("desktop-order")))
            .assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        val notices = onAllNodesWithTag("trade-notice").fetchSemanticsNodes().map { it.config.toString() }
        assertTrue(onAllNodesWithTag("desktop-order-confirmation").fetchSemanticsNodes().isNotEmpty(),
            "The main order review did not open; notices: $notices; broker events: ${broker.events}")
        onNode(hasText("主窗口账户", substring = true) and hasAnyAncestor(hasTestTag("desktop-order-confirmation"))).assertExists()
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { "place:100" in broker.events }
        assertEquals(1, broker.openSession(first.id).state.value.positions.size)
        assertEquals(1, broker.openSession(second.id).state.value.positions.size)
        runOnIdle { broker.catalog.value = broker.catalog.value.copy(accounts = listOf(first)) }
        onNode(hasTestTag("quick-account-picker") and child).assertTextContains("选择子账户", substring = true)
        onNode(hasTestTag("flatten-selected-account") and child).assertIsNotEnabled()
        onNode(hasText("子账户不可用，请在此窗口重新选择子账户") and child).assertExists()
        assertEquals(listOf("place:200", "place:100"), broker.events.filter { it.startsWith("place:") })
    }

    @Test fun pendingChildProtectionPreventsEitherWindowFromChangingItsAccount() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val broker = RiskFixture(children = listOf(AccountInfo("100", "主窗口账户"), AccountInfo("200", "子窗口账户")))
        broker.hold("200", listOf(riskPosition(Side.BUY, 100, "child-position")))
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        val windows = FakePanelWindows(strip = 760.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP,
            LocalPanelWindowHost provides windows) {
            Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("chart-new-workspace").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").performClick()
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open }
        val child = hasAnyAncestor(hasTestTag("fake-window-chart-c1"))
        val main = hasAnyAncestor(hasTestTag("app-toolbar"))
        onNode(hasTestTag("quick-account-picker") and child).performClick()
        onNodeWithTag("quick-account-200").performClick()
        onNodeWithTag("detached-rail-terminal-c1").performScrollTo().performClick()
        onNode(hasTestTag("protect-position-child-position") and child).performClick()
        onNodeWithTag("protection-dialog").assertExists()
        onNode(hasTestTag("quick-account-picker") and child).assertIsNotEnabled()
        onNode(hasTestTag("quick-account-picker") and main).assertIsNotEnabled()
        onNode(hasText("取消") and hasAnyAncestor(hasTestTag("protection-dialog"))).performClick()
        onNode(hasTestTag("quick-account-picker") and child).assertIsEnabled()
        onNode(hasTestTag("quick-account-picker") and main).assertIsEnabled()
        assertTrue(broker.events.none { it.startsWith("protect:") }, "Opening and cancelling an edit must not send a broker command")
    }

    @Test fun detachedSubaccountRestoresWithoutChangingTheMainAccount() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val store = MemorySnapshotStore()
        val broker = RiskFixture(children = listOf(AccountInfo("100", "主窗口账户"), AccountInfo("200", "子窗口账户")))
        fun runtime() = TradingRuntime(store, listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        var current by mutableStateOf(runtime())
        var windows by mutableStateOf(FakePanelWindows(strip = 760.dp))
        setContent {
            val app = current; val host = windows
            key(app, host) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP,
                    LocalPanelWindowHost provides host) {
                    Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(app) }
                }
            }
        }
        onNodeWithTag("chart-new-workspace").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").performClick()
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open }
        val child = hasAnyAncestor(hasTestTag("fake-window-chart-c1"))
        val main = hasAnyAncestor(hasTestTag("app-toolbar"))
        onNode(hasTestTag("quick-account-picker") and child).performClick()
        onNodeWithTag("quick-account-200").performClick()
        waitUntil(timeoutMillis = 10_000) { current.workspaceAccounts.selections.value[broker.descriptor.id]?.get("c1") == "200" }
        assertTrue(runBlocking { current.desktopWorkspaceSaver!!.invoke() })
        runOnIdle { windows = FakePanelWindows(strip = 760.dp); current = runtime() }
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open }
        onNode(hasTestTag("quick-account-picker") and child).assertTextContains("子窗口账户", substring = true)
        onNode(hasTestTag("quick-account-picker") and main).assertTextContains("主窗口账户", substring = true)
        onNode(hasTestTag("quick-login-picker") and child).assertIsNotEnabled()
    }

    @Test fun mainWindowAloneChangesTheSharedLogin() = runDesktopComposeUiTest(width = 1600, height = 1000) {
        val first = RiskFixture(login = "first-login", children = listOf(AccountInfo("100", "主账户一")))
        val second = RiskFixture(login = "second-login", children = listOf(AccountInfo("300", "主账户二")))
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(first, second), includeDemoBrokers = false).apply { paused.value = true }
        runBlocking {
            assertTrue(runtime.accountAliases.setLogin(first.descriptor.id, "平台甲"))
            assertTrue(runtime.accountAliases.setLogin(second.descriptor.id, "平台乙"))
        }
        val windows = FakePanelWindows(strip = 760.dp)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.DESKTOP,
            LocalPanelWindowHost provides windows) { Box(Modifier.requiredSize(1600.dp, 1000.dp)) { TradingScreen(runtime) } } }
        onNodeWithTag("chart-new-workspace").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").performClick()
        waitUntil(timeoutMillis = 10_000) { "chart-c1" in windows.open && "c1" in runtime.chartPreferences.preferences.value.detachedWorkspaces }
        val child = hasAnyAncestor(hasTestTag("fake-window-chart-c1"))
        val main = hasAnyAncestor(hasTestTag("app-toolbar"))
        onNode(hasTestTag("quick-login-picker") and child).assertIsNotEnabled().assertTextContains("平台甲", substring = true)
        onNode(hasTestTag("quick-login-picker") and main).performClick()
        onNodeWithTag("quick-login-second-login").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag("quick-login-picker") and hasText("平台乙", substring = true) and child).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasTestTag("quick-login-picker") and child).assertIsNotEnabled().assertTextContains("平台乙", substring = true)
        onNode(hasTestTag("quick-account-picker") and child).performClick()
        onNodeWithTag("rename-account-300").assertDoesNotExist()
    }
}
