package com.unitrading.app

import com.unitrading.broker.*

/** A manual start requires a single complete, current broker book for every selected account. */
internal object CopyStartAssessment {
    fun reason(config: CopySavedConfiguration, descriptor: BrokerDescriptor, catalog: BrokerCatalog, market: MarketSnapshot,
        snapshots: Map<String, AccountSnapshot>, unresolvedOperations: Boolean): String? {
        if (descriptor.id != config.brokerId || !descriptor.id.startsWith("tradovate-") ||
            descriptor.environment !in setOf(BrokerEnvironment.REMOTE_DEMO, BrokerEnvironment.LIVE)) return "券商或账户环境不匹配"
        if (!market.connected || !market.tradingReady) return "券商未连接或交易数据尚未准备就绪"
        val ids = listOf(config.leaderId) + config.followers.map { it.accountId }
        if (config.leaderId.isBlank() || config.followers.isEmpty() || ids.any(String::isBlank) || ids.distinct().size != ids.size ||
            config.followers.any { it.multiplier !in 1..100 || it.maxContracts !in 1..1000 ||
                (it.fixedContracts != null && it.fixedContracts !in 1..it.maxContracts) }) return "账户或跟随手数设置无效"
        if (unresolvedOperations) return "存在未确认操作，请先核对"
        val accounts = catalog.accounts.groupBy { it.id }
        if (ids.any { id -> accounts[id]?.singleOrNull()?.let { !it.readOnly } != true }) return "账户不存在或不可交易"
        val selected = ids.map { id -> snapshots[id]?.takeIf { it.accountId == id } ?: return "账户快照缺失" }
        if (selected.any { !it.copyPositionsComplete || !it.copyOrdersComplete || !it.copyOrdersClear || it.copyEpoch == null || it.copyRevision == null })
            return "账户数据不完整或存在挂单，请先核对"
        if (selected.map { it.copyEpoch to it.copyRevision }.distinct().size != 1) return "账户数据版本不一致，请重新核对"
        if (selected.any { it.positions.isNotEmpty() || it.orders.isNotEmpty() }) return "启动前所有账户必须空仓且无挂单"
        return null
    }
}
