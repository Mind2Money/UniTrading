package com.unitrading.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import org.junit.Rule
import org.junit.Test
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
