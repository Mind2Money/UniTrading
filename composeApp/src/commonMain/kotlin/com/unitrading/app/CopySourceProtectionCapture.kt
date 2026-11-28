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
        when (journal.beginResult(row, snapshot)) {
            CopySourceBeginResult.STORAGE_FAILED -> {
                stop("保护单跟单记录无法保存，相关跟随账户已暂停")
                return try { send().let { it.copy(message = "${it.message}；跟随账户已暂停，请核对") } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { TradeResult(false, "主账户保护操作结果未知，请在券商端核对，未自动重发", outcome = CommandOutcome.UNKNOWN) }
            }
            CopySourceBeginResult.UNSAFE -> {
                stop("主账户保护单来源或状态无法确认，跟单已暂停")
                return TradeResult(false, "主账户保护操作无法确认，请核对后再操作")
            }
            CopySourceBeginResult.RECORDED -> Unit
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
