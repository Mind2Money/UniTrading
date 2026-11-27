package com.unitrading.app

import com.unitrading.broker.CommandOutcome

/** A pure state machine; it never sends a command or assumes broker acceptance means a fill. */
internal sealed interface CopyGateState {
    data object Ready : CopyGateState
    data class Submitted(val requestId: String, val adjustment: CopyAdjustment) : CopyGateState
    data class AwaitingFill(val requestId: String, val adjustment: CopyAdjustment) : CopyGateState
    data class Stopped(val reason: String) : CopyGateState
}

internal class CopyTradeCommandGate {
    var state: CopyGateState = CopyGateState.Ready
        private set

    /** One in flight per group. Ids are unique for the lifetime of the group, including completed commands. */
    private val usedRequestIds = mutableSetOf<String>()
    fun begin(requestId: String, adjustment: CopyAdjustment): Boolean {
        if (state != CopyGateState.Ready || requestId.isBlank() || !usedRequestIds.add(requestId)) return false
        state = CopyGateState.Submitted(requestId, adjustment)
        return true
    }

    fun response(requestId: String, outcome: CommandOutcome) {
        val pending = state as? CopyGateState.Submitted ?: return stop("收到无法匹配的订单回执")
        if (pending.requestId != requestId) return stop("收到无法匹配的订单回执")
        state = if (outcome == CommandOutcome.ACCEPTED) CopyGateState.AwaitingFill(requestId, pending.adjustment)
            else CopyGateState.Stopped("订单被拒绝或结果未知，请核对账户，未自动重试")
    }

    /** Called only with a complete, fresh broker snapshot and final status for this exact request. */
    fun confirmed(requestId: String, accountId: String, position: Int, finalFill: Boolean) {
        val pending = state as? CopyGateState.AwaitingFill ?: return stop("收到无法匹配的成交确认")
        if (pending.requestId != requestId || pending.adjustment.accountId != accountId)
            return stop("收到无法匹配的成交确认")
        if (!finalFill || position != pending.adjustment.targetContracts)
            return stop("成交或仓位不一致，请核对账户，未自动重试")
        state = CopyGateState.Ready
    }

    fun stop(reason: String) { state = CopyGateState.Stopped(reason) }
}
