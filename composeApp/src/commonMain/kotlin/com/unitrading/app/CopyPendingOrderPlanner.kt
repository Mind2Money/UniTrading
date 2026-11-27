package com.unitrading.app

import com.unitrading.broker.*

internal sealed interface CopyPendingPreparation {
    data class Ready(val request: OrderRequest, val sourceOrderId: String, val sourceVersion: Long) : CopyPendingPreparation
    data class Stop(val reason: String) : CopyPendingPreparation
}

/** Prepare an independent broker-held limit or stop entry; it does not send an order. */
internal object CopyPendingOrderPlanner {
    fun prepare(leaderId: String, registeredSourceIds: Set<String>, source: PendingOrder, follower: CopyFollower,
        spec: Instrument, quote: Quote?, nowMs: Long): CopyPendingPreparation {
        fun stop(reason: String) = CopyPendingPreparation.Stop(reason)
        if (leaderId.isBlank() || follower.accountId.isBlank() || leaderId == follower.accountId || source.id !in registeredSourceIds)
            return stop("订单不是软件登记的主账户订单")
        val order = source.request
        if (source.role != OrderRole.ENTRY || !source.editable || source.filledLotsHundredths != 0L || source.version <= 0 ||
            order.kind !in setOf(OrderKind.LIMIT, OrderKind.STOP) || order.lotsHundredths <= 0 || order.lotsHundredths % 100 != 0L)
            return stop("主挂单已部分成交或状态不可确认")
        if (spec.symbol != order.symbol || spec.isContinuous || spec.tickSize <= 0 || order.entryPrice == null ||
            listOfNotNull(order.entryPrice, order.stopLoss, order.takeProfit).any { it <= 0 || it % spec.tickSize != 0L })
            return stop("跟随合约或价格不符合规格")
        if ((order.stopLoss != null || order.takeProfit != null) && !source.protectionEditable)
            return stop("主订单的保护单关联未确认")
        if (follower.multiplier !in 1..100 || follower.maxContracts !in 1..1000 ||
            (follower.fixedContracts != null && follower.fixedContracts !in 1..follower.maxContracts)) return stop("跟随数量设置无效")
        val leaderContracts = order.lotsHundredths / 100
        if (leaderContracts > 100_000) return stop("主订单数量无效")
        val quantity = follower.fixedContracts?.toLong() ?: leaderContracts * follower.multiplier
        if (quantity !in 1L..follower.maxContracts.toLong() || quantity * 100 !in spec.minLotsHundredths..spec.maxLotsHundredths)
            return stop("跟随订单数量超过上限或合约规格")
        if (quote == null || quote.bid <= 0 || quote.ask <= 0 || quote.ask < quote.bid || nowMs - quote.timeMs !in 0..15_000)
            return stop("缺少有效报价，不能确认挂单价格")
        val price = order.entryPrice
        val crossed = when (order.kind) {
            OrderKind.LIMIT -> if (order.side == Side.BUY) price >= quote.ask else price <= quote.bid
            OrderKind.STOP -> if (order.side == Side.BUY) price <= quote.ask else price >= quote.bid
            OrderKind.MARKET -> true
        }
        if (crossed) return stop("跟随挂单价格已越过当前市场，未发送")
        return CopyPendingPreparation.Ready(order.copy(lotsHundredths = quantity * 100, automated = true, sourceSymbol = null,
            copyExpectedRevision = null), source.id, source.version)
    }
}
