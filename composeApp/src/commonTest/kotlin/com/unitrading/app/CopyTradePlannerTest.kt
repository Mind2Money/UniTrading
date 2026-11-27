package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyTradePlannerTest {
    private val settings = CopyPlanSettings("leader", listOf(CopyFollower("one", 1, 5), CopyFollower("two", 2, 5)), "MESZ6")
    private fun position(side: Side, contracts: Long, symbol: String = "MESZ6", stop: Long? = null) =
        Position("p", symbol, side, contracts * 100, 100, 0, stopLoss = stop, marginMicros = 0)
    private fun view(id: String, position: Position? = null, ready: Boolean = true, complete: Boolean = true) =
        CopyAccountView(AccountSnapshot(id, 0, positions = listOfNotNull(position)), ready, ready, complete, complete, false)
    private fun views(leader: Position? = null, one: Position? = null, two: Position? = null) = mapOf(
        "leader" to view("leader", leader), "one" to view("one", one), "two" to view("two", two))
    private val flat = mapOf("one" to 0, "two" to 0)

    @Test fun requiresFlatSynchronizedAccountsBeforeStarting() {
        assertIs<CopyPlan.Ready>(CopyTradePlanner.initial(settings, views()))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(settings, views(one = position(Side.BUY, 1))))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(settings, views() + ("leader" to view("leader", complete = false))))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(settings, views() + ("one" to view("one", ready = false))))
    }

    @Test fun producesASeparateBoundedAdjustmentForEachFollower() {
        val result = assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(settings, views(leader = position(Side.BUY, 2)), flat))
        assertEquals(listOf(CopyAdjustment("one", Side.BUY, 2, 2), CopyAdjustment("two", Side.BUY, 4, 4)), result.adjustments)
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.plan(settings, views(leader = position(Side.BUY, 3)), flat))
    }

    @Test fun unconfirmedOrUnexpectedFollowerPositionStopsInsteadOfResending() {
        val current = views(leader = position(Side.BUY, 2), one = position(Side.BUY, 1))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.plan(settings, current, flat))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.plan(settings, current, mapOf("one" to 1)))
    }

    @Test fun reversalIsTwoStepsAndWaitsForConfirmedFlat() {
        val current = views(leader = position(Side.SELL, 1), one = position(Side.BUY, 1), two = position(Side.BUY, 2))
        val close = assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(settings, current, mapOf("one" to 1, "two" to 2)))
        assertEquals(listOf(CopyAdjustment("one", Side.SELL, 1, 0), CopyAdjustment("two", Side.SELL, 2, 0)), close.adjustments)
        val reopen = assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(settings, views(leader = position(Side.SELL, 1)), flat))
        assertEquals(listOf(CopyAdjustment("one", Side.SELL, 1, -1), CopyAdjustment("two", Side.SELL, 2, -2)), reopen.adjustments)
    }

    @Test fun blocksUnsupportedProtectionOtherContractsAndDuplicateAccounts() {
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.plan(settings, views(leader = position(Side.BUY, 1, stop = 90)), flat))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.plan(settings, views(leader = position(Side.BUY, 1, "MNQZ6")), flat))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(settings.copy(followers = listOf(CopyFollower("leader", 1, 1))), views()))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(settings.copy(symbol = "@MES"), views()))
    }

    @Test fun noOrdersWhenPositionsAlreadyMatch() {
        val current = views(position(Side.BUY, 1), position(Side.BUY, 1), position(Side.BUY, 2))
        assertTrue(assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(settings, current, mapOf("one" to 1, "two" to 2))).adjustments.isEmpty())
    }

    @Test fun fixedAndMultiplierFollowersHaveIndependentTargets() {
        val mixed = settings.copy(followers = listOf(CopyFollower("one", 1, 5, fixedContracts = 3), CopyFollower("two", 2, 8)))
        val result = assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(mixed, views(leader = position(Side.BUY, 2)), flat))
        assertEquals(listOf(CopyAdjustment("one", Side.BUY, 3, 3), CopyAdjustment("two", Side.BUY, 4, 4)), result.adjustments)
        val close = assertIs<CopyPlan.Ready>(CopyTradePlanner.plan(mixed, views(one = position(Side.BUY, 3), two = position(Side.BUY, 4)), mapOf("one" to 3, "two" to 4)))
        assertEquals(listOf(CopyAdjustment("one", Side.SELL, 3, 0), CopyAdjustment("two", Side.SELL, 4, 0)), close.adjustments)
    }

    @Test fun fixedQuantityCannotExceedFollowerLimit() {
        val invalid = settings.copy(followers = listOf(CopyFollower("one", 1, 2, fixedContracts = 3)))
        assertIs<CopyPlan.Blocked>(CopyTradePlanner.initial(invalid, views()))
    }
}
