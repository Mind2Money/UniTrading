package com.unitrading.app

import com.unitrading.broker.*

/** Broker-neutral inspection; it never sends orders and does not itself start copy trading. */
internal sealed interface CopyStartPreparation {
    data object Ready : CopyStartPreparation
    data class NeedsCleanup(val accounts: List<CopyAccountExposure>) : CopyStartPreparation
    data class Blocked(val reason: String) : CopyStartPreparation
}

internal data class CopyAccountExposure(val account: AccountInfo, val positions: Int, val orders: Int)

internal object CopyStartPreparationInspector {
    fun inspect(accountIds: List<String>, catalog: BrokerCatalog, market: MarketSnapshot,
        snapshots: Map<String, AccountSnapshot>): CopyStartPreparation {
        fun stop(reason: String) = CopyStartPreparation.Blocked(reason)
        if (accountIds.size < 2 || accountIds.any(String::isBlank) || accountIds.distinct().size != accountIds.size)
            return stop("请先选择不同的主账户和跟随账户")
        if (!market.connected || !market.tradingReady) return stop("券商连接或账户数据尚未准备好")
        val catalogById = catalog.accounts.groupBy { it.id }
        val accounts = accountIds.map { id -> catalogById[id]?.singleOrNull()?.takeIf { !it.readOnly }
            ?: return stop("所选账户不存在、重复或不可交易") }
        val books = accountIds.map { id -> snapshots[id]?.takeIf { it.accountId == id } ?: return stop("所选账户快照缺失") }
        if (books.any { !it.copyPositionsComplete || !it.copyOrdersComplete || it.copyRevision == null || it.copyEpoch == null })
            return stop("所选账户持仓或挂单数据不完整，请同步后再试")
        if (books.map { it.copyEpoch to it.copyRevision }.distinct().size != 1) return stop("账户快照不同步，请重新核对")
        if (books.any { !it.copyOrdersClear && it.orders.isEmpty() }) return stop("券商挂单状态尚未完全确认")
        val exposure = books.mapIndexed { i, book -> CopyAccountExposure(accounts[i], book.positions.size, book.orders.size) }
        return if (exposure.any { it.positions > 0 || it.orders > 0 }) CopyStartPreparation.NeedsCleanup(exposure)
        else CopyStartPreparation.Ready
    }
}
