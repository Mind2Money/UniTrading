package com.unitrading.app

import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HyperpropContractGuardTest {
    private val chart = Instrument("@MES", "MES", "continuous", 2, tickSize = 25, tickValueMicrosPerLot = 1_250_000,
        isContinuous = true, tradeSymbol = "MESZ6", quoteSymbol = "MESZ6")
    private val quote = Quote(548500, 548525, 100_000)

    @Test fun acceptsOnlyTheSameReviewedDatedContract() {
        val binding = bindHyperpropContract("tradovate-1", chart, "MESZ6")
        assertNull(hyperpropContractError(binding, "tradovate-1", chart, "MESZ6", quote, 101_000))
        assertFails { bindHyperpropContract("tradovate-1", chart, "MESH7") }
        assertFails { bindHyperpropContract("tradovate-1", chart.copy(tradeSymbol = null), "MESZ6") }
    }

    @Test fun rolloverAndDataSourceOrSpecificationChangesRequireReview() {
        val binding = bindHyperpropContract("tradovate-1", chart, "MESZ6")
        assertNotNull(hyperpropContractError(binding, "rithmic-1", chart, "MESZ6", quote, 101_000))
        assertNotNull(hyperpropContractError(binding, "tradovate-1", chart.copy(tradeSymbol = "MESH7"), "MESZ6", quote, 101_000))
        assertNotNull(hyperpropContractError(binding, "tradovate-1", chart.copy(tickSize = 50), "MESZ6", quote, 101_000))
    }

    @Test fun missingOrDelayedQuoteNeverAllowsTheReviewedOrder() {
        val binding = bindHyperpropContract("tradovate-1", chart, "MESZ6")
        assertNotNull(hyperpropContractError(binding, "tradovate-1", chart, "MESZ6", null, 101_000))
        assertNotNull(hyperpropContractError(binding, "tradovate-1", chart, "MESZ6", quote, 116_000))
        assertNotNull(hyperpropContractError(binding, "tradovate-1", chart, "MESZ6", quote.copy(bid = 548550), 101_000))
    }
}
