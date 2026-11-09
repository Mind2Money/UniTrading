package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.unitrading.app.CachedCapacity
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.OrderCapacityCache
import com.unitrading.broker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CapacitySession : AccountSession {
    var answer = OrderCapacity(500)
    val lastKnown = mutableListOf<Long?>()
    override val account = AccountInfo("A1", "Test")
    override val state = MutableStateFlow(AccountSnapshot("A1", 0))
    override fun preview(request: OrderRequest) = OrderPreview(1)
    override suspend fun orderCapacity(request: OrderRequest, lastKnown: Long?): OrderCapacity { this.lastKnown.add(lastKnown); return answer }
    override fun previewOrderModification(orderId: String, entryPrice: Long) = OrderPreview(1)
    override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) = TradeResult(false, "unused")
    override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) = TradeResult(false, "unused")
    override suspend fun placeOrder(requestId: String, request: OrderRequest) = TradeResult(false, "unused")
    override suspend fun closePosition(requestId: String, positionId: String) = TradeResult(false, "unused")
    override suspend fun cancelOrder(requestId: String, orderId: String) = TradeResult(false, "unused")
}

class OrderCapacityUiTest {
    @get:Rule val compose = createComposeRule()
    private val mes = Instrument("MESZ6", "Micro", "Futures", 6, tickValueMicrosPerLot = 1250000, minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0, quantityUnit = "张")
    private val store = MemorySnapshotStore()
    private val cache = OrderCapacityCache(store)
    private val session = CapacitySession()
    private var draft by mutableStateOf(OrderDraft(quantity = "1"))
    private var account by mutableStateOf(AccountSnapshot("A1", 0))
    private var state = QuantityCapacityState()
    private var refresh: (() -> Unit)? = null

    private fun render() = compose.setContent {
        val (capacity, onRefresh) = rememberQuantityCapacity(cache, "broker", session, mes, draft, account, ready = true, localDemo = false)
        state = capacity
        refresh = onRefresh
    }
    private fun awaitChecks(count: Int) {
        compose.waitUntil(5_000) { session.lastKnown.size >= count }
        compose.waitForIdle()
    }

    @Test fun theLastLimitStaysUntilTheServerAnswersDifferently() {
        render()
        compose.runOnIdle { assertEquals(QuantityCapacityState(message = "正在确认可下单数量", loading = true), state) }
        awaitChecks(1)
        compose.waitUntil(5_000) { state.maximum == 500L }
        assertFalse(state.loading)
        session.answer = OrderCapacity(message = "账户状态已变化，请刷新数量上限")
        compose.runOnIdle { refresh!!() }
        awaitChecks(2)
        compose.waitUntil(5_000) { !state.loading }
        assertEquals(500, state.maximum, "A failed check is not an answer")
        assertNull(state.message)
        assertEquals(listOf(null, 500L), session.lastKnown)
        session.answer = OrderCapacity(300)
        compose.runOnIdle { refresh!!() }
        awaitChecks(3)
        compose.waitUntil(5_000) { state.maximum == 300L }
        assertEquals(CachedCapacity(300), OrderCapacityCache(store).entries.value.values.single(), "The next launch starts from it")
    }

    @Test fun aReturningOrderContextShowsItsLimitBeforeTheCheckAnswers() {
        render()
        awaitChecks(1)
        compose.waitUntil(5_000) { state.maximum == 500L }
        session.answer = OrderCapacity(800)
        compose.runOnIdle { draft = draft.copy(side = Side.SELL) }
        compose.runOnIdle { assertNull(state.maximum, "Each side has its own limit"); assertTrue(state.loading) }
        awaitChecks(2)
        compose.waitUntil(5_000) { state.maximum == 800L }
        session.answer = OrderCapacity(500)
        compose.runOnIdle { draft = draft.copy(side = Side.BUY) }
        compose.runOnIdle { assertEquals(QuantityCapacityState(500), state, "Shown at once, not as loading") }
        awaitChecks(3)
        assertEquals(500L, session.lastKnown.last())
    }

    @Test fun onlyPositionsAndOrdersAskTheServerAgainAndTheLimitNeverBlanks() {
        render()
        awaitChecks(1)
        compose.waitUntil(5_000) { state.maximum == 500L }
        compose.runOnIdle { account = account.copy(balanceMicros = 123_000_000, marginMicros = 5, equityMicros = 9) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(1, session.lastKnown.size, "A balance update alone does not query")
        assertEquals(QuantityCapacityState(500), state)
        compose.runOnIdle { account = account.copy(positions = listOf(Position("p", "MESZ6", Side.BUY, 100, 5_000_000_000, 0, marginMicros = 0))) }
        compose.runOnIdle { assertEquals(QuantityCapacityState(500), state, "Still shown while the check runs") }
        awaitChecks(2)
        assertEquals(500L, session.lastKnown.last())
    }

    @Test fun aCorruptCacheFileIsIgnoredAndAnUnchangedAnswerWritesNothing() = kotlinx.coroutines.test.runTest {
        var writes = 0
        val counted = object : com.unitrading.app.SnapshotStore {
            override fun read(key: String) = "{bad"
            override fun write(key: String, value: String) { writes++ }
        }
        val loaded = OrderCapacityCache(counted)
        assertTrue(loaded.entries.value.isEmpty())
        loaded.put("k", CachedCapacity(100))
        loaded.put("k", CachedCapacity(100))
        assertEquals(1, writes)
    }

    @Test fun sliderUsesTheAccountMaximumAndDisappearsWhenItCannotBeConfirmed() {
        val instrument = Instrument("MES", "Micro", "Futures", 6, tickValueMicrosPerLot = 1250000,
            minLotsHundredths = 100, maxLotsHundredths = Int.MAX_VALUE.toLong() * 100, lotStepHundredths = 100,
            quantityDecimals = 0, quantityUnit = "张")
        var capacity by mutableStateOf(QuantityCapacityState(500))
        var draft by mutableStateOf(OrderDraft(quantity = "1"))
        compose.setContent {
            TradingTheme {
                Box(Modifier.requiredSize(400.dp, 180.dp)) {
                    OrderQuantitySlider(instrument, draft, { draft = it }, true, capacity, {})
                }
            }
        }
        compose.onNodeWithTag("order-quantity-slider").assertIsEnabled().performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .8f, centerY))
        }
        compose.runOnIdle { assertTrue(draft.quantity.toInt() in 2..5) }
        compose.onNodeWithTag("order-quantity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals("5", draft.quantity); capacity = QuantityCapacityState(200) }
        compose.onNodeWithText("当前最多 2 张").assertExists()
        compose.runOnIdle { assertEquals("5", draft.quantity) } // A lower cap must not rewrite a user's draft.
        // The over-cap draft already renders at fraction 1; move away before choosing the new maximum.
        compose.onNodeWithTag("order-quantity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("order-quantity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals("2", draft.quantity); capacity = QuantityCapacityState(message = "可下单数量未知") }
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
        compose.onNodeWithText("可下单数量未知").assertExists()
        compose.runOnIdle { capacity = QuantityCapacityState(0) }
        compose.onNodeWithTag("order-quantity-slider").assertDoesNotExist()
        compose.onNodeWithText("当前最多 0 张").assertExists()
    }
}
