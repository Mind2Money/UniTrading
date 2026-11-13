package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.OrderPanelLayout
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MobileOrderDockResizeTest {
    @Test fun rotationKeepsTheChartViewportAndOrderDraft() = runDesktopComposeUiTest(width = 820, height = 820) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        runBlocking { assertTrue(runtime.tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) }
        var landscape by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(if (landscape) 800.dp else 400.dp, if (landscape) 400.dp else 800.dp)) {
                    TradingScreen(runtime)
                }
            }
        }
        val plot = onNodeWithTag("trading-chart")
        val dock = onNodeWithTag("order-dock")
        fun viewport() = plot.fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore(";low:")
        val initial = viewport()
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("order-kind-LIMIT").performClick()
        onNodeWithTag("order-quantity").performTextReplacement("0.23")
        onNodeWithTag("order-entry").performTextReplacement("123.45")
        assertTrue(plot.fetchSemanticsNode().boundsInRoot.bottom <= dock.fetchSemanticsNode().boundsInRoot.top)
        runOnIdle { landscape = true }
        assertTrue(plot.fetchSemanticsNode().boundsInRoot.right <= dock.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(initial, viewport())
        onNodeWithTag("order-quantity").assertTextContains("0.23")
        onNodeWithTag("order-entry").assertTextContains("123.45")
        runOnIdle { landscape = false }
        assertTrue(plot.fetchSemanticsNode().boundsInRoot.bottom <= dock.fetchSemanticsNode().boundsInRoot.top)
        assertEquals(initial, viewport())
        onNodeWithTag("order-quantity").assertTextContains("0.23")
    }
}
