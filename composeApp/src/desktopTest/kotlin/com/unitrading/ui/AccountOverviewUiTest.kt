package com.unitrading.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.ClosedTrade
import com.unitrading.broker.MarketSnapshot
import com.unitrading.broker.Side
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountOverviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()

    /** On the desktop the hub opens over the workspace, which stays in place with any panel that has a window of its own. */
    @Test fun desktopToolbarOpensTheAccountHubOverTheWorkspace() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("account-hub").assertDoesNotExist()
        compose.onNodeWithTag("toggle-account-overview").performClick()
        compose.onNodeWithTag("account-overview").assertIsDisplayed()
        compose.onNodeWithTag("account-tab-2").performClick()
        compose.onNodeWithTag("account-analytics").assertIsDisplayed()
        compose.onNodeWithTag("trading-chart").assertExists()
    }

    /** The net result of the loaded history stands with the funds and the drawdown, and a trade without a known result is never counted as zero. */
    @Test fun overviewShowsTheLoadedHistoryTotalWithFundsAndDrawdownAndSaysWhenResultsAreUnknown() {
        val account = plugin.accounts.first()
        val win = ClosedTrade("win", "p", "MES", Side.BUY, 100, 1, 2, 10, 20, 3_000_000, "fixture")
        var snapshot by mutableStateOf(AccountSnapshot(account.id, 1_000_000_000, trades = listOf(win, win.copy(id = "loss", netPnlMicros = -1_000_000))))
        compose.setContent { TradingTheme { AccountOverviewPage(plugin, account, snapshot, MarketSnapshot(), {}, Modifier.requiredSize(400.dp, 800.dp)) } }
        val list = compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("account-overview")))
        compose.onNodeWithTag("overview-equity-USD").assertTextEquals("1000.00")
        compose.onNodeWithTag("drawdown-room-value").assertExists()
        list.performScrollToNode(hasTestTag("overview-realized"))
        compose.onNodeWithText("已加载历史净盈亏").assertIsDisplayed()
        compose.onNodeWithTag("overview-realized").assertTextEquals("+2.00")
        compose.onNodeWithTag("overview-unknown-closed").assertDoesNotExist()
        compose.runOnIdle { snapshot = snapshot.copy(trades = snapshot.trades + win.copy(id = "fees-missing", netPnlMicros = null)) }
        list.performScrollToNode(hasTestTag("overview-unknown-closed"))
        compose.onNodeWithTag("overview-realized").assertTextEquals("—")
        compose.onNodeWithTag("overview-unknown-closed").assertTextEquals("1 笔历史净盈亏待确认，未计为零。")
        val target = File("../artifacts/account-overview-history.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", target)
    }

    @Test fun quickSwitchersRouteLoginAndChildIndependently() {
        var broker: String? = null
        var child: String? = null
        compose.setContent { TradingTheme {
            AccountContextBar(runtime.registry, plugin, plugin.accounts.first().id, { broker = it }, { child = it }, Modifier.requiredSize(390.dp, 64.dp))
        } }
        compose.onNodeWithTag("quick-account-picker").performClick()
        val next = plugin.accounts.last()
        compose.onNodeWithTag("quick-account-${next.id}").performClick()
        compose.runOnIdle { assertEquals(next.id, child); assertNull(broker) }
        compose.onNodeWithTag("quick-login-picker").performClick()
        val other = runtime.registry.plugins.last()
        compose.onNodeWithTag("quick-login-${other.descriptor.id}").performClick()
        compose.runOnIdle { assertEquals(other.descriptor.id, broker) }
    }
}
