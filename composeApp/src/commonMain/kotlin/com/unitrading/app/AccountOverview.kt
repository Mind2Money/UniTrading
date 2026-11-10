package com.unitrading.app

import com.unitrading.broker.*

/**
 * The trading child's totals. A value the broker has not reported stays null instead of showing as zero, and the
 * closed-trade total covers the history loaded so far, not today.
 */
data class OverviewTotals(val equityMicros: Long?, val floatingMicros: Long?, val realizedMicros: Long?, val unknownClosed: Int)

fun overviewTotals(snapshot: AccountSnapshot): OverviewTotals {
    val known = snapshot.trades.mapNotNull { it.netPnlMicros }
    val unknown = snapshot.trades.size - known.size
    return OverviewTotals(
        equityMicros = snapshot.equityMicros.takeIf { snapshot.equityKnown },
        floatingMicros = snapshot.floatingPnlMicros.takeIf { snapshot.equityKnown && snapshot.balanceKnown },
        realizedMicros = if (unknown > 0) null else runCatching { known.fold(0L, ::checkedAdd) }.getOrNull(),
        unknownClosed = unknown,
    )
}
