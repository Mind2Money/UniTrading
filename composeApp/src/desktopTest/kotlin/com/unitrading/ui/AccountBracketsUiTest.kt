package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountBracketsUiTest {
    @Test fun mobileSettingsApplyBracketsToAReviewedOrder() = runDesktopComposeUiTest(width = 450, height = 850) {
        val broker = OfflineOrderBroker()
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(450.dp, 850.dp)) { TradingScreen(runtime) }
            }
        }
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("edit-account-brackets").performScrollTo().performClick()
        onNodeWithTag("account-bracket-loss").performTextInput("100")
        onNodeWithTag("account-bracket-profit").performTextInput("200")
        onNodeWithTag("save-account-brackets").performClick()
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, "100").brackets.enabled }
        onNodeWithTag("nav-1").performClick()
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("order-account-brackets").assertTextContains("SL 5480.25", substring = true)
        onNodeWithTag("review-order").performClick()
        onNodeWithText("止损价").assertExists()
        onNodeWithText("止盈价").assertExists()
        assertTrue(broker.placed.isEmpty())
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
        assertEquals(548_025L, broker.placed.single().stopLoss)
        assertEquals(554_025L, broker.placed.single().takeProfit)
    }
}
