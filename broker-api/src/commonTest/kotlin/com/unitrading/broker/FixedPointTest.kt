package com.unitrading.broker

import kotlin.test.*

class FixedPointTest {
    private val fx = Instrument("EURUSD", "EUR/USD", "FX", 5, tickValueMicrosPerLot = 1_000_000)
    @Test fun strictDecimalParsing() {
        assertEquals(108_725L, parseFixed("1.08725", 5))
        assertEquals(1L, parseFixed("0.01", 2))
        for (input in listOf("NaN", "1e5", "1.000001", "", "--2", "999999999999999999999999")) assertNull(parseFixed(input, 5))
        assertEquals("-1.25", formatFixed(-125, 2))
        assertEquals("-9223372036854775808", formatFixed(Long.MIN_VALUE, 0))
    }
    @Test fun integerPnlAndMargin() {
        assertEquals(1_000_000L, grossPnl(fx, Side.BUY, 1, 100_000, 100_100))
        assertEquals(-1_000_000L, grossPnl(fx, Side.SELL, 1, 100_000, 100_100))
        assertEquals(10_000_000L, requiredMargin(fx, 1, 100_000))
    }
    @Test fun overflowIsRejected() {
        assertFailsWith<IllegalArgumentException> { checkedMultiply(Long.MAX_VALUE, 2) }
        assertFailsWith<IllegalArgumentException> { checkedMultiply(Long.MIN_VALUE, -1) }
        assertFailsWith<IllegalArgumentException> { checkedAdd(Long.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { checkedAdd(Long.MIN_VALUE, -1) }
        assertEquals(-6, checkedMultiply(-2, 3))
    }
}
