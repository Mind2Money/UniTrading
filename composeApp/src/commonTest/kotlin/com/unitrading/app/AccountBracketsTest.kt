package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class AccountBracketsTest {
    private val contract = Instrument("MES", "MES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000, minStopTicks = 1)
    /** Whole contracts and no ceiling of its own, as Tradovate and Rithmic describe their futures. */
    private val futures = contract.copy(minLotsHundredths = 100, lotStepHundredths = 100, maxLotsHundredths = Int.MAX_VALUE.toLong() * 100)
    private val quote = Quote(550_000, 550_025, 0)
    private val amounts = AccountBrackets(loss = 101_000_000, profit = 201_000_000)
    private fun failure(block: () -> Unit) = assertFailsWith<IllegalArgumentException> { block() }.message

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
        val risk = AccountBrackets(sizingRisk = 99_000_000, sizingMaxLots = 1_000)
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 999, stopLoss = 549_525, takeProfit = 552_000)
        val sized = risk.prices(input, futures, "USD", quote)
        assertEquals(input.copy(lotsHundredths = 300), sized)
        assertNull(risk.sizingLimit(input, futures, quote), "the amount decided")
        assertNull(risk.refusal(sized, futures, "USD", quote.ask))
        assertNotNull(risk.refusal(sized.copy(lotsHundredths = 400), futures, "USD", quote.ask))
        val sell = input.copy(side = Side.SELL, kind = OrderKind.LIMIT, entryPrice = 550_000, stopLoss = 550_500)
        assertEquals(300L, risk.prices(sell, futures, "USD", null).lotsHundredths)
    }

    @Test fun sizingRejectsInvalidStopsOrAnInsufficientBudget() {
        val risk = AccountBrackets(sizingRisk = 1_000_000, sizingMaxLots = 1_000)
        val input = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 100)
        assertFalse(risk.copy(loss = 1_000_000).valid())
        for (stop in listOf(null, 549_501L, 550_050L, 549_525L)) {
            assertFailsWith<IllegalArgumentException> { risk.prices(input.copy(stopLoss = stop), futures, "USD", quote) }
        }
        assertEquals("风险金额不足以下最小手数，请调整风险金额或止损价", failure { risk.prices(input.copy(stopLoss = 549_525), futures, "USD", quote) })
    }

    @Test fun aSizedQuantityStaysUnderEveryCeilingAndTheFormIsToldWhichOneCutIt() {
        val risk = AccountBrackets(sizingRisk = 300_000_000, sizingMaxLots = 500)
        // One tick of stop costs 1.25 a contract on MES: 300 would buy 240 contracts, which no check stopped.
        val tight = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 100, stopLoss = 550_000)
        fun lots(lotRoom: Long? = null, capacity: Long? = null, spec: Instrument = futures) =
            risk.prices(tight, spec, "USD", quote, lotRoom, capacity).lotsHundredths to risk.sizingLimit(tight, spec, quote, lotRoom, capacity)
        assertEquals(24_000L, risk.copy(sizingMaxLots = 999_999_999).prices(tight, futures, "USD", quote).lotsHundredths)
        assertEquals(500L to "单笔最大数量", lots())
        assertEquals(200L to "子账户最大手数", lots(lotRoom = 250), "what the lot cap leaves, on the contract grid")
        assertEquals(300L to "可下单上限", lots(capacity = 300))
        assertEquals(200L to "子账户最大手数", lots(lotRoom = 200, capacity = 300), "the lowest of them")
        assertEquals(400L to "合约最大手数", lots(spec = futures.copy(maxLotsHundredths = 400)))
        // With the stop 80 ticks away the amount buys three contracts, and a ceiling of three cuts nothing.
        val wide = tight.copy(stopLoss = 548_025)
        assertEquals(300L, risk.prices(wide, futures, "USD", quote, 300, 300).lotsHundredths)
        assertNull(risk.sizingLimit(wide, futures, quote, 300, 300))
        // A ceiling under one contract leaves nothing to send, and the message names that ceiling, not the amount.
        assertEquals("受子账户最大手数限制，可下数量不足最小手数", failure { risk.prices(tight, futures, "USD", quote, lotRoom = 0) })
        assertEquals("受可下单上限限制，可下数量不足最小手数", failure { risk.prices(tight, futures, "USD", quote, capacity = 50) })
        assertNull(risk.sizingLimit(tight, futures, quote, lotRoom = 0), "nothing was sized")
        // The check at submit holds an order to the amount and to the ceiling of the rule.
        assertNull(risk.refusal(tight.copy(lotsHundredths = 500), futures, "USD", quote.ask))
        assertNotNull(risk.refusal(tight.copy(lotsHundredths = 600), futures, "USD", quote.ask))
    }

    @Test fun aSizingRuleWithoutItsCeilingIsInvalidAndSizesNothing() {
        val bare = AccountBrackets(sizingRisk = 300_000_000)
        assertFalse(bare.valid())
        assertTrue(bare.copy(sizingMaxLots = 500).valid())
        assertFalse(bare.copy(sizingMaxLots = 0).valid())
        assertFalse(AccountBrackets(loss = 100_000_000, sizingMaxLots = 500).valid(), "the ceiling belongs to the sizing")
        assertTrue(AccountBrackets(loss = 100_000_000).valid())
        val order = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 100, stopLoss = 549_025)
        val missing = "请先在设置中填写风险计算手数的单笔最大数量"
        assertEquals(missing, failure { bare.prices(order, futures, "USD", quote) })
        assertEquals(missing, bare.refusal(order, futures, "USD", quote.ask))
        assertNull(bare.sizingLimit(order, futures, quote))
    }

    @Test fun theRuleLeavesAnOrderThatOnlyReducesThePositionAsItIsAndSizesTheOthersUnderTheLotCap() {
        fun held(side: Side, lots: Long) = AccountSnapshot("a", 0, positions = listOf(Position("p", "MES", side, lots, 550_000, 0, marginMicros = 0)))
        val long = held(Side.BUY, 300)
        val cash = AccountRiskRule(brackets = AccountBrackets(loss = 100_000_000, profit = 200_000_000))
        val scaleOut = OrderRequest("MES", Side.SELL, OrderKind.MARKET, 100)
        assertFalse(cash.bracketsApply(long, false, scaleOut))
        assertSame(scaleOut, cash.bracketed(scaleOut, futures, "USD", quote, long, hedging = false))
        // Adding, reversing, an order in a flat account and every order of a hedging account are entries.
        val add = OrderRequest("MES", Side.BUY, OrderKind.MARKET, 100)
        assertEquals(add.copy(stopLoss = 548_025, takeProfit = 554_025), cash.bracketed(add, futures, "USD", quote, long, hedging = false))
        assertTrue(cash.bracketsApply(long, false, scaleOut.copy(lotsHundredths = 400)))
        assertTrue(cash.bracketsApply(AccountSnapshot("a", 0), false, scaleOut))
        assertEquals(scaleOut.copy(stopLoss = 552_000, takeProfit = 546_000), cash.bracketed(scaleOut, futures, "USD", quote, long, hedging = true))
        assertFalse(AccountRiskRule().bracketsApply(long, false, add), "no brackets, nothing to apply")

        // Long three under a cap of five leaves two contracts; the ceiling of the sizing would allow four.
        val sizing = AccountRiskRule(orderLimits = AccountOrderLimits(500), brackets = AccountBrackets(sizingRisk = 300_000_000, sizingMaxLots = 400))
        val tight = add.copy(stopLoss = 550_000)
        assertEquals(200L, sizing.lotRoom(long, false, tight))
        assertEquals(tight.copy(lotsHundredths = 200), sizing.bracketed(tight, futures, "USD", quote, long, hedging = false))
        assertEquals(tight.copy(lotsHundredths = 100), sizing.bracketed(tight, futures, "USD", quote, long, hedging = false, capacity = 100))
        assertEquals(tight.copy(lotsHundredths = 400), sizing.copy(orderLimits = AccountOrderLimits()).bracketed(tight, futures, "USD", quote, long, false))
        assertNull(sizing.copy(orderLimits = AccountOrderLimits()).lotRoom(long, false, tight))
        // A reduction keeps the quantity it was given, also where the cap is already exceeded.
        assertSame(scaleOut, sizing.copy(orderLimits = AccountOrderLimits(100)).bracketed(scaleOut, futures, "USD", quote, long, hedging = false))
        assertEquals("受子账户最大手数限制，可下数量不足最小手数",
            failure { sizing.copy(orderLimits = AccountOrderLimits(300)).bracketed(tight, futures, "USD", quote, long, hedging = false) })
    }

    @Test fun anEditMayTightenButNotLoosenPastTheAmountsOrPastWhereItWas() {
        val rule = AccountBrackets(loss = 50_000_000, profit = 100_000_000)
        // Short one MES from 5510 with the stop 40 ticks above and the target 80 below: 50 to lose, 100 to make.
        val order = OrderRequest("MES", Side.SELL, OrderKind.LIMIT, 100, entryPrice = 551_000, stopLoss = 552_000, takeProfit = 549_000)
        fun edit(after: OrderRequest, before: OrderRequest = order) = rule.editRefusal(before, after, futures, "USD")
        val loss = "修改后的预估亏损超出子账户风险设置；可以收紧止损或减少数量"
        val profit = "修改后的预估利润低于子账户设置；止盈只能设得更远"
        assertNull(edit(order))
        assertNull(edit(order.copy(stopLoss = 551_500)), "a tighter stop")
        assertNull(edit(order.copy(takeProfit = 548_000)), "a further target")
        assertNull(edit(order.copy(entryPrice = 551_500)), "nearer the stop, further from the target")
        // The entry alone moves on Tradovate, and its stop stays where it was: 80 ticks away at 5500.
        assertEquals(loss, edit(order.copy(entryPrice = 550_000)))
        assertEquals(loss, edit(order.copy(stopLoss = 552_025)))
        assertEquals(loss, edit(order.copy(lotsHundredths = 200)))
        assertEquals("该子账户设有风险金额，止损不能移除", edit(order.copy(stopLoss = null)))
        assertEquals(profit, edit(order.copy(takeProfit = 549_025)))
        assertEquals("该子账户设有利润金额，止盈不能移除", edit(order.copy(takeProfit = null)))
        // A stop pulled in may go back out to the amount, and no further.
        val tightened = order.copy(stopLoss = 551_500)
        assertNull(edit(order, tightened))
        assertEquals(loss, edit(order.copy(stopLoss = 552_025), tightened))
        // An order from before the rule loses three times the amount: it may come closer, and not go further than it is.
        val older = order.copy(stopLoss = 554_000)
        assertNull(edit(older.copy(stopLoss = 553_000), older))
        assertNull(edit(older.copy(entryPrice = 551_025), older))
        assertEquals(loss, edit(older.copy(stopLoss = 554_025), older))
        // No stop or target is one without a bound. Moving such an order changes nothing, any first stop tightens it, and a
        // first target has to make the amount: 80 ticks under the short do, 79 do not.
        val bare = order.copy(stopLoss = null, takeProfit = null)
        assertNull(edit(bare.copy(entryPrice = 550_000, lotsHundredths = 500), bare))
        assertNull(edit(bare.copy(stopLoss = 560_000, takeProfit = 549_000), bare))
        assertEquals(profit, edit(bare.copy(takeProfit = 549_025), bare))
        assertNull(rule.editRefusal(bare, bare.copy(takeProfit = 550_975), futures, "EUR"), "nothing to hold it to")
        // Under sizing the amount is the risk, and no target is asked for.
        val sizing = AccountBrackets(sizingRisk = 50_000_000, sizingMaxLots = 1_000)
        assertEquals(loss, sizing.editRefusal(order, order.copy(lotsHundredths = 200), futures, "USD"))
        assertNull(sizing.editRefusal(order, order.copy(takeProfit = null), futures, "USD"))
        // An amount in another currency cannot be compared: there only "no worse than it was" is left.
        assertNull(rule.editRefusal(order, tightened, futures, "EUR"))
        assertEquals(loss, rule.editRefusal(tightened, order, futures, "EUR"))
        assertEquals("无法复核子账户止损止盈", edit(order.copy(stopLoss = 0)))
        assertNull(AccountBrackets().editRefusal(order, bare, futures, "USD"))
    }
}
