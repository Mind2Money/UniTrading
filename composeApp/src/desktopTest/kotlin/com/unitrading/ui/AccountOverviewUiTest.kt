package com.unitrading.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountOverviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val spec = plugin.instruments.first()
    private val trade = ClosedTrade("t", "p", spec.symbol, Side.BUY, 100, 10000, 10100, 10, 20, 1_000_000, "止盈")
    private val accounts = listOf("a", "b").map { id ->
        OverviewAccount(AccountInfo(id, "Child $id"), AccountSnapshot(id, 50_000_000, trades = listOf(trade)))
    }

    @Test fun viewingAnotherChildDoesNotSwitchTradingUntilExplicitlyOpened() {
        var opened: String? = null
        compose.setContent { TradingTheme(ThemeSettings(ThemePreset.LIGHT)) {
            AccountOverviewPage(plugin, accounts, plugin.market.value, "a", { opened = it }, Modifier.requiredSize(1100.dp, 900.dp))
        } }
        compose.onNodeWithTag("overview-scope-b").performScrollTo().performClick()
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithTag("overview-search").performScrollTo().performTextInput("not-a-symbol")
        compose.onNodeWithText("没有符合条件的记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("overview-search").performScrollTo().performTextClearance()
        val row = overviewRows(listOf(accounts[1]), plugin.instruments, plugin.market.value).single()
        compose.onNodeWithTag("overview-open-${row.key}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("b", opened) }
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
