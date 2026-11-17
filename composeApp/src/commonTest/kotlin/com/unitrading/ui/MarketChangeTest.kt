package com.unitrading.ui

import com.unitrading.broker.Quote
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketChangeTest {
    @Test fun calculatesFromTradeAndSettlementOnly() {
        assertEquals("+2.00%" to 1, marketChangePercent(Quote(100, 102, 0, 102, 100)))
        assertEquals("−2.00%" to -1, marketChangePercent(Quote(100, 102, 0, 98, 100)))
        assertEquals("0.00%" to 0, marketChangePercent(Quote(100, 102, 0, 100, 100)))
        assertEquals("—" to 0, marketChangePercent(Quote(100, 102, 0)))
        assertEquals("—" to 0, marketChangePercent(Quote(100, 102, 0, 100, 0)))
    }
}
