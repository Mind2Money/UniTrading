package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

@Serializable
internal enum class RiskAction { LOCK, FLATTEN }

@Serializable
internal enum class RiskFreeze { DAY, WEEK }

/** Where the automatic flatten of a triggered rule stands. Saved by name; the views hold the text shown for it. */
@Serializable
internal enum class FlattenStatus {
    WAITING, IN_PROGRESS, FAILED, FLAT,
    /** The trigger could not be saved: the account stays locked in this process, and nothing is sent automatically. */
    UNSAVED,
}

@Serializable
internal data class RiskLimits(
    val loss: Long? = null,
    val profit: Long? = null,
    val action: RiskAction = RiskAction.LOCK,
) {
    fun valid() = (loss != null || profit != null) && (loss == null || loss > 0) && (profit == null || profit > 0)
}

@Serializable
internal data class RiskTrigger(val day: LocalDate, val pnl: Long, val flattenStatus: FlattenStatus? = null)

/** The sentences the contributed build saved as a flatten's state, before states were saved by name. */
private val flattenSentences = mapOf(
    "等待执行撤单和平仓" to FlattenStatus.WAITING,
    "平仓结果待核对，请检查券商持仓和挂单" to FlattenStatus.IN_PROGRESS,
    "未确认全部撤单和平仓，请检查券商持仓和挂单" to FlattenStatus.FAILED,
    "券商已确认无持仓、无挂单" to FlattenStatus.FLAT,
    "锁定未能保存，请检查存储并手动核对持仓" to FlattenStatus.UNSAVED,
)

/**
 * Reads a trigger the way the contributed build saved it too, so that its rule file still loads. That build kept the
 * rule's `action` in the trigger, which is dropped, and the flatten's state as the sentence it showed, which is read as
 * the state it named; a sentence it did not write reads as [FlattenStatus.FAILED], which sends nothing by itself.
 * Anything else this build does not know still makes the file unreadable.
 */
private object SavedTrigger : JsonTransformingSerializer<RiskTrigger>(RiskTrigger.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val status = (element["flattenStatus"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { text -> FlattenStatus.entries.none { it.name == text } }
            ?.let { (flattenSentences[it] ?: FlattenStatus.FAILED).name }
        return JsonObject(element - "action" + listOfNotNull(status?.let { "flattenStatus" to JsonPrimitive(it) }))
    }
}

@Serializable
internal data class AccountRiskRule(
    val limits: RiskLimits? = null,
    /**
     * The first broker trading day on which frozen [limits] may change again; null when they are not frozen. Cleared
     * once a fresh broker day has reached it, so that the freeze stays over when the broker's snapshot goes stale.
     */
    val frozenUntil: LocalDate? = null,
    @Serializable(with = SavedTrigger::class) val trigger: RiskTrigger? = null,
    val orderLimits: AccountOrderLimits = AccountOrderLimits(),
    val brackets: AccountBrackets = AccountBrackets(),
) {
    /**
     * Whether the P&L limits are frozen on the broker's [tradingDay]. A freeze follows the trading day the lock itself
     * uses and never the device clock: without a fresh broker day (null) the rule stays frozen until one has reached
     * [frozenUntil].
     */
    fun frozen(tradingDay: LocalDate?): Boolean = frozenUntil != null && (tradingDay == null || tradingDay < frozenUntil)

    /**
     * Whether the cash brackets or the sizing apply to a new [order]. One that only reduces the position held, against it
     * and for no more than it, needs neither: it gets no stop loss or take profit of its own and keeps the quantity it
     * was given ([ExposureEffect.reduces]); whether it may go beside what else works against the position is for
     * [reductionRefusal]. The order form and the guard both ask here, so they cannot disagree on it.
     */
    fun bracketsApply(snapshot: AccountSnapshot, hedging: Boolean, order: OrderRequest): Boolean =
        brackets.enabled && !newOrderExposure(snapshot, hedging, order).reduces

    /**
     * Why the brackets refuse a new [order] that [bracketsApply] leaves without a stop, or null. Beside the orders already
     * working against the position it could fill after them and open the other side with no stop, so it has to be an exit;
     * a protective stop may go all the same, as it only adds protection. Without brackets nothing is refused here. The
     * order form and the guard both ask here.
     */
    fun reductionRefusal(snapshot: AccountSnapshot, hedging: Boolean, order: OrderRequest): String? =
        newOrderExposure(snapshot, hedging, order).takeIf { brackets.enabled && it.reduces && !it.exit && !it.protective }
            ?.let { EXITS_COVER_POSITION }

    /** The lots the lot cap still leaves a new order like [order], for a quantity sized from a loss amount; null without a cap. */
    fun lotRoom(snapshot: AccountSnapshot, hedging: Boolean, order: OrderRequest): Long? =
        orderLimits.maxLots?.let { lotsUnderCap(snapshot, hedging, order, it) }

    /**
     * [order] as the brackets make it at [quote], when they apply: with their stop loss and take profit, or with the
     * quantity the loss amount buys under its own ceiling, the lot cap and [capacity], the server's order limit when
     * the caller knows it. Throws [IllegalArgumentException] with the reason when that cannot be worked out.
     */
    fun bracketed(
        order: OrderRequest, spec: Instrument, currency: String, quote: Quote?, snapshot: AccountSnapshot, hedging: Boolean,
        capacity: Long? = null,
    ): OrderRequest = if (!bracketsApply(snapshot, hedging, order)) order
        else brackets.prices(order, spec, currency, quote, lotRoom(snapshot, hedging, order), capacity)
}

/**
 * The first trading day after a freeze taken on [tradingDay]: the next weekday, so a Friday's ends on the Monday, or
 * the Monday after it for a week.
 */
internal fun riskFreezeEnd(tradingDay: LocalDate, period: RiskFreeze): LocalDate {
    val weekday = tradingDay.dayOfWeek.ordinal // Monday is 0
    return tradingDay.plus(if (period == RiskFreeze.DAY && weekday < 4) 1 else 7 - weekday, DateTimeUnit.DAY)
}

/** A positive amount typed with at most two decimals, in micros; null for anything else. */
internal fun riskAmount(text: String): Long? = parseFixed(text, 2)?.takeIf { it in 1..99_999_999_999L }?.times(10_000)

private const val STORE_KEY = "account-risk-v1"

/** What a paused account can still do. A hedging account has no order that reduces a position: each one opens its own. */
private fun stillAllowed(hedging: Boolean) = if (hedging) "；仍可平仓、撤单或收紧止损。" else "；仍可平仓、撤单、收紧止损或挂减仓单。"

/** Shown wherever the P&L lock is offered on a broker without [BrokerCapabilities.tradingDayPnl]. */
internal const val PNL_LOCK_UNSUPPORTED = "当前交易商不提供当日盈亏和交易日，无法使用盈亏锁定"

/**
 * A trade command as the risk rules see it. Closing a position and cancelling an order are not among them: they can
 * only reduce exposure, so no rule stops them.
 */
internal sealed interface RiskCheck {
    data class Place(val request: OrderRequest) : RiskCheck
    /** A working order moved to [price], with [lotsHundredths] in total when its quantity changes as well. */
    data class Change(val orderId: String, val price: Long, val lotsHundredths: Long? = null) : RiskCheck
    /**
     * The protection of a position set to [stopLoss] and [takeProfit]. Only the stop matters to a lock; the cash
     * amounts of the account hold both.
     */
    data class PositionStop(val positionId: String, val stopLoss: Long?, val takeProfit: Long?) : RiskCheck
    /** The protection a pending entry carries set to [stopLoss] and [takeProfit], judged as for [PositionStop]. */
    data class OrderStop(val orderId: String, val stopLoss: Long?, val takeProfit: Long?) : RiskCheck
}

internal data class FlattenTarget(val broker: BrokerPlugin, val account: AccountInfo) {
    val key get() = broker.descriptor.id to account.id
}

/** [note] says why an account was not worked on at all; without one the outcome is the flatten's own, confirmed or not. */
internal data class FlattenOutcome(val target: FlattenTarget, val confirmed: Boolean, val note: String? = null)

/**
 * An automatic flatten of [account], on the login whose broker id is [login], that ended without the broker confirming it
 * flat; or, when [interrupted], one that the monitor's end cut off (the screen built again or closed), so that what it
 * sent is not known. Its state stays [FlattenStatus.IN_PROGRESS] then, which sends nothing again.
 */
internal data class FlattenFailure(val login: String, val account: AccountInfo, val interrupted: Boolean = false)

/** Why a flatten of every sub-account leaves one out. Nothing can be sent for it, so it is not a failure either. */
internal enum class FlattenSkip { DISCONNECTED, READ_ONLY }

/** The accounts of every login a bulk flatten can act on, and the ones it cannot with the reason. */
internal fun bulkFlattenTargets(plugins: List<BrokerPlugin>): Pair<List<FlattenTarget>, List<Pair<FlattenTarget, FlattenSkip>>> {
    val targets = mutableListOf<FlattenTarget>()
    val skipped = mutableListOf<Pair<FlattenTarget, FlattenSkip>>()
    for (broker in plugins) {
        val ready = broker.market.value.let { it.connected && it.tradingReady }
        for (account in broker.catalog.value.accounts) {
            val target = FlattenTarget(broker, account)
            when {
                !ready -> skipped += target to FlattenSkip.DISCONNECTED
                account.readOnly -> skipped += target to FlattenSkip.READ_ONLY
                else -> targets += target
            }
        }
    }
    return targets to skipped
}

internal class AccountRiskController(
    private val store: SnapshotStore,
    private val clock: Clock = Clock.System,
    /** Where the store is written. Tests pass their own dispatcher, so that saving runs in virtual time. */
    private val io: CoroutineContext = Dispatchers.Default,
) {
    private val storageMutex = Mutex()
    /** Accounts a bulk flatten has reserved. Read and changed under [storageMutex]. */
    private val bulkTargets = mutableSetOf<Pair<String, String>>()
    private val commandMutexes = mutableMapOf<Pair<String, String>, Mutex>()
    private val initial = runCatching {
        store.read(STORE_KEY)?.let { Json.decodeFromString<Map<String, Map<String, AccountRiskRule>>>(it) }.orEmpty()
    }
    private val mutableRules = MutableStateFlow(initial.getOrDefault(emptyMap()))
    val rules = mutableRules.asStateFlow()
    private val unreadable = MutableStateFlow(initial.isFailure)
    /**
     * The saved rules could not be read. Until [resetUnreadable] no account may add exposure, because a rule that
     * cannot be read might have forbidden it.
     */
    val storageUnreadable = unreadable.asStateFlow()
    private val unread = MutableStateFlow<List<FlattenFailure>>(emptyList())
    /**
     * The automatic flattens of this run that ended unconfirmed or were cut off, in the order they ended, each until
     * [acknowledge] takes it off. They are kept here and not on the screen, so a screen built again, as Android does for
     * a change of dark mode, font size or language, still shows the ones not read. A previous run's show only in
     * [RiskTrigger.flattenStatus].
     */
    val unreadFailures: StateFlow<List<FlattenFailure>> = unread.asStateFlow()

    /** Takes [failure], once the user has dismissed it, off [unreadFailures]. */
    fun acknowledge(failure: FlattenFailure) = unread.update { it - failure }

    fun rule(broker: BrokerPlugin, accountId: String) =
        rules.value[broker.descriptor.id]?.get(accountId) ?: AccountRiskRule()

    /**
     * The rule whose P&L part counts. That part needs the broker's day P&L and trading day: on a broker without them
     * it could never trigger or end, so a rule saved there anyway is inert and never pauses trading.
     */
    private fun pnlRule(broker: BrokerPlugin, accountId: String): AccountRiskRule? =
        rule(broker, accountId).takeIf { broker.capabilities.tradingDayPnl && (it.limits != null || it.trigger != null) }

    /** Whether the P&L limits of this account are frozen right now; see [AccountRiskRule.frozen]. */
    fun frozen(broker: BrokerPlugin, accountId: String): Boolean = broker.capabilities.tradingDayPnl &&
        rule(broker, accountId).let { it.frozenUntil != null && it.frozen(freshDay(broker, accountId)) }

    suspend fun configure(broker: BrokerPlugin, id: String, limits: RiskLimits?, freeze: RiskFreeze? = null): String? {
        // A freeze needs the broker's trading day, and an account without a rule yet may have no fresh snapshot of it:
        // ask once, as the monitor does, before saying there is none. Not under the storage lock every command waits for.
        if (freeze != null && broker.capabilities.tradingDayPnl && freshDay(broker, id) == null) refreshPnl(broker, id)
        return storageMutex.withLock {
            val previous = rule(broker, id)
            val supported = broker.capabilities.tradingDayPnl
            val day = if (supported) freshDay(broker, id) else null
            val next = if (supported) previous.copy(limits = limits, frozenUntil = freeze?.let { period -> day?.let { riskFreezeEnd(it, period) } })
                // Nothing ever enforced a rule here, so all that can happen is clearing it: limits, freeze and any trigger.
                else previous.copy(limits = null, frozenUntil = null, trigger = null)
            when {
                unreadable.value -> "无法读取风控设置，请检查本机存储。"
                !supported && limits != null -> "$PNL_LOCK_UNSUPPORTED。"
                supported && previous.frozen(day) -> "风控设置已冻结，暂不能修改。"
                limits?.valid() == false -> "请至少输入一项有效的盈亏额度。"
                freeze != null && limits?.loss == null -> "冻结设置前请先输入有效的亏损额度。"
                freeze != null && day == null -> "等待券商交易日数据，暂不能冻结设置。"
                !persist(broker, id, next) -> "风控设置保存失败，请重试。"
                else -> null
            }
        }
    }

    /** Asks the broker for an account's day P&L now, waiting at most 12 seconds; a failure leaves the snapshot as it was. */
    private suspend fun refreshPnl(broker: BrokerPlugin, accountId: String) {
        if (!broker.market.value.let { it.connected && it.tradingReady }) return
        try {
            withTimeoutOrNull(12_000) { broker.refreshDailyPnl(accountId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // No fresh snapshot then, which the caller reads as such.
        }
    }

    suspend fun configureOrders(broker: BrokerPlugin, id: String, limits: AccountOrderLimits): String? =
        commands(broker, id).withLock {
            storageMutex.withLock {
                when {
                    unreadable.value -> "无法读取风控设置，请检查本机存储。"
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
                    unreadable.value -> "无法读取风控设置，请检查本机存储。"
                    !brackets.valid() -> "请输入有效的金额；按风险计算手数还需要单笔最大数量。"
                    !persist(broker, id, rule(broker, id).copy(brackets = brackets)) -> "金额止损止盈保存失败，请重试。"
                    else -> null
                }
            }
        }

    /**
     * The way out of an unreadable rule file: keeps its text under `account-risk-v1-unreadable` when that still works,
     * then starts over with no rules. Every lock, limit and bracket rule on this device is gone afterwards.
     */
    suspend fun resetUnreadable(): Boolean = storageMutex.withLock {
        if (!unreadable.value) return@withLock true
        withContext(NonCancellable) {
            try {
                withContext(io) {
                    runCatching { store.read(STORE_KEY)?.let { store.write("$STORE_KEY-unreadable", it) } }
                    store.write(STORE_KEY, Json.encodeToString(emptyMap<String, Map<String, AccountRiskRule>>()))
                }
                mutableRules.value = emptyMap()
                unreadable.value = false
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun persist(broker: BrokerPlugin, id: String, value: AccountRiskRule): Boolean {
        val brokerId = broker.descriptor.id
        val updated = rules.value + (brokerId to (rules.value[brokerId].orEmpty() + (id to value)))
        return withContext(NonCancellable) {
            try {
                withContext(io) { store.write(STORE_KEY, Json.encodeToString(updated)) }
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

    /** The broker's current trading day for an account, when its P&L snapshot is fresh. */
    private fun freshDay(broker: BrokerPlugin, accountId: String): LocalDate? =
        runCatching { broker.openSession(accountId) }.getOrNull()?.let { freshPnl(broker, it)?.first }

    /** The trigger that locks the account now: it holds until the broker reports a later trading day. */
    fun lockingTrigger(broker: BrokerPlugin, session: AccountSession): RiskTrigger? {
        val trigger = pnlRule(broker, session.account.id)?.trigger ?: return null
        val pnl = freshPnl(broker, session)
        return trigger.takeIf { pnl == null || pnl.first <= it.day }
    }

    /** Why this account may not add exposure right now, or null. What only reduces it stays possible: see [refusal]. */
    fun restriction(broker: BrokerPlugin, session: AccountSession): String? {
        if (unreadable.value) return "本机风控记录无法读取，已暂停开仓和改单。"
        lockingTrigger(broker, session)?.let {
            return "${it.day} 交易日已锁定 · ${if (it.pnl < 0) "达到亏损额度" else "达到盈利额度"}"
        }
        val waiting = pnlRule(broker, session.account.id)?.limits != null && freshPnl(broker, session) == null
        return if (waiting) "等待券商当日盈亏数据，已暂停开仓和改单。" else null
    }

    /**
     * Why [action] would be refused on this account, or null: the P&L lock, the pause while broker P&L is missing, an
     * unreadable rule file, the lot cap, the blocked symbols and the brackets (a new order that would go without a stop
     * beside the exits already working, and for an edit the cash amounts and the lots raised against the position), all
     * read from the account as it is now. The screen asks before it opens a confirmation; [guard] decides again when
     * the command is sent, where it also prices or checks a new order against the brackets.
     */
    fun refusal(broker: BrokerPlugin, session: AccountSession, action: RiskCheck): String? =
        refusal(broker, session, action, restriction(broker, session))

    /** [pause] is the reason exposure may not grow right now. It only stops what could add exposure or loosen a stop. */
    private fun refusal(broker: BrokerPlugin, session: AccountSession, action: RiskCheck, pause: String?): String? {
        val snapshot = session.state.value
        val hedging = broker.capabilities.hedging
        val rule = rule(broker, session.account.id)
        val limits = rule.orderLimits
        val unit = LotCapUnit.of(broker.instruments)
        /** [covered]: what is refused would reduce the position, but the exits already working could take all of it. */
        fun paused(harmless: Boolean, covered: Boolean = false) = pause?.takeUnless { harmless }?.let {
            if (covered) EXITS_COVER_POSITION else it.trimEnd('。') + stillAllowed(hedging)
        }
        /** The cash amounts, for a pending entry or a position that an edit turns from [before] into [after]. */
        fun amounts(before: OrderRequest, after: OrderRequest): String? {
            if (!rule.brackets.enabled) return null
            val spec = broker.instruments.firstOrNull { it.symbol == before.symbol } ?: return "合约规格尚未加载"
            return rule.brackets.editRefusal(before, after, spec, session.account.currency)
        }
        /**
         * With the brackets on, what an edit that raises [order] would open without a stop: lots past the position, of an
         * order against it whoever placed it, or of a stop or target whose position has gone. Raised, the order has to stay
         * an exit, or a protective stop (a stop that reduces). The refusal names what is in the way: the order itself, or
         * the other orders working against the position beside it.
         */
        fun raisedPast(order: PendingOrder, effect: ExposureEffect): String? = when {
            !rule.brackets.enabled || !effect.grows || effect.exit || !(effect.against || order.isProtection) -> null
            order.request.kind == OrderKind.STOP && effect.reduces -> null
            effect.reduces -> "保护单加上其他已挂的减仓单会超过持仓：超出的部分是不带止损的开仓。请先撤销或减少其他减仓单。"
            else -> "保护单数量不能超过持仓：超出的部分是不带止损的开仓。"
        }
        return when (action) {
            is RiskCheck.Place -> newOrderExposure(snapshot, hedging, action.request).let { effect ->
                paused(effect.harmless, covered = effect.reduces) ?: limits.refusal(action.request, effect, unit)
                    ?: rule.reductionRefusal(snapshot, hedging, action.request)
            }
            is RiskCheck.Change -> {
                val order = snapshot.orders.firstOrNull { it.id == action.orderId } ?: return "挂单已更新，请重新选择。"
                val changed = order.request.copy(lotsHundredths = action.lotsHundredths ?: order.request.lotsHundredths)
                val effect = editedOrderExposure(snapshot, hedging, order, changed.lotsHundredths)
                // Any order against the position may move or shrink, whatever its size, and grow as an exit; an entry can
                // only be cancelled, and a stop may not move away from the market.
                val loosened = order.stopLoosenedAt(action.price)
                paused(effect.harmless && !loosened, covered = effect.reduces && !loosened) ?: limits.refusal(changed, effect, unit)
                    ?: raisedPast(order, effect)
                    ?: when (order.role) {
                        // An entry keeps the stop and target it was placed with: moved or enlarged, it still has to meet the
                        // amounts. They stay at their prices, unless the broker keeps them at their distance from the entry.
                        OrderRole.ENTRY -> {
                            val shift = if (!broker.capabilities.bracketsFollowEntry) 0L else action.price - (order.request.entryPrice ?: action.price)
                            amounts(order.request, changed.copy(entryPrice = action.price,
                                stopLoss = changed.stopLoss?.plus(shift), takeProfit = changed.takeProfit?.plus(shift)))
                        }
                        // A stop or a target changed on its own. Its price is held to the amounts like the protection of the
                        // position, from the position's entry for no more lots than the position has, where it moves to cost
                        // the position more, down for a long and up for a short, or where it takes more lots: a stop raised
                        // at its price loses more, so no number of edits of the leg takes it past the amount. A leg made
                        // smaller at the same price always passes, and cancelling one is never refused. A new stop placed
                        // beside it, or in its place once it is cancelled, is a protective stop, which the amounts do not hold.
                        OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT -> if (!rule.brackets.enabled) null else
                            snapshot.positions.firstOrNull { it.symbol == order.request.symbol && it.side != order.request.side }?.let { held ->
                                val was = order.request.entryPrice
                                val costlier = was == null || if (held.side == Side.BUY) action.price < was else action.price > was
                                if (!costlier && !effect.grows) return@let null
                                val stop = order.role == OrderRole.STOP_LOSS
                                fun leg(price: Long?, lots: Long) = OrderRequest(held.symbol, held.side, OrderKind.MARKET, minOf(lots, held.lotsHundredths),
                                    held.entryPrice, stopLoss = if (stop) price else null, takeProfit = if (stop) null else price)
                                amounts(leg(was, order.remainingLots), leg(action.price, changed.lotsHundredths - order.filledLotsHundredths))
                            }
                        OrderRole.UNRESOLVED -> null
                    }
            }
            is RiskCheck.PositionStop -> snapshot.positions.firstOrNull { it.id == action.positionId }.let { position ->
                paused(position != null && !stopLoosened(position.side, position.stopLoss, action.stopLoss)) ?: position?.let {
                    val held = OrderRequest(it.symbol, it.side, OrderKind.MARKET, it.lotsHundredths, it.entryPrice, it.stopLoss, it.takeProfit)
                    amounts(held, held.copy(stopLoss = action.stopLoss, takeProfit = action.takeProfit))
                }
            }
            is RiskCheck.OrderStop -> snapshot.orders.firstOrNull { it.id == action.orderId }.let { order ->
                paused(order != null && !stopLoosened(order.request.side, order.request.stopLoss, action.stopLoss))
                    ?: order?.takeIf { it.role == OrderRole.ENTRY }?.let {
                        amounts(it.request, it.request.copy(stopLoss = action.stopLoss, takeProfit = action.takeProfit))
                    }
            }
        }
    }

    /**
     * Latches a limit the broker's day P&L has reached, and lets go of an old latch on a later trading day. Returns
     * why exposure may not grow right now, or null.
     */
    private suspend fun evaluate(broker: BrokerPlugin, session: AccountSession): String? = storageMutex.withLock {
        val id = session.account.id
        val fresh = freshPnl(broker, session)
        // A freeze is over once a fresh broker day reaches its end. That is saved at once: a snapshot that goes stale
        // later counts as frozen again, and would bring back a freeze that ended long ago.
        rule(broker, id).let { if (it.frozenUntil != null && fresh != null && fresh.first >= it.frozenUntil) persist(broker, id, it.copy(frozenUntil = null)) }
        restriction(broker, session)?.let { return@withLock it }
        val previous = pnlRule(broker, id) ?: return@withLock null
        val limits = previous.limits
        val (day, pnl) = fresh ?: return@withLock "等待券商当日盈亏数据。"
        val lossReached = limits?.loss?.let { pnl <= -it } == true
        val profitReached = limits?.profit?.let { pnl >= it } == true
        if (limits == null || (!lossReached && !profitReached)) {
            if (previous.trigger != null && !persist(broker, id, previous.copy(trigger = null))) {
                return@withLock "新交易日的解锁记录保存失败，请检查本机存储。"
            }
            return@withLock null
        }
        val trigger = RiskTrigger(day, pnl, FlattenStatus.WAITING.takeIf { limits.action == RiskAction.FLATTEN })
        if (!persist(broker, id, previous.copy(trigger = trigger))) {
            // Keep this process locked even if storage cannot accept the write. No auto-orders before durable state.
            val locked = previous.copy(trigger = trigger.copy(flattenStatus = FlattenStatus.UNSAVED))
            val brokerId = broker.descriptor.id
            mutableRules.value = rules.value + (brokerId to (rules.value[brokerId].orEmpty() + (id to locked)))
        }
        restriction(broker, session) ?: "子账户已触发交易锁定。"
    }

    private suspend fun commands(broker: BrokerPlugin, id: String): Mutex =
        storageMutex.withLock { commandMutexes.getOrPut(broker.descriptor.id to id) { Mutex() } }

    private suspend fun reserved(key: Pair<String, String>) = storageMutex.withLock { key in bulkTargets }

    fun guard(broker: BrokerPlugin, raw: AccountSession): AccountSession = object : AccountSession by raw {
        /** [check] is null for a close or a cancel, which no rule stops. */
        private suspend fun submit(check: RiskCheck?, action: suspend () -> TradeResult): TradeResult {
            val key = broker.descriptor.id to account.id
            val bulkRefusal = TradeResult(false, "该子账户正在批量撤单和平仓。")
            if (reserved(key)) return bulkRefusal
            return commands(broker, account.id).withLock {
                if (reserved(key)) return@withLock bulkRefusal
                if (check != null) refusal(broker, raw, check, evaluate(broker, raw))?.let { return@withLock TradeResult(false, it) }
                action()
            }
        }

        override suspend fun placeOrder(requestId: String, request: OrderRequest) = submit(RiskCheck.Place(request)) {
            val rule = rule(broker, account.id)
            val snapshot = raw.state.value
            val hedging = broker.capabilities.hedging
            if (!rule.bracketsApply(snapshot, hedging, request)) return@submit raw.placeOrder(requestId, request)
            val spec = broker.instruments.firstOrNull { it.symbol == request.symbol }
                ?: return@submit TradeResult(false, "合约规格尚未加载")
            // A market order is priced again from the quote of this moment. The quote it was reviewed at is older by the
            // time the command gets here, and one tick against the order would put its stop past the loss amount. A new
            // quote may make a sized order smaller, never larger than the one that was reviewed.
            val order = if (request.kind != OrderKind.MARKET) request else try {
                val priced = rule.bracketed(request, spec, account.currency, broker.market.value.quotes[request.symbol], snapshot, hedging)
                priced.copy(lotsHundredths = minOf(request.lotsHundredths, priced.lotsHundredths))
            } catch (error: IllegalArgumentException) {
                return@submit TradeResult(false, error.message ?: "无法计算金额止损止盈")
            }
            val checked = raw.preview(order)
            val refused = when {
                !checked.valid -> checked.errors.firstOrNull() ?: "订单参数无效"
                order.kind == OrderKind.MARKET -> null
                // A resting entry keeps the price it was reviewed at, so its stop and target must meet the amounts as they stand.
                else -> rule.brackets.refusal(order, spec, account.currency, checked.entryPrice)
            }
            if (refused != null) TradeResult(false, refused) else raw.placeOrder(requestId, order)
        }

        override suspend fun modifyOrder(requestId: String, orderId: String, expectedVersion: Long, entryPrice: Long) =
            submit(RiskCheck.Change(orderId, entryPrice)) { raw.modifyOrder(requestId, orderId, expectedVersion, entryPrice) }

        override suspend fun changeOrder(requestId: String, orderId: String, expectedVersion: Long, price: Long, lotsHundredths: Long) =
            submit(RiskCheck.Change(orderId, price, lotsHundredths)) {
                raw.changeOrder(requestId, orderId, expectedVersion, price, lotsHundredths)
            }

        override suspend fun modifyOrderProtection(requestId: String, orderId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
            submit(RiskCheck.OrderStop(orderId, stopLoss, takeProfit)) { raw.modifyOrderProtection(requestId, orderId, expectedVersion, stopLoss, takeProfit) }

        override suspend fun modifyProtection(requestId: String, positionId: String, expectedVersion: Long, stopLoss: Long?, takeProfit: Long?) =
            submit(RiskCheck.PositionStop(positionId, stopLoss, takeProfit)) { raw.modifyProtection(requestId, positionId, expectedVersion, stopLoss, takeProfit) }

        override suspend fun closePosition(requestId: String, positionId: String) =
            submit(null) { raw.closePosition(requestId, positionId) }

        override suspend fun cancelOrder(requestId: String, orderId: String) =
            submit(null) { raw.cancelOrder(requestId, orderId) }
    }

    /** Run only against the account identities reviewed in the confirmation dialog. */
    suspend fun flattenAccounts(registry: BrokerRegistry, reviewed: List<FlattenTarget>): List<FlattenOutcome> {
        val targets = reviewed.distinctBy { it.key }
        val reserved = targets.mapTo(mutableSetOf()) { it.key }
        storageMutex.withLock {
            if (reserved.any { it in bulkTargets }) {
                return targets.map { FlattenOutcome(it, false, "已有批量平仓正在处理，请核对账户。") }
            }
            bulkTargets += reserved
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
                FlattenOutcome(target, confirmed)
            }
        } finally {
            withContext(NonCancellable) {
                storageMutex.withLock { bulkTargets -= reserved }
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
                for (child in broker.catalog.value.accounts) {
                    if (pnlRule(broker, child.id) == null) continue
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
                            val time = clock.now().toEpochMilliseconds()
                            val last = lastRefresh[key]
                            val snapshotTime = raw.state.value.dailyPnl?.updatedAtMs
                            val due = (last == null || time - last !in 0..<20_000) &&
                                (snapshotTime == null || time - snapshotTime !in 0..<20_000)
                            if (due && pnlRule(broker, child.id) != null
                                && broker.market.value.let { it.connected && it.tradingReady }) {
                                lastRefresh[key] = time
                                refreshPnl(broker, child.id)
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
            if ((broker.descriptor.id to id) in bulkTargets) return
            val current = rule(broker, id)
            val trigger = current.trigger ?: return
            if (trigger.flattenStatus != FlattenStatus.WAITING || freshPnl(broker, raw)?.first != trigger.day) return
            // Saved before anything is sent: after a restart the flatten is not sent a second time.
            if (!persist(broker, id, current.copy(trigger = trigger.copy(flattenStatus = FlattenStatus.IN_PROGRESS)))) return
            trigger
        }
        try {
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
                    val status = if (confirmed) FlattenStatus.FLAT else FlattenStatus.FAILED
                    persist(broker, id, current.copy(trigger = trigger.copy(flattenStatus = status)))
                    // Told also when the state could not be saved: the position may be open either way.
                    if (!confirmed) unread.update { it + FlattenFailure(broker.descriptor.id, raw.account) }
                }
            }
        } catch (cancel: CancellationException) {
            // The monitor ended before the outcome was saved: the screen was built again or closed. What was sent is not
            // known, and IN_PROGRESS stays saved, so nothing is sent again; the user is told to check at the broker. The
            // update does not suspend, so the cancellation cannot stop it.
            unread.update { it + FlattenFailure(broker.descriptor.id, raw.account, interrupted = true) }
            throw cancel
        }
    }

    /**
     * Cancels what could open, add or reverse, closes every position, and only then cancels the orders that were
     * reducing them: this app leaves a stop the user placed after the entry working until its position is confirmed
     * closed. The broker may not. Tradovate's close request cancels the contract's working orders itself, so after a
     * close that failed there the stop may be gone at the broker. Nothing with an unknown outcome is sent again.
     */
    private suspend fun flatten(
        broker: BrokerPlugin, raw: AccountSession, authorized: () -> Boolean = { true },
    ): Boolean {
        val hedging = broker.capabilities.hedging
        suspend fun ready(): Boolean {
            if (!authorized()) return false
            val state = withTimeoutOrNull(12_000) { broker.market.first { !it.connected || it.tradingReady } }
            return state?.connected == true && authorized()
        }
        suspend fun cancel(orderId: String): Boolean {
            if (!ready()) return false
            if (raw.state.value.orders.none { it.id == orderId }) return true
            if (raw.cancelOrder(requestId(), orderId).outcome == CommandOutcome.UNKNOWN) return false
            // A refused cancel is no failure once the order has gone: it filled, or the broker cancelled it with its position.
            return withTimeoutOrNull(12_000) { raw.state.first { s -> s.orders.none { it.id == orderId } } } != null
        }
        /**
         * Orders that reduce no position now: entries, what is more than a position can take (see [reducesPosition]),
         * and whatever a closed position left behind. Entries go before the legs tied to them.
         */
        suspend fun cancelOpening(): Boolean {
            while (true) {
                val state = raw.state.value
                val opening = state.orders.filterNot { reducesPosition(state, hedging, it) }
                val order = opening.firstOrNull { !it.isProtection } ?: opening.firstOrNull() ?: return true
                if (!cancel(order.id)) return false
            }
        }
        if (!ready()) return false
        // A second and third pass close a position that came back: an exit that filled while its position was closing.
        repeat(3) {
            for (position in raw.state.value.positions) {
                if (!cancelOpening() || !ready()) return false
                if (raw.state.value.positions.none { it.id == position.id }) continue
                val result = raw.closePosition(requestId(), position.id)
                if (!result.accepted || result.outcome != CommandOutcome.ACCEPTED) return false
                // A net account keeps one id for a contract: a position that reversed under it is closed too, and the next pass takes the new one.
                val closed = withTimeoutOrNull(12_000) { raw.state.first { s -> s.positions.none { it.id == position.id && it.side == position.side } } }
                if (closed == null) return false
            }
            if (!cancelOpening()) return false
            if (raw.state.value.positions.isEmpty()) {
                // Nothing is left to protect: the protection of hedged positions goes too.
                for (order in raw.state.value.orders) if (!cancel(order.id)) return false
                if (raw.state.value.let { it.positions.isEmpty() && it.orders.isEmpty() }) return ready()
            }
        }
        return false
    }
}
