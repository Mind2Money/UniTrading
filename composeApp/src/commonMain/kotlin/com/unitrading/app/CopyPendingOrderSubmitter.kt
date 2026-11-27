package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CopyPendingSubmission {
    data class Accepted(val followerOrderId: String) : CopyPendingSubmission
    data class Stopped(val reason: String) : CopyPendingSubmission
    data class Review(val reason: String) : CopyPendingSubmission
}

/** A single Demo-only broker-held entry. It is not connected to a running copier yet. */
internal class CopyPendingOrderSubmitter(
    private val registry: BrokerRegistry,
    private val risk: AccountRiskController,
    private val sources: CopySourceOrders,
    private val journal: CopyPendingOrderJournal,
    private val now: () -> Long,
) {
    suspend fun submit(broker: BrokerPlugin, leaderId: String, groupId: String, follower: CopyFollower, sourceOrderId: String,
        requestId: String): CopyPendingSubmission {
        fun stopped(reason: String) = CopyPendingSubmission.Stopped(reason)
        if (!broker.descriptor.id.startsWith("tradovate-") || broker.descriptor.environment != BrokerEnvironment.REMOTE_DEMO ||
            registry.plugins.none { it === broker } || requestId.isBlank()) return stopped("仅允许已连接的 Tradovate Demo")
        val source = try { broker.openSession(leaderId) } catch (_: Exception) { return stopped("主账户不可用") }
        val target = try { broker.openSession(follower.accountId) }
            catch (_: Exception) { return stopped("跟随账户不可用") }

        suspend fun prepared(): Pair<CopyPendingPreparation.Ready, Long>? {
            if (registry.plugins.none { it === broker } || !broker.market.value.let { it.connected && it.tradingReady } ||
                source.account.readOnly || target.account.readOnly || leaderId == follower.accountId ||
                broker.catalog.value.accounts.none { it.id == leaderId && !it.readOnly } ||
                broker.catalog.value.accounts.none { it.id == follower.accountId && !it.readOnly }) return null
            val leader = source.state.value
            val destination = target.state.value
            val rev = leader.copyRevision ?: return null
            if (destination.copyRevision != rev || leader.copyEpoch == null || leader.copyEpoch != destination.copyEpoch ||
                !leader.copyPositionsComplete || !destination.copyPositionsComplete || !leader.copyOrdersComplete || !destination.copyOrdersComplete) return null
            val originIds = sources.acceptedIds(broker.descriptor.id, leaderId, groupId) ?: return null
            val order = leader.orders.singleOrNull { it.id == sourceOrderId } ?: return null
            val origin = sources.read()?.singleOrNull { it.brokerId == broker.descriptor.id && it.leaderId == leaderId &&
                it.groupId == groupId && it.brokerOrderId == sourceOrderId && it.state == CopySourceState.ACCEPTED } ?: return null
            val sourceRequest = origin.request ?: return null
            if (sourceRequest.symbol != order.request.symbol || sourceRequest.side != order.request.side ||
                sourceRequest.kind != order.request.kind || sourceRequest.lotsHundredths != order.request.lotsHundredths ||
                sourceRequest.entryPrice != order.request.entryPrice || sourceRequest.stopLoss != order.request.stopLoss ||
                sourceRequest.takeProfit != order.request.takeProfit) return null
            if (leader.positions.any { it.symbol == order.request.symbol } || destination.positions.any { it.symbol == order.request.symbol } ||
                destination.orders.any { it.request.symbol == order.request.symbol }) return null
            val spec = broker.catalog.value.instruments.singleOrNull { it.symbol == order.request.symbol } ?: return null
            if (!protectionVerified(order, leader)) return null
            val plan = CopyPendingOrderPlanner.prepare(leaderId, originIds, order, follower, spec,
                broker.market.value.quotes[order.request.symbol], now()) as? CopyPendingPreparation.Ready ?: return null
            val checked = plan.request.copy(copyExpectedRevision = rev)
            if (!target.preview(checked).valid) return null
            return plan.copy(request = checked) to requireNotNull(leader.copyEpoch)
        }

        val first = prepared() ?: return stopped("订单、账户或保护关系不可确认，未发送")
        val plan = first.first
        val revision = plan.request.copyExpectedRevision ?: return stopped("账户版本不可确认")
        val automated = try { risk.guard(broker, broker.openAutomatedSession(follower.accountId, revision) ?: return stopped("券商不支持自动订单")) }
            catch (_: Exception) { return stopped("跟随账户不可用") }
        val intent = CopyPendingOrder(broker.descriptor.id, leaderId, sourceOrderId, plan.sourceVersion, follower.accountId,
            requestId, plan.request)
        if (!journal.begin(intent)) return stopped("订单已记录或日志保存失败，未发送")
        if (prepared() != first) return CopyPendingSubmission.Review("主单或账户状态已变化，未发送；请核对已保存记录")
        val result = try { withTimeoutOrNull(20_000) { automated.placeOrder(requestId, plan.request) } }
            catch (cancel: CancellationException) {
                withContext(NonCancellable) { journal.uncertain(requestId) }
                throw cancel
            } catch (_: Exception) { null }
        if (result == null || result.outcome == CommandOutcome.UNKNOWN || (result.outcome == CommandOutcome.REJECTED && result.accepted) ||
            (result.outcome == CommandOutcome.ACCEPTED && (!result.accepted || result.entityId.isNullOrBlank()))) {
            journal.uncertain(requestId)
            return CopyPendingSubmission.Review("跟随订单结果未知，未重发；请核对券商")
        }
        if (result.outcome == CommandOutcome.REJECTED) {
            if (!journal.rejected(requestId)) return CopyPendingSubmission.Review("拒绝结果无法保存，请核对账户")
            return CopyPendingSubmission.Stopped("跟随订单被拒绝：${result.message}")
        }
        val id = requireNotNull(result.entityId)
        if (!journal.accepted(requestId, id)) return CopyPendingSubmission.Review("跟随订单已受理但记录保存失败，请核对")
        return CopyPendingSubmission.Accepted(id)
    }

    private fun protectionVerified(entry: PendingOrder, snapshot: AccountSnapshot): Boolean {
        val required = listOfNotNull(entry.request.stopLoss?.let { Triple(entry.stopLossOrderId, OrderRole.STOP_LOSS, it) },
            entry.request.takeProfit?.let { Triple(entry.takeProfitOrderId, OrderRole.TAKE_PROFIT, it) })
        val legs = required.map { (id, role, price) ->
            snapshot.orders.singleOrNull { it.id == id && it.role == role && it.request.entryPrice == price } ?: return false
        }
        return legs.size != 2 || (legs[0].ocoGroup != null && legs[0].ocoGroup == legs[1].ocoGroup)
    }
}
