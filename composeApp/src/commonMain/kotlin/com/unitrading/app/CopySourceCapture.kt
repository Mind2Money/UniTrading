package com.unitrading.app

import com.unitrading.broker.CommandOutcome
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.TradeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Captures only a user-submitted leader order. No follower order is dispatched here. */
internal class CopySourceCapture(private val journal: CopySourceOrders, private val stop: (String) -> Unit) {
    suspend fun submit(brokerId: String, leaderId: String, groupId: String, accountId: String, requestId: String, request: OrderRequest,
        active: Boolean, send: suspend () -> TradeResult): TradeResult {
        if (!active || accountId != leaderId || request.automated) return send()
        when (journal.beginResult(CopySourceOrder(brokerId, leaderId, requestId, groupId, kind = request.kind, request = request))) {
            CopySourceBeginResult.STORAGE_FAILED -> {
                stop("主订单跟单记录无法保存，相关跟随账户已暂停")
                return try { send().let { it.copy(message = "${it.message}；跟随账户已暂停，请核对") } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { TradeResult(false, "主订单结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN) }
            }
            CopySourceBeginResult.UNSAFE -> {
                stop("主订单来源或请求标识无法确认，跟单已暂停")
                return TradeResult(false, "主订单请求无法确认，请核对后再操作")
            }
            CopySourceBeginResult.RECORDED -> Unit
        }
        val result = try { send() } catch (cancel: CancellationException) {
            withContext(NonCancellable) { journal.uncertain(requestId); stop("主订单结果未知，请核对") }
            throw cancel
        } catch (_: Exception) {
            journal.uncertain(requestId)
            stop("主订单结果未知，请核对")
            return TradeResult(false, "主订单结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN)
        }
        if (result.accepted != (result.outcome == CommandOutcome.ACCEPTED) && result.outcome != CommandOutcome.UNKNOWN) {
            journal.uncertain(requestId)
            stop("主订单回执不一致，请核对")
            return TradeResult(false, "主订单回执不一致，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN)
        }
        val recorded = when (result.outcome) {
            CommandOutcome.ACCEPTED -> result.entityId?.takeIf(String::isNotBlank)?.let { journal.accepted(requestId, it) } ?: false
            CommandOutcome.REJECTED -> journal.rejected(requestId)
            CommandOutcome.UNKNOWN -> journal.uncertain(requestId)
        }
        if (!recorded || result.outcome == CommandOutcome.UNKNOWN) {
            stop("主订单回执或来源记录不完整，请核对账户")
            return result.copy(message = "${result.message}；跟单已停止，请核对账户")
        }
        return result
    }
}
