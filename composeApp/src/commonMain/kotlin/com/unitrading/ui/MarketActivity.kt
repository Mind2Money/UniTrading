package com.unitrading.ui

import com.unitrading.broker.*

/** Counts belong to the selected account and the executable contract, including continuous aliases. */
internal data class MarketActivity(
    val longPositions: Int = 0,
    val shortPositions: Int = 0,
    val entries: Int = 0,
    val stops: Int = 0,
    val targets: Int = 0,
    val unresolved: Int = 0,
) {
    val hasActivity: Boolean get() = longPositions + shortPositions + entries + stops + targets + unresolved > 0
    val description: String get() = listOfNotNull(
        longPositions.takeIf { it > 0 }?.let { "多头持仓 $it 笔" },
        shortPositions.takeIf { it > 0 }?.let { "空头持仓 $it 笔" },
        entries.takeIf { it > 0 }?.let { "入场挂单 $it 笔" },
        stops.takeIf { it > 0 }?.let { "止损单 $it 笔" },
        targets.takeIf { it > 0 }?.let { "止盈单 $it 笔" },
        unresolved.takeIf { it > 0 }?.let { "关联待确认 $it 笔" },
    ).joinToString("，").ifEmpty { "无持仓或挂单" }
}

internal fun marketActivities(account: AccountSnapshot): Map<String, MarketActivity> {
    val result = mutableMapOf<String, MarketActivity>()
    account.positions.filter { it.lotsHundredths > 0 }.forEach { position ->
        val current = result[position.symbol] ?: MarketActivity()
        result[position.symbol] = when (position.side) {
            Side.BUY -> current.copy(longPositions = current.longPositions + 1)
            Side.SELL -> current.copy(shortPositions = current.shortPositions + 1)
        }
    }
    account.orders.forEach { order ->
        val symbol = order.request.symbol
        val current = result[symbol] ?: MarketActivity()
        result[symbol] = when (order.role) {
            OrderRole.ENTRY -> current.copy(entries = current.entries + 1)
            OrderRole.STOP_LOSS -> current.copy(stops = current.stops + 1)
            OrderRole.TAKE_PROFIT -> current.copy(targets = current.targets + 1)
            OrderRole.UNRESOLVED -> current.copy(unresolved = current.unresolved + 1)
        }
    }
    return result
}

/** UTC, matching the chart. Never substitute the device time for the last quote time. */
internal fun quoteTime(timeMs: Long): String {
    val seconds = ((timeMs / 1_000) % 86_400 + 86_400) % 86_400
    return listOf(seconds / 3_600, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
}

internal fun quoteSpread(quote: Quote?, instrument: Instrument): String =
    if (quote == null || quote.ask < quote.bid || instrument.tickSize <= 0) "—"
    else formatFixed(((quote.ask - quote.bid).toDouble() / instrument.tickSize * 100).toLong(), 2).trimEnd('0').trimEnd('.')
