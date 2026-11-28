package com.unitrading.ui

import androidx.compose.ui.test.*
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import kotlin.test.*
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
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        setContent { TradingTheme { CopyTradingDialog(runtime, broker, "100") {} } }
        onNodeWithTag("copy-follower-200").performClick()
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("copy-cleanup-cancel").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("未选择的子账户不受影响。", substring = true).assertExists()
        onNodeWithTag("copy-cleanup-cancel").performClick()
        assertTrue(broker.events.isEmpty())
        assertEquals(1, broker.snapshots.getValue("100").value.positions.size)
        onNodeWithTag("copy-save-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("copy-cleanup-confirm").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("copy-cleanup-confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.events.contains("close:leader-position") }
        assertEquals(listOf("cancel:leader-entry", "close:leader-position"), broker.events)
        assertEquals(1, broker.snapshots.getValue("300").value.positions.size)
    }
}
