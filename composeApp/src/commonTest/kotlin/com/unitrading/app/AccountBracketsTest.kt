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

    @Test fun sizingRoundsDownToTheQuantityGridAndPreservesProtectionPrices() {
        val futures = contract.copy(minLotsHundredths = 100, lotStepHundredths = 100)
        val risk = AccountBrackets(sizingRisk = 99_000_000)
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 999, stopLoss = 549_525, takeProfit = 552_000)
        val sized = risk.prices(input, futures, "USD", quote)
        assertEquals(input.copy(lotsHundredths = 300), sized)
        assertNull(risk.refusal(sized, futures, "USD", quote.ask))
        assertNotNull(risk.refusal(sized.copy(lotsHundredths = 400), futures, "USD", quote.ask))
        val sell = input.copy(side = Side.SELL, kind = OrderKind.LIMIT, entryPrice = 550_000, stopLoss = 550_500)
        assertEquals(300L, risk.prices(sell, futures, "USD", null).lotsHundredths)
    }

    @Test fun sizingRejectsInvalidStopsOrAnInsufficientBudget() {
        val futures = contract.copy(minLotsHundredths = 100, lotStepHundredths = 100)
        val risk = AccountBrackets(sizingRisk = 1_000_000)
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 100)
        assertFalse(risk.copy(loss = 1_000_000).valid())
        for (stop in listOf(null, 549_501L, 550_050L, 549_525L)) {
            assertFailsWith<IllegalArgumentException> { risk.prices(input.copy(stopLoss = stop), futures, "USD", quote) }
        }
    }
}
