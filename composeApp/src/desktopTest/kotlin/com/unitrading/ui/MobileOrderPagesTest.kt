package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.AccountBrackets
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.OrderPanelLayout
import com.unitrading.app.TradingPreferences
import com.unitrading.app.TradingPreferencesController
import com.unitrading.app.TradingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * The order pages a phone can use: the sheet opens unless the settings chose the dock or the popup, the choice is saved
 * and switches the page, and the popup keeps its draft however it is closed. The sheet's own tests are
 * [MobileLayoutTest] and [MobileSheetMotionTest], the dock's [MobileOrderDockTest].
 */
@OptIn(ExperimentalTestApi::class)
class MobileOrderPagesTest {
    private lateinit var compose: ComposeUiTest

    private fun phoneTest(block: () -> Unit) = runDesktopComposeUiTest(width = 300, height = 600) {
        compose = this
        block()
    }
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val session = plugin.openSession(plugin.accounts.first().id)

    private fun render(layout: OrderPanelLayout? = null) {
        if (layout != null) runBlocking { assertTrue(runtime.tradingPreferences.setOrderPanelLayout(layout)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 800.dp).testTag("test-host")) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    /** Picks [layout] in the settings, as a user does, and comes back to the chart. */
    private fun choose(layout: OrderPanelLayout, from: OrderPanelLayout) {
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-order-layout").performScrollTo().assertTextContains(from.label, substring = true).performClick()
        compose.onNodeWithTag("settings-order-layout-${from.name}").assertIsSelected()
        compose.onNodeWithTag("settings-order-layout-${layout.name}").assertIsNotSelected().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runtime.tradingPreferences.preferences.value.orderPanelLayout == layout }
        assertEquals(layout, TradingPreferencesController(store).preferences.value.orderPanelLayout, "a restart opens the chosen page")
        // A saved choice closes the list.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("settings-order-layout-${layout.name}").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("settings-order-layout").assertTextContains(layout.label, substring = true)
        compose.onNodeWithTag("nav-1").performClick()
    }
    private fun handle() = compose.onNodeWithTag("order-sheet-handle", useUnmergedTree = true)

    @Test fun theSheetOpensByDefaultAndTheSettingsSwitchToTheDockThePopupAndBack() = phoneTest {
        render()
        assertEquals(OrderPanelLayout.SHEET, TradingPreferences().orderPanelLayout)
        assertEquals(OrderPanelLayout.SHEET, runtime.tradingPreferences.preferences.value.orderPanelLayout)
        val closed = height("trading-chart")
        compose.onNodeWithTag("open-order").performClick()
        handle().assertExists()
        compose.onNodeWithTag("order-sheet").assertIsDisplayed()
        compose.onNodeWithTag("order-dock").assertDoesNotExist()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        assertTrue(height("trading-chart") >= closed, "the sheet lies over the chart and takes none of its height")
        compose.onNodeWithTag("close-order").performClick()

        // The settings name the three pages and say what each does.
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-order-layout").performScrollTo().performClick()
        for (layout in OrderPanelLayout.entries) compose.onNodeWithTag("settings-order-layout-${layout.name}")
            .assertIsDisplayed().assertTextContains(layout.label).assertTextContains(layout.summary)
        assertEquals(listOf("上滑面板" to "从底部上滑，可拖动收起", "停靠下单栏" to "停在图表下方或右侧，不遮挡 K 线", "独立弹窗" to "在对话框中下单"),
            OrderPanelLayout.entries.map { it.label to it.summary })
        capture("settings-order-page-choices")
        compose.onNodeWithTag("settings-order-layout-close").performClick()
        compose.onNodeWithTag("settings-order-layout-SHEET").assertDoesNotExist()
        assertEquals(OrderPanelLayout.SHEET, runtime.tradingPreferences.preferences.value.orderPanelLayout, "closing the list changes nothing")
        compose.onNodeWithTag("nav-1").performClick()

        choose(OrderPanelLayout.DOCKED, from = OrderPanelLayout.SHEET)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-dock").assertIsDisplayed()
        handle().assertDoesNotExist()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        assertTrue(height("trading-chart") < closed, "the dock takes its space from the chart")
        compose.onNodeWithTag("close-order").performClick()

        choose(OrderPanelLayout.POPUP, from = OrderPanelLayout.DOCKED)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-popup").assertIsDisplayed()
        compose.onNodeWithTag("order-dock").assertDoesNotExist()
        handle().assertDoesNotExist()
        assertEquals(closed, height("trading-chart"), "the popup leaves the chart page as it is")
        compose.onNodeWithTag("popup-discard-order").performClick()

        choose(OrderPanelLayout.SHEET, from = OrderPanelLayout.POPUP)
        compose.onNodeWithTag("open-order").performClick()
        handle().assertExists()
        compose.onNodeWithTag("order-dock").assertDoesNotExist()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
    }

    @Test fun choosePopupAndReturnToTheChartWithoutChangingItsHeight() = phoneTest {
        render()
        val chartHeight = height("trading-chart")
        choose(OrderPanelLayout.POPUP, from = OrderPanelLayout.SHEET)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-popup").assertIsDisplayed()
        compose.onNodeWithTag("order-dock").assertDoesNotExist()
        assertEquals(chartHeight, height("trading-chart"))
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.25")
        compose.onNodeWithTag("popup-adjust-on-chart").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        assertEquals(chartHeight, height("trading-chart"))
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("confirm-action").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")
        compose.onNodeWithTag("close-order-dialog").performClick()
        // The draft the popup left on the chart is the dock's minimized order once the dock is chosen.
        choose(OrderPanelLayout.DOCKED, from = OrderPanelLayout.POPUP)
        compose.onNodeWithTag("minimized-order").assertIsDisplayed()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-dock").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")
        assertTrue(height("trading-chart") < chartHeight)
    }

    @Test fun popupCloseButtonAndATapBesideItKeepTheDraftAndOnlyCancelOrderDropsIt() = phoneTest {
        render(OrderPanelLayout.POPUP)
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.25")
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        val stop = text("order-sl")
        val target = text("order-tp")
        assertTrue(stop.isNotBlank() && target.isNotBlank())
        capture("mobile-popup-order")

        // The close button says what it does, and does only that.
        compose.onNodeWithTag("close-order-dialog").assertContentDescriptionEquals("返回图表").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertIsDisplayed()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn()
        compose.onNodeWithTag("order-tp-enabled").assertIsOn()
        assertEquals(stop, text("order-sl"))
        assertEquals(target, text("order-tp"))

        // A tap beside the dialog asks it to dismiss, which is also what the system back does.
        compose.onNodeWithTag("test-host").performTouchInput { click(Offset(width / 2f, 3f)) }
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")
        assertEquals(stop, text("order-sl"))

        // "到图表调整" is the same step in words.
        compose.onNodeWithTag("popup-adjust-on-chart").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.25")

        compose.onNodeWithTag("popup-discard-order").assertTextEquals("取消订单").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOff()
        compose.onNodeWithTag("order-tp-enabled").assertIsOff()
        assertEquals(before, session.state.value, "closing, reopening and discarding never send an order")
    }

    @Test fun popupNamesTheAccountAndAnOrderPlacedFromItStartsAFreshDraft() = phoneTest {
        render(OrderPanelLayout.POPUP)
        val positions = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        val instrument = plugin.instruments.first()
        compose.onNodeWithText("${plugin.accounts.first().name} · ${instrument.symbol} · ${instrument.currency} 结算").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.12")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.positions.size == positions + 1 }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("order-popup").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
    }

    /** The sheet's peek and the minimized dock share their controls, so a sub-account's bracket rules show in both the same way. */
    @Test fun minimizedSheetAndDockNameTheSizedQuantityAndDoNotSwitchWhatTheAccountDecides() = phoneTest {
        val spec = plugin.instruments.first()
        val quote = plugin.market.value.quotes[spec.symbol]
        var brackets by mutableStateOf(AccountBrackets(sizingRisk = 99_000_000))
        var draft by mutableStateOf(OrderDraft())
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) { TradingTheme { Column {
            MobileOrderSheet(spec, quote, plugin.capabilities, draft, { draft = it }, MobileOrderPanelState.MINIMIZED, {}, {}, {}, true,
                Modifier.requiredSize(400.dp, 400.dp), brackets = brackets, calculatedLots = 300, content = {}, body = {})
            Box(Modifier.requiredSize(400.dp, 97.dp)) {
                MobileOrderDock(spec, quote, plugin.capabilities, "账户", draft, { draft = it }, MobileOrderPanelState.MINIMIZED, {}, {}, {}, true,
                    busy = false, skipConfirmation = false, keyboardOpen = false, brackets = brackets, calculatedLots = 300) {}
            }
        } } } }
        compose.waitForIdle()
        fun both(tag: String, check: SemanticsNodeInteraction.() -> Unit) {
            compose.onAllNodesWithTag(tag).assertCountEquals(2)
            repeat(2) { compose.onAllNodesWithTag(tag)[it].check() }
        }
        // Sized from a loss amount: the quantity is the computed one, and the stop that sizing needs is not a switch.
        both("restore-order") { assertTextContains("${spec.formatQuantity(300)} ${spec.quantityUnit} · 买入") }
        both("compact-sl") { assertIsNotEnabled(); assertIsOff() }
        both("compact-tp") { assertIsEnabled(); assertIsOff() }
        compose.runOnIdle { draft = draft.withProtectionPrice(true, spec.formatPrice(quote!!.bid - 100 * spec.tickSize)) }
        both("compact-sl") { assertIsNotEnabled(); assertIsOn() }
        compose.onAllNodesWithTag("compact-tp")[0].performClick()
        both("compact-tp") { assertIsOn() }
        // Cash brackets attach both protections themselves.
        compose.runOnIdle { brackets = AccountBrackets(loss = 100_000_000, profit = 200_000_000); draft = OrderDraft() }
        both("restore-order") { assertTextContains("${draft.quantity} ${spec.quantityUnit} · 买入") }
        both("compact-sl") { assertIsNotEnabled(); assertIsOn() }
        both("compact-tp") { assertIsNotEnabled(); assertIsOn() }
        // Without account rules both are plain switches again.
        compose.runOnIdle { brackets = AccountBrackets() }
        both("compact-sl") { assertIsEnabled(); assertIsOff() }
        compose.onAllNodesWithTag("compact-sl")[1].performClick()
        both("compact-sl") { assertIsOn() }
    }

    @Test fun theSheetNamesTheAccountWhileItCoversTheAccountBar() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        // Expanded, the side buttons show the prices and the header says which account trades.
        compose.onNodeWithTag("restore-order").assertTextContains(plugin.accounts.first().name)
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("restore-order").assertTextContains("0.23 手 · 买入")
    }
}
