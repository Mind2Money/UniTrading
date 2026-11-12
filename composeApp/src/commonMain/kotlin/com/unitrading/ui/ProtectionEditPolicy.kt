package com.unitrading.ui

import com.unitrading.broker.*

/** The editor validates capabilities before enabling a server command. */
fun protectionEditError(capabilities: BrokerCapabilities, position: Position, sl: Long?, tp: Long?): String? =
    if (!capabilities.modifyProtection || !position.protectionEditable) "当前保护单不可编辑"
    else protectionChangeError(capabilities, capabilities.addPositionProtection, capabilities.ocoProtection, position.stopLoss to sl, position.takeProfit to tp)

/** The same rules for a pending entry's stop loss and take profit, which only move while the entry is still unfilled. */
fun pendingProtectionEditError(capabilities: BrokerCapabilities, order: PendingOrder, sl: Long?, tp: Long?): String? =
    if (!capabilities.modifyPendingProtection || order.role != OrderRole.ENTRY || !order.editable || !order.protectionEditable) "当前挂单的止损/止盈不可编辑"
    else protectionChangeError(capabilities, capabilities.addProtection, false, order.request.stopLoss to sl, order.request.takeProfit to tp)

/**
 * What adding a stop loss or take profit to [position] sends, where the broker places them as plain orders paired by OCO
 * ([BrokerCapabilities.ocoProtection]); null when the change adds no line.
 */
fun protectionAddNote(capabilities: BrokerCapabilities, position: Position, sl: Long?, tp: Long?): String? {
    if (!capabilities.ocoProtection) return null
    val stopAdded = position.stopLoss == null && sl != null
    val targetAdded = position.takeProfit == null && tp != null
    if (!stopAdded && !targetAdded) return null
    val kept = when { position.stopLoss != null -> "止损"; position.takeProfit != null -> "止盈"; else -> null }
    return when {
        kept != null -> "将先撤销现有$kept，服务器确认撤销后，以一组 OCO 同时下止损和止盈：成交一条，服务器撤销另一条。" +
            "撤销到重新下单之间约 1–2 秒没有这条$kept；OCO 若被拒，仓位会缺这条$kept。"
        stopAdded && targetAdded -> "止损（Stop）和止盈（Limit）作为一组 OCO 提交，数量等于持仓：成交一条，服务器撤销另一条。"
        stopAdded -> "止损以普通 Stop 单提交，数量等于持仓。之后再添加止盈时，会与它组成 OCO。"
        else -> "止盈以普通 Limit 单提交，数量等于持仓。之后再添加止损时，会与它组成 OCO。"
    }
}

/**
 * [canAdd]: a line can be added where there is none. [pairable]: a change that adds a line goes out with the other line as one
 * OCO pair, so that one may move with it.
 */
private fun protectionChangeError(capabilities: BrokerCapabilities, canAdd: Boolean, pairable: Boolean, vararg lines: Pair<Long?, Long?>): String? {
    val changes = lines.filter { it.first != it.second }
    return when {
        changes.isEmpty() -> "止损/止盈尚未改变"
        !capabilities.atomicProtectionEdit && changes.size > 1 && !(pairable && changes.any { it.first == null }) -> "当前交易商每次只允许修改一条保护线"
        !capabilities.removeProtection && changes.any { it.second == null } -> "当前交易商不支持在此移除保护，请保留原价"
        !canAdd && changes.any { it.first == null } -> "当前交易商不支持在此新增保护"
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

/** Result of a protection order for [quantity] filling at [exit]: a smaller order closes only its share of [position]. */
internal fun estimatedExitPnl(instrument: Instrument, position: Position, quantity: Long, exit: Long): Long? =
    if (quantity >= position.lotsHundredths) estimatedExitPnl(instrument, position, exit)
    else estimatedExitPnl(instrument, position.side, quantity, position.entryPrice, exit)
