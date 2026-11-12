package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountRiskUiTest {
    private fun ComposeUiTest.show(runtime: TradingRuntime, width: Int = 450, height: Int = 850) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
        }
    }

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

    @Test fun flattenAllSkipsViewOnlyAccountsAndLoginsThatCannotTrade() = runDesktopComposeUiTest(width = 450, height = 850) {
        val trading = RiskFixture(children = listOf(AccountInfo("100", "Trading"), AccountInfo("101", "View only", readOnly = true)))
        val offline = RiskFixture(login = "offline-login", children = listOf(AccountInfo("300", "Unreachable")))
        offline.source.market.value = offline.source.market.value.copy(connected = false, statusMessage = "已断开")
        trading.hold("100", listOf(riskPosition(Side.BUY, 100)), listOf(riskOrder("stop", Side.SELL, OrderKind.STOP, 100, 548_000)))
        // Positions there too: left alone, and not because they looked flat.
        trading.hold("101", listOf(riskPosition(Side.BUY, 100, "watched")))
        offline.hold("300", listOf(riskPosition(Side.BUY, 100, "unreachable")))
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(trading, offline)).apply { paused.value = true }
        show(runtime)
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("account-tab-1").performClick()
        onNodeWithTag("flatten-all-accounts").performClick()
        onNodeWithText("将处理以下 1 个子账户", substring = true).assertExists()
        onNodeWithText("以下 2 个子账户不处理：").assertExists()
        onNodeWithText("只读，跳过", substring = true).assertTextContains("View only · 101", substring = true)
        onNodeWithText("未连接，跳过", substring = true).assertTextContains("Unreachable · 300", substring = true)
        onNodeWithTag("confirm-bulk-flatten").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("已确认空仓且无挂单 1 / 1 个子账户").fetchSemanticsNodes().isNotEmpty() }
        // One outcome for the one target: neither skipped account is reported as a failure, and nothing was sent for them.
        onAllNodesWithText("账户已断开、切换或为只读。", substring = true).assertCountEquals(0)
        assertEquals(listOf("close:held", "cancel:stop"), trading.events)
        assertTrue(offline.events.isEmpty())
        assertEquals(1, trading.snapshots.getValue("101").value.positions.size)
    }

    @Test fun aLoginThatCannotTradeLeavesNothingToConfirm() = runDesktopComposeUiTest(width = 450, height = 800) {
        val offline = RiskFixture()
        setContent {
            TradingTheme {
                FlattenConfirmation(FlattenReview(true, emptyList(), offline.accounts.map { FlattenTarget(offline, it) to FlattenSkip.DISCONNECTED }),
                    onCancel = {}, onConfirm = {})
            }
        }
        onNodeWithText("没有可以处理的子账户。").assertExists()
        onNodeWithTag("confirm-bulk-flatten").assertIsNotEnabled()
        onNodeWithTag("cancel-bulk-flatten").assertIsEnabled()
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
            override val capabilities = source.capabilities.copy(tradingDayPnl = true)
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
        show(runtime)
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
        onNodeWithText("下一个交易日", substring = true).assertExists()
        onNodeWithText("确认启用").performClick()
        // Frozen until the trading day after the one the broker reports, whatever the device's calendar says.
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, child.id).frozenUntil != null }
        assertEquals("2026-11-12", runtime.accountRisk.rule(broker, child.id).frozenUntil.toString())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("规则已冻结，券商交易日 2026-11-12 起可修改").fetchSemanticsNodes().isNotEmpty() }
        shot("03-risk-frozen")
        onNodeWithTag("nav-4").performClick()
        runOnIdle { snapshot.value = snapshot.value.copy(dailyPnl = DailyPnl(-205_000_000,
            tradingDay = "2026-11-11", updatedAtMs = Clock.System.now().toEpochMilliseconds())) }
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, child.id).trigger != null }
        onNodeWithTag("account-risk-notice").assertIsDisplayed()
        shot("04-risk-triggered")
    }

    @Test fun thePnlLockIsNotOfferedWithoutABrokerTradingDay() = runDesktopComposeUiTest(width = 450, height = 850) {
        // No trading day comes with this broker's P&L, as with Rithmic and the local demo.
        val broker = RiskFixture(tradingDayPnl = false)
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, listOf(broker)).apply { paused.value = true }
        show(runtime)
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("edit-account-risk").performScrollTo().assertIsNotEnabled()
        onNodeWithText(PNL_LOCK_UNSUPPORTED, useUnmergedTree = true).assertExists()
        // The limits and the cash brackets need no P&L from the broker.
        onNodeWithTag("edit-account-order-limits").performScrollTo().assertIsEnabled()
        onNodeWithTag("edit-account-brackets").performScrollTo().assertIsEnabled()
    }

    @Test fun aRuleLeftOnABrokerWithoutTradingDayCanOnlyBeSwitchedOff() = runDesktopComposeUiTest(width = 450, height = 850) {
        val broker = RiskFixture(tradingDayPnl = false)
        val left = AccountRiskRule(RiskLimits(200_000_000), kotlinx.datetime.LocalDate.parse("2099-01-01"))
        val store = MemorySnapshotStore().apply {
            write("account-risk-v1", kotlinx.serialization.json.Json.encodeToString(mapOf(broker.descriptor.id to mapOf("100" to left))))
        }
        val runtime = TradingRuntime(store, listOf(broker)).apply { paused.value = true }
        show(runtime)
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("edit-account-risk").performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag("risk-loss").assertIsNotEnabled()
        onNodeWithTag("save-risk-settings").assertIsNotEnabled()
        onNodeWithTag("freeze-risk-settings").assertIsNotEnabled()
        onNodeWithTag("disable-risk-settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, "100") == AccountRiskRule() }
        onNodeWithTag("edit-account-risk").performScrollTo().assertIsNotEnabled()
    }

    @Test fun anUnreadableRiskFileIsResetOnlyAfterConfirmation() = runDesktopComposeUiTest(width = 450, height = 850) {
        val broker = RiskFixture()
        val newer = """{"offline-orders":{"100":{"fromANewerBuild":1}}}"""
        val store = MemorySnapshotStore().apply { write("account-risk-v1", newer) }
        val runtime = TradingRuntime(store, listOf(broker)).apply { paused.value = true }
        show(runtime)
        // Every account is paused, and the account page says why.
        onNodeWithTag("nav-4").performClick()
        onNodeWithText("本机风控记录无法读取，已暂停开仓和改单。").assertIsDisplayed()
        onNodeWithTag("nav-3").performClick()
        onNodeWithTag("reset-account-risk").performScrollTo().assertTextContains("本机风控记录无法读取，已暂停开仓和改单")
        for (row in listOf("edit-account-brackets", "edit-account-risk", "edit-account-order-limits")) {
            onNodeWithTag(row).performScrollTo().assertIsNotEnabled()
        }
        onNodeWithTag("reset-account-risk").performScrollTo().performClick()
        onNodeWithText("重置风控设置？").assertExists()
        onNodeWithText("取消").performClick()
        assertTrue(runtime.accountRisk.storageUnreadable.value)
        assertNull(store.read("account-risk-v1-unreadable"))
        onNodeWithTag("reset-account-risk").performClick()
        onNodeWithTag("confirm-reset-account-risk").performClick()
        waitUntil(timeoutMillis = 5_000) { !runtime.accountRisk.storageUnreadable.value }
        onNodeWithTag("reset-account-risk").assertDoesNotExist()
        for (row in listOf("edit-account-brackets", "edit-account-risk", "edit-account-order-limits")) {
            onNodeWithTag(row).performScrollTo().assertIsEnabled()
        }
        assertEquals(newer, store.read("account-risk-v1-unreadable"))
        assertEquals("{}", store.read("account-risk-v1"))
        // The pause is over, and the account page stops announcing it although no rule changed.
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("account-hub").assertIsDisplayed()
        onNodeWithTag("account-risk-notice").assertDoesNotExist()
    }

    @Test fun theLimitsDialogSaysWhatCountsOnThisKindOfAccount() = runDesktopComposeUiTest(width = 450, height = 850) {
        val net = RiskFixture()
        val hedged = RiskFixture(hedging = true, login = "hedging-login")
        val risk = AccountRiskController(MemorySnapshotStore())
        setContent {
            TradingTheme {
                Column {
                    AccountOrderLimitSettings(risk, net, net.accounts.first(), busy = false)
                    AccountOrderLimitSettings(risk, hedged, hedged.accounts.first(), busy = false)
                }
            }
        }
        onAllNodesWithTag("edit-account-order-limits")[0].performClick()
        onNodeWithText("止损、止盈等减仓单不占额度。", substring = true).assertExists()
        onNodeWithText("限制只在本应用生效。平仓、撤单和减仓单不受限制。").assertExists()
        onNodeWithText("取消").performClick()
        // Every order opens a position of its own there: both sides add up, and nothing is promised about reducing orders.
        onAllNodesWithTag("edit-account-order-limits")[1].performClick()
        onNodeWithText("持仓、未成交开仓挂单和新单合计，多空不抵扣。", substring = true).assertExists()
        onNodeWithText("限制只在本应用生效，仍可手动撤单和平仓。").assertExists()
        onAllNodesWithText("减仓单", substring = true).assertCountEquals(0)
    }

    @Test fun aLockedAccountStillReviewsAnExitAndRefusesAnEntryBeforeAnyConfirmation() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            // The runtime's controller reads the system clock, so the P&L snapshots are stamped with it.
            val broker = RiskFixture(TestClock(Clock.System.now()))
            broker.hold("100", listOf(riskPosition(Side.BUY, 100)))
            val runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply { paused.value = true }
            runBlocking { assertNull(runtime.accountRisk.configure(broker, "100", RiskLimits(200_000_000))) }
            broker.pnl("100", -250_000_000)
            show(runtime, 1400, 900)
            // The monitor latches the limit without any order being tried.
            waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, "100").trigger != null }
            onNodeWithTag("review-order").performClick()
            onNodeWithTag("confirm-action").assertDoesNotExist()
            onNodeWithTag("trade-notice").assertTextEquals("2026-11-11 交易日已锁定 · 达到亏损额度；仍可平仓、撤单、收紧止损或挂减仓单。")
            // One contract sold against the long of one only reduces it.
            onNodeWithTag("order-side-SELL").performClick()
            onNodeWithTag("review-order").performClick()
            onNodeWithTag("confirm-action").performClick()
            waitUntil(timeoutMillis = 5_000) { broker.events == listOf("place:100") }
        }
}
