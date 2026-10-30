package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unitrading.app.ChartPreferencesController
import com.unitrading.app.IndicatorSettings
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import com.unitrading.broker.OrderKind
import com.unitrading.broker.Side
import com.unitrading.broker.Timeframe
import com.unitrading.chart.IndicatorConfig
import com.unitrading.chart.IndicatorKind
import com.unitrading.chart.PriceScale
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Phone layouts and the Material order sheet, using only synthetic broker data. */
@OptIn(ExperimentalTestApi::class)
class MobileLayoutTest {
    private lateinit var compose: ComposeUiTest

    private fun phoneTest(width: Int = 300, height: Int = 600, block: () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            compose = this
            block()
        }
    private val store = MemorySnapshotStore()
    private val runtime = TradingRuntime(store, includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val session = plugin.openSession(plugin.accounts.first().id)

    private fun render(width: Dp = 400.dp, height: Dp = 800.dp, fontScale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f, fontScale)) {
            Box(Modifier.requiredSize(width, height).testTag("test-host")) { TradingScreen(runtime) }
        } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val target = File("../artifacts/$name.png"); target.parentFile.mkdirs()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", target)
    }
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test fun portraitChartFillsTheScreenAndTradingWaitsInTheCollapsedPanel() = phoneTest {
        render()
        val host = height("test-host")
        assertTrue(height("trading-chart") >= host * .66f, "chart ${height("trading-chart")} px of $host px")
        compose.onNodeWithTag("chart-toolbar").assertExists()
        compose.onNodeWithText("UniTrading").assertDoesNotExist()
        compose.onNodeWithText("余额 USD").assertDoesNotExist()
        compose.onNodeWithTag("legend-sell").assertDoesNotExist()
        compose.onNodeWithTag("order-quantity").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        capture("mobile-chart-first")

        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(hasText(Side.SELL.label()) and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        assertTrue(height("trading-chart") >= host * .66f, "The sheet must overlay the chart without shrinking it")
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-quantity").assertIsDisplayed()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("order-quantity-slider").assertIsDisplayed()
        capture("mobile-order-panel")

        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        compose.onNodeWithTag("chart-readout").assertExists()
        compose.onNodeWithTag("panel-positions").performClick()
        compose.onNodeWithTag("nav-2").assertIsSelected()
        compose.onNodeWithText("余额 USD").assertExists()
        compose.onNodeWithTag("trade-terminal").assertExists()
    }

    @Test fun draggingTheDraftEntryBringsBackTheLegendWithItsEstimate() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        compose.onNodeWithTag("order-entry").performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("chart-legend").assertDoesNotExist()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("legend-toggle").performClick()
        val position = session.state.value.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit, entry) +
            session.state.value.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice }
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, bounds.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(bounds.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)) }
        compose.onNodeWithTag("chart-readout").assertTextContains("入场预览", substring = true)
        chart.performTouchInput { up() }
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
    }

    @Test fun legendFoldWithItsIndicatorListIsRememberedAcrossTabsAndRestarts() = phoneTest {
        runBlocking { assertTrue(runtime.indicators.apply(IndicatorSettings(listOf(IndicatorConfig("m", IndicatorKind.MA))))) }
        render()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("chart-readout").assertDoesNotExist()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) { runtime.chartPreferences.preferences.value.legendCollapsed == true }
        // Leaving the chart tab drops the chart from composition; it comes back folded.
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("indicator-readout").assertDoesNotExist()
        assertEquals(true, ChartPreferencesController(store).preferences.value.legendCollapsed, "a restart reads the saved fold")
        compose.onNodeWithTag("legend-toggle").performClick()
        compose.onNodeWithTag("indicator-readout").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) { ChartPreferencesController(store).preferences.value.legendCollapsed == false }
    }

    @Test fun nativeSwipeAndDismissActionPreserveTheDraft() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        compose.onNodeWithTag("order-panel").performTouchInput {
            swipe(Offset(centerX, 8f), Offset(centerX, height - 1f), durationMillis = 300)
        }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("minimized-order").assertExists()
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
    }

    @Test fun scrimDismissMinimizesWithoutDiscardingTheDraft() = phoneTest {
        render()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        // Tap the native scrim above the sheet; Android Back/IME is covered by AppearanceUiTest.
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("minimized-order").assertExists()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
    }

    @Test fun confirmedOrderClosesThePanel() = phoneTest {
        render()
        val before = session.state.value.positions.size
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithText("确认模拟下单").assertExists()
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.positions.size == before + 1 }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("order-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mobile-navigation").assertExists()
    }

    @Test fun minimizedDraftKeepsChartEditsAndCloseStartsAFreshOrder() = phoneTest {
        render()
        val collapsedChartHeight = height("trading-chart")
        val instrument = plugin.instruments.first()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.23")
        val q = plugin.market.value.quotes.getValue(instrument.symbol)
        val entry = q.ask - 500
        compose.onNodeWithTag("order-entry").performScrollTo().performTextReplacement(instrument.formatPrice(entry))
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("minimized-order").assertExists()
        compose.onNodeWithTag("mobile-navigation").assertExists()
        assertEquals(collapsedChartHeight, height("trading-chart"), "Minimizing restores the same chart area as the closed panel")
        capture("mobile-order-minimized")
        // Turning protection off before the drag gives a deterministic price scale.
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("minimize-order").performClick()
        val position = before.positions.firstOrNull { it.symbol == instrument.symbol }
        val extras = listOfNotNull(q.bid, q.ask, position?.entryPrice, position?.stopLoss, position?.takeProfit, entry) +
            before.orders.filter { it.request.symbol == instrument.symbol }.mapNotNull { it.request.entryPrice }
        val chart = compose.onNodeWithTag("trading-chart")
        val bounds = chart.fetchSemanticsNode().size
        val scale = PriceScale.from(plugin.history(instrument.symbol, Timeframe.M5).takeLast(80), extras, 18f * .75f, bounds.height - 30f * .75f)
        val y = scale.y(entry)
        chart.performTouchInput { down(Offset(bounds.width * .55f, y)); moveBy(Offset(0f, 6f)); moveBy(Offset(0f, 18f)); up() }
        compose.onNodeWithTag("order-panel").assertDoesNotExist()
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("restore-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.23")
        compose.onNodeWithTag("order-entry").assertTextContains(instrument.formatPrice(scale.price(y + 24f, instrument.tickSize)))
        compose.onNodeWithTag("minimize-order").performClick()
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("minimized-order").assertDoesNotExist()
        compose.onNodeWithTag("trade-bar").assertExists()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("order-kind-MARKET").assertIsSelected()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().assertIsOff()
        compose.onNodeWithTag("order-tp-enabled").assertIsOff()
        assertEquals(before, session.state.value, "Draft lifecycle and chart edits must never send orders")
    }

    @Test fun openingAndDismissingTheSheetDoesNotResizeTheChart() = phoneTest {
        render()
        val before = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("open-order").performClick()
        assertEquals(before, compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("minimize-order").performClick()
        assertEquals(before, compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun toolbarRowKeepsIntervalsIndicatorsAndLayers() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("interval-current").assertTextContains("5分钟").performClick()
        compose.onNodeWithTag("interval-option-240").performClick()
        compose.onNodeWithText("${instrument.name} · 4小时 · ${instrument.category}").assertExists()
        compose.onNodeWithTag("chart-indicators").performClick()
        compose.onNodeWithTag("indicator-settings").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("chart-layers").performClick()
        compose.onNodeWithTag("layer-3").performClick()
        compose.onNodeWithTag("ohlc-table").assertExists()
        compose.onNodeWithTag("chart-toolbar").assertExists()
    }

    @Test fun landscapeUsesTheSameModalSheetAndKeepsReviewVisible() = phoneTest(645, 300) {
        render(860.dp, 400.dp)
        val before = compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("open-order").performClick()
        assertEquals(before, compose.onNodeWithTag("trading-chart").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("order-panel").assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertExists()
        compose.onNodeWithTag("order-quantity").assertExists()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        capture("mobile-landscape-order")
        compose.onNodeWithTag("close-order").performClick()
        compose.onNodeWithTag("trade-bar").assertExists()
    }

    @Test fun quantityButtonsAndSideQuotesOnlyEditTheDraft() = phoneTest {
        render()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("quantity-increase").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.11")
        compose.onNodeWithTag("quantity-decrease").performClick()
        compose.onNodeWithTag("order-quantity").assertTextContains("0.10")
        compose.onNodeWithTag("reference-protection").performScrollTo().performClick()
        val sl = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        compose.onNodeWithTag("order-side-BUY").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-kind-MARKET").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(sl)
        compose.onNodeWithTag("order-side-SELL").performClick()
        compose.onNodeWithTag("order-sl-enabled").assertIsOn()
        val sellStop = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertNotEquals(sl, sellStop)
        compose.onNodeWithTag("order-side-SELL").assertIsSelected()
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.01")
        compose.onNodeWithTag("quantity-decrease").assertIsNotEnabled()
        compose.onNodeWithTag("order-quantity").performTextReplacement("wrong")
        compose.onNodeWithTag("quantity-increase").assertIsNotEnabled()
        compose.onNodeWithTag("review-order").assertIsNotEnabled()
        compose.onNodeWithTag("order-error").assertIsDisplayed()
        assertEquals(before, session.state.value, "Opening, choosing a side and editing quantities must not submit an order")
    }

    @Test fun smallPhoneKeepsReviewFixedWhileSheetDetailsScroll() = phoneTest(240, 480) {
        render(320.dp, 640.dp, fontScale = 1.3f)
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-entry").performScrollTo().assertIsDisplayed()
        val footer = compose.onNodeWithTag("review-order").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("order-estimates-toggle").performScrollTo().performClick()
        compose.onNodeWithText("双边手续费").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review-order").assertIsDisplayed()
        assertEquals(footer, compose.onNodeWithTag("review-order").fetchSemanticsNode().boundsInRoot)
        capture("mobile-small-order-details")
        compose.onNodeWithTag("review-order").assertIsDisplayed()
    }

    @Test fun protectionTogglesKeepOnlyEnabledProtectionInTheConfirmedOrder() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-kind-LIMIT").performClick()
        compose.onNodeWithTag("order-sl-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-tp-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("order-sl-enabled").assertIsOn()
        val rememberedStop = compose.onNodeWithTag("order-sl").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertDoesNotExist()
        compose.onNodeWithTag("order-sl-enabled").performClick()
        compose.onNodeWithTag("order-sl").assertTextContains(rememberedStop)
        capture("mobile-protection-controls")
        compose.onNodeWithTag("order-sl-enabled").performClick()
        val target = compose.onNodeWithTag("order-tp").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        val before = session.state.value.orders.size
        compose.onNodeWithTag("review-order").performClick()
        compose.onNodeWithTag("confirm-action").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.orders.size == before + 1 }
        val sent = session.state.value.orders.last().request
        assertNull(sent.stopLoss)
        assertEquals(instrument.parsePrice(target), sent.takeProfit)
    }

    @Test fun quantitySliderDragsOnContractStepsAndTracksManualQuantity() = phoneTest {
        render()
        val instrument = plugin.instruments.first()
        val before = session.state.value
        compose.onNodeWithTag("open-order").performClick()
        compose.onNodeWithTag("order-quantity-slider").performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .65f, centerY))
        }
        val text = compose.onNodeWithTag("order-quantity").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        val quantity = instrument.parseQuantity(text)!!
        assertNotEquals(10L, quantity)
        assertTrue(quantity in instrument.minLotsHundredths..instrument.maxLotsHundredths)
        assertEquals(0L, quantity % instrument.lotStepHundredths)
        compose.onNodeWithTag("order-quantity").performTextReplacement("0.10")
        val progress = compose.onNodeWithTag("order-quantity-slider").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(QuantitySliderScale.from(instrument)!!.fraction(10), progress, .00001f)
        assertEquals(before, session.state.value)
    }
}
