package com.unitrading.app

import com.unitrading.broker.Position

internal sealed interface CopyProtectionAction {
    data class Change(val positionId: String, val version: Long, val stopLoss: Long?, val takeProfit: Long?) : CopyProtectionAction
    data class Cancel(val orderId: String) : CopyProtectionAction
    data object Unchanged : CopyProtectionAction
    data class Stop(val reason: String) : CopyProtectionAction
}

/** Plans one linked protection change at a time. The caller must prove a UniTrading-originated source mutation. */
internal object CopyProtectionPlanner {
    fun plan(originConfirmed: Boolean, source: Position?, follower: Position?, tickSize: Long): CopyProtectionAction {
        fun stop(reason: String) = CopyProtectionAction.Stop(reason)
        if (!originConfirmed) return stop("未确认由 UniTrading 发出的保护单修改")
        if (source == null || follower == null || source.symbol != follower.symbol || source.side != follower.side ||
            source.lotsHundredths <= 0 || follower.lotsHundredths <= 0 || tickSize <= 0) return stop("仓位不匹配，请先核对")
        if (!source.protectionEditable || !follower.protectionEditable) return stop("保护单关联尚未确认")
        if (listOfNotNull(source.stopLoss, source.takeProfit).any { it <= 0 || it % tickSize != 0L }) return stop("主账户保护价格不符合合约规格")
        val stopChanged = source.stopLoss != follower.stopLoss
        val takeChanged = source.takeProfit != follower.takeProfit
        if (!stopChanged && !takeChanged) return CopyProtectionAction.Unchanged
        // Deleting an existing leg uses the linked broker order, with subsequent re-read of the sibling OCO leg.
        if (stopChanged && source.stopLoss == null) return follower.stopLossOrderId?.takeIf { it.isNotBlank() }
            ?.let { CopyProtectionAction.Cancel(it) } ?: stop("无法确认跟随账户止损订单")
        if (takeChanged && source.takeProfit == null) return follower.takeProfitOrderId?.takeIf { it.isNotBlank() }
            ?.let { CopyProtectionAction.Cancel(it) } ?: stop("无法确认跟随账户止盈订单")
        // Broker operations affect one leg at a time; a fresh position version is needed before the next change.
        return if (stopChanged) CopyProtectionAction.Change(follower.id, follower.version, source.stopLoss, follower.takeProfit)
            else CopyProtectionAction.Change(follower.id, follower.version, follower.stopLoss, source.takeProfit)
    }
}
