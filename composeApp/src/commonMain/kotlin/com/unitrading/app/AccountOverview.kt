package com.unitrading.app

import com.unitrading.broker.*

/**
 * The trading child's totals. A value the broker has not reported stays null instead of showing as zero, and the
 * closed-trade total covers the history loaded so far, not today. Like the analytics page it adds up the trades whose
 * net result is known and counts the others in [unknownClosed]; it is null only when no trade's result is known while
 * some are pending, or when the sum overflows.
 */
data class OverviewTotals(val equityMicros: Long?, val floatingMicros: Long?, val realizedMicros: Long?, val unknownClosed: Int)

fun overviewTotals(snapshot: AccountSnapshot): OverviewTotals {
    val known = snapshot.trades.mapNotNull { it.netPnlMicros }
    val unknown = snapshot.trades.size - known.size
    return OverviewTotals(
        equityMicros = snapshot.equityMicros.takeIf { snapshot.equityKnown },
        floatingMicros = snapshot.floatingPnlMicros.takeIf { snapshot.equityKnown && snapshot.balanceKnown },
        // A cached trade the broker no longer reports may never get its result, so it must not blank the total for weeks.
        realizedMicros = if (known.isEmpty() && unknown > 0) null else runCatching { known.fold(0L, ::checkedAdd) }.getOrNull(),
        unknownClosed = unknown,
    )
}
