package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.Serializable

@Serializable
internal data class CopyRecoveryPosition(val id: String, val symbol: String, val side: Side, val lotsHundredths: Long,
    val entryPrice: Long, val stopLoss: Long?, val takeProfit: Long?, val stopOrderId: String?, val targetOrderId: String?)

@Serializable
internal data class CopyRecoveryOrder(val id: String, val request: OrderRequest, val role: OrderRole, val positionId: String?,
    val filledLotsHundredths: Long, val stopOrderId: String?, val targetOrderId: String?)

@Serializable
internal data class CopyRecoveryExecution(val id: String, val orderId: String, val symbol: String, val side: Side,
    val lotsHundredths: Long, val price: Long, val timeMs: Long)

/** Only broker identifiers, exposure and protective order relationships; never infer a state from balances. */
@Serializable
internal data class CopyRecoveryAccount(val accountId: String, val positions: List<CopyRecoveryPosition>, val orders: List<CopyRecoveryOrder>,
    val executions: List<CopyRecoveryExecution>)

internal data class CopyRecoveryResult(val leaderReason: String?, val followerReasons: Map<String, String?>) {
    val leaderReady get() = leaderReason == null
}

internal object CopyRecoveryAssessment {
    /** Capture only complete, unambiguous broker books. A new epoch on reconnect is allowed, not assumed equivalent. */
    fun checkpoint(snapshot: AccountSnapshot, executionHistoryComplete: Boolean): CopyRecoveryAccount? {
        if (snapshot.accountId.isBlank() || snapshot.copyRevision == null || snapshot.copyEpoch == null ||
            !snapshot.copyPositionsComplete || !snapshot.copyOrdersComplete || !executionHistoryComplete ||
            snapshot.positions.any { it.id.isBlank() || it.symbol.isBlank() || it.lotsHundredths <= 0 || !it.protectionEditable } ||
            snapshot.orders.any { it.id.isBlank() || it.request.symbol.isBlank() || it.role == OrderRole.UNRESOLVED ||
                it.filledLotsHundredths < 0 || it.filledLotsHundredths > it.request.lotsHundredths } ||
            snapshot.executions.any { it.id.isBlank() || it.orderId.isBlank() || it.symbol.isBlank() || it.lotsHundredths <= 0 } ||
            snapshot.positions.map { it.id }.distinct().size != snapshot.positions.size ||
            snapshot.orders.map { it.id }.distinct().size != snapshot.orders.size ||
            snapshot.executions.map { it.id }.distinct().size != snapshot.executions.size) return null
        return CopyRecoveryAccount(snapshot.accountId, snapshot.positions.map {
            CopyRecoveryPosition(it.id, it.symbol, it.side, it.lotsHundredths, it.entryPrice, it.stopLoss, it.takeProfit, it.stopLossOrderId, it.takeProfitOrderId)
        }.sortedBy { it.id }, snapshot.orders.map {
            CopyRecoveryOrder(it.id, it.request, it.role, it.positionId, it.filledLotsHundredths, it.stopLossOrderId, it.takeProfitOrderId)
        }.sortedBy { it.id }, snapshot.executions.map {
            CopyRecoveryExecution(it.id, it.orderId, it.symbol, it.side, it.lotsHundredths, it.price, it.timeMs)
        }.sortedBy { it.id })
    }

    /** Unresolved journal entries always block their affected account; no recovery action is submitted here. */
    fun assess(leaderId: String, expected: Map<String, CopyRecoveryAccount>, observed: Map<String, AccountSnapshot>,
        followerIds: List<String>, sourceUnresolved: Boolean, followerUnresolved: Set<String>,
        executionHistoryComplete: Set<String>): CopyRecoveryResult {
        val ids = listOf(leaderId) + followerIds
        if (leaderId.isBlank() || followerIds.isEmpty() || ids.any(String::isBlank) || ids.distinct().size != ids.size)
            return CopyRecoveryResult("账户配置无效", followerIds.associateWith { "账户配置无效" })
        val leader = observed[leaderId]
        val leaderReason = when {
            sourceUnresolved -> "主账户存在未确认操作"
            expected[leaderId]?.accountId != leaderId -> "主账户缺少恢复记录"
            leader == null -> "主账户尚未同步"
            checkpoint(leader, leaderId in executionHistoryComplete) == null -> "主账户数据或成交历史不完整"
            checkpoint(leader, true) != expected[leaderId] -> "主账户仓位、挂单或成交已变化"
            else -> null
        }
        val results = followerIds.associateWith { id ->
            val snapshot = observed[id]
            when {
                leaderReason != null -> "主账户尚未通过核对"
                id in followerUnresolved -> "跟随账户存在未确认操作"
                expected[id]?.accountId != id -> "跟随账户缺少恢复记录"
                snapshot == null -> "跟随账户尚未同步"
                snapshot.copyRevision != leader?.copyRevision || snapshot.copyEpoch != leader?.copyEpoch -> "账户快照不同步"
                checkpoint(snapshot, id in executionHistoryComplete) == null -> "跟随账户数据或成交历史不完整"
                checkpoint(snapshot, true) != expected[id] -> "跟随账户仓位、挂单或成交已变化"
                else -> null
            }
        }
        return CopyRecoveryResult(leaderReason, results)
    }
}
