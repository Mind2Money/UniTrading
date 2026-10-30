package com.unitrading.ui

import com.unitrading.broker.*

/** The editor validates capabilities before enabling a server command. */
fun protectionEditError(capabilities: BrokerCapabilities, position: Position, sl: Long?, tp: Long?): String? =
    if (!capabilities.modifyProtection || !position.protectionEditable) "当前保护单不可编辑"
    else protectionChangeError(capabilities, position.stopLoss to sl, position.takeProfit to tp)

/** The same rules for a pending entry's stop loss and take profit, which only move while the entry is still unfilled. */
fun pendingProtectionEditError(capabilities: BrokerCapabilities, order: PendingOrder, sl: Long?, tp: Long?): String? =
    if (!capabilities.modifyPendingProtection || order.role != OrderRole.ENTRY || !order.editable || !order.protectionEditable) "当前挂单的止损/止盈不可编辑"
    else protectionChangeError(capabilities, order.request.stopLoss to sl, order.request.takeProfit to tp)

private fun protectionChangeError(capabilities: BrokerCapabilities, vararg lines: Pair<Long?, Long?>): String? {
    val changes = lines.filter { it.first != it.second }
    return when {
        changes.isEmpty() -> "保护价尚未改变"
        !capabilities.atomicProtectionEdit && changes.size > 1 -> "当前交易商每次只允许修改一条保护线"
        !capabilities.removeProtection && changes.any { it.second == null } -> "当前交易商不支持在此移除保护，请保留原价"
        !capabilities.addProtection && changes.any { it.first == null } -> "当前交易商不支持在此新增保护"
        else -> null
    }
}

/**
 * Result of an order entered at [entry] that exits at [exit]: gross, less a commission each way, which is zero where the
 * broker reports fees itself. Matches the brokers' own previews; null when the prices or the arithmetic are out of range.
 */
internal fun estimatedExitPnl(instrument: Instrument, side: Side, lots: Long, entry: Long, exit: Long): Long? = runCatching {
    checkedAdd(grossPnl(instrument, side, lots, entry, exit), -checkedMultiply(commission(instrument, lots), 2))
}.getOrNull()

/** Result of closing [position] at [exit], counting the entry fee it actually paid. */
internal fun estimatedExitPnl(instrument: Instrument, position: Position, exit: Long): Long? = runCatching {
    checkedAdd(checkedAdd(grossPnl(instrument, position.side, position.lotsHundredths, position.entryPrice, exit), -position.entryFeeMicros),
        -commission(instrument, position.lotsHundredths))
}.getOrNull()
