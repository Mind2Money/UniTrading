package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
internal enum class RiskAction { LOCK, FLATTEN }

@Serializable
internal enum class RiskFreeze { DAY, WEEK }

@Serializable
internal data class RiskLimits(
    val loss: Long? = null,
    val profit: Long? = null,
    val action: RiskAction = RiskAction.LOCK,
) {
    fun valid() = (loss != null || profit != null) && (loss == null || loss > 0) && (profit == null || profit > 0)
}

@Serializable
internal data class RiskTrigger(
    val day: LocalDate,
    val pnl: Long,
    val action: RiskAction,
    val flattenStatus: String? = null,
)

@Serializable
internal data class AccountRiskRule(
    val limits: RiskLimits? = null,
    val frozenUntil: LocalDate? = null,
    val trigger: RiskTrigger? = null,
    val orderLimits: AccountOrderLimits = AccountOrderLimits(),
    val brackets: AccountBrackets = AccountBrackets(),
) {
    fun frozen(today: LocalDate): Boolean = frozenUntil?.let { today < it } == true
}

internal fun riskDate(now: Instant): LocalDate = now.toLocalDateTime(TimeZone.of("America/Chicago")).date

internal fun riskFreezeEnd(today: LocalDate, period: RiskFreeze): LocalDate =
    today.plus(if (period == RiskFreeze.DAY) 1 else 7 - today.dayOfWeek.ordinal, DateTimeUnit.DAY)

internal fun riskAmount(text: String): Long? {
    val parts = text.trim().split('.')
    if (parts.size !in 1..2 || parts[0].isEmpty() || parts[0].length > 9 ||
        parts.any { part -> part.any { it !in '0'..'9' } } || (parts.getOrNull(1)?.length ?: 0) > 2) return null
    val cents = parts.getOrNull(1).orEmpty().padEnd(2, '0').toLong()
    return (parts[0].toLong() * 1_000_000L + cents * 10_000L).takeIf { it > 0 }
}

private const val STORE_KEY = "account-risk-v1"
private const val WAITING = "等待执行撤单和平仓"
private const val IN_PROGRESS = "平仓结果待核对，请检查券商持仓和挂单"
private const val FAILED = "未确认全部撤单和平仓，请检查券商持仓和挂单"
private const val FLAT = "券商已确认无持仓、无挂单"

internal data class FlattenTarget(val broker: BrokerPlugin, val account: AccountInfo) {
    val key get() = broker.descriptor.id to account.id
}

internal data class FlattenOutcome(val target: FlattenTarget, val confirmed: Boolean, val message: String)

internal class AccountRiskController(
    private val store: SnapshotStore,
    private val clock: Clock = Clock.System,
) {
    private val storageMutex = Mutex()
    private val bulkTargets = MutableStateFlow(emptySet<Pair<String, String>>())
    private val commandMutexes = mutableMapOf<Pair<String, String>, Mutex>()
    private val initial = runCatching {
        store.read(STORE_KEY)?.let { Json.decodeFromString<Map<String, Map<String, AccountRiskRule>>>(it) }.orEmpty()
    }
    private val mutableRules = MutableStateFlow(initial.getOrDefault(emptyMap()))
    val rules = mutableRules.asStateFlow()
    val storageUnreadable: Boolean get() = initial.isFailure

    fun rule(broker: BrokerPlugin, accountId: String) =
        rules.value[broker.descriptor.id]?.get(accountId) ?: AccountRiskRule()

    fun today() = riskDate(clock.now())

    suspend fun configure(broker: BrokerPlugin, id: String, limits: RiskLimits?, freeze: RiskFreeze? = null): String? =
        storageMutex.withLock {
            val previous = rule(broker, id)
            val date = today()
            when {
                storageUnreadable -> "无法读取风控设置，请检查本机存储。"
                previous.frozen(date) -> "风控设置已冻结，暂不能修改。"
                limits?.valid() == false -> "请至少输入一项有效的盈亏额度。"
                freeze != null && limits?.loss == null -> "冻结设置前请先输入有效的亏损额度。"
                !persist(broker, id, previous.copy(limits = limits, frozenUntil = freeze?.let { riskFreezeEnd(date, it) })) ->
                    "风控设置保存失败，请重试。"
                else -> null
            }
        }

    suspend fun configureOrders(broker: BrokerPlugin, id: String, limits: AccountOrderLimits): String? =
        commands(broker, id).withLock {
            storageMutex.withLock {
                when {
                    storageUnreadable -> "无法读取风控设置，请检查本机存储。"
                    !limits.valid() -> "手数或品种代码不合法。"
                    !persist(broker, id, rule(broker, id).copy(orderLimits = limits)) -> "交易限制保存失败，请重试。"
                    else -> null
                }
            }
        }

    suspend fun configureBrackets(broker: BrokerPlugin, id: String, brackets: AccountBrackets): String? =
        commands(broker, id).withLock {
            storageMutex.withLock {
                when {
                    storageUnreadable -> "无法读取风控设置，请检查本机存储。"
                    !brackets.valid() -> "请输入有效的风险或利润金额。"
                    !persist(broker, id, rule(broker, id).copy(brackets = brackets)) -> "金额止损止盈保存失败，请重试。"
                    else -> null
                }
            }
        }

    private suspend fun persist(broker: BrokerPlugin, id: String, value: AccountRiskRule): Boolean {
        val brokerId = broker.descriptor.id
        val updated = rules.value + (brokerId to (rules.value[brokerId].orEmpty() + (id to value)))
        return withContext(NonCancellable) {
            try {
                withContext(Dispatchers.Default) { store.write(STORE_KEY, Json.encodeToString(updated)) }
                mutableRules.value = updated
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun freshPnl(broker: BrokerPlugin, session: AccountSession): Pair<LocalDate, Long>? {
        val market = broker.market.value
        if (!market.connected || !market.tradingReady) return null
        val pnl = session.state.value.dailyPnl ?: return null
        val age = clock.now().toEpochMilliseconds() - pnl.updatedAtMs
        if (age !in 0..120_000L) return null
        val day = pnl.tradingDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return day to pnl.totalMicros
    }

    fun restriction(broker: BrokerPlugin, session: AccountSession): String? {
        if (storageUnreadable) return "本机风控记录无法读取，已暂停开仓和改单。"
        val rule = rule(broker, session.account.id)
        val pnl = if (rule.trigger != null || rule.limits != null) freshPnl(broker, session) else null
        val trigger = rule.trigger
        if (trigger != null && (pnl == null || pnl.first <= trigger.day)) {
            return "${trigger.day} 交易日已锁定 · ${if (trigger.pnl < 0) "达到亏损额度" else "达到盈利额度"}"
        }
        return if (rule.limits != null && pnl == null) "等待券商当日盈亏数据，已暂停开仓和改单。" else null
    }

    private suspend fun evaluate(broker: BrokerPlugin, session: AccountSession): String? = storageMutex.withLock {
        val id = session.account.id
        restriction(broker, session)?.let { return@withLock it }
        val previous = rule(broker, id)
        val limits = previous.limits
        if (limits == null && previous.trigger == null) return@withLock null
        val (day, pnl) = freshPnl(broker, session) ?: return@withLock "等待券商当日盈亏数据。"
        val lossReached = limits?.loss?.let { pnl <= -it } == true
        val profitReached = limits?.profit?.let { pnl >= it } == true
        if (limits == null || (!lossReached && !profitReached)) {
            if (previous.trigger != null && !persist(broker, id, previous.copy(trigger = null))) {
                return@withLock "新交易日的解锁记录保存失败，请检查本机存储。"
            }
            return@withLock null
        }
        val trigger = RiskTrigger(day, pnl, limits.action, if (limits.action == RiskAction.FLATTEN) WAITING else null)
        if (!persist(broker, id, previous.copy(trigger = trigger))) {
            // Keep this process locked even if storage cannot accept the write. No auto-orders before durable state.
            val locked = previous.copy(trigger = trigger.copy(flattenStatus = "锁定未能保存，请检查存储并手动核对持仓"))
            val brokerId = broker.descriptor.id
            mutableRules.value = rules.value + (brokerId to (rules.value[brokerId].orEmpty() + (id to locked)))
        }
        restriction(broker, session) ?: "子账户已触发交易锁定。"
    }

    private suspend fun commands(broker: BrokerPlugin, id: String): Mutex =
        storageMutex.withLock { commandMutexes.getOrPut(broker.descriptor.id to id) { Mutex() } }

    fun guard(broker: BrokerPlugin, raw: AccountSession): AccountSession = object : AccountSession by raw {
        private suspend fun submit(
            riskIncreasing: Boolean = true,
            checkLimits: () -> String? = { null },
            action: suspend () -> TradeResult,
        ): TradeResult {
            val key = broker.descriptor.id to account.id
            val bulkRefusal = TradeResult(false, "该子账户正在批量撤单和平仓。")
            if (key in bulkTargets.value) return bulkRefusal
            return commands(broker, account.id).withLock {
                if (key in bulkTargets.value) return@withLock bulkRefusal
                if (riskIncreasing) evaluate(broker, raw)?.let { return@withLock TradeResult(false, it) }
                checkLimits()?.let { return@withLock TradeResult(false, it) }
                action()
            }
        }

        private fun checkEdit(orderId: String, newLots: Long? = null): String? {
            val snapshot = raw.state.value
            val order = snapshot.orders.firstOrNull { it.id == orderId } ?: return "挂单已更新，请重新选择。"
            if (order.isProtection) return null
            return rule(broker, account.id).orderLimits.refusal(snapshot,
                order.request.copy(lotsHundredths = newLots ?: order.request.lotsHundredths), order)
        }

        override suspend fun placeOrder(requestId: String, request: OrderRequest) =
            submit(checkLimits = {
                val rule = rule(broker, account.id)
                rule.orderLimits.refusal(raw.state.value, request) ?: if (rule.brackets.enabled) {
                    val spec = broker.instruments.firstOrNull { it.symbol == request.symbol }
                    if (spec == null) "合约规格尚未加载"
                    else {
                        val checked = raw.preview(request)
                        if (!checked.valid) checked.errors.firstOrNull() ?: "订单参数无效"
                        else rule.brackets.refusal(request, spec, account.currency, checked.entryPrice)
                    }
                } else null
            }) {
                raw.placeOrder(requestId, request)
            }

        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) =
            submit(checkLimits = { checkEdit(orderId) }) { raw.modifyOrder(requestId, orderId, expectedVersion, entryPrice) }

        override suspend fun changeOrder(requestId: String, orderId: String, expectedVersion: Long, price: Long, lotsHundredths: Long) =
            submit(checkLimits = { checkEdit(orderId, lotsHundredths) }) {
                raw.changeOrder(requestId, orderId, expectedVersion, price, lotsHundredths)
            }

        override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
            submit { raw.modifyOrderProtection(requestId, orderId, expectedVersion, stopLoss, takeProfit) }

        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
            submit { raw.modifyProtection(requestId, positionId, expectedVersion, stopLoss, takeProfit) }

        override suspend fun closePosition(requestId: String, positionId: String) =
            submit(riskIncreasing = false) { raw.closePosition(requestId, positionId) }

        override suspend fun cancelOrder(requestId: String, orderId: String) =
            submit(riskIncreasing = false) { raw.cancelOrder(requestId, orderId) }
    }

    /** Run only against the account identities reviewed in the confirmation dialog. */
    suspend fun flattenAccounts(registry: BrokerRegistry, reviewed: List<FlattenTarget>): List<FlattenOutcome> {
        val targets = reviewed.distinctBy { it.key }
        val reserved = targets.mapTo(mutableSetOf()) { it.key }
        storageMutex.withLock {
            if (reserved.any { it in bulkTargets.value }) {
                return targets.map { FlattenOutcome(it, false, "已有批量平仓正在处理，请核对账户。") }
            }
            bulkTargets.value = bulkTargets.value + reserved
        }
        return try {
            targets.map { target ->
                val (broker, account) = target
                fun stillAuthorized() = registry.plugins.any { it === broker } &&
                    broker.catalog.value.accounts.any { it.id == account.id && !it.readOnly }
                if (!stillAuthorized()) return@map FlattenOutcome(target, false, "账户已断开、切换或为只读。")
                val confirmed = try {
                    withTimeoutOrNull(90_000) {
                        commands(broker, account.id).withLock {
                            if (!stillAuthorized()) false
                            else flatten(broker, broker.openSession(account.id), ::stillAuthorized)
                        }
                    } == true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    false
                }
                FlattenOutcome(target, confirmed, if (confirmed) FLAT else FAILED)
            }
        } finally {
            withContext(NonCancellable) {
                storageMutex.withLock { bulkTargets.value = bulkTargets.value - reserved }
            }
        }
    }

    /** Monitor every configured child on connected logins, including accounts not selected in the UI. */
    suspend fun monitor(registry: BrokerRegistry) = coroutineScope {
        val active = mutableMapOf<Pair<String, String>, Job>()
        val lastRefresh = mutableMapOf<Pair<String, String>, Long>()
        while (isActive) {
            active.entries.removeAll { it.value.isCompleted }
            for (broker in registry.plugins) {
                val configured = rules.value[broker.descriptor.id].orEmpty()
                for (child in broker.catalog.value.accounts) {
                    val rule = configured[child.id] ?: continue
                    if (rule.limits == null && rule.trigger == null) continue
                    val key = broker.descriptor.id to child.id
                    if (child.readOnly || key in active) continue
                    active[key] = launch {
                        try {
                            val raw = broker.openSession(child.id)
                            val mutex = commands(broker, child.id)
                            suspend fun checkAccount() = mutex.withLock {
                                evaluate(broker, raw)
                                flattenIfPending(broker, raw)
                            }
                            checkAccount()
                            val current = rule(broker, child.id)
                            val time = clock.now().toEpochMilliseconds()
                            val last = lastRefresh[key]
                            val snapshotTime = raw.state.value.dailyPnl?.updatedAtMs
                            val due = (last == null || time - last !in 0..<20_000) &&
                                (snapshotTime == null || time - snapshotTime !in 0..<20_000)
                            if (due && (current.limits != null || current.trigger != null)
                                && broker.market.value.let { it.connected && it.tradingReady }) {
                                lastRefresh[key] = time
                                withTimeoutOrNull(12_000) { broker.refreshDailyPnl(child.id) }
                                checkAccount()
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // A catalog or connection can be replaced between iterations. Never retry a claimed close.
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    private suspend fun flattenIfPending(broker: BrokerPlugin, raw: AccountSession) {
        val id = raw.account.id
        val trigger = storageMutex.withLock {
            if ((broker.descriptor.id to id) in bulkTargets.value) return
            val current = rule(broker, id)
            val trigger = current.trigger ?: return
            if (trigger.flattenStatus != WAITING || freshPnl(broker, raw)?.first != trigger.day) return
            if (!persist(broker, id, current.copy(trigger = trigger.copy(flattenStatus = IN_PROGRESS)))) return
            trigger
        }
        val confirmed = try {
            withTimeoutOrNull(60_000) { flatten(broker, raw) } == true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
        storageMutex.withLock {
            val current = rule(broker, id)
            if (current.trigger?.day == trigger.day) {
                persist(broker, id, current.copy(trigger = trigger.copy(flattenStatus = if (confirmed) FLAT else FAILED)))
            }
        }
    }

    private suspend fun flatten(
        broker: BrokerPlugin, raw: AccountSession, authorized: () -> Boolean = { true },
    ): Boolean {
        suspend fun ready(): Boolean {
            if (!authorized()) return false
            val state = withTimeoutOrNull(12_000) { broker.market.first { !it.connected || it.tradingReady } }
            return state?.connected == true && authorized()
        }
        suspend fun cancel(orderId: String): Boolean {
            if (!ready()) return false
            if (raw.state.value.orders.none { it.id == orderId }) return true
            val result = raw.cancelOrder(requestId(), orderId)
            return result.accepted && result.outcome == CommandOutcome.ACCEPTED &&
                withTimeoutOrNull(12_000) { raw.state.first { s -> s.orders.none { it.id == orderId } } } != null
        }
        if (!ready()) return false
        // Keep confirmed protection while there is exposure, then remove any orphaned orders.
        for (order in raw.state.value.orders.filterNot { it.isProtection }) {
            if (!cancel(order.id)) return false
        }
        for (position in raw.state.value.positions) {
            if (!ready() || raw.state.value.orders.any { !it.isProtection }) return false
            if (raw.state.value.positions.none { it.id == position.id }) continue
            val result = raw.closePosition(requestId(), position.id)
            if (!result.accepted || result.outcome != CommandOutcome.ACCEPTED) return false
            if (withTimeoutOrNull(12_000) { raw.state.first { s -> s.positions.none { it.id == position.id } } } == null) return false
        }
        if (raw.state.value.positions.isNotEmpty()) return false
        for (order in raw.state.value.orders) {
            if (raw.state.value.positions.isNotEmpty() || !cancel(order.id)) return false
        }
        return ready() && raw.state.value.let { it.positions.isEmpty() && it.orders.isEmpty() }
    }
}
