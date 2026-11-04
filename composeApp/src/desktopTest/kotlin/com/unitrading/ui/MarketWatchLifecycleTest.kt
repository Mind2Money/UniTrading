package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.BrokerPlugin
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MarketWatchLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedQuotesLoadOnStartupAndSurviveMobileNavigationUntilScreenCloses() {
        val demo = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).registry.plugins.first()
        val subscriptions = mutableListOf<Set<String>>()
        val plugin = object : BrokerPlugin by demo {
            override fun watchQuotes(symbols: Set<String>) { subscriptions += symbols.toSet() }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(plugin)).apply { paused.value = true }
        val saved = demo.instruments.take(2).map { it.symbol }
        runtime.marketWatch.update(plugin.descriptor.id, saved)
        val showing = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    if (showing.value) TradingScreen(runtime)
                }
            }
        }

        // Startup opens the chart; restoring the list must not wait for a visit to Market.
        compose.onNodeWithTag("nav-1").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(saved.toSet()), subscriptions) }
        compose.onNodeWithTag("nav-0").performClick()
        compose.onNodeWithTag("market-quotes").assertIsDisplayed()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("nav-0").performClick()
        compose.runOnIdle { assertEquals(listOf(saved.toSet()), subscriptions) }

        compose.onNodeWithTag("market-edit").performClick()
        compose.onNodeWithTag("market-remove-${saved.first()}").performClick()
        compose.runOnIdle { assertEquals(listOf(saved.toSet(), setOf(saved.last())), subscriptions) }
        compose.runOnIdle { showing.value = false }
        compose.runOnIdle { assertEquals(listOf(saved.toSet(), setOf(saved.last()), emptySet()), subscriptions) }
    }
}
