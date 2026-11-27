package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CopyPendingMutationSubmission {
    data object Accepted : CopyPendingMutationSubmission
    data class Stopped(val reason: String) : CopyPendingMutationSubmission
    data class Review(val reason: String) : CopyPendingMutationSubmission
}

/** Sends one linked Demo mutation only after a confirmed, app-originated leader mutation. */
internal class CopyPendingMutationSubmitter(
    private val registry: BrokerRegistry,
    private val risk: AccountRiskController,
    private val sources: CopySourceOrders,
    private val sourceMutations: CopySourceMutationJournal,
    private val links: CopyPendingOrderJournal,
    private val journal: CopyPendingMutationJournal,
) {
    suspend fun submit(broker: BrokerPlugin, leaderId: String, groupId: String, followerId: String,
        sourceMutationId: String, requestId: String): CopyPendingMutationSubmission {
        fun stopped(reason: String) = CopyPendingMutationSubmission.Stopped(reason)
        if (!broker.descriptor.id.startsWith("tradovate-") || broker.descriptor.environment != BrokerEnvironment.REMOTE_DEMO ||
            registry.plugins.none { it === broker } || requestId.isBlank() || leaderId == followerId) return stopped("仅允许已连接的 Tradovate Demo")
        val source = try { broker.openSession(leaderId) } catch (_: Exception) { return stopped("主账户不可用") }
        val target = try { broker.openSession(followerId) } catch (_: Exception) { return stopped("跟随账户不可用") }
        data class Prepared(val revision: Long, val epoch: Long, val source: CopySourceMutation,
            val followerOrder: PendingOrder, val action: CopyOrderMutation)

        suspend fun prepared(): Prepared? {
            if (registry.plugins.none { it === broker } || !broker.market.value.let { it.connected && it.tradingReady } ||
                source.account.readOnly || target.account.readOnly || broker.catalog.value.accounts.none { it.id == leaderId && !it.readOnly } ||
                broker.catalog.value.accounts.none { it.id == followerId && !it.readOnly }) return null
            val leader = source.state.value
            val follower = target.state.value
            val revision = leader.copyRevision ?: return null
            val epoch = leader.copyEpoch ?: return null
            if (follower.copyRevision != revision || follower.copyEpoch != epoch || !leader.copyPositionsComplete || !follower.copyPositionsComplete ||
                !leader.copyOrdersComplete || !follower.copyOrdersComplete) return null
            val mutations = sourceMutations.read() ?: return null
            val sameGroup = mutations.filter { it.brokerId == broker.descriptor.id && it.leaderId == leaderId && it.groupId == groupId }
            if (sameGroup.any { it.state == CopySourceMutationState.INTENDED || it.state == CopySourceMutationState.UNCERTAIN }) return null
            val origin = sameGroup.singleOrNull { it.requestId == sourceMutationId && it.state == CopySourceMutationState.CONFIRMED } ?: return null
            if (sources.acceptedSource(broker.descriptor.id, leaderId, groupId, origin.sourceOrderId) == null) return null
            val saved = links.read()?.singleOrNull { it.brokerId == broker.descriptor.id && it.leaderId == leaderId &&
                it.sourceOrderId == origin.sourceOrderId && it.followerId == followerId && it.state == CopyPendingState.ACCEPTED } ?: return null
            val orderId = saved.followerOrderId ?: return null
            val order = follower.orders.singleOrNull { it.id == orderId } ?: return null
            val link = CopyPendingLink(origin.sourceOrderId, orderId, followerId)
            val action = when (origin.kind) {
                CopySourceMutationKind.CHANGE_ENTRY_PRICE -> CopyOrderMutationPlanner.changePrice(link, origin.sourceOrderId, followerId,
                    leader.orders.singleOrNull { it.id == origin.sourceOrderId }, order, origin.newPrice ?: return null, true)
                CopySourceMutationKind.CANCEL_ENTRY -> CopyOrderMutationPlanner.cancel(link, origin.sourceOrderId, followerId,
                    leader.copyOrderLifecycle.singleOrNull { it.id == origin.sourceOrderId }, order, true)
            }
            if (action is CopyOrderMutation.Stop) return null
            return Prepared(revision, epoch, origin, order, action)
        }

        val first = prepared() ?: return stopped("主单修改、账户或跟随订单状态不可确认")
        val automated = try { risk.guard(broker, broker.openAutomatedSession(followerId, first.revision) ?: return stopped("券商不支持自动订单")) }
            catch (_: Exception) { return stopped("跟随账户不可用") }
        val intent = CopyPendingMutation(broker.descriptor.id, leaderId, groupId, sourceMutationId, followerId, first.followerOrder.id,
            requestId, first.source.kind, first.followerOrder.version, first.source.newPrice)
        if (!journal.begin(intent)) return stopped("操作已记录或日志保存失败，未发送")
        if (prepared() != first) return CopyPendingMutationSubmission.Review("账户或订单已变化，未发送；请核对已保存记录")
        val result = try { withTimeoutOrNull(20_000) {
            when (val action = first.action) {
                is CopyOrderMutation.ChangePrice -> automated.modifyOrder(requestId, action.orderId, action.expectedVersion, action.price)
                is CopyOrderMutation.Cancel -> automated.cancelOrder(requestId, action.orderId)
                is CopyOrderMutation.Stop -> return@withTimeoutOrNull TradeResult(false, action.reason)
            }
        } } catch (cancel: CancellationException) {
            withContext(NonCancellable) { journal.uncertain(requestId) }
            throw cancel
        } catch (_: Exception) { null }
        if (result == null || result.outcome == CommandOutcome.UNKNOWN || (result.outcome == CommandOutcome.REJECTED && result.accepted) ||
            (result.outcome == CommandOutcome.ACCEPTED && !result.accepted)) {
            journal.uncertain(requestId)
            return CopyPendingMutationSubmission.Review("跟随订单操作结果未知，未重发；请核对券商")
        }
        if (result.outcome == CommandOutcome.REJECTED) {
            if (!journal.rejected(requestId)) return CopyPendingMutationSubmission.Review("拒绝结果无法保存，请核对账户")
            return stopped("跟随订单操作被拒绝：${result.message}")
        }
        if (!journal.accepted(requestId)) return CopyPendingMutationSubmission.Review("操作已受理但记录保存失败，请核对")
        return CopyPendingMutationSubmission.Accepted
    }
}
