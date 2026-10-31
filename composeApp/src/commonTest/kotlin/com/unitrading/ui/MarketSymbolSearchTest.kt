package com.unitrading.ui

import com.unitrading.broker.Instrument
import kotlin.test.*

class MarketSymbolSearchTest {
    private fun instrument(symbol: String) = Instrument(symbol, "S&P futures", "期货", 2,
        tickValueMicrosPerLot = 1, isContinuous = symbol.startsWith("@"))
    private val catalog = listOf("MESZ6", "@MES", "ESZ6", "@ES").map(::instrument)

    @Test fun aliasesAreNormalizedAndExactContinuousRootRanksFirst() {
        assertEquals("@ES", marketSearchResults(catalog, " es ").first().symbol)
        assertEquals(listOf("@ES"), marketSearchResults(catalog, " @es ").map { it.symbol })
        assertEquals(listOf("@ES"), marketSearchResults(catalog, "ES1!").map { it.symbol })
        assertEquals(listOf("ESZ6"), marketSearchResults(catalog, "esz6").map { it.symbol })
        assertEquals(4, marketSearchResults(catalog, "S&P").size)
        assertTrue(marketSearchResults(catalog, "missing").isEmpty())
    }
}
