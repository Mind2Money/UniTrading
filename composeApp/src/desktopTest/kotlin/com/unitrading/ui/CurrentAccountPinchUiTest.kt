package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class CurrentAccountPinchUiTest {
    @Test fun overviewAndAnalyticsFollowTheTradingChildImmediately() = runDesktopComposeUiTest(width = 400, height = 800) {
        val seed = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        val source = seed.registry.plugins.first()
        val children = source.accounts.take(2)
        assertEquals(2, children.size)
        val time = Clock.System.now().toEpochMilliseconds()
        val sessions = children.mapIndexed { i, info ->
            val delegate = source.openSession(info.id)
            object : AccountSession by delegate {
                override val state = MutableStateFlow(AccountSnapshot(info.id, if (i == 0) 1000_000_000L else 2000_000_000L,
                    trades = listOf(ClosedTrade("same", "p", source.instruments.first().symbol, Side.BUY, 100, 1, 2, time - 60000, time,
                        if (i == 0) 100_000_000L else -50_000_000L, "fixture"))))
            }
        }.associateBy { it.account.id }
        val broker = object : BrokerPlugin by source {
            override val accounts = children
            override val catalog = MutableStateFlow(BrokerCatalog(source.instruments, children))
            override fun openSession(accountId: String) = sessions.getValue(accountId)
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) } } }
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("overview-equity-USD").assertTextEquals("1000.00")
        onNodeWithTag("overview-scope-all").assertDoesNotExist()
        onNodeWithTag("account-tab-2").performClick()
        onNodeWithText("+100.00").assertExists()
        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("quick-account-${children[1].id}").performClick()
        onNodeWithText("-50.00").assertExists()
        onNodeWithText("+100.00").assertDoesNotExist()
        onNodeWithTag("account-tab-0").performClick()
        onNodeWithTag("overview-equity-USD").assertTextEquals("2000.00")
        onNodeWithTag("quick-account-picker").performClick()
        onNodeWithTag("quick-account-${children[0].id}").performClick()
        onNodeWithTag("overview-equity-USD").assertTextEquals("1000.00")
    }

    @Test fun twoFingerZoomConsumesTheRemainingFingerAndNeverChangesOrders() = runDesktopComposeUiTest(width = 400, height = 800) {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        val broker = runtime.registry.plugins.first()
        val session = broker.openSession(broker.accounts.first().id)
        val snapshot = session.state.value
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { Box(Modifier.requiredSize(400.dp, 800.dp)) { TradingScreen(runtime) } } }
        val chart = onNodeWithTag("trading-chart")
        fun values() = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
        val before = values()
        chart.performTouchInput {
            down(0, Offset(width * .32f, height * .40f))
            down(1, Offset(width * .58f, height * .60f))
            repeat(10) { i ->
                updatePointerTo(0, Offset(width * (.32f - .012f * (i + 1)), height * (.40f - .008f * (i + 1))))
                updatePointerTo(1, Offset(width * (.58f + .012f * (i + 1)), height * (.60f + .008f * (i + 1))))
                move()
            }
            up(1)
        }
        val zoomed = values()
        assertTrue(zoomed.getValue("bars").toDouble() < before.getValue("bars").toDouble() * .8)
        assertTrue(zoomed.getValue("high").toDouble() - zoomed.getValue("low").toDouble() < before.getValue("high").toDouble() - before.getValue("low").toDouble())
        chart.performTouchInput { moveTo(0, Offset(width * .4f, height * .7f)); up(0) }
        assertEquals(zoomed.getValue("bars"), values().getValue("bars"))
        assertEquals(zoomed.getValue("offset"), values().getValue("offset"))
        assertEquals(snapshot, session.state.value)
        onNodeWithTag("confirm-action").assertDoesNotExist()
        chart.performTouchInput {
            down(0, Offset(width * .18f, height * .32f)); down(1, Offset(width * .72f, height * .68f))
            repeat(10) { i ->
                updatePointerTo(0, Offset(width * (.18f + .012f * (i + 1)), height * (.32f + .008f * (i + 1))))
                updatePointerTo(1, Offset(width * (.72f - .012f * (i + 1)), height * (.68f - .008f * (i + 1))))
                move()
            }
            up(0); up(1)
        }
        assertTrue(values().getValue("bars").toDouble() > zoomed.getValue("bars").toDouble())
    }
}
