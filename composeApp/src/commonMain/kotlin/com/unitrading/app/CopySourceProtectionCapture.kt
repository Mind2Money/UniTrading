package com.unitrading.app

import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.CommandOutcome
import com.unitrading.broker.TradeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Records an app-originated leader protection request; the follower is never dispatched from here. */
internal class CopySourceProtectionCapture(private val journal: CopySourceProtectionJournal, private val stop: (String) -> Unit) {
    suspend fun submit(row: CopySourceProtection, snapshot: AccountSnapshot, active: Boolean, send: suspend () -> TradeResult): TradeResult {
        if (!active) return send()
        if (!journal.begin(row, snapshot)) {
            stop("主账户保护单来源或状态无法确认，跟单已停止")
            return TradeResult(false, "无法保存跟单记录，未提交主账户保护操作")
        }
        val result = try { send() } catch (cancel: CancellationException) {
            withContext(NonCancellable) { journal.uncertain(row.requestId); stop("主账户保护操作结果未知，请核对") }
            throw cancel
        } catch (_: Exception) {
            journal.uncertain(row.requestId)
            stop("主账户保护操作结果未知，请核对")
            return TradeResult(false, "主账户保护操作结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN)
        }
        val inconsistent = (result.outcome == CommandOutcome.ACCEPTED && !result.accepted) ||
            (result.outcome == CommandOutcome.REJECTED && result.accepted)
        if (inconsistent) {
            journal.uncertain(row.requestId)
            stop("主账户保护操作回执不一致，请核对")
            return TradeResult(false, "主账户保护操作回执不一致，请在券商端核对", outcome = CommandOutcome.UNKNOWN)
        }
        val recorded = when (result.outcome) {
            CommandOutcome.ACCEPTED -> journal.accepted(row.requestId)
            CommandOutcome.REJECTED -> journal.rejected(row.requestId)
            CommandOutcome.UNKNOWN -> journal.uncertain(row.requestId)
        }
        if (!recorded || result.outcome == CommandOutcome.UNKNOWN) {
            stop("主账户保护操作记录不完整或结果未知，请核对")
            return result.copy(message = "${result.message}；跟单已停止，请核对账户")
        }
        return result
    }
}
