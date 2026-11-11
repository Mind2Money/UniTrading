package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class AccountBracketsTest {
    private val contract = Instrument("MES", "MES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000, minStopTicks = 1)
    private val quote = Quote(550_000, 550_025, 0)
    private val amounts = AccountBrackets(loss = 101_000_000, profit = 201_000_000)

    @Test fun twoContractBuyUsesAskAndRoundsRiskDownAndProfitUp() {
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 200)
        val order = amounts.prices(input, contract, "USD", quote)
        assertEquals(549_025L, order.stopLoss)
        assertEquals(552_050L, order.takeProfit)
        assertNull(amounts.refusal(order, contract, "USD", quote.ask))
        assertNotNull(amounts.refusal(order, contract, "USD", quote.ask + contract.tickSize))
    }

    @Test fun shortLimitUsesPendingEntryAndPreservesTheUnconfiguredLeg() {
        val input = OrderRequest("MES", Side.SELL, OrderKind.LIMIT, 200, entryPrice = 551_000, takeProfit = 548_000)
        val order = AccountBrackets(loss = 100_000_000).prices(input, contract, "USD", null)
        assertEquals(552_000L, order.stopLoss)
        assertEquals(548_000L, order.takeProfit)
    }

    @Test fun impossibleRiskDoesNotProduceAnUnprotectedOrder() {
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 200)
        assertFailsWith<IllegalArgumentException> { AccountBrackets(loss = 1_000_000).prices(input, contract, "USD", quote) }
        assertFailsWith<IllegalArgumentException> { amounts.prices(input, contract, "EUR", quote) }
        assertFailsWith<IllegalArgumentException> { amounts.prices(input, contract, "USD", null) }
        assertNotNull(amounts.refusal(input, contract, "USD", quote.ask))
    }
}
