package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TradeListUiTest {
    @get:Rule val compose = createComposeRule()
    private val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
    private val plugin = runtime.registry.plugins.first()
    private val spec = plugin.instruments.first()
    private val at = 1_798_589_000_000L
    private val position = Position("same-id", spec.symbol, Side.BUY, 10, 110000, at, marginMicros = 0)
    private val order = PendingOrder("same-id", OrderRequest(spec.symbol, Side.SELL, OrderKind.STOP, 10, 109000), at,
        role = OrderRole.STOP_LOSS, positionId = position.id)
    private val trade = ClosedTrade("same-id", "closed", spec.symbol, Side.BUY, 10, 110000, 111000, at - 60000, at, 10_000_000, "平仓")
    private val account = AccountSnapshot("test", 0, positions = listOf(position),
        orders = listOf(order, order.copy(id = "tp", role = OrderRole.TAKE_PROFIT, request = order.request.copy(kind = OrderKind.LIMIT, entryPrice = 112000))),
        trades = listOf(trade), events = listOf(ActivityEvent(at, "仅在设置页显示的活动")))
    private val entry = PendingOrder("entry", OrderRequest(spec.symbol, Side.BUY, OrderKind.LIMIT, 10, 110000, 109000, 112000), at, stopLossOrderId = "b-sl", takeProfitOrderId = "b-tp")
    private val waiting = "入场尚未成交，保护单等待激活"
    private val brackets = listOf(
        PendingOrder("b-tp", OrderRequest(spec.symbol, Side.SELL, OrderKind.LIMIT, 10, 112000), at, role = OrderRole.TAKE_PROFIT, editable = false, note = waiting),
        PendingOrder("b-sl", OrderRequest(spec.symbol, Side.SELL, OrderKind.STOP, 10, 109000), at, role = OrderRole.STOP_LOSS, editable = false, note = waiting),
    )
    private var selectedTrade: ClosedTrade? = null
    private var cancelled: PendingOrder? = null

    private fun renderTerminal(snapshot: AccountSnapshot = account, width: Int = 400, height: Int = 740, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f, fontScale)) {
                TradingTheme {
                    TradeTerminal(plugin, snapshot, plugin.market.value, null, true, {}, { selectedTrade = it }, {}, {}, { cancelled = it }, {}, false,
                        Modifier.requiredSize(width.dp, height.dp))
                }
            }
        }
    }

    private fun capture(name: String) {
        val target = File("../artifacts/trade-list/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", target)
    }

    @Test fun sectionsShareOneListAndIdsCanOverlapAcrossSections() {
        renderTerminal()
        val list = compose.onNodeWithTag("trade-terminal")
        compose.onNodeWithTag("terminal-tab-0").assertDoesNotExist()
        compose.onNodeWithText("仅在设置页显示的活动").assertDoesNotExist()
        compose.onNodeWithTag("position-same-id").assertIsDisplayed()
        val positions = compose.onNodeWithTag("terminal-section-positions").fetchSemanticsNode().boundsInRoot
        val orders = compose.onNodeWithTag("terminal-section-orders").fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag("terminal-section-history").fetchSemanticsNode().boundsInRoot
        assertTrue(positions.bottom <= orders.top && orders.bottom <= history.top)
        compose.onNode(hasText("SL 止损") and hasAnyAncestor(hasTestTag("order-role-same-id")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("TP 止盈") and hasAnyAncestor(hasTestTag("order-role-tp")), useUnmergedTree = true).assertIsDisplayed()
        capture("sections-mobile")
        list.performScrollToKey("trade-same-id")
        compose.onNodeWithTag("paired-trade-same-id").performClick()
        list.performScrollToNode(hasTestTag("locate-trade-same-id"))
        compose.onNodeWithTag("locate-trade-same-id").performClick()
        assertEquals(trade, selectedTrade)
    }

    @Test fun anEntryCarriesItsMatchedStopAndTarget() {
        renderTerminal(AccountSnapshot("entry", 0, orders = listOf(entry) + brackets))
        compose.onNodeWithText("挂单 1").assertIsDisplayed()
        for ((id, label) in listOf("b-sl" to "SL 止损", "b-tp" to "TP 止盈")) {
            compose.onNode(hasTestTag("pending-order-$id") and hasAnyAncestor(hasTestTag("pending-order-entry"))).assertIsDisplayed()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("order-role-$id"))).assertIsDisplayed()
            compose.onNodeWithTag("edit-order-$id").assertDoesNotExist()
        }
        val stop = assertNotNull(estimatedExitPnl(spec, Side.BUY, 10, 110000, 109000))
        compose.onNodeWithText(com.unitrading.chart.resultLabel(stop, !spec.estimatesIncludeFees)).assertIsDisplayed()
        compose.onAllNodesWithText(waiting).assertCountEquals(1)
        capture("entry-with-brackets")
        compose.onNodeWithTag("cancel-order-b-sl").performClick()
        assertEquals(brackets[1], cancelled)
    }

    @Test fun ordersGoToWhatTheyBelongToAndAnyOtherStaysARow() {
        val filled = brackets[1].copy(positionId = position.id)
        val orphan = PendingOrder("orphan", OrderRequest(spec.symbol, Side.SELL, OrderKind.STOP, 10, 108000), at, role = OrderRole.STOP_LOSS, editable = false)
        val unknown = orphan.copy(id = "unknown", role = OrderRole.UNRESOLVED)
        val groups = terminalOrders(AccountSnapshot("all", 0, positions = listOf(position), orders = listOf(entry, brackets[0], filled, orphan, unknown)))
        assertEquals(listOf(filled), groups.onPosition[position.id], "A bracket protecting a fill stays with the position")
        assertEquals(listOf(brackets[0]), groups.onEntry["entry"])
        assertEquals(listOf(entry, orphan, unknown), groups.rows)
        val sorted = terminalOrders(AccountSnapshot("sorted", 0, orders = listOf(entry) + brackets))
        assertEquals(listOf(brackets[1], brackets[0]), sorted.onEntry["entry"], "The stop reads first")
    }

    @Test fun longListReachesOrdersAndHistoryOnSmallScreenWithLargeText() {
        renderTerminal(account.copy(positions = (1..30).map { position.copy(id = "p-$it") }), width = 320, height = 640, fontScale = 1.3f)
        val list = compose.onNodeWithTag("trade-terminal")
        list.performScrollToKey("order-same-id")
        compose.onNodeWithTag("edit-order-same-id").assertIsDisplayed()
        capture("small-protection-order")
        list.performScrollToKey("trade-same-id")
        compose.onNodeWithTag("paired-trade-same-id").assertIsDisplayed().performClick()
        list.performScrollToNode(hasTestTag("locate-trade-same-id"))
        compose.onNodeWithTag("locate-trade-same-id").assertIsDisplayed()
    }

    @Test fun emptySectionsRemainVisibleWithoutTabs() {
        renderTerminal(AccountSnapshot("empty", 0))
        compose.onNodeWithText("持仓 0").assertIsDisplayed()
        compose.onNodeWithText("暂无持仓").assertIsDisplayed()
        compose.onNodeWithText("挂单 0").assertIsDisplayed()
        compose.onNodeWithText("暂无挂单").assertIsDisplayed()
        compose.onNodeWithText("历史 0").assertIsDisplayed()
        compose.onNodeWithText("暂无已配对交易").assertIsDisplayed()
    }

    @Test fun mobileSettingsKeepsAccountsAndActivityHasItsOwnPage() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 740.dp)) { TradingScreen(runtime) }
        } }
        compose.onNodeWithTag("nav-3").assertTextContains("设置").performClick()
        compose.onNodeWithTag("settings-account").assertIsDisplayed()
        compose.onNodeWithTag("open-activity").performScrollTo().performClick()
        compose.onNodeWithTag("activity-page").assertIsDisplayed()
        compose.onNodeWithTag("trade-terminal").assertDoesNotExist()
        capture("activity-mobile")
        compose.onNodeWithTag("activity-back").performClick()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
        compose.onNodeWithTag("settings-account").performScrollTo().performClick()
        compose.onNodeWithTag("broker-picker").assertExists()
        compose.onNodeWithText(plugin.accounts[1].name).performScrollTo().performClick()
        compose.onNodeWithTag("open-activity").performScrollTo().performClick()
        compose.onNodeWithText("${plugin.descriptor.name} · ${plugin.accounts[1].name}").assertIsDisplayed()
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("trade-terminal").assertIsDisplayed()
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
    }

    @Test fun desktopSettingsOpensActivityAndReturnsWithoutChangingTradingAccount() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("open-activity").performScrollTo().performClick()
        compose.onNodeWithTag("activity-page").assertIsDisplayed()
        compose.onNodeWithText("${plugin.descriptor.name} · ${plugin.accounts.first().name}").assertIsDisplayed()
        compose.onNodeWithTag("activity-back").performClick()
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("trading-chart").assertIsDisplayed()
    }

    @Test fun activityListsNewestFirst() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f, 1.3f)) {
            TradingTheme {
                ActivityLogPage("测试账户", listOf(ActivityEvent(at, "较早活动"), ActivityEvent(at + 1000, "最新活动")), {}, Modifier.requiredSize(320.dp, 640.dp))
            }
        } }
        val newest = compose.onNodeWithText("最新活动").fetchSemanticsNode().boundsInRoot
        val oldest = compose.onNodeWithText("较早活动").fetchSemanticsNode().boundsInRoot
        assertTrue(newest.bottom <= oldest.top)
    }

    @Test fun activityEmptyStateCanReturnToSettings() {
        var returned = false
        compose.setContent { TradingTheme {
            ActivityLogPage("测试账户", emptyList(), { returned = true }, Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("暂无活动").assertIsDisplayed()
        compose.onNodeWithTag("activity-back").performClick()
        assertTrue(returned)
    }
}
