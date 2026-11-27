package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyPendingOrderPlannerTest {
    private val spec = Instrument("MESZ6", "Micro ES", "Futures", 2, tickSize = 25, tickValueMicrosPerLot = 1,
        minLotsHundredths = 100, maxLotsHundredths = 10_000)
    private val quote = Quote(10_050, 10_100, 1000)
    private val leader = PendingOrder("src", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 300,
        entryPrice = 10_000, stopLoss = 9_900, takeProfit = 10_200), 0, version = 2)
    private val fixed = CopyFollower("follower", 1, 10, fixedContracts = 2)
    private fun prepare(order: PendingOrder = leader, follower: CopyFollower = fixed, sourceIds: Set<String> = setOf("src"),
        instrument: Instrument = spec, market: Quote? = quote, now: Long = 1000) =
        CopyPendingOrderPlanner.prepare("leader", sourceIds, order, follower, instrument, market, now)

    @Test fun preparesIndependentFixedOrderWithIdenticalPrices() {
        val planned = assertIs<CopyPendingPreparation.Ready>(prepare())
        assertEquals(200L, planned.request.lotsHundredths)
        assertEquals(10_000L, planned.request.entryPrice)
        assertEquals(9_900L, planned.request.stopLoss)
        assertEquals(10_200L, planned.request.takeProfit)
        assertTrue(planned.request.automated)
        assertEquals(2L, planned.sourceVersion)
    }

    @Test fun multiplierUsesSourceOrderQuantityAndEnforcesCap() {
        val ratio = fixed.copy(multiplier = 2, fixedContracts = null)
        assertEquals(600L, assertIs<CopyPendingPreparation.Ready>(prepare(follower = ratio)).request.lotsHundredths)
        assertIs<CopyPendingPreparation.Stop>(prepare(follower = ratio.copy(maxContracts = 5)))
    }

    @Test fun blocksUnknownPartiallyFilledOrUnprotectedSource() {
        assertIs<CopyPendingPreparation.Stop>(prepare(sourceIds = emptySet()))
        assertIs<CopyPendingPreparation.Stop>(prepare(order = leader.copy(filledLotsHundredths = 100)))
        assertIs<CopyPendingPreparation.Stop>(prepare(order = leader.copy(protectionEditable = false)))
    }

    @Test fun blocksCrossedOrStaleQuotesAndInvalidTickPrices() {
        assertIs<CopyPendingPreparation.Stop>(prepare(market = Quote(9_975, 10_000, 1000)))
        assertIs<CopyPendingPreparation.Stop>(prepare(now = 20_000))
        assertIs<CopyPendingPreparation.Stop>(prepare(order = leader.copy(request = leader.request.copy(entryPrice = 10_001))))
        assertIs<CopyPendingPreparation.Stop>(prepare(instrument = spec.copy(symbol = "MNQZ6")))
    }
}
