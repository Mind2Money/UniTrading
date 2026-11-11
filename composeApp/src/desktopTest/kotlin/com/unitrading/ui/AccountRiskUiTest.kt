package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Clock
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountRiskUiTest {
    @Test fun bulkCloseConfirmationReversesTheButtonsAndRequiresExplicitConfirmation() =
        runDesktopComposeUiTest(width = 450, height = 800) {
            val broker = OfflineOrderBroker()
            var confirmations = 0
            var cancellations = 0
            setContent {
                TradingTheme {
                    FlattenConfirmation(FlattenReview(true, listOf(FlattenTarget(broker, broker.accounts.single()))),
                        onCancel = { cancellations++ }, onConfirm = { confirmations++ })
                }
            }
            val confirm = onNodeWithTag("confirm-bulk-flatten")
            val cancel = onNodeWithTag("cancel-bulk-flatten")
            assertTrue(confirm.fetchSemanticsNode().boundsInRoot.left < cancel.fetchSemanticsNode().boundsInRoot.left)
            cancel.performClick()
            runOnIdle { assertTrue(cancellations == 1 && confirmations == 0) }
            confirm.performClick()
            runOnIdle { assertTrue(confirmations == 1) }
        }

    @Test fun fundsMoveToOverviewAndFreezeRequiresLossLimit() = runDesktopComposeUiTest(width = 450, height = 850) {
        val source = OfflineOrderBroker()
        val raw = source.openSession("100")
        val child = raw.account.copy(name = "25K 风控示例")
        val snapshot = MutableStateFlow(AccountSnapshot(child.id, 24_990_500_000, dailyPnl = DailyPnl(
            -97_520_000, tradingDay = "2026-11-11", updatedAtMs = Clock.System.now().toEpochMilliseconds()),
            drawdown = AccountDrawdown(DrawdownRule(levelMicros = 24_288_650_000,
                amountMicros = 1_000_000_000, peakMicros = 25_288_650_000, mode = DrawdownMode.END_OF_DAY,
                reported = true), 701_850_000, .30, DrawdownValueSource.CASH)))
        val broker = object : BrokerPlugin by source {
            override val descriptor = source.descriptor.copy(name = "离线模拟")
            override val accounts = listOf(child)
            override val catalog = MutableStateFlow(BrokerCatalog(source.instruments, accounts))
            override fun openSession(accountId: String) = object : AccountSession by raw {
                override val account = child
                override val state = snapshot
            }
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
        runBlocking { runtime.theme.apply(ThemeSettings(ThemePreset.LIGHT)) }
        val folder = File("../artifacts/account-risk").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(450.dp, 850.dp)) { TradingScreen(runtime) }
            }
        }
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("overview-equity-USD").assertTextEquals("24990.50")
        onNodeWithText("回撤与风险").assertIsDisplayed()
        shot("01-account-overview")
        onNodeWithTag("account-tab-1").performClick()
        onNodeWithText("回撤与风险").assertDoesNotExist()
        onNodeWithTag("trade-terminal").assertIsDisplayed()
        shot("02-trading")
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("edit-account-risk").performScrollTo().performClick()
        onNodeWithTag("freeze-risk-settings").assertIsNotEnabled()
        onNodeWithTag("risk-profit").performTextInput("300")
        onNodeWithTag("save-risk-settings").assertIsEnabled()
        onNodeWithTag("freeze-risk-settings").assertIsNotEnabled()
        onNodeWithTag("risk-loss").performTextInput("200")
        onNodeWithTag("freeze-risk-settings").assertIsEnabled()
        shot("03-risk-rules")
        onNodeWithTag("freeze-risk-settings").performScrollTo().performClick()
        onNodeWithText("确认启用").performClick()
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, child.id).frozenUntil != null }
        onNodeWithTag("nav-4").performClick()
        runOnIdle { snapshot.value = snapshot.value.copy(dailyPnl = DailyPnl(-205_000_000,
            tradingDay = "2026-11-11", updatedAtMs = Clock.System.now().toEpochMilliseconds())) }
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, child.id).trigger != null }
        onNodeWithTag("account-risk-notice").assertIsDisplayed()
        shot("04-risk-triggered")
    }
}
