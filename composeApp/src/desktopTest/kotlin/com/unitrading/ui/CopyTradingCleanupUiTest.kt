package com.unitrading.ui

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.CopySavedConfiguration
import com.unitrading.app.CopySavedFollower
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CopyTradingCleanupUiTest {
    @Test fun onlyConfirmedSelectedAccountsAreFlattened() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val broker = RiskFixture(login = "tradovate-demo-ui", children = listOf(
            AccountInfo("100", "Leader"), AccountInfo("200", "Follower"), AccountInfo("300", "Not selected")))
        broker.hold("100", listOf(riskPosition(Side.BUY, 100, "leader-position")),
            listOf(riskOrder("leader-entry", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        broker.hold("300", listOf(riskPosition(Side.BUY, 100, "other-position")))
        broker.snapshots.forEach { (id, state) -> state.value = state.value.copy(copyRevision = 7, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = id != "100") }
        broker.onCancel = { orderId ->
            val state = broker.snapshots.getValue("100")
            state.value = state.value.copy(orders = state.value.orders.filterNot { it.id == orderId }, copyOrdersClear = true)
            TradeResult(true, "cancelled")
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        val previous = CopySavedConfiguration(broker.descriptor.id, "100", listOf(CopySavedFollower("300")))
        runBlocking { assertTrue(runtime.copyTradingConfiguration.save(previous)) }
        var open by mutableStateOf(true)
        setContent { TradingTheme { if (open) CopyTradingDialog(runtime, broker, "100") { open = false } } }
        onNodeWithTag("copy-follower-300").performScrollTo().performClick()
        onNodeWithTag("copy-follower-200").performScrollTo().performClick()
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("copy-cleanup-cancel").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("未选择的子账户不受影响。", substring = true).assertExists()
        assertEquals(previous, runtime.copyTradingConfiguration.configurations.value[broker.descriptor.id])
        onNodeWithTag("copy-cleanup-cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { !open }
        assertTrue(broker.events.isEmpty())
        assertEquals(1, broker.snapshots.getValue("100").value.positions.size)
        assertEquals(previous, runtime.copyTradingConfiguration.configurations.value[broker.descriptor.id])
        runOnIdle { open = true }
        onNodeWithTag("copy-follower-300").performScrollTo().performClick()
        onNodeWithTag("copy-follower-200").performScrollTo().performClick()
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("copy-cleanup-confirm").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("copy-cleanup-confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.events.contains("close:leader-position") }
        waitUntil(timeoutMillis = 5_000) { runtime.copyTradingConfiguration.configurations.value[broker.descriptor.id]?.followers?.singleOrNull()?.accountId == "200" }
        assertEquals(listOf("cancel:leader-entry", "close:leader-position"), broker.events)
        assertEquals(1, broker.snapshots.getValue("300").value.positions.size)
    }

    @Test fun failedCleanupDoesNotSaveNewConfiguration() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val broker = RiskFixture(login = "tradovate-demo-failure", children = listOf(
            AccountInfo("100", "Leader"), AccountInfo("200", "Follower"))).apply { rejectCancel = true }
        broker.hold("100", orders = listOf(riskOrder("entry", Side.BUY, OrderKind.LIMIT, 100, 549_000)))
        broker.snapshots.forEach { (id, state) -> state.value = state.value.copy(copyRevision = 7, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = id != "100") }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        setContent { TradingTheme { CopyTradingDialog(runtime, broker, "100") {} } }
        onNodeWithTag("copy-follower-200").performClick()
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("copy-cleanup-confirm").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("copy-cleanup-confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("平仓结果").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("cancel:entry"), broker.events)
        assertNull(runtime.copyTradingConfiguration.configurations.value[broker.descriptor.id])
    }

    @Test fun flatAccountsSaveWithoutCleanupPrompt() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val broker = RiskFixture(login = "tradovate-demo-flat", children = listOf(
            AccountInfo("100", "Leader"), AccountInfo("200", "Follower")))
        broker.snapshots.values.forEach { state -> state.value = state.value.copy(copyRevision = 7, copyEpoch = 1,
            copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = true) }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        setContent { TradingTheme { CopyTradingDialog(runtime, broker, "100") {} } }
        onNodeWithTag("copy-follower-200").performClick()
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runtime.copyTradingConfiguration.configurations.value[broker.descriptor.id] != null }
        onAllNodesWithTag("copy-cleanup-confirm").assertCountEquals(0)
        assertTrue(broker.events.isEmpty())
    }
}
