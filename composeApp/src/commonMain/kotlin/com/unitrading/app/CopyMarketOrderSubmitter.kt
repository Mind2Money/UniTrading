package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CopyMarketSubmission {
    data class Accepted(val followerOrderId: String) : CopyMarketSubmission
    data object NoChange : CopyMarketSubmission
    data class Stopped(val reason: String) : CopyMarketSubmission
    data class Review(val reason: String) : CopyMarketSubmission
}

/** Checkpoint inputs supplied only after the broker's execution history has been proven complete. */
internal data class CopyLeaderFillEvidence(val previousNet: Map<String, Int>, val previouslySeen: Map<String, Execution>,
    val managedProtectionOrders: Map<String, String>, val executionHistoryComplete: Boolean)

/** Sends one journalled Demo market leg; no UI calls this until the complete copier is integrated. */
internal class CopyMarketOrderSubmitter(
    private val registry: BrokerRegistry,
    private val risk: AccountRiskController,
    private val sources: CopySourceOrders,
    private val journal: CopyDispatchJournal,
) {
    suspend fun submit(broker: BrokerPlugin, leaderId: String, groupId: String, follower: CopyFollower, symbol: String,
        fill: CopySourceFill, requestId: String, evidence: CopyLeaderFillEvidence? = null): CopyMarketSubmission {
        fun stopped(reason: String) = CopyMarketSubmission.Stopped(reason)
        if (!broker.descriptor.id.startsWith("tradovate-") || broker.descriptor.environment != BrokerEnvironment.REMOTE_DEMO ||
            registry.plugins.none { it === broker } || requestId.isBlank() || symbol.isBlank()) return stopped("仅允许已连接的 Tradovate Demo")
        val source = try { broker.openSession(leaderId) } catch (_: Exception) { return stopped("主账户不可用") }
        val target = try { broker.openSession(follower.accountId) }
            catch (_: Exception) { return stopped("跟随账户不可用") }

        data class Prepared(val revision: Long, val epoch: Long, val leg: CopyDispatchLeg, val before: Int, val after: Int, val signed: Int)
        fun net(snapshot: AccountSnapshot): Int? {
            val positions = snapshot.positions.filter { it.symbol == symbol }
            if (positions.size > 1) return null
            val p = positions.singleOrNull() ?: return 0
            if (p.lotsHundredths <= 0 || p.lotsHundredths % 100 != 0L || p.lotsHundredths > 100_000L) return null
            val quantity = (p.lotsHundredths / 100).toInt()
            return if (p.side == Side.BUY) quantity else -quantity
        }
        suspend fun prepared(ownIntent: String? = null): Prepared? {
            if (registry.plugins.none { it === broker } || !broker.market.value.let { it.connected && it.tradingReady } ||
                source.account.readOnly || target.account.readOnly || leaderId == follower.accountId ||
                broker.catalog.value.accounts.none { it.id == leaderId && !it.readOnly } ||
                broker.catalog.value.accounts.none { it.id == follower.accountId && !it.readOnly }) return null
            val leader = source.state.value
            val destination = target.state.value
            val dispatches = journal.read() ?: return null
            if (dispatches.any { it.brokerId == broker.descriptor.id && it.followerId == follower.accountId &&
                    it.requestId != ownIntent && it.state in setOf(CopyDispatchState.INTENDED, CopyDispatchState.ACCEPTED, CopyDispatchState.UNCERTAIN) }) return null
            val revision = leader.copyRevision ?: return null
            val epoch = leader.copyEpoch ?: return null
            if (destination.copyRevision != revision || destination.copyEpoch != epoch ||
                !leader.copyPositionsComplete || !destination.copyPositionsComplete || !leader.copyOrdersComplete || !destination.copyOrdersComplete ||
                destination.orders.any { it.request.symbol == symbol } || leader.orders.any { it.request.symbol == symbol && it.isProtection } ||
                leader.positions.any { it.symbol == symbol && (it.stopLoss != null || it.takeProfit != null) } ||
                destination.positions.any { it.symbol == symbol && (it.stopLoss != null || it.takeProfit != null) }) return null
            val origin = sources.acceptedSource(broker.descriptor.id, leaderId, groupId, fill.orderId) ?: return null
            if (origin.kind != OrderKind.MARKET) return null
            val originIds = setOf(fill.orderId)
            val brokerOrderId = origin.brokerOrderId ?: return null
            val sourceRequest = origin.request ?: return null
            if (sourceRequest.symbol != symbol || sourceRequest.stopLoss != null || sourceRequest.takeProfit != null) return null
            if (brokerOrderId !in originIds || fill.mode != CopySourceExecutionMode.FOLLOW_FILLS) return null
            val execution = leader.executions.singleOrNull { it.id == fill.executionId && it.orderId == fill.orderId && it.symbol == symbol } ?: return null
            if (execution.side != sourceRequest.side || execution.lotsHundredths <= 0 || execution.lotsHundredths % 100 != 0L ||
                execution.lotsHundredths / 100 * (if (execution.side == Side.BUY) 1 else -1) != fill.signedContracts.toLong()) return null
            if (evidence == null) {
                if (net(leader) != fill.leaderAfter) return null
            } else {
                val verified = CopyLeaderFillAssessment.assess(leader, broker.descriptor.id, groupId, sources.read(),
                    evidence.previousNet, evidence.previouslySeen, evidence.managedProtectionOrders, evidence.executionHistoryComplete)
                    as? CopyLeaderFillResult.Ready ?: return null
                val next = verified.events.firstOrNull() as? CopyLeaderEvent.EntryOrExit ?: return null
                if (next.fill != fill || next.symbol != symbol) return null
            }
            val before = net(destination) ?: return null
            val plan = CopyExecutionPlanner.plan(fill, originIds, leaderId, follower, before) as? CopyFillPlan.Ready ?: return null
            val direction = if (fill.signedContracts > 0) 1 else -1
            val leg = if (plan.closeContracts > 0) CopyDispatchLeg.CLOSE else CopyDispatchLeg.OPEN
            val signed = direction * if (leg == CopyDispatchLeg.CLOSE) plan.closeContracts else plan.openContracts
            if (signed == 0) return Prepared(revision, epoch, leg, before, before, 0)
            if (leg == CopyDispatchLeg.OPEN) {
                if (dispatches.any { it.brokerId == broker.descriptor.id && it.sourceExecutionId == fill.executionId &&
                        it.followerId == follower.accountId && it.leg == CopyDispatchLeg.CLOSE && it.state != CopyDispatchState.CONFIRMED }) return null
            }
            return Prepared(revision, epoch, leg, before, before + signed, signed)
        }

        val first = prepared() ?: return stopped("成交、来源或账户状态不可确认，未发送")
        if (first.signed == 0) return CopyMarketSubmission.NoChange
        val request = OrderRequest(symbol, if (first.signed > 0) Side.BUY else Side.SELL, OrderKind.MARKET,
            kotlin.math.abs(first.signed.toLong()) * 100, automated = true, copyExpectedRevision = first.revision)
        if (!target.preview(request).valid) return stopped("跟随订单未通过账户校验")
        val automated = try { risk.guard(broker, broker.openAutomatedSession(follower.accountId, first.revision) ?: return stopped("券商不支持自动订单")) }
            catch (_: Exception) { return stopped("跟随账户不可用") }
        val intent = CopyDispatch(broker.descriptor.id, leaderId, fill.orderId, fill.executionId, follower.accountId, first.leg,
            requestId, first.signed, first.before, first.after)
        if (!journal.begin(intent)) return stopped("成交已记录或日志保存失败，未发送")
        if (prepared(requestId) != first) return CopyMarketSubmission.Review("账户状态已变化，未发送；请核对已保存记录")
        val result = try { withTimeoutOrNull(20_000) { automated.placeOrder(requestId, request) } }
            catch (cancel: CancellationException) {
                withContext(NonCancellable) { journal.uncertain(requestId) }
                throw cancel
            } catch (_: Exception) { null }
        if (result == null || result.outcome == CommandOutcome.UNKNOWN || (result.outcome == CommandOutcome.REJECTED && result.accepted) ||
            (result.outcome == CommandOutcome.ACCEPTED && (!result.accepted || result.entityId.isNullOrBlank()))) {
            journal.uncertain(requestId)
            return CopyMarketSubmission.Review("跟随订单结果未知，未重发；请核对券商")
        }
        if (result.outcome == CommandOutcome.REJECTED) {
            if (!journal.rejected(requestId)) return CopyMarketSubmission.Review("拒绝结果无法保存，请核对账户")
            return stopped("跟随订单被拒绝：${result.message}")
        }
        val id = requireNotNull(result.entityId)
        if (!journal.accepted(requestId, id)) return CopyMarketSubmission.Review("跟随订单已受理但记录保存失败，请核对")
        return CopyMarketSubmission.Accepted(id)
    }
}
