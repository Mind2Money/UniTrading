package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class ContinuousOrderDraftTest {
    private val continuous = Instrument("@MES", "Micro", "连续合约", 6, 250_000, 1_250_000,
        minLotsHundredths = 100, lotStepHundredths = 100, quantityDecimals = 0, isContinuous = true, tradeSymbol = "MESZ6")

    @Test fun continuousDraftPinsTheDatedTargetAndPreservesEnteredPrices() {
        val request = OrderDraft(kind = OrderKind.LIMIT, quantity = "2", entry = "5490", stopLoss = "5480", takeProfit = "5510").request(continuous)!!
        assertEquals("MESZ6", request.symbol)
        assertEquals("@MES", request.sourceSymbol)
        assertEquals(200, request.lotsHundredths)
        assertEquals(5_490_000_000, request.entryPrice)
        assertEquals(5_480_000_000, request.stopLoss)
        assertEquals(5_510_000_000, request.takeProfit)
        assertEquals("MESH7", OrderDraft(quantity = "1").request(continuous.copy(tradeSymbol = "MESH7"))?.symbol)
    }

    @Test fun unresolvedContinuousDraftCannotBeSubmitted() {
        assertNull(OrderDraft(quantity = "1").request(continuous.copy(tradeSymbol = null)))
        val direct = OrderDraft(quantity = "1").request(continuous.copy(symbol = "MESZ6", isContinuous = false, tradeSymbol = null))!!
        assertEquals("MESZ6", direct.symbol)
        assertNull(direct.sourceSymbol)
    }
}
