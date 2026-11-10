package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.datetime.*
import kotlin.time.Instant

/** Every record retains its account identity: brokers can reuse trade IDs across accounts. */
data class AnalyticsTrade(val account: AccountInfo, val trade: ClosedTrade) {
    fun date(zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(trade.closedAtMs).toLocalDateTime(zone).date
}

fun analyticsTrades(accounts: List<OverviewAccount>): List<AnalyticsTrade> = accounts.flatMap { account ->
    account.snapshot.trades.distinctBy { it.id }.map { AnalyticsTrade(account.info, it) }
}.sortedWith(compareBy({ it.trade.closedAtMs }, { it.account.id }, { it.trade.id }))

data class TradeStatistics(
    val total: Int, val known: Int, val wins: Int, val losses: Int, val breakeven: Int,
    val netMicros: Long?, val positiveMicros: Long?, val negativeMicros: Long?, val drawdownMicros: Long?,
) {
    val unknown: Int get() = total - known
    val winRate: Double? get() = if (known == 0) null else wins * 100.0 / known
    val profitFactor: Double? get() = when {
        known == 0 || positiveMicros == null || negativeMicros == null -> null
        negativeMicros < 0 -> positiveMicros.toDouble() / -negativeMicros.toDouble()
        positiveMicros > 0 -> Double.POSITIVE_INFINITY
        else -> null
    }
    val averageWinLoss: Double? get() = if (wins > 0 && losses > 0 && positiveMicros != null && negativeMicros != null)
        (positiveMicros.toDouble() / wins) / (-negativeMicros.toDouble() / losses) else null
}

/** Input must have one currency. Unknown P&L is excluded, never treated as breakeven. */
fun tradeStatistics(records: List<AnalyticsTrade>): TradeStatistics {
    require(records.map { it.account.currency }.distinct().size <= 1) { "不同币种不能合并统计" }
    val pnl = records.sortedBy { it.trade.closedAtMs }.mapNotNull { it.trade.netPnlMicros }
    fun sum(values: List<Long>): Long? = runCatching { values.fold(0L, ::checkedAdd) }.getOrNull()
    val drawdown = runCatching {
        var equity = 0L; var peak = 0L; var maximum = 0L
        pnl.forEach { value ->
            equity = checkedAdd(equity, value); peak = maxOf(peak, equity)
            maximum = maxOf(maximum, if (equity == Long.MIN_VALUE) error("Amount overflow") else checkedAdd(peak, -equity))
        }
        maximum
    }.getOrNull()
    return TradeStatistics(records.size, pnl.size, pnl.count { it > 0 }, pnl.count { it < 0 }, pnl.count { it == 0L },
        if (pnl.isEmpty()) null else sum(pnl), sum(pnl.filter { it > 0 }), sum(pnl.filter { it < 0 }),
        drawdown.takeIf { pnl.isNotEmpty() })
}

/** Sunday-first calendar; exact six weeks, including visible adjacent-month cells. */
fun calendarDates(month: LocalDate): List<LocalDate> {
    val first = LocalDate(month.year, month.month, 1)
    val start = first.minus(first.dayOfWeek.isoDayNumber % 7, DateTimeUnit.DAY)
    return List(42) { start.plus(it, DateTimeUnit.DAY) }
}
