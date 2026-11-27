package com.unitrading.app

import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.Side

/** Read-only planning data. A missing or incomplete broker snapshot must never be treated as a flat account. */
internal data class CopyAccountView(
    val snapshot: AccountSnapshot,
    val connected: Boolean,
    val synchronized: Boolean,
    val positionsComplete: Boolean,
    val ordersComplete: Boolean,
    val readOnly: Boolean,
)

/** With fixedContracts set, hold that many contracts while the leader has a position. */
internal data class CopyFollower(val accountId: String, val multiplier: Int, val maxContracts: Int, val fixedContracts: Int? = null)
internal data class CopyPlanSettings(val leaderId: String, val followers: List<CopyFollower>, val symbol: String)
internal data class CopyAdjustment(val accountId: String, val side: Side, val contracts: Int, val targetContracts: Int)
internal sealed interface CopyPlan {
    data class Ready(val adjustments: List<CopyAdjustment>) : CopyPlan
    data class Blocked(val reason: String) : CopyPlan
}

/**
 * Pure, non-executing planner for one dated contract and one login. The caller must obtain the readiness and completeness
 * flags from the broker, pin the exact authorized account identities, serialize commands and wait for confirmed fills.
 * A planned adjustment is not authorization to send a trade. The first usable implementation remains Demo-only until
 * command outcomes, reconnect and device lifecycle have been verified with the broker.
 */
internal object CopyTradePlanner {
    fun initial(settings: CopyPlanSettings, views: Map<String, CopyAccountView>): CopyPlan {
        validate(settings, views)?.let { return CopyPlan.Blocked(it) }
        val selected = listOf(settings.leaderId) + settings.followers.map { it.accountId }
        if (selected.any { views.getValue(it).snapshot.positions.isNotEmpty() })
            return CopyPlan.Blocked("启动跟单前，主账户和跟随账户必须全部空仓")
        return CopyPlan.Ready(emptyList())
    }

    /**
     * expectedFollowerPositions are the last *confirmed* positions; they must never be advanced on an accepted order
     * alone. A mismatch stops the group so a manual change or partial/unknown execution cannot trigger a second order.
     */
    fun plan(settings: CopyPlanSettings, views: Map<String, CopyAccountView>, expectedFollowerPositions: Map<String, Int>): CopyPlan {
        validate(settings, views)?.let { return CopyPlan.Blocked(it) }
        if (expectedFollowerPositions.keys != settings.followers.map { it.accountId }.toSet())
            return CopyPlan.Blocked("缺少跟随账户的已确认仓位")
        val leader = net(views.getValue(settings.leaderId).snapshot, settings.symbol)
            ?: return CopyPlan.Blocked("主账户仓位不完整或数量无效")
        val adjustments = mutableListOf<CopyAdjustment>()
        for (follower in settings.followers) {
            val snapshot = views.getValue(follower.accountId).snapshot
            val current = net(snapshot, settings.symbol) ?: return CopyPlan.Blocked("跟随账户仓位不完整或数量无效")
            if (current != expectedFollowerPositions[follower.accountId])
                return CopyPlan.Blocked("跟随账户仓位与已确认仓位不一致，请先核对")
            val target = if (follower.fixedContracts != null) {
                follower.fixedContracts.toLong() * when { leader > 0 -> 1; leader < 0 -> -1; else -> 0 }
            } else leader.toLong() * follower.multiplier.toLong()
            if (target !in -follower.maxContracts.toLong()..follower.maxContracts.toLong())
                return CopyPlan.Blocked("目标仓位超过跟随账户手数上限")
            // Never reverse in one order: first close, then wait for broker-confirmed flat before opening the other side.
            val next = if (current != 0 && target != 0L && (current > 0) != (target > 0)) 0 else target.toInt()
            val delta = next.toLong() - current.toLong()
            if (delta != 0L) adjustments += CopyAdjustment(follower.accountId,
                if (delta > 0) Side.BUY else Side.SELL, kotlin.math.abs(delta).toInt(), next)
        }
        return CopyPlan.Ready(adjustments)
    }

    private fun validate(settings: CopyPlanSettings, views: Map<String, CopyAccountView>): String? {
        if (settings.leaderId.isBlank() || settings.symbol.isBlank() || settings.symbol.startsWith('@')) return "请选择有效账户和具体到期合约"
        if (settings.followers.isEmpty() || settings.followers.any { it.accountId.isBlank() || it.multiplier !in 1..100 ||
                it.maxContracts !in 1..1000 || (it.fixedContracts != null && it.fixedContracts !in 1..it.maxContracts) })
            return "请选择跟随账户，设置有效倍率和手数上限"
        val selected = listOf(settings.leaderId) + settings.followers.map { it.accountId }
        if (selected.distinct().size != selected.size) return "主账户和跟随账户不能重复"
        for (id in selected) {
            val view = views[id] ?: return "账户数据不可用"
            if (view.snapshot.accountId != id || !view.connected || !view.synchronized || !view.positionsComplete || !view.ordersComplete || view.readOnly)
                return "账户未连接、未同步或数据不完整"
            if (view.snapshot.orders.isNotEmpty()) return "存在挂单或保护单，请先处理并核对"
            if (view.snapshot.positions.any { it.stopLoss != null || it.takeProfit != null }) return "存在止盈止损，当前规划器不支持同步保护单"
            if (view.snapshot.positions.any { it.symbol != settings.symbol }) return "存在其他合约仓位，请先核对"
        }
        return null
    }

    private fun net(snapshot: AccountSnapshot, symbol: String): Int? {
        val positions = snapshot.positions.filter { it.symbol == symbol }
        if (positions.size > 1) return null
        val position = positions.singleOrNull() ?: return 0
        if (position.lotsHundredths <= 0 || position.lotsHundredths % 100L != 0L || position.lotsHundredths > 100_000L) return null
        val quantity = (position.lotsHundredths / 100L).toInt()
        return if (position.side == Side.BUY) quantity else -quantity
    }
}
