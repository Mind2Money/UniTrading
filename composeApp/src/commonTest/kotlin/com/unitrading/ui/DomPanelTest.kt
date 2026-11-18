package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class DomPanelTest {
    @Test fun ladderUsesRealBestPricesAndTickSize() {
        val snapshot = OrderFlowSnapshot(symbol = "MESZ6", bids = listOf(DepthLevel(5_500_000_000, 3)),
            asks = listOf(DepthLevel(5_500_250_000, 4)), bestBid = DepthLevel(5_500_000_000, 3), depthStatus = DepthStatus.AVAILABLE, connected = true)
        val prices = domPriceLadder(snapshot, 250_000)
        assertEquals(81, prices.size)
        assertEquals(5_500_000_000, prices[40])
        assertTrue(prices.zipWithNext().all { (a, b) -> a - b == 250_000L })
        assertEquals(emptyList(), domPriceLadder(snapshot.copy(depthStatus = DepthStatus.UNAVAILABLE), 250_000))
        assertEquals(emptyList(), domPriceLadder(snapshot.copy(bids = emptyList(), asks = emptyList(), bestBid = null), 250_000))
    }
}
