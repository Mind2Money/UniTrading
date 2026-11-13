package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import com.unitrading.chart.OrderTagButtons
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AccountBracketsUiTest {
    /** The desktop workspace: the order ticket stays open beside the chart. */
    private fun ComposeUiTest.desktop(runtime: TradingRuntime) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(1400.dp, 900.dp)) { TradingScreen(runtime) }
        }
    }
    private fun runtimeWith(broker: BrokerPlugin, brackets: AccountBrackets) = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply {
        paused.value = true
        runBlocking { assertNull(accountRisk.configureBrackets(broker, "100", brackets)) }
    }
    /** The phone tests drive the docked order bar, whichever order page a phone opens by default. */
    private fun phoneRuntime(broker: BrokerPlugin) = TradingRuntime(MemorySnapshotStore(), listOf(broker)).apply {
        runBlocking { assertTrue(tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) }
    }
    private val cash = AccountBrackets(loss = 100_000_000, profit = 200_000_000)
    private fun ComposeUiTest.chartPoint(price: Long): Offset {
        val node = onNodeWithTag("trading-chart").fetchSemanticsNode()
        val axis = node.config[SemanticsProperties.StateDescription].split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
        val scale = PriceScale(axis.getValue("low").toDouble(), axis.getValue("high").toDouble(), 18f, axis.getValue("plotBottom").toFloat())
        return Offset(node.size.width * .7f, scale.y(price))
    }
    /** A click on the plot gives the chart the keyboard focus, which makes the trading keys live. */
    private fun ComposeUiTest.focusChart() {
        val at = chartPoint(550_500)
        onNodeWithTag("trading-chart").performMouseInput { moveTo(at); press(); release() }
        waitForIdle()
    }
    private fun ComposeUiTest.rightClickChart(price: Long) {
        val at = chartPoint(price)
        onNodeWithTag("trading-chart").performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        waitForIdle()
    }
    /** Waits for the order to reach the broker and for the screen to take commands again: it ignores them while one is in flight. */
    private fun ComposeUiTest.awaitPlaced(broker: RiskFixture, count: Int) {
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == count }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("review-order") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun mobileSettingsApplyBracketsToAReviewedOrder() = runDesktopComposeUiTest(width = 450, height = 850) {
        val broker = OfflineOrderBroker()
        val runtime = phoneRuntime(broker)
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
        // The confirmation shows the prices the order will carry, not just the two rows.
        onNodeWithText("5480.25").assertExists()
        onNodeWithText("5540.25").assertExists()
        assertTrue(broker.placed.isEmpty())
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
        assertEquals(548_025L, broker.placed.single().stopLoss)
        assertEquals(554_025L, broker.placed.single().takeProfit)
    }

    @Test fun choosingRiskSizingReplacesBracketsAndRequiresAStopBeforeOrdering() =
        runDesktopComposeUiTest(width = 450, height = 850) {
            val broker = OfflineOrderBroker()
            val runtime = phoneRuntime(broker)
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
            // The amount cannot be saved alone: without a ceiling a stop next to the entry would size an order without bound.
            onNodeWithTag("save-account-brackets").assertIsNotEnabled()
            onNodeWithTag("account-sizing-max-lots").performTextInput("2.5")
            onNodeWithTag("save-account-brackets").assertIsNotEnabled()
            onNodeWithTag("account-sizing-max-lots").performTextReplacement("5")
            onNodeWithTag("save-account-brackets").performClick()
            waitUntil(timeoutMillis = 5_000) { runtime.accountRisk.rule(broker, "100").brackets == AccountBrackets(sizingRisk = 99_000_000, sizingMaxLots = 500) }
            onNodeWithTag("edit-account-brackets").assertTextContains("自动计算手数 · 风险 99.00 USD · 单笔最多 5 张")
            onNodeWithTag("nav-1").performClick()
            onNodeWithTag("open-order").performClick()
            onNodeWithTag("review-order").assertIsNotEnabled()
            // Ten ticks under the ask the amount would buy seven contracts: the ceiling holds the order at five, and the form says so.
            onNodeWithTag("order-risk-sizing-sl").performScrollTo().performTextReplacement("5497.75")
            onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 5 张 · 风险 99.00 USD · 受单笔最大数量限制")
            onNodeWithTag("order-risk-sizing-sl").performTextReplacement("5495.25")
            onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 3 张 · 风险 99.00 USD")
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
            val runtime = phoneRuntime(broker)
            kotlinx.coroutines.runBlocking {
                assertNull(runtime.accountRisk.configureBrackets(broker, "100", AccountBrackets(sizingRisk = 99_000_000, sizingMaxLots = 1_000)))
                assertNull(runtime.accountRisk.configureOrders(broker, "100", AccountOrderLimits(500)))
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
            // Ten ticks under the ask the amount would buy seven contracts: the estimate of the drag stops at the lot cap of five, as the form will.
            // The handle sits close to that price on the shorter chart beside the docked form, so the drag first leaves the touch slop.
            chart.performTouchInput { down(handle); moveTo(Offset(handle.x, scale.y(549_025))); moveTo(Offset(handle.x, scale.y(549_775))) }
            onNodeWithTag("chart-readout").assertTextContains("自动手数 5 张", substring = true)
            chart.performTouchInput { moveTo(Offset(handle.x, scale.y(549_525))) }
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

    @Test fun anOrderAgainstThePositionIsBuiltAndPlacedWithoutTheAccountsBrackets() = runDesktopComposeUiTest(width = 1400, height = 900) {
        // A net account, as Tradovate and Rithmic are, long two contracts. Both refuse a bracket in a contract with a position.
        val broker = RiskFixture().apply { hold("100", listOf(riskPosition(Side.BUY, 200))) }
        desktop(runtimeWith(broker, cash))
        val reduction = "减仓单不附加子账户止损止盈。提交时会按最新报价和账户状态重新校验。"
        // Buying adds to the long, so the amounts price a stop and a target for the order.
        onNodeWithTag("order-account-brackets").assertTextEquals("子账户自动保护 · SL 5480.25 · TP 5540.25")
        // Selling one of the two only reduces the position: the order goes as it is, and the confirmation says so.
        onNodeWithTag("order-side-SELL").performClick()
        onNodeWithTag("order-error").assertDoesNotExist()
        onNodeWithTag("order-account-brackets").assertTextEquals("减仓单 · 不附加子账户止损止盈")
        onNodeWithTag("review-order").assertIsEnabled().performClick()
        onNodeWithText(reduction).assertExists()
        onNodeWithTag("confirm-action").assertIsEnabled().performClick()
        awaitPlaced(broker, 1)
        assertEquals(OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100), broker.placed.single())
        // The whole position is still a reduction; one contract more would open a short, which is an entry again.
        onNodeWithTag("order-quantity").performTextReplacement("2")
        onNodeWithTag("review-order").performClick()
        onNodeWithText(reduction).assertExists()
        onNodeWithText("取消").performClick()
        onNodeWithTag("order-quantity").performTextReplacement("3")
        onNodeWithTag("order-account-brackets").assertTextEquals("子账户自动保护 · SL 5506.50 · TP 5486.50")
        // A sell limit above the market is a target for the long. A resting order is checked as it stands before it is
        // sent, and a reduction has no amounts to meet there either.
        onNodeWithTag("order-quantity").performTextReplacement("1")
        onNodeWithTag("order-kind-LIMIT").performClick()
        onNodeWithTag("order-entry").performTextReplacement("5510.00")
        onNodeWithTag("review-order").performClick()
        onNodeWithText(reduction).assertExists()
        onNodeWithTag("confirm-action").assertIsEnabled().performClick()
        awaitPlaced(broker, 2)
        assertEquals(OrderRequest("TESTZ6", Side.SELL, OrderKind.LIMIT, 100, 551_000), broker.placed.last())
    }

    @Test fun theMinimizedBarShowsTheAccountsProtectionOffForAReduction() = runDesktopComposeUiTest(width = 450, height = 850) {
        // Long two contracts: a buy adds and gets the account's stop and target, a sell of one only reduces and gets neither.
        val broker = RiskFixture().apply { hold("100", listOf(riskPosition(Side.BUY, 200))) }
        val runtime = runtimeWith(broker, cash).apply { runBlocking { assertTrue(tradingPreferences.setOrderPanelLayout(OrderPanelLayout.DOCKED)) } }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(450.dp, 850.dp)) { TradingScreen(runtime) }
            }
        }
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("minimize-order").performClick()
        onNodeWithTag("compact-sl").assertIsOn().assertIsNotEnabled()
        onNodeWithTag("compact-tp").assertIsOn().assertIsNotEnabled()
        onNodeWithTag("restore-order").performClick()
        onNodeWithTag("order-side-SELL").performClick()
        onNodeWithTag("order-account-brackets").assertTextEquals("减仓单 · 不附加子账户止损止盈")
        onNodeWithTag("minimize-order").performClick()
        onNodeWithTag("compact-sl").assertIsOff().assertIsNotEnabled()
        onNodeWithTag("compact-tp").assertIsOff().assertIsNotEnabled()
    }

    @Test fun aKeyOrAMenuOrderAgainstThePositionGoesOutWithoutTheBrackets() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val broker = RiskFixture().apply { hold("100", listOf(riskPosition(Side.BUY, 200))) }
        val runtime = runtimeWith(broker, cash)
        runBlocking { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true) && runtime.tradingPreferences.setSkipConfirmation(true)) }
        desktop(runtime)
        focusChart()
        onNodeWithTag("trading-keys-hint").assertTextEquals("W 市价买入 · S 市价卖出 · 1 张 · 开仓自动附加 SL/TP · 按键即成交")
        // Long two: S sells one of them, and a sell limit above the market is a target. Neither opens anything.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.S) }
        awaitPlaced(broker, 1)
        rightClickChart(551_000)
        onNodeWithTag("plot-menu-SELL-LIMIT").performClick()
        awaitPlaced(broker, 2)
        // W adds to the long: an entry, which goes out with the stop and target of the amounts.
        focusChart()
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.W) }
        awaitPlaced(broker, 3)
        assertEquals(listOf(OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100), OrderRequest("TESTZ6", Side.SELL, OrderKind.LIMIT, 100, 551_000),
            OrderRequest("TESTZ6", Side.BUY, OrderKind.MARKET, 100, stopLoss = 548_025, takeProfit = 554_025)), broker.placed)
    }

    @Test fun aSizedAccountTypesTheQuantityOfAReductionWithinThePosition() = runDesktopComposeUiTest(width = 1400, height = 900) {
        // The position has its stop and target, so a button on the chart that adds one can only belong to the draft.
        val broker = RiskFixture().apply { hold("100", listOf(riskPosition(Side.BUY, 200, stop = 547_000, target = 553_000))) }
        desktop(runtimeWith(broker, AccountBrackets(sizingRisk = 99_000_000, sizingMaxLots = 500)))
        // Buying adds to the long: the stop sizes the order and no quantity is typed.
        onNodeWithTag("order-quantity").assertDoesNotExist()
        onNodeWithTag("order-risk-sizing-sl").performTextReplacement("5495.25")
        onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 3 张 · 风险 99.00 USD")
        // Selling works against the long. Nothing can size a reduction, so its quantity is typed, up to the two held.
        onNodeWithTag("order-side-SELL").performClick()
        onNodeWithTag("order-risk-sizing-sl").assertDoesNotExist()
        onNodeWithTag("order-risk-sized-quantity").assertDoesNotExist()
        onNodeWithTag("order-reduction").assertTextEquals("减仓单 · 最多 2 张，按输入数量下单，不附加止损")
        onNodeWithText("减仓数量（张）").assertExists()
        onNodeWithTag("order-quantity").assertTextContains("1")
        onNodeWithTag("quantity-increase").performClick()
        onNodeWithTag("order-quantity").assertTextContains("2")
        onNodeWithTag("quantity-increase").assertIsNotEnabled()
        onNodeWithTag("review-order").assertIsEnabled()
        // On the chart the draft of a reduction can take a target, and has no stop to drag out.
        val offered = onNodeWithTag("trading-chart").fetchSemanticsNode().config[OrderTagButtons].keys
        assertTrue("拖动设置止盈" in offered && "拖动设置止损" !in offered, "$offered")
        onNodeWithTag("order-quantity").performTextReplacement("3")
        onNodeWithTag("order-error").assertTextEquals("减仓数量不能超过持仓的 2 张；反手请先平仓")
        onNodeWithTag("review-order").assertIsNotEnabled()
        onNodeWithTag("order-quantity").performTextReplacement("1")
        onNodeWithTag("review-order").performClick()
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
        // The stop typed for the buy sized that order; it is no part of this one.
        assertEquals(OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100), broker.placed.single())
        // With a stop and a target already working for the whole position the sell would go with no stop of its own, and
        // after it they could fill to open a short: the form says so before anything is reviewed. A stop for the long may go.
        runOnIdle {
            broker.hold("100", listOf(riskPosition(Side.BUY, 200)), listOf(
                riskOrder("stop", Side.SELL, OrderKind.STOP, 200, 548_000), riskOrder("target", Side.SELL, OrderKind.LIMIT, 200, 552_000)))
        }
        waitForIdle()
        onNodeWithTag("order-error").assertTextEquals(EXITS_COVER_POSITION)
        onNodeWithTag("review-order").assertIsNotEnabled()
        onNodeWithTag("order-kind-STOP").performClick()
        onNodeWithTag("order-entry").performTextReplacement("5480.00")
        onNodeWithTag("order-error").assertDoesNotExist()
        onNodeWithTag("order-reduction").assertTextEquals("减仓单 · 最多 2 张，按输入数量下单，不附加止损")
        onNodeWithTag("review-order").assertIsEnabled()
        // Back on the side of the position the order is sized again, from the stop it was given before.
        onNodeWithTag("order-kind-MARKET").performClick()
        onNodeWithTag("order-side-BUY").performClick()
        onNodeWithTag("order-quantity").assertDoesNotExist()
        onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 3 张 · 风险 99.00 USD")
    }

    @Test fun theSizedQuantityStopsAtTheLotCapAndTheServersLimitAndTheFormSaysWhich() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val broker = RiskFixture()
        val runtime = runtimeWith(broker, AccountBrackets(sizingRisk = 300_000_000, sizingMaxLots = 500))
        desktop(runtime)
        // Ten ticks under the ask, 300 would buy 24 contracts.
        onNodeWithTag("order-risk-sizing-sl").performTextReplacement("5497.75")
        onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 5 张 · 风险 300.00 USD · 受单笔最大数量限制")
        runBlocking { assertNull(runtime.accountRisk.configureOrders(broker, "100", AccountOrderLimits(400))) }
        waitForIdle()
        onNodeWithTag("order-risk-sized-quantity").assertTextEquals("自动手数 4 张 · 风险 300.00 USD · 受子账户最大持仓限制")
        // One contract held elsewhere leaves three under the cap, and the server now answers that it takes two.
        runOnIdle {
            broker.capacity = 200
            broker.hold("100", listOf(riskPosition(Side.BUY, 100, symbol = "OTHRZ6")))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("自动手数 2 张 · 风险 300.00 USD · 受可下单上限限制").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("order-error").assertDoesNotExist()
        onNodeWithTag("review-order").performClick()
        onNodeWithTag("confirm-action").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.MARKET, 200, stopLoss = 549_775), broker.placed.single())
    }

    @Test fun theConfirmationShowsTheBrokersWarningAndCallsAMarketOrdersProtectionAnEstimate() = runDesktopComposeUiTest(width = 1400, height = 900) {
        // Rithmic places a market order's brackets by their distance from the fill, and says so in its preview.
        val warning = "市价单的括号保护按实际成交价加减相同 tick 数生成，实际 SL/TP 价位可能与输入不同"
        val broker = RiskFixture().apply { previewWarning = warning }
        desktop(runtimeWith(broker, cash))
        onNodeWithTag("review-order").performClick()
        onNodeWithText("止损价（预估）").assertExists()
        onNodeWithText("止盈价（预估）").assertExists()
        onNodeWithText("5480.25").assertExists()
        onNodeWithText("5540.25").assertExists()
        // Once in the order form behind the dialog and once in the dialog, where an order from the chart or a key is first seen.
        onAllNodesWithText(warning).assertCountEquals(2)
        onNodeWithText("取消").performClick()
        // A resting entry fills at its own price, so its stop and target are the prices that will be working.
        onNodeWithTag("order-kind-LIMIT").performClick()
        onNodeWithTag("order-entry").performTextReplacement("5490.00")
        onNodeWithTag("review-order").performClick()
        onNodeWithText("止损价").assertExists()
        onNodeWithText("止盈价").assertExists()
        onNodeWithText("止损价（预估）").assertDoesNotExist()
        onNodeWithText("5470.00").assertExists()
        onNodeWithText("5530.00").assertExists()
    }

    @Test fun onAPhoneHeldSidewaysTheConfirmationScrollsToTheErrorBeforeTheNoteWithConfirmInView() = runDesktopComposeUiTest(width = 800, height = 360) {
        val warning = "市价单的括号保护按实际成交价加减相同 tick 数生成，实际 SL/TP 价位可能与输入不同"
        val broker = RiskFixture().apply { previewWarning = warning }
        val runtime = runtimeWith(broker, cash)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(800.dp, 360.dp)) { TradingScreen(runtime) }
            }
        }
        onNodeWithTag("open-order").performClick()
        onNodeWithTag("review-order").performClick()
        onNodeWithTag("confirm-action").assertIsDisplayed().assertIsEnabled()
        // The broker turns the order down while the dialog is open, as a margin check or a server limit can.
        runOnIdle { broker.source.refusal = "保证金不足"; broker.quote(550_000, 550_025) }
        val error = onNodeWithTag("confirm-error").assertTextEquals("保证金不足")
        val note = onNodeWithTag("confirm-warning").assertTextEquals(warning)
        // Under the market order's estimates and the cash amounts' rows, the end of the dialog is out of view at first.
        note.assertIsNotDisplayed()
        note.performScrollTo().assertIsDisplayed()
        error.performScrollTo().assertIsDisplayed()
        onNodeWithTag("confirm-action").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(error.fetchSemanticsNode().positionInRoot.y < note.fetchSemanticsNode().positionInRoot.y, "the reason 确认 is off comes first")
    }

    @Test fun aMarketOrderIsNotHeldToAQuoteTheBrokerHasNotPublishedYet() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val broker = RiskFixture()
        desktop(runtimeWith(broker, cash))
        // The broker's own quote is a tick ahead of the one it has published, so its check enters at 5500.50. Against the
        // stop priced from the published ask of 5500.25 that would lose 101.25, over the amount by one tick: no reason
        // to block the confirmation or to refuse the order, which is priced once more as it goes out.
        broker.previewQuote = Quote(550_025, 550_050, 0)
        onNodeWithTag("review-order").performClick()
        onNodeWithText("5480.25").assertExists()
        onNodeWithTag("confirm-action").assertIsEnabled().performClick()
        awaitPlaced(broker, 1)
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.MARKET, 100, stopLoss = 548_025, takeProfit = 554_025), broker.placed.single())
    }

    @Test fun cashAmountsAreNamedWhereverAnOrderCanGoOutWithoutTheOrderForm() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val broker = OfflineOrderBroker()
        val runtime = runtimeWith(broker, AccountBrackets(loss = 100_000_000))
        runBlocking { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true) && runtime.tradingPreferences.setSkipConfirmation(true)) }
        desktop(runtime)
        focusChart()
        onNodeWithTag("trading-keys-hint").assertTextEquals("W 市价买入 · S 市价卖出 · 1 张 · 开仓自动附加 SL · 按键即成交")
        // With confirmations skipped the menu item is the last thing shown before the order is out.
        rightClickChart(549_000)
        onNodeWithTag("plot-menu-BUY-LIMIT").assertTextEquals("买入限价 1 张 @ 5490.00 · 开仓自动附加 SL").performClick()
        waitUntil(timeoutMillis = 5_000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.LIMIT, 100, 549_000, stopLoss = 547_000), broker.placed.single())
        onNodeWithTag("open-settings").performClick()
        onNodeWithTag("settings-keyboard-trading-summary", useUnmergedTree = true)
            .assertTextContains("开仓单按当前子账户的金额设置自动附加止损止盈。已开启“跳过交易确认”，按键即成交。", substring = true)
        onNodeWithTag("settings-skip-confirmation").assertTextContains("开启键盘下单时按键即成交。", substring = true)
    }

    @Test fun onASizedAccountTheKeysAndTheChartMenuOnlyOpenTheOrderPanelAndSaySo() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val broker = OfflineOrderBroker()
        val runtime = runtimeWith(broker, AccountBrackets(sizingRisk = 99_000_000, sizingMaxLots = 500))
        runBlocking { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true) && runtime.tradingPreferences.setSkipConfirmation(true)) }
        desktop(runtime)
        onNodeWithTag("trading-keys-hint").assertTextEquals("键盘下单：点一下图表后 W / S 生效")
        focusChart()
        onNodeWithTag("trading-keys-hint").assertTextEquals("W 买入 · S 卖出 · 打开下单栏填写 SL，不直接下单")
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.S) }
        waitForIdle()
        // The key chose the side in the order panel and sent nothing, although confirmations are skipped.
        onNodeWithTag("order-side-SELL").assertIsSelected()
        onNodeWithTag("confirm-action").assertDoesNotExist()
        rightClickChart(549_000)
        onNodeWithTag("plot-menu-BUY-LIMIT").assertTextEquals("买入限价 @ 5490.00 · 打开下单栏填写 SL").performClick()
        waitForIdle()
        onNodeWithTag("order-side-BUY").assertIsSelected()
        onNodeWithTag("order-entry").assertTextContains("5490.00")
        assertTrue(broker.placed.isEmpty())
        onNodeWithTag("open-settings").performClick()
        val summary = onNodeWithTag("settings-keyboard-trading-summary", useUnmergedTree = true)
        summary.assertTextContains("按 W 或 S 打开下单栏并选好买入或卖出，不会直接下单", substring = true)
        assertFalse("按键即成交" in summary.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        // The row that skips the confirmations says the same for this sub-account instead of promising a fill.
        val skip = onNodeWithTag("settings-skip-confirmation")
        skip.assertTextContains("当前子账户按风险计算手数，开启键盘下单时按键只打开下单栏，不会直接下单。", substring = true)
        assertFalse("按键即成交" in skip.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
    }
}
