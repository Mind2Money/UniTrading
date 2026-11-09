package com.unitrading.broker

import kotlin.test.*

class AccountDrawdownTest {
    private val rule = DrawdownRule(levelMicros = 145_500_000_000, amountMicros = 4_500_000_000)
    private fun at(micros: Long, drawdownRule: DrawdownRule = rule) = accountDrawdown(drawdownRule, DrawdownAccountValue(micros, DrawdownValueSource.QUOTES))!!

    @Test fun anAccountWithoutARuleHasNoDrawdownNotAZeroOne() {
        assertNull(accountDrawdown(null, DrawdownAccountValue(1_000_000, DrawdownValueSource.CASH)))
    }

    @Test fun theRoomIsTheValueLessTheLineAndDangerTheShareOfTheTrailingAmountUsed() {
        val drawdown = at(148_500_000_000)
        assertEquals(3_000_000_000, drawdown.roomMicros)
        assertEquals(1.0 / 3.0, drawdown.danger!!, 1e-9)
        assertEquals(DrawdownValueSource.QUOTES, drawdown.source)
    }

    @Test fun dangerStaysBetweenAFullAmountOfRoomAndTheLine() {
        assertEquals(0.0, at(150_000_000_000).danger, "a full amount of room")
        assertEquals(0.0, at(155_000_000_000).danger, "more than a full amount")
        assertEquals(1.0, at(145_500_000_000).danger, "at the line")
        val below = at(145_453_500_000)
        assertEquals(-46_500_000, below.roomMicros, "the room shows how far past the line the account is")
        assertEquals(1.0, below.danger)
    }

    @Test fun aMissingValueOrLineKeepsTheRuleAndLeavesRoomAndDangerUnknown() {
        assertEquals(AccountDrawdown(rule), accountDrawdown(rule, null))
        val noLine = DrawdownRule(amountMicros = 1_500_000_000, criteria = "Loss Limit")
        assertEquals(AccountDrawdown(noLine), accountDrawdown(noLine, DrawdownAccountValue(25_000_000_000, DrawdownValueSource.CASH)))
    }

    @Test fun aLineWithNothingToMeasureItAgainstHasARoomAndNoDanger() {
        val drawdown = at(48_000_000_000, DrawdownRule(levelMicros = 47_500_000_000))
        assertEquals(500_000_000, drawdown.roomMicros)
        assertNull(drawdown.danger)
    }

    @Test fun aValueOutOfRangeLeavesTheRoomUnknownInsteadOfWrappingAround() {
        val negativeLine = DrawdownRule(levelMicros = -1, amountMicros = 1)
        assertEquals(AccountDrawdown(negativeLine), accountDrawdown(negativeLine, DrawdownAccountValue(Long.MAX_VALUE, DrawdownValueSource.CASH)))
    }
}
