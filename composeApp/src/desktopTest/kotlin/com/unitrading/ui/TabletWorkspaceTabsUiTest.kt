package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabletWorkspaceTabsUiTest {
    private fun verifySeparatedTabletActions(platform: ChartPlatform, width: Int, height: Int) =
        runDesktopComposeUiTest(width = width, height = height) {
            val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
            val broker = runtime.registry.plugins.first()
            val account = broker.accounts.first()
            val initialPositions = broker.openSession(account.id).state.value.positions.size
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides platform) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
            } }
            val chart = onNodeWithTag("chart-toolbar").fetchSemanticsNode().boundsInRoot
            val header = onNodeWithTag("tablet-workspace-header").fetchSemanticsNode().boundsInRoot
            val order = onNodeWithTag("rail-order").fetchSemanticsNode().boundsInRoot
            val dom = onNodeWithTag("rail-dom").fetchSemanticsNode().boundsInRoot
            val terminal = onNodeWithTag("rail-terminal").fetchSemanticsNode().boundsInRoot
            val actions = onNodeWithTag("desktop-flatten-actions").fetchSemanticsNode().boundsInRoot
            val accountPicker = onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
            val selected = onNodeWithTag("flatten-selected-account").fetchSemanticsNode().boundsInRoot
            val all = onNodeWithTag("flatten-all-accounts").fetchSemanticsNode().boundsInRoot
            assertTrue(actions.top >= header.top && actions.bottom <= header.bottom, "Flatten actions must stay in the workspace header")
            assertTrue(actions.bottom <= chart.top && chart.right <= accountPicker.left, "Keep chart controls separate from account and flatten actions")
            assertTrue(accountPicker.right <= width && order.right >= width - 12f && dom.bottom <= order.top && order.bottom <= terminal.top, "Place order on the right rail between DOM and trading")
            onNodeWithTag("open-order").assertDoesNotExist()
            assertTrue(selected.right + 15f <= all.left && selected.height >= 48f && all.height >= 48f,
                "Flatten targets must have adequate spacing and touch size")
            val screenshot = File("../artifacts/desktop-tabs/tablet-${platform.name.lowercase()}-${width}x${height}.png")
                .also { it.parentFile.mkdirs() }
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", screenshot)

            onNodeWithTag("rail-order").performClick()
            onNodeWithTag("desktop-order").assertIsDisplayed()
            onNodeWithTag("desktop-order-minimize").performClick()
            onNodeWithTag("desktop-order").assertDoesNotExist()
            onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
            onNodeWithTag("chart-layout-TWO_COLUMNS").assertExists()
            onNodeWithText("平仓全部子账户？").assertDoesNotExist()
            onNodeWithTag("chart-layout-SINGLE").performClick()
            onNodeWithTag("flatten-selected-account").assertIsEnabled().performClick()
            onNodeWithText("平仓此子账户？").assertExists()
            onNodeWithTag("confirm-bulk-flatten").assertTextEquals("确认平仓此子账户")
            onNodeWithTag("cancel-bulk-flatten").performClick()
            onNodeWithTag("flatten-all-accounts").performClick()
            onNodeWithText("平仓全部子账户？").assertExists()
            onNodeWithTag("confirm-bulk-flatten").assertTextEquals("确认平仓全部子账户")
            onNodeWithTag("cancel-bulk-flatten").performClick()
            assertEquals(initialPositions, broker.openSession(account.id).state.value.positions.size, "Cancellation must not close positions")
        }

    @Test fun mediumAndroidTabletKeepsLayoutAndFlattenControlsSeparate() =
        verifySeparatedTabletActions(ChartPlatform.ANDROID, 1024, 768)

    @Test fun portraitAndroidTabletKeepsLayoutAndFlattenControlsSeparate() =
        verifySeparatedTabletActions(ChartPlatform.ANDROID, 800, 1200)

    @Test fun ipadKeepsLayoutAndFlattenControlsSeparate() =
        verifySeparatedTabletActions(ChartPlatform.IOS, 1024, 768)

    @Test fun androidTabletCreatesSwitchesReordersAndRestoresTabsWithoutDetaching() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val store = MemorySnapshotStore()
        val broker = OfflineOrderBroker()
        fun newRuntime() = TradingRuntime(store, listOf(broker), includeDemoBrokers = false).apply { paused.value = true }
        var runtime by mutableStateOf(newRuntime())
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            val current = runtime
            key(current) { Box(Modifier.requiredSize(1440.dp, 900.dp)) { TradingScreen(current) } }
        } }
        runOnIdle {
            val first = broker.market.value.quotes.getValue("TESTZ6").copy(lastTrade = 550000, settlement = 555000)
            val second = broker.market.value.quotes.getValue("OTHRZ6").copy(lastTrade = 551000, settlement = 550000)
            broker.market.value = broker.market.value.copy(quotes = mapOf("TESTZ6" to first, "OTHRZ6" to second), revision = 1)
        }
        onNodeWithTag("tablet-workspace-header").assertIsDisplayed()
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        onNodeWithTag("desktop-panel-rail").assertIsDisplayed()
        onNodeWithTag("desktop-flatten-actions").assertIsDisplayed()
        onNodeWithTag("desktop-trade-accounts").assertIsDisplayed()
        val flatten = onNodeWithTag("desktop-flatten-actions").fetchSemanticsNode().boundsInRoot
        val accounts = onNodeWithTag("desktop-trade-accounts").fetchSemanticsNode().boundsInRoot
        val chartToolbar = onNodeWithTag("chart-toolbar").fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("tablet-workspace-header").fetchSemanticsNode().boundsInRoot
        val order = onNodeWithTag("rail-order").fetchSemanticsNode().boundsInRoot
        assertTrue(flatten.top >= header.top && flatten.bottom <= header.bottom && flatten.bottom <= chartToolbar.top)
        assertTrue(chartToolbar.right <= accounts.left && order.right >= 1428f)
        onNodeWithTag("open-order").assertDoesNotExist()
        onNodeWithTag("desktop-trade-tab-POSITIONS").assertIsDisplayed()
        onNodeWithTag("rail-analytics").performScrollTo().performClick()
        onNodeWithTag("desktop-analytics").assertIsDisplayed()
        onNodeWithTag("rail-market").performScrollTo().performClick()
        onNodeWithTag("open-settings").performScrollTo().performClick()
        onNodeWithTag("settings-page").assertIsDisplayed()
        onNodeWithTag("close-settings").performClick()
        onNodeWithTag("reset-desktop-layout").performClick()
        onNodeWithTag("cancel-reset-desktop-layout").performClick()
        onNodeWithTag("rail-new-chart").assertDoesNotExist()
        onNodeWithTag("nav-4").assertDoesNotExist()
        onNodeWithTag("workspace-price-main", useUnmergedTree = true).assertTextEquals("5,500.00")
        onNodeWithTag("workspace-change-main", useUnmergedTree = true).assertTextContains("−0.90%", substring = true)
        onNodeWithTag("chart-new-workspace").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-main").assertDoesNotExist()
        onNodeWithTag("detach-workspace-c1").assertDoesNotExist()
        onNodeWithTag("chart-symbol").performClick()
        onNodeWithTag("symbol-option-OTHRZ6").performClick()
        onNodeWithTag("workspace-price-c1", useUnmergedTree = true).assertTextEquals("5,510.00")
        onNodeWithTag("workspace-change-c1", useUnmergedTree = true).assertTextContains("+0.18%", substring = true)
        waitUntil(timeoutMillis = 5_000) { broker.quoteWatches.lastOrNull()?.containsAll(listOf("TESTZ6", "OTHRZ6")) == true }
        onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
        onNodeWithTag("chart-layout-TWO_COLUMNS").performClick()
        onNodeWithTag("split-cell-c1s1").assertExists()
        onNodeWithTag("workspace-tab-main").performClick()
        onNodeWithTag("split-cell-c1s1").assertDoesNotExist()
        onNodeWithTag("chart-symbol").assertTextContains("TESTZ6", substring = true)
        onNodeWithTag("workspace-tab-c1").performClick()
        onNodeWithTag("chart-symbol").assertTextContains("OTHRZ6", substring = true)
        onNodeWithTag("split-cell-c1s1").assertExists()
        val from = onNodeWithTag("workspace-tab-c1")
        val bounds = from.fetchSemanticsNode().boundsInRoot
        val target = onNodeWithTag("workspace-tab-main").fetchSemanticsNode().boundsInRoot
        val delta = target.center.x - bounds.center.x - 25f
        from.performTouchInput { swipe(center, center + Offset(delta, 0f), durationMillis = 500) }
        waitForIdle()
        assertTrue(onNodeWithTag("workspace-tab-c1").fetchSemanticsNode().boundsInRoot.left <
            onNodeWithTag("workspace-tab-main").fetchSemanticsNode().boundsInRoot.left)
        // A vertical gesture on a tablet never creates a separate OS window.
        onNodeWithTag("workspace-tab-c1").performTouchInput { swipe(center, center + Offset(0f, 80f)) }
        onNodeWithTag("workspace-tab-c1").assertExists()
        onNodeWithTag("detach-workspace-c1").assertDoesNotExist()
        assertTrue(runBlocking { runtime.desktopWorkspaceSaver!!.invoke() })
        assertEquals(listOf("c1", "main"), runtime.chartPreferences.preferences.value.workspaceOrder)
        runOnIdle { runtime = newRuntime() }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onNodeWithTag("workspace-tab-c1").fetchSemanticsNode().boundsInRoot.left <
            onNodeWithTag("workspace-tab-main").fetchSemanticsNode().boundsInRoot.left)
        onNodeWithTag("workspace-tab-c1").performClick()
        onNodeWithTag("chart-symbol").assertTextContains("OTHRZ6", substring = true)
        onNodeWithTag("split-cell-c1s1").assertExists()
        assertTrue(broker.placed.isEmpty())
        val out = File("../artifacts/desktop-tabs/android-tablet-tabs.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
    }

    @Test fun portraitAndroidTabletAlsoSwitchesWorkspacesWithoutDetaching() = runDesktopComposeUiTest(width = 800, height = 1200) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(800.dp, 1200.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("tablet-workspace-header").assertIsDisplayed()
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        onNodeWithTag("rail-order").assertIsDisplayed()
        onNodeWithTag("open-order").assertDoesNotExist()
        onNodeWithTag("desktop-flatten-actions").assertIsDisplayed()
        onNodeWithTag("quick-account-picker").assertIsDisplayed()
        onNodeWithTag("rail-analytics").assertExists()
        onNodeWithTag("chart-new-workspace").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").assertDoesNotExist()
        onNodeWithTag("workspace-tab-main").performClick()
        onNodeWithTag("workspace-tab-c1").performClick()
        onNodeWithTag("chart-layout-menu").performScrollTo().performClick()
        onNodeWithTag("chart-layout-TWO_COLUMNS").performClick()
        onNodeWithTag("split-cell-c1s1").assertExists()
    }

    @Test fun ipadTabletHasTabsAndNoDetachButton() = runDesktopComposeUiTest(width = 1024, height = 768) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.IOS) {
            Box(Modifier.requiredSize(1024.dp, 768.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("desktop-workspace").assertIsDisplayed()
        onNodeWithTag("rail-analytics").assertExists()
        onNodeWithTag("desktop-flatten-actions").assertIsDisplayed()
        onNodeWithTag("chart-new-workspace").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("workspace-tab-c1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("detach-workspace-c1").assertDoesNotExist()
        onNodeWithTag("workspace-tab-main").performClick()
        onNodeWithTag("workspace-tab-c1").performClick()
    }

    @Test fun phoneKeepsItsExistingInterface() = runDesktopComposeUiTest(width = 412, height = 860) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalChartPlatform provides ChartPlatform.ANDROID) {
            Box(Modifier.requiredSize(412.dp, 860.dp)) { TradingScreen(runtime) }
        } }
        onNodeWithTag("tablet-workspace-header").assertDoesNotExist()
        onNodeWithTag("chart-workspace-tabs").assertDoesNotExist()
        onNodeWithTag("chart-new-workspace").assertDoesNotExist()
        onNodeWithTag("quick-login-picker").assertIsDisplayed()
    }
}

