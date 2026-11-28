package com.unitrading.app

import com.unitrading.broker.*

internal sealed interface CopyLeaderEvent {
    data class EntryOrExit(val fill: CopySourceFill, val symbol: String) : CopyLeaderEvent
    data class ProtectionFill(val executionId: String, val orderId: String, val entryOrderId: String, val symbol: String,
        val signedContracts: Int, val leaderBefore: Int, val leaderAfter: Int) : CopyLeaderEvent
}
internal sealed interface CopyLeaderFillResult {
    data class Ready(val events: List<CopyLeaderEvent>, val netBySymbol: Map<String, Int>, val observedExecutions: Map<String, Execution>) : CopyLeaderFillResult
    data class Blocked(val reason: String) : CopyLeaderFillResult
}

/** Derives events from complete broker fills and exact app-origin IDs; it never follows an unexplained net-position change. */
internal object CopyLeaderFillAssessment {
    fun assess(snapshot: AccountSnapshot, brokerId: String, groupId: String, origins: List<CopySourceOrder>?,
        previousNet: Map<String, Int>, previouslySeen: Map<String, Execution>, managedProtectionOrders: Map<String, String>,
        executionHistoryComplete: Boolean): CopyLeaderFillResult {
        fun stop(reason: String) = CopyLeaderFillResult.Blocked(reason)
        if (brokerId.isBlank() || groupId.isBlank() || snapshot.accountId.isBlank() || !executionHistoryComplete ||
            !snapshot.copyPositionsComplete || !snapshot.copyOrdersComplete || snapshot.copyRevision == null || snapshot.copyEpoch == null)
            return stop("主账户或成交历史不完整")
        val rows = origins ?: return stop("主账户来源记录无法读取")
        val own = rows.filter { it.brokerId == brokerId && it.leaderId == snapshot.accountId }
        if (own.any { it.state in setOf(CopySourceState.INTENDED, CopySourceState.UNCERTAIN) }) return stop("主账户存在未确认操作")
        val accepted = own.filter { it.groupId == groupId && it.state == CopySourceState.ACCEPTED }
        val sourceMap = accepted.associateBy { it.brokerOrderId }
        if (sourceMap.size != accepted.size || sourceMap.containsKey(null)) return stop("主账户订单关联不完整")
        if (previousNet.any { it.key.isBlank() || it.value == 0 } || previouslySeen.any { (id, fill) -> id.isBlank() || id != fill.id } ||
            snapshot.executions.any { it.id.isBlank() || it.orderId.isBlank() || it.symbol.isBlank() ||
                it.lotsHundredths <= 0 || it.lotsHundredths % 100L != 0L || it.lotsHundredths > 100_000_00L } ||
            snapshot.executions.map { it.id }.distinct().size != snapshot.executions.size) return stop("成交数据不可识别")
        val all = snapshot.executions.associateBy { it.id }
        if (previouslySeen.any { (id, fill) -> all[id] != fill }) return stop("成交历史缺失或已变化，请重新核对")
        val fresh = snapshot.executions.filterNot { it.id in previouslySeen }.sortedWith(compareBy<Execution> { it.timeMs }.thenBy { it.id })
        val lastSeenTime = previouslySeen.values.groupBy { it.symbol }.mapValues { (_, fills) -> fills.maxOf { it.timeMs } }
        if (fresh.any { fill -> lastSeenTime[fill.symbol]?.let { fill.timeMs <= it } == true })
            return stop("出现延迟或顺序不明的历史成交，请重新核对")
        if (fresh.groupBy { it.symbol to it.timeMs }.values.any { fills -> fills.map { it.side }.distinct().size > 1 })
            return stop("成交顺序无法确认，请核对")
        val net = previousNet.toMutableMap()
        val events = mutableListOf<CopyLeaderEvent>()
        for (fill in fresh) {
            val direct = sourceMap[fill.orderId]
            val protectionEntry = managedProtectionOrders[fill.orderId]
            if (direct == null && protectionEntry == null) return stop("出现本软件未登记的主账户成交")
            val origin = direct ?: sourceMap[protectionEntry] ?: return stop("保护订单缺少已确认的主单关联")
            val request = origin.request ?: return stop("主账户原始订单缺失")
            if (request.symbol != fill.symbol || (direct != null && request.side != fill.side) ||
                (protectionEntry != null && request.side == fill.side)) return stop("成交与订单方向或合约不一致")
            val before = net[fill.symbol] ?: 0
            val signed = (fill.lotsHundredths / 100L).toInt() * if (fill.side == Side.BUY) 1 else -1
            if (protectionEntry != null && (before == 0 || (before > 0) != (request.side == Side.BUY) ||
                    kotlin.math.abs(signed.toLong()) > kotlin.math.abs(before.toLong()))) return stop("保护单成交与主账户仓位不一致")
            val afterLong = before.toLong() + signed
            if (afterLong !in -100_000L..100_000L) return stop("主账户仓位数量无效")
            val after = afterLong.toInt()
            if (after == 0) net.remove(fill.symbol) else net[fill.symbol] = after
            if (protectionEntry != null) events += CopyLeaderEvent.ProtectionFill(fill.id, fill.orderId, protectionEntry, fill.symbol, signed, before, after)
            else events += CopyLeaderEvent.EntryOrExit(CopySourceFill(fill.id, fill.orderId, snapshot.accountId, signed, before, after,
                if (request.kind == OrderKind.MARKET) CopySourceExecutionMode.FOLLOW_FILLS else CopySourceExecutionMode.INDEPENDENT_PENDING), fill.symbol)
        }
        val reported = mutableMapOf<String, Int>()
        for (position in snapshot.positions) {
            if (position.symbol.isBlank() || position.lotsHundredths <= 0 || position.lotsHundredths % 100L != 0L ||
                position.lotsHundredths > 100_000_00L || position.symbol in reported) return stop("主账户仓位无法确认")
            reported[position.symbol] = (position.lotsHundredths / 100L).toInt() * if (position.side == Side.BUY) 1 else -1
        }
        if (net != reported) return stop("主账户仓位与成交记录不一致")
        return CopyLeaderFillResult.Ready(events, net.toMap(), all)
    }
}
