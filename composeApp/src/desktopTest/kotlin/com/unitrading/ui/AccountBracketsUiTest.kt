package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.chart.OrderTagButtons
import com.unitrading.chart.PriceScale
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

    @Test fun choosingRiskSizingReplacesBracketsAndRequiresAStopBeforeOrdering() =
        runDesktopComposeUiTest(width = 450, height = 850) {
            val broker = OfflineOrderBroker()
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
            kotlinx.coroutines.runBlocking {
                assertNull(runtime.accountRisk.configureBrackets(broker, "100", AccountBrackets(loss = 100_000_000, profit = 200_000_000)))
            }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.requiredSize(450.dp, 850.dp)) { TradingScreen(runtime) }
                }
            }
            onNodeWithTag("nav-3").performClick()
            onNodeWithTag("edit-account-brackets").performScrollTo().performClick()
            onNodeWithTag("account-mode-sizing").performClick().assertIsSelected()
            onNodeWithTag("account-bracket-loss").assertDoesNotExist()
            onNodeWithTag("account-sizing-risk").performTextInput("99")
            onNodeWithTag("save-account-brackets").performClick()
            waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, "100").brackets == AccountBrackets(sizingRisk = 99_000_000) }
            onNodeWithTag("nav-1").performClick()
            onNodeWithTag("open-order").performClick()
            onNodeWithTag("review-order").assertIsNotEnabled()
            onNodeWithTag("order-risk-sizing-sl").performScrollTo().performTextReplacement("5495.25")
            onNodeWithTag("order-risk-sized-quantity").assertTextContains("3 张", substring = true)
            onNodeWithTag("review-order").performClick()
            onNodeWithTag("confirm-action").performClick()
            waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
            assertEquals(300L, broker.placed.single().lotsHundredths)
            assertEquals(549_525L, broker.placed.single().stopLoss)
            assertNull(broker.placed.single().takeProfit)
        }

    @Test fun dragStopFromTheChartAndPreviewChangingSizeBeforeRelease() =
        runDesktopComposeUiTest(width = 450, height = 850) {
            val broker = OfflineOrderBroker()
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker))
            kotlinx.coroutines.runBlocking {
                assertNull(runtime.accountRisk.configureBrackets(broker, "100", AccountBrackets(sizingRisk = 99_000_000)))
            }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.requiredSize(450.dp, 850.dp)) { TradingScreen(runtime) }
                }
            }
            // The docked page keeps the chart beside the open form; the default sheet would lie over the line being dragged.
            kotlinx.coroutines.runBlocking { assertTrue(runtime.tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) }
            onNodeWithTag("open-order").performClick()
            onNodeWithTag("review-order").assertIsNotEnabled()
            val chart = onNodeWithTag("trading-chart")
            val node = chart.fetchSemanticsNode()
            val handle = assertNotNull(node.config[OrderTagButtons]["拖动设置止损"]).center
            val axis = node.config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
            val scale = PriceScale(axis.getValue("low").toDouble(), axis.getValue("high").toDouble(), 18f, axis.getValue("plotBottom").toFloat())
            chart.performTouchInput { down(handle); moveTo(Offset(handle.x, scale.y(549_525))) }
            onNodeWithTag("chart-readout").assertTextContains("自动手数 3 张", substring = true)
            chart.performTouchInput { moveTo(Offset(handle.x, scale.y(549_025))) }
            onNodeWithTag("chart-readout").assertTextContains("自动手数 1 张", substring = true)
            assertTrue(broker.placed.isEmpty())
            chart.performTouchInput { up() }
            onNodeWithTag("order-risk-sizing-sl").assertTextContains("5490.25")
            onNodeWithTag("order-risk-sized-quantity").assertTextContains("1 张", substring = true)
            onNodeWithTag("review-order").performClick()
            onNodeWithTag("confirm-action").performClick()
            waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
            assertEquals(100L, broker.placed.single().lotsHundredths)
            assertEquals(549_025L, broker.placed.single().stopLoss)
        }
}
