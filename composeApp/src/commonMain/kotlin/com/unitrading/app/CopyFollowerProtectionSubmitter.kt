package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

internal sealed interface CopyFollowerProtectionSubmission {
    data object Accepted : CopyFollowerProtectionSubmission
    data object Unchanged : CopyFollowerProtectionSubmission
    data class Stopped(val reason: String) : CopyFollowerProtectionSubmission
    data class Review(val reason: String) : CopyFollowerProtectionSubmission
}

/** Isolated Demo executor; the caller must stop only the affected follower on failure. */
internal class CopyFollowerProtectionSubmitter(private val registry: BrokerRegistry, private val risk: AccountRiskController,
    private val origins: CopySourceProtectionJournal, private val journal: CopyFollowerProtectionJournal,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    suspend fun submit(broker: BrokerPlugin, leaderId: String, groupId: String, followerId: String,
        sourceMutationId: String, requestId: String): CopyFollowerProtectionSubmission {
        fun stopped(reason: String) = CopyFollowerProtectionSubmission.Stopped(reason)
        if (!broker.descriptor.id.startsWith("tradovate-") || broker.descriptor.environment != BrokerEnvironment.REMOTE_DEMO ||
            registry.plugins.none { it === broker } || requestId.isBlank() || leaderId == followerId) return stopped("当前仅可验证 Tradovate Demo")
        val source = try { broker.openSession(leaderId) } catch (_: Exception) { return stopped("主账户不可用") }
        val target = try { broker.openSession(followerId) } catch (_: Exception) { return stopped("跟随账户不可用") }
        data class Prepared(val revision: Long, val epoch: Long, val origin: CopySourceProtection,
            val source: Position, val follower: Position, val action: CopyProtectionAction)
        suspend fun prepared(): Prepared? {
            if (registry.plugins.none { it === broker } || !broker.market.value.let { it.connected && it.tradingReady } ||
                source.account.readOnly || target.account.readOnly || broker.catalog.value.accounts.none { it.id == leaderId && !it.readOnly } ||
                broker.catalog.value.accounts.none { it.id == followerId && !it.readOnly }) return null
            val sourceSnapshot = source.state.value
            val targetSnapshot = target.state.value
            val rev = sourceSnapshot.copyRevision ?: return null
            val epoch = sourceSnapshot.copyEpoch ?: return null
            if (targetSnapshot.copyRevision != rev || targetSnapshot.copyEpoch != epoch || !sourceSnapshot.copyPositionsComplete ||
                !targetSnapshot.copyPositionsComplete || !sourceSnapshot.copyOrdersComplete || !targetSnapshot.copyOrdersComplete) return null
            val all = origins.read() ?: return null
            val group = all.filter { it.brokerId == broker.descriptor.id && it.leaderId == leaderId && it.groupId == groupId }
            if (group.any { it.state in setOf(CopySourceProtectionState.INTENDED, CopySourceProtectionState.ACCEPTED, CopySourceProtectionState.UNCERTAIN) }) return null
            val origin = group.singleOrNull { it.requestId == sourceMutationId && it.state == CopySourceProtectionState.CONFIRMED } ?: return null
            val leaderPosition = sourceSnapshot.positions.singleOrNull { it.id == origin.positionId } ?: return null
            val followerPosition = targetSnapshot.positions.singleOrNull { it.symbol == origin.symbol } ?: return null
            if (epoch != origin.epoch || leaderPosition.symbol != origin.symbol || leaderPosition.side != origin.expectedSide ||
                leaderPosition.lotsHundredths != origin.expectedLotsHundredths || leaderPosition.version <= origin.previousVersion ||
                leaderPosition.stopLoss != origin.requestedStop || leaderPosition.takeProfit != origin.requestedTarget) return null
            val spec = broker.catalog.value.instruments.singleOrNull { it.symbol == origin.symbol } ?: return null
            val plan = CopyProtectionPlanner.plan(true, leaderPosition, followerPosition, spec.tickSize)
            if (plan is CopyProtectionAction.Stop) return null
            if (plan is CopyProtectionAction.Change) {
                val quote = broker.market.value.quotes[origin.symbol] ?: return null
                if (quote.bid <= 0 || quote.ask < quote.bid || now() - quote.timeMs !in -5_000L..15_000L ||
                    positionProtectionError(spec, followerPosition, quote, plan.stopLoss, plan.takeProfit) != null) return null
            }
            return Prepared(rev, epoch, origin, leaderPosition, followerPosition, plan)
        }
        val first = prepared() ?: return stopped("主账户保护操作、当前行情或跟随账户状态不可确认，请核对")
        if (first.action == CopyProtectionAction.Unchanged) return CopyFollowerProtectionSubmission.Unchanged
        val follower = first.follower
        val (leg, nextStop, nextTarget) = when (val action = first.action) {
            is CopyProtectionAction.Change -> if (action.stopLoss != follower.stopLoss)
                Triple(CopyProtectionLeg.STOP, action.stopLoss, action.takeProfit)
                else Triple(CopyProtectionLeg.TARGET, action.stopLoss, action.takeProfit)
            is CopyProtectionAction.Cancel -> when (action.orderId) {
                follower.stopLossOrderId -> Triple(CopyProtectionLeg.STOP, null, follower.takeProfit)
                follower.takeProfitOrderId -> Triple(CopyProtectionLeg.TARGET, follower.stopLoss, null)
                else -> return stopped("无法确认目标保护单")
            }
            else -> return stopped("保护操作不可用")
        }
        val automated = try { risk.guard(broker, broker.openAutomatedSession(followerId, first.revision) ?: return stopped("券商不支持自动保护操作")) }
            catch (_: Exception) { return stopped("跟随账户不可用") }
        val intent = CopyFollowerProtection(broker.descriptor.id, leaderId, groupId, sourceMutationId, followerId, follower.id,
            follower.symbol, leg, requestId, follower.version, first.revision, first.epoch, follower.side, follower.lotsHundredths,
            follower.stopLoss, follower.takeProfit, nextStop, nextTarget)
        if (!journal.begin(intent, target.state.value)) return stopped("来源或跟随成交不可确认，或操作已记录")
        if (prepared() != first) return CopyFollowerProtectionSubmission.Review("账户状态已变化，未发送；请核对记录")
        val result = try { withTimeoutOrNull(20_000) {
            when (val action = first.action) {
                is CopyProtectionAction.Change -> automated.modifyProtection(requestId, action.positionId, action.version, action.stopLoss, action.takeProfit)
                is CopyProtectionAction.Cancel -> automated.cancelOrder(requestId, action.orderId)
                else -> TradeResult(false, "保护操作不可用")
            }
        } } catch (cancel: CancellationException) {
            withContext(NonCancellable) { journal.uncertain(requestId) }
            throw cancel
        } catch (_: Exception) { null }
        if (result == null || result.outcome == CommandOutcome.UNKNOWN || (result.outcome == CommandOutcome.REJECTED && result.accepted) ||
            (result.outcome == CommandOutcome.ACCEPTED && !result.accepted)) {
            journal.uncertain(requestId)
            return CopyFollowerProtectionSubmission.Review("跟随保护操作结果未知，未重发；请核对券商")
        }
        if (result.outcome == CommandOutcome.REJECTED) {
            if (!journal.rejected(requestId)) return CopyFollowerProtectionSubmission.Review("拒绝结果无法保存，请核对账户")
            return stopped("跟随保护操作被拒绝：${result.message}")
        }
        if (!journal.accepted(requestId)) return CopyFollowerProtectionSubmission.Review("操作已受理但记录保存失败，请核对")
        return CopyFollowerProtectionSubmission.Accepted
    }
}
