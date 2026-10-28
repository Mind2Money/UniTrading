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
}
