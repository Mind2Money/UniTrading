package com.unitrading.app

import com.unitrading.broker.OrderRole
import com.unitrading.broker.PendingOrder

/** Explicit broker identifiers are required: a matching price or symbol is never proof of an order link. */
internal data class CopyPendingLink(val leaderOrderId: String, val followerOrderId: String, val followerAccountId: String)

internal sealed interface CopyOrderMutation {
    data class ChangePrice(val orderId: String, val expectedVersion: Long, val price: Long) : CopyOrderMutation
    data class Cancel(val orderId: String) : CopyOrderMutation
    data class Stop(val reason: String) : CopyOrderMutation
}

internal object CopyOrderMutationPlanner {
    private fun linked(link: CopyPendingLink, leaderId: String, followerId: String, follower: PendingOrder?): String? = when {
        link.leaderOrderId.isBlank() || link.followerOrderId.isBlank() || link.followerAccountId.isBlank() -> "缺少订单关联"
        link.leaderOrderId != leaderId || link.followerAccountId != followerId -> "订单或账户关联不匹配"
        follower == null || follower.id != link.followerOrderId -> "跟随订单已成交、撤销或状态不明"
        follower.role != OrderRole.ENTRY || !follower.editable -> "跟随订单不可安全修改"
        follower.filledLotsHundredths > 0 -> "跟随订单已部分成交，请核对"
        else -> null
    }

    fun changePrice(link: CopyPendingLink, leaderId: String, followerId: String, follower: PendingOrder?, price: Long): CopyOrderMutation {
        linked(link, leaderId, followerId, follower)?.let { return CopyOrderMutation.Stop(it) }
        if (price <= 0) return CopyOrderMutation.Stop("目标价格无效")
        return CopyOrderMutation.ChangePrice(follower!!.id, follower.version, price)
    }

    fun cancel(link: CopyPendingLink, leaderId: String, followerId: String, follower: PendingOrder?): CopyOrderMutation {
        // A partially filled linked entry can still have a working remainder. Cancelling that remainder is safe;
        // the executor must separately reconcile the already filled position after broker confirmation.
        if (link.leaderOrderId.isBlank() || link.followerOrderId.isBlank() || link.followerAccountId.isBlank() ||
            link.leaderOrderId != leaderId || link.followerAccountId != followerId || follower == null ||
            follower.id != link.followerOrderId || follower.role != OrderRole.ENTRY)
            return CopyOrderMutation.Stop("跟随订单不存在或关联未确认")
        return CopyOrderMutation.Cancel(follower.id)
    }
}
