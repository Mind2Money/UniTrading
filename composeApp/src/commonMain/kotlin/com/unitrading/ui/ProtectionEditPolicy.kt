package com.unitrading.ui

import com.unitrading.broker.BrokerCapabilities
import com.unitrading.broker.Position

/** The editor validates capabilities before enabling a server command. */
fun protectionEditError(capabilities: BrokerCapabilities, position: Position, sl: Long?, tp: Long?): String? {
    val changes = listOf(position.stopLoss to sl, position.takeProfit to tp).filter { it.first != it.second }
    return when {
        !capabilities.modifyProtection || !position.protectionEditable -> "当前保护单不可编辑"
        changes.isEmpty() -> "保护价尚未改变"
        !capabilities.atomicProtectionEdit && changes.size > 1 -> "当前交易商每次只允许修改一条保护线"
        !capabilities.removeProtection && changes.any { it.second == null } -> "当前交易商不支持在此移除保护，请保留原价"
        !capabilities.addProtection && changes.any { it.first == null } -> "当前交易商不支持在此新增保护"
        else -> null
    }
}
