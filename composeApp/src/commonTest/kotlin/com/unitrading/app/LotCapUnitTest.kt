package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class LotCapUnitTest {
    private val futures = Instrument("MESZ6", "Micro E-mini S&P", "期货", 2, 25, 1_250_000, quantityUnit = "张", quantityDecimals = 0)
    private val fx = Instrument("EURUSD", "EUR/USD", "外汇", 5, 1, 1_000_000)
    private val buy = OrderRequest("MESZ6", Side.BUY, OrderKind.MARKET, 100)

    @Test fun aLoginOfWholeContractsTakesWholeNumbersInTheirUnit() {
        val unit = LotCapUnit.of(listOf(futures, futures.copy(symbol = "NQZ6")))
        assertTrue(unit.whole)
        assertEquals("张", unit.label)
        assertEquals("3", unit.format(300))
        assertEquals(300, unit.parse("3"))
        assertEquals(300, unit.parse(" 3 "))
        assertNull(unit.parse("2.5"), "a fraction of a contract cannot be traded")
        assertNull(unit.parse("3.0"))
        assertNull(unit.parse("0"))
        assertNull(unit.parse("-1"))
        assertNull(unit.parse("三"))
        assertEquals(999_999_900, unit.parse("9999999"))
        assertNull(unit.parse("10000000"), "beyond the largest cap the rules keep")
        assertNull(unit.parse("99999999999999999"))
        // A login whose contract specs have not loaded yet lists none: only the demo trades fractions, and it lists its own.
        val unloaded = LotCapUnit.of(emptyList())
        assertTrue(unloaded.whole)
        assertEquals("张", unloaded.label)
    }

    @Test fun anyFractionalInstrumentKeepsTwoDecimalsOfLots() {
        for (unit in listOf(LotCapUnit.of(listOf(futures, fx)), LotCapUnit.of(listOf(fx)))) {
            assertFalse(unit.whole)
            assertEquals("手", unit.label)
            assertEquals("2.50", unit.format(250))
            assertEquals(250, unit.parse("2.5"))
            assertEquals(1, unit.parse("0.01"))
            assertNull(unit.parse("0.001"))
            assertNull(unit.parse("0"))
        }
    }

    @Test fun theRefusalNamesTheCapInTheLoginsUnitAndTheCapStaysInHundredths() {
        val limits = AccountOrderLimits(maxLots = 300)
        val over = ExposureEffect(300, 400, false)
        assertEquals("超过子账户最大持仓 3 张（含持仓和未成交开仓挂单）。", limits.refusal(buy, over, LotCapUnit.of(listOf(futures))))
        assertEquals("超过子账户最大持仓 3.00 手（含持仓和未成交开仓挂单）。", limits.refusal(buy, over, LotCapUnit.of(listOf(fx))))
        assertNull(limits.refusal(buy, ExposureEffect(200, 300, false), LotCapUnit.of(listOf(futures))))
    }
}
