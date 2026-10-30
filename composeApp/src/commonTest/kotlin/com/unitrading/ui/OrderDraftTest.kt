package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class OrderDraftTest {
    private val fx = Instrument("EURUSD", "欧元/美元", "FX", 5, tickValueMicrosPerLot = 1_000_000)
    @Test fun invalidOptionalPriceDoesNotBecomeMissingProtection() {
        assertNull(OrderDraft(stopLoss = "wrong").request(fx))
        assertNull(OrderDraft(quantity = "0.001").request(fx))
        assertNull(OrderDraft(kind = OrderKind.LIMIT).request(fx))
        assertNull(OrderDraft(stopLoss = "1.123456").request(fx))
        assertNull(OrderDraft(quantity = "NaN").request(fx))
        assertNull(OrderDraft(takeProfit = "1e2").request(fx))
        assertNotNull(OrderDraft().request(fx))
    }
    @Test fun referenceProtectionRespectsDirection() {
        val quote = Quote(110_000, 110_020, 0)
        val buy = OrderDraft().withReferenceProtection(fx, quote).request(fx)!!
        assertTrue(buy.stopLoss!! < quote.bid)
        assertTrue(buy.takeProfit!! > quote.ask)
        val sell = OrderDraft(side = Side.SELL).withReferenceProtection(fx, quote).request(fx)!!
        assertTrue(sell.stopLoss!! > quote.ask)
        assertTrue(sell.takeProfit!! < quote.bid)
    }

    @Test fun quantityStepsRespectContractUnitsBoundsAndInvalidInput() {
        val future = fx.copy(quantityDecimals = 0, quantityUnit = "张", minLotsHundredths = 100, maxLotsHundredths = 300, lotStepHundredths = 100)
        assertEquals("2", OrderDraft(quantity = "1").steppedQuantity(future, true))
        assertEquals("1", OrderDraft(quantity = "2").steppedQuantity(future, false))
        assertNull(OrderDraft(quantity = "1").steppedQuantity(future, false))
        assertNull(OrderDraft(quantity = "3").steppedQuantity(future, true))
        assertNull(OrderDraft(quantity = "0.10").steppedQuantity(future, true))
        assertNull(OrderDraft(quantity = "").steppedQuantity(future, true))
        assertNull(OrderDraft(quantity = "NaN").steppedQuantity(future, true))
        assertNull(OrderDraft(quantity = "4").steppedQuantity(future, false))
        val stepped = fx.copy(minLotsHundredths = 5, lotStepHundredths = 5)
        assertEquals("0.15", OrderDraft(quantity = "0.10").steppedQuantity(stepped, true))
        assertEquals("0.05", OrderDraft(quantity = "0.10").steppedQuantity(stepped, false))
        assertNull(OrderDraft(quantity = "0.11").steppedQuantity(stepped, true))
        val huge = fx.copy(maxLotsHundredths = Long.MAX_VALUE)
        assertNull(OrderDraft(quantity = huge.formatQuantity(Long.MAX_VALUE)).steppedQuantity(huge, true))
    }

    @Test fun selectingTheCurrentSideOrKindPreservesProtectionAndEditedEntry() {
        val quote = Quote(110_000, 110_020, 0)
        val draft = OrderDraft(kind = OrderKind.LIMIT, entry = "1.09000", stopLoss = "1.08000", takeProfit = "1.12000")
        assertEquals(draft, draft.withKind(fx, quote, OrderKind.LIMIT))
        assertEquals(draft, draft.withSide(Side.BUY))
        assertEquals("", draft.withSide(Side.SELL).stopLoss)
        assertEquals("", draft.withKind(fx, quote, OrderKind.MARKET).entry)
        assertTrue(OrderDraft().withKind(fx, quote, OrderKind.LIMIT).request(fx)!!.entryPrice!! < quote.ask)
        assertTrue(OrderDraft().withKind(fx, quote, OrderKind.STOP).request(fx)!!.entryPrice!! > quote.ask)
    }
}
