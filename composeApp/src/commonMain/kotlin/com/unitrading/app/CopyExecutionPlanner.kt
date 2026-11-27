package com.unitrading.app

/** A broker execution is eligible only after its exact source order has been registered by this application. */
internal data class CopySourceFill(
    val executionId: String,
    val orderId: String,
    val accountId: String,
    val signedContracts: Int,
    val leaderBefore: Int,
    val leaderAfter: Int,
)

internal sealed interface CopyFillPlan {
    data class Ready(val followerAfter: Int, val closeContracts: Int, val openContracts: Int) : CopyFillPlan
    data class Blocked(val reason: String) : CopyFillPlan
}

/** Pure calculation only: it never submits an order or decides that an accepted order has filled. */
internal object CopyExecutionPlanner {
    fun plan(fill: CopySourceFill, sourceOrders: Set<String>, leaderId: String, follower: CopyFollower,
        followerBefore: Int): CopyFillPlan {
        fun stop(reason: String) = CopyFillPlan.Blocked(reason)
        if (fill.executionId.isBlank() || fill.orderId.isBlank() || fill.accountId != leaderId || fill.orderId !in sourceOrders)
            return stop("成交不是本软件登记的主账户订单")
        if (follower.accountId == leaderId || follower.accountId.isBlank() || follower.multiplier !in 1..100 ||
            follower.maxContracts !in 1..1000 || (follower.fixedContracts != null && follower.fixedContracts !in 1..follower.maxContracts))
            return stop("跟随账户数量设置无效")
        if (fill.signedContracts == 0 || fill.signedContracts.toLong() !in -100_000L..100_000L ||
            fill.leaderBefore.toLong() + fill.signedContracts.toLong() != fill.leaderAfter.toLong() ||
            fill.leaderBefore.toLong() !in -100_000L..100_000L || fill.leaderAfter.toLong() !in -100_000L..100_000L ||
            followerBefore.toLong() !in -follower.maxContracts.toLong()..follower.maxContracts.toLong())
            return stop("主账户成交或跟随账户仓位无效")
        fun sign(value: Int) = when { value > 0 -> 1; value < 0 -> -1; else -> 0 }
        val priorDirection = sign(fill.leaderBefore)
        val direction = sign(fill.signedContracts)
        if (followerBefore != 0 && (priorDirection == 0 || sign(followerBefore) != priorDirection))
            return stop("跟随账户仓位方向与主账户不一致")
        val fillAbs = kotlin.math.abs(fill.signedContracts.toLong())
        val reduction = if (priorDirection != 0 && direction != priorDirection) minOf(fillAbs, kotlin.math.abs(fill.leaderBefore.toLong())) else 0L
        val addition = fillAbs - reduction
        val remainingLeader = fill.leaderBefore.toLong() + direction * reduction
        val flatOrReversed = reduction > 0 && remainingLeader == 0L
        val requestedClose = if (reduction == 0L) 0L else if (flatOrReversed) kotlin.math.abs(followerBefore.toLong())
            else follower.fixedContracts?.toLong() ?: reduction * follower.multiplier.toLong()
        val close = minOf(requestedClose, kotlin.math.abs(followerBefore.toLong()))
        val open = if (addition == 0L) 0L else follower.fixedContracts?.toLong() ?: addition * follower.multiplier.toLong()
        val afterClose = followerBefore.toLong() + direction * close
        val after = afterClose + direction * open
        if (after !in -follower.maxContracts.toLong()..follower.maxContracts.toLong()) return stop("跟随账户目标仓位超过手数上限")
        return CopyFillPlan.Ready(after.toInt(), close.toInt(), open.toInt())
    }
}

/** Duplicate broker events are ignored; a conflicting replay of the same execution stops the group. */
internal class CopyFillDeduplicator {
    private val observed = mutableMapOf<String, CopySourceFill>()
    fun observe(fill: CopySourceFill): Boolean? {
        val prior = observed[fill.executionId]
        if (prior != null) return if (prior == fill) false else null
        if (fill.executionId.isBlank()) return null
        observed[fill.executionId] = fill
        return true
    }
}
