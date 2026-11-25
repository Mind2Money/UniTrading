package com.unitrading.chart

import com.unitrading.broker.checkedAdd

/** Closed-trade statistics only; an intrabar equity drawdown cannot be recovered from OHLC. */
internal data class ReplayAnalysis(val wins: Int, val losses: Int, val grossProfit: Long, val grossLoss: Long,
    val maxClosedDrawdown: Long, val best: Long?, val worst: Long?)

internal fun replayAnalysis(trades: List<ReplayTrade>): ReplayAnalysis {
    var wins = 0; var losses = 0; var profit = 0L; var loss = 0L
    var balance = 0L; var peak = 0L; var maxDrawdown = 0L
    var best: Long? = null; var worst: Long? = null
    for (trade in trades) {
        val pnl = trade.pnlMicros
        if (pnl > 0) { wins++; profit = checkedAdd(profit, pnl) }
        if (pnl < 0) { losses++; loss = checkedAdd(loss, pnl) }
        balance = checkedAdd(balance, pnl)
        peak = maxOf(peak, balance)
        maxDrawdown = maxOf(maxDrawdown, checkedAdd(peak, -balance))
        best = best?.let { maxOf(it, pnl) } ?: pnl
        worst = worst?.let { minOf(it, pnl) } ?: pnl
    }
    return ReplayAnalysis(wins, losses, profit, loss, maxDrawdown, best, worst)
}
