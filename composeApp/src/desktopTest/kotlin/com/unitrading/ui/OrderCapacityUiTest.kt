package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.unitrading.broker.Instrument
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class OrderCapacityUiTest {
    @get:Rule val compose = createComposeRule()

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
