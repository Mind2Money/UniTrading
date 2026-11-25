package com.unitrading.tradovate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class ContinuousPriceMatcherTest {
    private fun maturity(id: Long, expiration: String) = Json.parseToJsonElement(
        """{"id":$id,"productId":400,"expirationDate":"$expiration"}""").jsonObject

    @Test fun choosesOnlyTheNearestThreeUnexpiredMonthsByExpiration() {
        val rows = listOf(maturity(4, "2027-06-20T00:00:00Z"), maturity(2, "2027-02-20T00:00:00Z"),
            maturity(5, "2026-10-20T00:00:00Z"), maturity(3, "2027-04-20T00:00:00Z"), maturity(1, "2026-12-20T00:00:00Z"))
        val now = timestamp("2026-11-25T00:00:00Z")!!
        assertEquals(listOf(1L, 2L, 3L), nearestThreeMaturities(rows, 400, now).map { it.long("id") })
    }

    @Test fun matchesOneUniqueLatestPriceAndKeepsItUntilExplicitReset() {
        val matcher = ContinuousPriceMatcher()
        val months = setOf("MGCZ6", "MGCG7", "MGCJ7")
        matcher.chart(100, 1000)
        matcher.trade("MGCZ6", 99, 1000); matcher.trade("MGCG7", 100, 1000); matcher.trade("MGCJ7", 101, 1000)
        assertEquals("MGCG7", matcher.unique(months, 1000))
        matcher.chart(99, 25000); matcher.trade("MGCZ6", 99, 25000); matcher.trade("MGCG7", 100, 25000)
        assertEquals("MGCG7", matcher.unique(months, 25000), "Do not rematch after a successful binding")
        assertEquals("MGCG7", matcher.unique(months, 300000), "The chosen month does not expire merely because prices are quiet")
        val refreshed = ContinuousPriceMatcher()
        refreshed.chart(99, 300000)
        refreshed.trade("MGCZ6", 99, 300000); refreshed.trade("MGCG7", 100, 300000); refreshed.trade("MGCJ7", 101, 300000)
        assertEquals("MGCZ6", refreshed.unique(months, 300000))
    }

    @Test fun multipleMatchesIncompleteOrStaleSnapshotsNeverGuess() {
        val months = setOf("MGCZ6", "MGCG7", "MGCJ7")
        val matcher = ContinuousPriceMatcher()
        matcher.chart(100, 1000)
        matcher.trade("MGCZ6", 100, 1000); matcher.trade("MGCG7", 100, 1000)
        assertNull(matcher.unique(months, 1000), "Missing a candidate must not be treated as a non-match")
        matcher.trade("MGCJ7", 101, 1000)
        assertNull(matcher.unique(months, 1000), "Identical prices are ambiguous")
        matcher.trade("MGCG7", 102, 1000)
        assertNull(matcher.unique(months, 17001), "Old chart and trade snapshots cannot start a match")
        matcher.chart(100, 18000)
        for ((symbol, price) in listOf("MGCZ6" to 100L, "MGCG7" to 102L, "MGCJ7" to 101L)) matcher.trade(symbol, price, 18000)
        assertEquals("MGCZ6", matcher.unique(months, 18000))
    }

    @Test fun independentProductsCanMatchAtTheSameTime() {
        val gold = ContinuousPriceMatcher(); val oil = ContinuousPriceMatcher()
        gold.chart(100, 1000); gold.trade("MGCZ6", 99, 1000); gold.trade("MGCG7", 100, 1000)
        oil.chart(200, 1000); oil.trade("MCLZ6", 200, 1000); oil.trade("MCLF7", 201, 1000)
        assertEquals("MGCG7", gold.unique(setOf("MGCZ6", "MGCG7"), 1000))
        assertEquals("MCLZ6", oil.unique(setOf("MCLZ6", "MCLF7"), 1000))
    }

    @Test fun scopeCoversOilMetalsAndCryptoButPreservesIndexes() {
        for (root in listOf("GC", "MGC", "SI", "SIL", "SL", "CL", "MCL", "BTC", "MBT")) assertTrue(usesContinuousPriceMatching("@$root"))
        for (root in listOf("ES", "MES", "NQ", "MNQ", "YM", "MYM")) assertFalse(usesContinuousPriceMatching("@$root"))
    }
}
