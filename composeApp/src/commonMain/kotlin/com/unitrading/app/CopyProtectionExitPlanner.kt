package com.unitrading.app

import com.unitrading.broker.*

internal sealed interface CopyProtectionExitAction {
    data class Cancel(val orderId: String) : CopyProtectionExitAction
    data class Close(val positionId: String, val side: Side, val lotsHundredths: Long) : CopyProtectionExitAction
    data object Finished : CopyProtectionExitAction
    data class Stop(val reason: String) : CopyProtectionExitAction
}

/** Only provide this after the exact closing order is broker-confirmed and its durable intent is reconciled. */
internal data class CopyProtectionCloseEvidence(val orderId: String, val lotsHundredths: Long)

/** Plans a single step only; the caller must persist each intent and confirm it at the broker before planning again. */
internal object CopyProtectionExitPlanner {
    fun fullExit(event: CopyLeaderEvent.ProtectionFill, followerId: String, followerPositionId: String,
        snapshot: AccountSnapshot, expectedEpoch: Long, protectionOrderIds: Set<String>, confirmedCancelledIds: Set<String>,
        previousExecutions: Map<String, Execution>, executionHistoryComplete: Boolean, unresolved: Boolean,
        confirmedClose: CopyProtectionCloseEvidence? = null): CopyProtectionExitAction {
        fun stop(reason: String) = CopyProtectionExitAction.Stop(reason)
        if (event.leaderBefore == 0 || event.signedContracts == 0 || (event.leaderBefore > 0) == (event.signedContracts > 0) ||
            event.leaderBefore.toLong() + event.signedContracts != event.leaderAfter.toLong() ||
            (event.leaderAfter != 0 && (event.leaderBefore > 0) != (event.leaderAfter > 0)) ||
            event.symbol.isBlank() || event.executionId.isBlank() || followerId.isBlank() || followerPositionId.isBlank())
            return stop("主账户保护成交无法确认")
        if (unresolved || !executionHistoryComplete || snapshot.accountId != followerId || snapshot.copyEpoch != expectedEpoch ||
            snapshot.copyRevision == null || !snapshot.copyPositionsComplete || !snapshot.copyOrdersComplete ||
            !protectionOrderIds.containsAll(confirmedCancelledIds) || protectionOrderIds.any(String::isBlank)) return stop("账户或保护单状态无法确认")
        val allFills = snapshot.executions.associateBy { it.id }
        val newFills = snapshot.executions.filter { it.id !in previousExecutions && it.symbol == event.symbol }
        val closeSide = if (event.leaderBefore > 0) Side.SELL else Side.BUY
        val verifiedClose = confirmedClose != null && confirmedClose.orderId.isNotBlank() && confirmedClose.lotsHundredths > 0 &&
            newFills.isNotEmpty() && newFills.all { it.orderId == confirmedClose.orderId && it.side == closeSide && it.lotsHundredths > 0 } &&
            newFills.sumOf { it.lotsHundredths } == confirmedClose.lotsHundredths && snapshot.positions.none { it.symbol == event.symbol }
        if (allFills.size != snapshot.executions.size || previousExecutions.any { (id, row) -> id != row.id || allFills[id] != row } ||
            (newFills.isNotEmpty() && !verifiedClose) || (confirmedClose != null && !verifiedClose))
            return stop("跟随账户出现新的成交或成交历史不完整，请核对")
        val positions = snapshot.positions.filter { it.symbol == event.symbol }
        if (positions.size > 1) return stop("跟随账户仓位无法确认")
        val position = positions.singleOrNull()
        if (position != null && (position.id != followerPositionId || position.lotsHundredths <= 0 ||
                (position.side == Side.BUY) != (event.leaderBefore > 0))) return stop("跟随账户仓位不一致")
        val orders = snapshot.orders.filter { it.request.symbol == event.symbol }
        if (orders.any { !it.isProtection || it.id !in protectionOrderIds || it.positionId != followerPositionId })
            return stop("存在未关联的订单，请先核对")
        val activeIds = orders.map { it.id }.toSet()
        if (activeIds.size != orders.size || activeIds.any { it in confirmedCancelledIds } ||
            protectionOrderIds.any { it !in activeIds && it !in confirmedCancelledIds }) return stop("保护单撤销结果尚未确认")
        val cancel = activeIds.sorted().firstOrNull()
        if (cancel != null) return CopyProtectionExitAction.Cancel(cancel)
        if (position == null && !verifiedClose) return stop("跟随账户已无仓位，但无法确认平仓来源；请核对")
        if (position == null) return if (event.leaderAfter == 0) CopyProtectionExitAction.Finished
            else stop("跟随账户已全平，主账户仍有仓位；请核对后恢复")
        return CopyProtectionExitAction.Close(position.id, if (position.side == Side.BUY) Side.SELL else Side.BUY, position.lotsHundredths)
    }
}
