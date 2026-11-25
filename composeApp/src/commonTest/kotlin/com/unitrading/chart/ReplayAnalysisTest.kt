package com.unitrading.chart

import com.unitrading.broker.Position
import com.unitrading.broker.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayAnalysisTest {
    @Test fun closedTradeDrawdownTracksEquityPeak() {
        val p = Position("a", "MES", Side.BUY, 100, 100, 0, marginMicros = 0, entryFeeMicros = 0)
        val trades = listOf(200L, -100L, -150L, 300L).mapIndexed { i, pnl -> ReplayTrade(p, 100, i.toLong(), pnl, "test") }
        val s = replayAnalysis(trades)
        assertEquals(2, s.wins)
        assertEquals(2, s.losses)
        assertEquals(500L, s.grossProfit)
        assertEquals(-250L, s.grossLoss)
        assertEquals(250L, s.maxClosedDrawdown)
    }
}
