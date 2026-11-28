package com.unitrading.app

import com.unitrading.broker.CommandOutcome
import com.unitrading.broker.TradeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Captures an app-originated pending-order change before sending; follower execution is separate. */
internal class CopySourceMutationCapture(private val journal: CopySourceMutationJournal, private val stop: (String) -> Unit) {
    suspend fun submit(row: CopySourceMutation, active: Boolean, send: suspend () -> TradeResult): TradeResult {
        if (!active) return send()
        when (journal.beginResult(row)) {
            CopySourceBeginResult.STORAGE_FAILED -> {
                stop("挂单跟单记录无法保存，相关跟随账户已暂停")
                return try { send().let { it.copy(message = "${it.message}；跟随账户已暂停，请核对") } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { TradeResult(false, "主账户挂单操作结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN) }
            }
            CopySourceBeginResult.UNSAFE -> {
                stop("主账户挂单操作来源或状态无法确认，跟单已暂停")
                return TradeResult(false, "主账户挂单操作无法确认，请核对后再操作")
            }
            CopySourceBeginResult.RECORDED -> Unit
        }
        val result = try { send() } catch (cancel: CancellationException) {
            withContext(NonCancellable) { journal.uncertain(row.requestId); stop("主账户挂单操作结果未知，请核对") }
            throw cancel
        } catch (_: Exception) {
            journal.uncertain(row.requestId)
            stop("主账户挂单操作结果未知，请核对")
            return TradeResult(false, "主账户挂单操作结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN)
        }
        if ((result.outcome == CommandOutcome.ACCEPTED && !result.accepted) ||
            (result.outcome == CommandOutcome.REJECTED && result.accepted)) {
            journal.uncertain(row.requestId); stop("主账户挂单回执不一致，请核对")
            return TradeResult(false, "主账户挂单回执不一致，请在券商端核对", outcome = CommandOutcome.UNKNOWN)
        }
        val recorded = when (result.outcome) {
            CommandOutcome.ACCEPTED -> journal.accepted(row.requestId)
            CommandOutcome.REJECTED -> journal.rejected(row.requestId)
            CommandOutcome.UNKNOWN -> journal.uncertain(row.requestId)
        }
        if (!recorded || result.outcome == CommandOutcome.UNKNOWN) {
            stop("主账户挂单操作记录不完整或结果未知，请核对")
            return result.copy(message = "${result.message}；跟单已暂停，请核对账户")
        }
        return result
    }
}
