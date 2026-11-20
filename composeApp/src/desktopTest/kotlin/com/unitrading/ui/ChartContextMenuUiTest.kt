package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import com.unitrading.chart.PriceScale
import com.unitrading.chart.drawing.DrawingTool
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** A right click on the empty plot opens the chart's own menu: a horizontal line and the entry orders that can rest at that price. */
@OptIn(ExperimentalTestApi::class)
class ChartContextMenuUiTest {
    @get:Rule val compose = createComposeRule()
    private val broker = OfflineOrderBroker()
    private lateinit var runtime: TradingRuntime

    private fun render(skipConfirmation: Boolean = false) {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        if (skipConfirmation) runBlocking { assertTrue(runtime.tradingPreferences.setSkipConfirmation(true)) }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.waitForIdle()
    }
    private fun chart() = compose.onNodeWithTag("trading-chart")
    private fun chartState() = chart().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .split(';').associate { it.substringBefore(':') to it.substringAfter(':') }
    /** Right of the legend, left of the price axis, on the row of [price]. */
    private fun pointAt(price: Long): Offset {
        val state = chartState()
        val scale = PriceScale(state.getValue("low").toDouble(), state.getValue("high").toDouble(), 18f * .6f, state.getValue("plotBottom").toFloat())
        return Offset(chart().fetchSemanticsNode().size.width * .7f, scale.y(price))
    }
    private fun rightClick(price: Long) {
        val at = pointAt(price)
        chart().performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        compose.waitForIdle()
    }
    /** A finger held still on the row of [price] until the long press fires. */
    private fun longPress(price: Long) {
        val at = pointAt(price)
        chart().performTouchInput { longClick(at) }
        compose.waitForIdle()
    }
    private fun item(name: String) = compose.onNodeWithTag("plot-menu-$name")

    @Test fun chartMenuReopensTheRightHandTicketAndPrefillsTheOrder() {
        render(skipConfirmation = true)
        compose.onNodeWithTag("rail-order").performClick()
        compose.onNodeWithTag("desktop-order").assertDoesNotExist()
        rightClick(549_000)
        item("BUY-LIMIT").performClick()
        compose.onNodeWithTag("order-popup").assertDoesNotExist()
        compose.onNodeWithTag("desktop-order").assertIsDisplayed()
        compose.onNode(hasTestTag("order-side-BUY") and hasAnyAncestor(hasTestTag("desktop-order"))).assertIsSelected()
        compose.onNode(hasTestTag("order-kind-LIMIT") and hasAnyAncestor(hasTestTag("desktop-order"))).assertIsSelected()
        compose.onNode(hasTestTag("order-entry") and hasAnyAncestor(hasTestTag("desktop-order"))).assertTextContains("5490.00")
        assertTrue(broker.placed.isEmpty(), "Choosing a context-menu order must never submit it")
    }

    @Test fun desktopMenuOmitsCopyPriceAndResetsTheView() {
        render()
        compose.onNodeWithTag("chart-zoom-in").performClick()
        compose.waitForIdle()
        assertTrue(chartState().getValue("bars").toDouble() < 80.0)
        rightClick(549_000)
        compose.onNodeWithTag("plot-copy-price").assertDoesNotExist()
        compose.onNodeWithTag("plot-reset").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(80.0, chartState().getValue("bars").toDouble())
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun orderTicketIsPrefilledButDoesNotSubmit() {
        render(skipConfirmation = true)
        rightClick(549_000)
        compose.onNodeWithTag("plot-open-order").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("order-entry").assertTextContains("5490.00")
        assertTrue(broker.placed.isEmpty(), "opening the ticket never sends an order")
    }

    @Test fun desktopMenuOmitsTimeLockAndTableButKeepsObjectTree() {
        render()
        rightClick(549_000)
        compose.onNodeWithTag("plot-time-lock").assertDoesNotExist()
        compose.onNodeWithTag("plot-table").assertDoesNotExist()
        compose.onNodeWithTag("plot-objects").performScrollTo().performClick()
        compose.onNodeWithTag("chart-object-tree").assertExists()
    }

    @Test fun templateIsSavedAndReopened() {
        render()
        rightClick(549_000)
        compose.onNodeWithTag("plot-templates").performScrollTo().performClick()
        compose.onNodeWithTag("chart-template-name").performTextInput("daily")
        compose.onNodeWithTag("chart-template-save").performClick()
        compose.waitUntil(5_000) { runtime.chartTemplates.templates.value.any { it.name == "daily" } }
        val savedAxis = runtime.chartTemplates.templates.value.single().axis
        runBlocking {
            runtime.chartPreferences.selectPriceAxis(savedAxis.copy(inverted = true))
            assertTrue(runtime.chartPreferences.savePriceAxis())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chart-template-apply-daily").performScrollTo().performClick()
        compose.waitUntil(5_000) { runtime.chartPreferences.priceAxis.value == savedAxis }
        assertEquals("daily", runtime.chartTemplates.templates.value.single().name)
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun desktopMenuOmitsPasteButKeyboardPasteStillWorks() {
        render()
        compose.runOnIdle {
            runtime.drawings.addHorizontalLine(com.unitrading.chart.drawing.DrawingPoint(broker.time - 60_000, 548_500.0))
            runtime.drawings.copySelected()
        }
        rightClick(549_000)
        compose.onNodeWithTag("plot-paste-drawing").assertDoesNotExist()
        compose.onNodeWithTag("plot-reset").performClick()
        val size = chart().fetchSemanticsNode().size
        chart().performMouseInput { click(Offset(size.width * .7f, size.height * .7f)) }
        chart().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        compose.waitForIdle()
        assertEquals(2, runtime.drawings.drawings.size)
        assertNotEquals(runtime.drawings.drawings[0].id, runtime.drawings.drawings[1].id)
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun objectTreeHidesLocksAndRemovesDrawings() {
        render()
        compose.runOnIdle { runtime.drawings.addHorizontalLine(com.unitrading.chart.drawing.DrawingPoint(broker.time - 60_000, 548_500.0)) }
        rightClick(549_000)
        compose.onNodeWithTag("plot-objects").performScrollTo().performClick()
        fun treeItem(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("chart-object-tree")))
        treeItem("隐藏").performClick()
        compose.waitForIdle()
        assertTrue(runtime.drawings.drawings.single().hidden)
        treeItem("锁定").performClick()
        compose.waitForIdle()
        assertTrue(runtime.drawings.drawings.single().locked)
        treeItem("删除").assertIsNotEnabled()
        treeItem("解锁").performClick()
        treeItem("删除").performClick()
        compose.waitForIdle()
        assertTrue(runtime.drawings.drawings.isEmpty())
    }

    @Test fun menuRemovesTheConfiguredIndicators() {
        render()
        runBlocking { assertTrue(runtime.indicators.apply(com.unitrading.app.IndicatorSettings(listOf(com.unitrading.chart.IndicatorConfig("ma", com.unitrading.chart.IndicatorKind.MA))))) }
        compose.waitForIdle()
        rightClick(549_000)
        compose.onNodeWithTag("plot-remove-indicators").performScrollTo().performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.isEmpty() }
        rightClick(549_000)
        compose.onNodeWithTag("plot-undo-indicators").assertDoesNotExist()
        compose.onNodeWithTag("plot-undo-drawings").assertDoesNotExist()
        compose.onNodeWithTag("plot-reset").performClick()
        compose.onNodeWithTag("drawing-undo").performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 1 }
        compose.onNodeWithTag("drawing-redo").performClick()
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.isEmpty() }
        val size = chart().fetchSemanticsNode().size
        chart().performMouseInput { click(Offset(size.width * .7f, size.height * .7f)) }
        chart().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.waitUntil(5_000) { runtime.indicators.settings.value.indicators.size == 1 }
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun menuRemovesAndRestoresDrawings() {
        render()
        compose.runOnIdle { runtime.drawings.addHorizontalLine(com.unitrading.chart.drawing.DrawingPoint(broker.time - 60_000, 548_500.0)) }
        rightClick(549_000)
        compose.onNodeWithTag("plot-remove-drawings").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, runtime.drawings.drawingCount())
        val size = chart().fetchSemanticsNode().size
        chart().performMouseInput { click(Offset(size.width * .7f, size.height * .7f)) }
        chart().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.waitUntil(5_000) { runtime.drawings.drawingCount() == 1 }
        assertEquals(1, runtime.drawings.drawingCount())
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun sellLimitShortcutRequiresKeyboardTradingAndUsesConfirmation() {
        render()
        val at = pointAt(551_000)
        chart().performMouseInput { moveTo(at); press(); release() }
        fun shortcut() = compose.onAllNodes(isRoot()).onLast().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.S) } }
        }
        shortcut()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        assertTrue(broker.placed.isEmpty())
        runBlocking { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true)) }
        compose.waitForIdle()
        chart().performMouseInput { moveTo(at); press(); release() }
        shortcut()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-action").assertExists()
        assertTrue(broker.placed.isEmpty(), "the shortcut still follows confirmation")
    }

    @Test fun theMenuOffersAMarketBuyAndSellWhereverItOpensAndTheyTakeNoPrice() {
        render()
        rightClick(549_000)
        item("BUY-MARKET").assertTextContains("买入市价 1 张")
        item("SELL-MARKET").assertTextContains("卖出市价 1 张").performClick()
        compose.waitForIdle()
        item("SELL-MARKET").assertDoesNotExist()
        compose.onNodeWithTag("order-side-SELL").assertIsSelected()
        compose.onNodeWithTag("order-kind-MARKET").assertIsSelected()
        assertTrue(broker.placed.isEmpty(), "Selecting a menu item only opens the order form")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("TESTZ6 · 卖出市价 · 1 张").assertExists()
        assertTrue(broker.placed.isEmpty(), "Nothing reaches the broker before the confirmation")
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.SELL, OrderKind.MARKET, 100), broker.placed.single(), "at the market, not at the clicked price")

        rightClick(551_000)
        item("BUY-MARKET").assertExists("over the market as well")
        item("SELL-MARKET").assertExists()
    }

    @Test fun aLongPressKeepsTheTouchMenu() {
        render(skipConfirmation = true)
        longPress(549_000)
        item("horizontal-line").assertTextContains("在 5490.00 画水平线")
        item("BUY-MARKET").assertExists()
        item("SELL-STOP").assertExists()
        item("BUY-LIMIT").assertTextContains("买入限价 1 张 @ 5490.00").performClick()
        compose.onNodeWithTag("order-entry").assertTextContains("5490.00")
        assertTrue(broker.placed.isEmpty(), "A long press cannot send an order")
        compose.onNodeWithTag("review-order").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.LIMIT, 100, 549_000), broker.placed.single())
        assertTrue(runtime.drawings.drawings.isEmpty(), "the press that opened the menu drew nothing")
    }

    @Test fun aTapOrASlideOpensNoMenu() {
        render()
        val at = pointAt(549_000)
        chart().performTouchInput { click(at) }
        compose.waitForIdle()
        item("horizontal-line").assertDoesNotExist()
        chart().performTouchInput { swipe(at, at + Offset(120f, 0f), durationMillis = 1_000) }
        compose.waitForIdle()
        item("horizontal-line").assertDoesNotExist()
    }

    @Test fun aLongPressOnADrawingOpensTheDrawingsMenu() {
        render()
        compose.runOnIdle { runtime.drawings.addHorizontalLine(com.unitrading.chart.drawing.DrawingPoint(broker.time - 60_000, 551_000.0)) }
        compose.waitForIdle()
        // Unselected, as a fresh press on a phone finds it.
        compose.runOnIdle { runtime.drawings.escape() }
        // No mouse event was sent: the finger is the only pointer.
        longPress(551_000)
        compose.onNodeWithTag("drawing-menu-settings").assertExists()
        item("horizontal-line").assertDoesNotExist()
    }

    @Test fun theTouchMenuDrawsAHorizontalLineAtThePriceUnderThePointer() {
        render()
        longPress(551_000)
        item("horizontal-line").assertTextContains("在 5510.00 画水平线").performClick()
        compose.waitForIdle()
        val line = runtime.drawings.drawings.single()
        assertEquals(DrawingTool.HORIZONTAL_LINE, line.tool)
        assertEquals(551_000.0, line.points.single().price)
        item("horizontal-line").assertDoesNotExist()
        compose.onNodeWithTag("drawing-bar").assertExists("the new line is selected, so its quick bar opens")
        assertTrue(broker.placed.isEmpty())

        // On the line the right click belongs to the drawing, as before.
        rightClick(551_000)
        compose.onNodeWithTag("drawing-menu-settings").assertExists()
        item("horizontal-line").assertDoesNotExist()
    }

    @Test fun desktopContextMenuHasDistinctIconsWithoutRestoringRemovedItems() {
        render()
        rightClick(549_000)
        listOf("reset", "BUY-MARKET", "SELL-MARKET", "BUY-LIMIT", "SELL-STOP", "open-order", "objects", "templates", "remove-indicators", "remove-drawings", "settings")
            .forEach { compose.onNodeWithTag("plot-icon-$it", useUnmergedTree = true).assertExists() }
        compose.onNodeWithTag("plot-copy-price").assertDoesNotExist()
        compose.onNodeWithTag("plot-paste-drawing").assertDoesNotExist()
        compose.onNodeWithTag("plot-time-lock").assertDoesNotExist()
        val file = File("../artifacts/desktop-order/context-menu.png").also { it.parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun desktopOrderReviewShowsLiveRefusalAndCancelNeverSends() {
        render()
        rightClick(549_000)
        item("BUY-LIMIT").performClick()
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("desktop-order-confirmation").assertIsDisplayed()
        compose.onNodeWithText("TESTZ6 · 买入限价 · 1 张").assertExists()
        compose.onNodeWithTag("confirm-action").assertIsEnabled()
        assertTrue(broker.placed.isEmpty())
        val folder = File("../artifacts/desktop-order").also { it.mkdirs() }
        compose.runOnIdle { runBlocking { runtime.theme.apply(com.unitrading.app.ThemeSettings(com.unitrading.app.ThemePreset.LIGHT)) } }
        compose.waitForIdle()
        ImageIO.write(compose.onNodeWithTag("desktop-order-confirmation").captureToImage().toAwtImage(), "png", File(folder, "confirm-valid.png"))
        compose.runOnIdle {
            broker.refusal = "券商拒绝：数量超过上限"
            broker.market.value = broker.market.value.copy(revision = broker.market.value.revision + 1)
        }
        compose.onNodeWithTag("confirm-error").assertTextEquals("券商拒绝：数量超过上限")
        compose.onNodeWithTag("confirm-action").assertIsNotEnabled()
        ImageIO.write(compose.onNodeWithTag("desktop-order-confirmation").captureToImage().toAwtImage(), "png", File(folder, "confirm-light.png"))
        compose.runOnIdle { runBlocking { runtime.theme.apply(com.unitrading.app.ThemeSettings(com.unitrading.app.ThemePreset.DARK)) } }
        compose.waitForIdle()
        ImageIO.write(compose.onNodeWithTag("desktop-order-confirmation").captureToImage().toAwtImage(), "png", File(folder, "confirm-dark.png"))
        compose.onNodeWithTag("cancel-order-review").performClick()
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
        assertTrue(broker.placed.isEmpty(), "Reviewing or cancelling an invalid order must never send it")
    }

    @Test fun underTheMarketItOffersABuyLimitAndASellStopAndTheOrderWaitsForTheConfirmation() {
        render()
        rightClick(549_000)
        val folder = File("../artifacts").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "chart-context-menu-chart.png"))
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "chart-context-menu.png"))
        item("SELL-LIMIT").assertDoesNotExist()
        item("BUY-STOP").assertDoesNotExist()
        item("SELL-STOP").assertTextContains("卖出突破 1 张 @ 5490.00")
        item("BUY-LIMIT").assertTextContains("买入限价 1 张 @ 5490.00").performClick()
        compose.waitForIdle()
        item("BUY-LIMIT").assertDoesNotExist()
        compose.onNodeWithTag("order-entry").assertTextContains("5490.00")
        assertTrue(broker.placed.isEmpty(), "A menu selection cannot send an order")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("确认 Tradovate Demo 下单").assertExists()
        compose.onNodeWithText("TESTZ6 · 买入限价 · 1 张").assertExists()
        assertTrue(broker.placed.isEmpty(), "Nothing reaches the broker before the confirmation")
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.LIMIT, 100, 549_000), broker.placed.single(), "the panel's quantity at the clicked price, without protection")
        assertTrue(runtime.drawings.drawings.isEmpty())
    }

    @Test fun overTheMarketItOffersASellLimitAndABuyStopWithThePanelQuantityAndSkipsTheConfirmationWhenToldTo() {
        render(skipConfirmation = true)
        compose.onNodeWithTag("order-quantity").performTextReplacement("3")
        compose.waitForIdle()
        rightClick(551_000)
        item("BUY-LIMIT").assertDoesNotExist()
        item("SELL-STOP").assertDoesNotExist()
        item("SELL-LIMIT").assertTextContains("卖出限价 3 张 @ 5510.00")
        item("BUY-STOP").assertTextContains("买入突破 3 张 @ 5510.00").performClick()
        compose.onNodeWithTag("order-entry").assertTextContains("5510.00")
        assertTrue(broker.placed.isEmpty(), "Skipping confirmation does not submit from the context menu")
        compose.onNodeWithTag("review-order").performClick()
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(OrderRequest("TESTZ6", Side.BUY, OrderKind.STOP, 300, 551_000), broker.placed.single())
        compose.onNodeWithTag("confirm-action").assertDoesNotExist()
    }

    @Test fun anOrderTheBrokerRefusesNeverGoesOutEvenWithConfirmationsSkipped() {
        render(skipConfirmation = true)
        broker.refusal = "Limit/Stop 入场方向或距离无效"
        rightClick(549_000)
        item("SELL-STOP").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun aQuantityThatDoesNotParseLeavesTheLabelsWithoutItAndStopsTheOrder() {
        render(skipConfirmation = true)
        compose.onNodeWithTag("order-quantity").performTextReplacement("")
        compose.waitForIdle()
        rightClick(549_000)
        item("BUY-LIMIT").assertTextContains("买入限价 @ 5490.00").performClick()
        compose.waitForIdle()
        assertEquals("", compose.onNodeWithTag("order-quantity").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun withoutAQuoteTheDesktopMenuKeepsChartActions() {
        render()
        compose.runOnIdle { broker.market.value = broker.market.value.copy(quotes = emptyMap(), revision = 1) }
        compose.waitForIdle()
        rightClick(549_000)
        item("horizontal-line").assertDoesNotExist()
        compose.onNodeWithTag("plot-reset").assertExists()
        for (name in listOf("BUY-LIMIT", "SELL-LIMIT", "BUY-STOP", "SELL-STOP")) item(name).assertDoesNotExist()
    }
}
