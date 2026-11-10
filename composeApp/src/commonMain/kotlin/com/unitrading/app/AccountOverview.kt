package com.unitrading.app

import com.unitrading.broker.*

/** A login's accounts only. Never combines accounts from different broker sessions. */
data class OverviewAccount(val info: AccountInfo, val snapshot: AccountSnapshot) {
    init { require(info.id == snapshot.accountId) }
}

enum class OverviewKind { POSITION, ORDER, CLOSED }
enum class OverviewStatus(val label: String) {
    FLOATING_PROFIT("浮盈"), FLOATING_LOSS("浮亏"), FLOATING_FLAT("持仓持平"),
    REALIZED_PROFIT("已盈利"), REALIZED_LOSS("已亏损"), REALIZED_FLAT("已平仓 · 持平"),
    PENDING("待成交"), UNKNOWN("盈亏待确认");
}

enum class OverviewFilter(val label: String) {
    ALL("全部"), OPEN("持仓"), FLOATING_PROFIT("浮盈"), FLOATING_LOSS("浮亏"),
    REALIZED_PROFIT("已盈利"), REALIZED_LOSS("已亏损"), PENDING("挂单"), CLOSED("已平仓"), UNKNOWN("待确认");

    fun matches(row: OverviewRow): Boolean = when (this) {
        ALL -> true
        OPEN -> row.kind == OverviewKind.POSITION
        CLOSED -> row.kind == OverviewKind.CLOSED
        PENDING -> row.kind == OverviewKind.ORDER
        FLOATING_PROFIT -> row.status == OverviewStatus.FLOATING_PROFIT
        FLOATING_LOSS -> row.status == OverviewStatus.FLOATING_LOSS
        REALIZED_PROFIT -> row.status == OverviewStatus.REALIZED_PROFIT
        REALIZED_LOSS -> row.status == OverviewStatus.REALIZED_LOSS
        UNKNOWN -> row.status == OverviewStatus.UNKNOWN
    }
}

data class OverviewRow(
    val account: AccountInfo, val id: String, val kind: OverviewKind,
    val symbol: String, val side: Side?, val quantity: String, val price: String,
    val timeMs: Long, val pnlMicros: Long?, val status: OverviewStatus,
    val detail: String, val currency: String, val gross: Boolean = false,
) {
    // Length prefixes avoid collisions even when a server ID contains delimiters.
    val key: String get() = "${account.id.length}:${account.id}:${kind.name}:${id.length}:$id"
    fun matchesQuery(query: String): Boolean = query.trim().let { q ->
        q.isEmpty() || listOf(symbol, account.name, account.id, id, detail).any { it.contains(q, ignoreCase = true) }
    }
}

private fun pnlStatus(pnl: Long?, open: Boolean): OverviewStatus = when {
    pnl == null -> OverviewStatus.UNKNOWN
    pnl > 0 -> if (open) OverviewStatus.FLOATING_PROFIT else OverviewStatus.REALIZED_PROFIT
    pnl < 0 -> if (open) OverviewStatus.FLOATING_LOSS else OverviewStatus.REALIZED_LOSS
    else -> if (open) OverviewStatus.FLOATING_FLAT else OverviewStatus.REALIZED_FLAT
}

/** Uses broker snapshots and the existing bid/ask valuation; an unavailable net result is never a zero. */
fun overviewRows(accounts: List<OverviewAccount>, instruments: List<Instrument>, market: MarketSnapshot): List<OverviewRow> {
    val specs = instruments.associateBy { it.symbol }
    return accounts.flatMap { (info, snapshot) ->
        val positions = snapshot.positions.map { p ->
            val spec = specs[p.symbol]
            val quote = market.quotes[p.symbol]
            val pnl = if (spec != null && quote != null) runCatching { positionPnl(spec, p, quote) }.getOrNull() else null
            OverviewRow(info, p.id, OverviewKind.POSITION, p.symbol, p.side,
                spec?.let { "${it.formatQuantity(p.lotsHundredths)} ${it.quantityUnit}" } ?: formatFixed(p.lotsHundredths, 2),
                spec?.formatPrice(p.entryPrice) ?: "—", p.openedAtMs, pnl, pnlStatus(pnl, true),
                "SL ${p.stopLoss?.let { spec?.formatPrice(it) } ?: "—"} · TP ${p.takeProfit?.let { spec?.formatPrice(it) } ?: "—"}",
                spec?.currency ?: info.currency, gross = true)
        }
        // Keep protection orders visible and labelled; do not count them as completed trades.
        val orders = snapshot.orders.map { o ->
            val r = o.request
            val spec = specs[r.symbol]
            val role = when (o.role) {
                OrderRole.ENTRY -> "入场单"
                OrderRole.STOP_LOSS -> "SL 止损保护"
                OrderRole.TAKE_PROFIT -> "TP 止盈保护"
                OrderRole.UNRESOLVED -> "订单归属待确认"
            }
            OverviewRow(info, o.id, OverviewKind.ORDER, r.symbol, r.side,
                spec?.let { "${it.formatQuantity(r.lotsHundredths)} ${it.quantityUnit}" } ?: formatFixed(r.lotsHundredths, 2),
                r.entryPrice?.let { spec?.formatPrice(it) } ?: "—", o.createdAtMs, null, OverviewStatus.PENDING,
                role + when (r.kind) { OrderKind.MARKET -> " · 市价"; OrderKind.LIMIT -> " · 限价"; OrderKind.STOP -> " · 止价" } +
                    if (o.filledLotsHundredths > 0) " · 部分成交" else "",
                spec?.currency ?: info.currency)
        }
        val trades = snapshot.trades.map { t ->
            val spec = specs[t.symbol]
            OverviewRow(info, t.id, OverviewKind.CLOSED, t.symbol, t.side.takeIf { t.directionKnown },
                spec?.let { "${it.formatQuantity(t.lotsHundredths)} ${it.quantityUnit}" } ?: formatFixed(t.lotsHundredths, 2),
                spec?.let { "${it.formatPrice(t.entryPrice)} → ${it.formatPrice(t.exitPrice)}" } ?: "—",
                t.closedAtMs, t.netPnlMicros ?: t.grossPnlMicros, pnlStatus(t.netPnlMicros, false),
                t.reason.ifBlank { "平仓原因未提供" } + if (t.netPnlMicros == null) " · 净盈亏待确认" else "",
                spec?.currency ?: info.currency, gross = t.netPnlMicros == null)
        }
        positions + orders + trades
    }.sortedWith(compareBy<OverviewRow> { it.kind.ordinal }.thenByDescending { it.timeMs }.thenBy { it.key })
}

data class OverviewTotals(val currency: String, val accounts: Int, val equityMicros: Long?, val floatingMicros: Long?,
    val realizedMicros: Long?, val unknownClosed: Int, val estimated: Boolean)

/** No currency conversion and no partial sums disguised as a complete total. History is the loaded snapshot, not today. */
fun overviewTotals(accounts: List<OverviewAccount>): List<OverviewTotals> = accounts.groupBy { it.info.currency }.map { (currency, group) ->
    fun sumKnown(values: List<Long?>): Long? = if (values.any { it == null }) null
        else runCatching { values.fold(0L) { total, value -> checkedAdd(total, value!!) } }.getOrNull()
    val trades = group.flatMap { it.snapshot.trades }
    OverviewTotals(currency, group.size,
        sumKnown(group.map { it.snapshot.equityMicros.takeIf { _ -> it.snapshot.equityKnown } }),
        sumKnown(group.map {
            val snapshot = it.snapshot
            if (!snapshot.equityKnown || !snapshot.balanceKnown) null else runCatching {
                val difference = snapshot.equityMicros - snapshot.balanceMicros
                require((snapshot.equityMicros xor snapshot.balanceMicros) and
                    (snapshot.equityMicros xor difference) >= 0) { "浮动盈亏超过支持范围" }
                difference
            }.getOrNull()
        }),
        sumKnown(trades.map { it.netPnlMicros }), trades.count { it.netPnlMicros == null },
        group.any { it.snapshot.equityEstimated })
}
