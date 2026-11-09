package com.unitrading.tradovate

import com.unitrading.broker.OrderRole
import com.unitrading.broker.Side
import kotlinx.serialization.json.*
import kotlin.math.abs

internal data class OrderRelation(
    val role: OrderRole,
    val group: String? = null,
    val parentId: Long? = null,
    val activated: Boolean = false,
    val note: String? = null,
)
internal data class PositionProtection(
    val stopId: Long? = null,
    val targetId: Long? = null,
    val editable: Boolean = false,
    val revision: String? = null,
    val note: String? = null,
    /** Every order confirmed as an exit of this position, including those [stopId] and [targetId] could not single out. */
    val confirmed: Set<Long> = emptySet(),
)

/** Pure projection of server evidence. It never writes orders or changes raw protocol rows. */
internal class ProtectionClassifier(private val book: EntityBook, private val accountId: Long, private val includeInactive: Boolean = false) {
    private val all = book.list("order").associateBy { it.long("id") }
    private val active = all.values.filter { it.long("accountId") == accountId &&
        (activeOrder(it) || includeInactive && it.str("ordStatus") in setOf("Filled", "Completed", "Cancelled")) }
    private val links = book.list("orderStrategyLink")
    private val memberships = links.groupBy { it.long("orderId") }
    private val filled = book.list("fill").filter { it.bool("active") != false }
        .groupBy { it.long("orderId") }.mapValues { (_, values) -> values.sumOf { (it.long("qty") ?: 0).coerceAtLeast(0) } }
    private val positions = book.list("position").filter { it.long("accountId") == accountId && (it.long("netPos") ?: 0) != 0L }
    private val relations = mutableMapOf<Long, OrderRelation>()
    private val unsafe = mutableSetOf<Long>()

    init {
        for (order in active) {
            val id = order.long("id") ?: continue
            val net = positions.firstOrNull { it.long("contractId") == order.long("contractId") }?.long("netPos") ?: 0
            val opposite = net != 0L && order.str("action") == (if (net > 0) "Sell" else "Buy")
            val linked = positive(order, "parentId") != null || positive(order, "ocoId") != null || memberships[id].orEmpty().isNotEmpty()
            relations[id] = if (linked || opposite) unresolved("保护/退出关联尚未确认") else OrderRelation(OrderRole.ENTRY)
        }
        classifyParents()
        classifyOco()
        classifyStrategies()
    }

    fun relation(orderId: Long): OrderRelation = relations[orderId] ?: unresolved("订单不在当前有效账户范围")
    fun filledQuantity(orderId: Long): Long = filled[orderId] ?: 0
    fun remainingQuantity(orderId: Long): Long? {
        val total = book.orderVersion(orderId)?.long("orderQty") ?: return null
        val done = filledQuantity(orderId)
        return (total - done).takeIf { total > 0 && it >= 0 }
    }
    fun entryEditable(orderId: Long): Boolean = relation(orderId).role == OrderRole.ENTRY &&
        relation(orderId).note == null && all[orderId]?.str("ordStatus") == "Working" &&
        filledQuantity(orderId) == 0L && (remainingQuantity(orderId) ?: 0) > 0

    fun forPosition(contractId: Long, side: Side, quantity: Long): PositionProtection {
        val opposite = if (side == Side.BUY) "Sell" else "Buy"
        val relevant = active.filter { it.long("contractId") == contractId && it.str("action") == opposite }
        val confirmed = relevant.filter { order ->
            val r = relation(order.long("id") ?: 0)
            r.role in setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT) && r.activated
        }
        val revision = relevant.sortedBy { it.long("id") }.joinToString("|") { order ->
            val id = order.long("id") ?: 0
            val r = relation(id)
            "$id:${book.orderVersion(id)?.long("id")}:${order.str("ordStatus")}:${remainingQuantity(id)}:${r.role}:${r.group}:${r.parentId}:${r.activated}:${r.note}"
        }.ifEmpty { null }
        val confirmedIds = confirmed.mapNotNull { it.long("id") }.toSet()
        fun unavailable(message: String) = PositionProtection(revision = revision, note = message, confirmed = confirmedIds)
        if (confirmed.isEmpty()) return if (relevant.isEmpty()) PositionProtection() else unavailable("存在反向挂单，保护关联尚未完整确认")
        if (confirmed.map { relation(it.long("id")!!).group }.distinct().size != 1) return unavailable("存在多组保护，请在挂单列表中逐项核对")
        val stops = confirmed.filter { relation(it.long("id")!!).role == OrderRole.STOP_LOSS }
        val targets = confirmed.filter { relation(it.long("id")!!).role == OrderRole.TAKE_PROFIT }
        if (stops.size > 1 || targets.size > 1) return unavailable("存在多个同类止损/止盈，无法确定哪一条对应当前仓位")
        if (confirmed.size == 2 && relation(confirmed.first().long("id")!!).group?.startsWith("parent:") == true && !linkedOco(confirmed[0], confirmed[1])) {
            return unavailable("父子关系已确认，但尚未确认两条保护单的 OCO 互斥关系")
        }
        if (confirmed.any { remainingQuantity(it.long("id")!!) != quantity }) return unavailable("保护单剩余数量与当前净仓不一致，仅显示订单")
        if (relevant.any { relation(it.long("id")!!).role == OrderRole.UNRESOLVED }) return unavailable("另有反向单的关联尚未确认，暂不能修改仓位保护")
        val stop = stops.singleOrNull()?.long("id")
        val target = targets.singleOrNull()?.long("id")
        val working = confirmed.all { it.str("ordStatus") == "Working" }
        return PositionProtection(stop, target, working, revision, if (working) null else "保护订单正在处理，暂不可修改", confirmedIds)
    }

    /**
     * Whether a working, unfilled entry's brackets can be repriced before it fills: at most one stop and one target,
     * each an untouched native child (not an ATM leg, which the strategy prices from the fill) covering the whole entry.
     */
    fun pendingBracketsEditable(entryId: Long): Boolean {
        if (!entryEditable(entryId)) return false
        val quantity = book.orderVersion(entryId)?.long("orderQty") ?: return false
        val stops = children(entryId, OrderRole.STOP_LOSS)
        val targets = children(entryId, OrderRole.TAKE_PROFIT)
        return stops.size <= 1 && targets.size <= 1 && (stops + targets).all { id ->
            relation(id).group?.startsWith("atm:") != true && all[id]?.str("ordStatus") in setOf("Working", "Suspended") &&
                filledQuantity(id) == 0L && book.orderVersion(id)?.long("orderQty") == quantity
        }
    }

    fun children(parentId: Long, role: OrderRole): List<Long> = relations.filter { (id, relation) ->
        relation.parentId == parentId && relation.role == role && activeOrder(all[id] ?: return@filter false)
    }.keys.toList()

    private fun classifyParents() {
        for (child in active) {
            val id = child.long("id") ?: continue
            val parentId = positive(child, "parentId") ?: continue
            val parent = all[parentId] ?: continue
            if (!sameScope(child, parent) || parentId == id || parent.str("action") == child.str("action") ||
                parent.str("action") !in setOf("Buy", "Sell") || positive(parent, "parentId") == id) {
                unsafe.add(id); relations[id] = unresolved("父子订单的账户、合约或方向冲突"); continue
            }
            val role = exitRole(id) ?: continue
            val activated = parent.str("ordStatus") in setOf("Filled", "Completed") || filledQuantity(parentId) > 0
            relations[id] = OrderRelation(role, "parent:$parentId", parentId, activated,
                if (activated) null else "入场尚未成交，保护单等待激活")
        }
    }

    private fun classifyOco() {
        val processed = mutableSetOf<Long>()
        for (order in active) {
            val id = order.long("id") ?: continue
            val groupId = positive(order, "ocoId") ?: continue
            if (id in processed || id in unsafe) continue
            val peers = active.filter { other -> other.long("id") != id && sameScope(order, other) && linkedOco(order, other) }
            if (peers.size != 1) {
                unsafe.add(id)
                relations[id] = unresolved("OCO 关联不完整或不唯一"); continue
            }
            val peer = peers.single()
            val peerId = peer.long("id")!!
            processed.add(id); processed.add(peerId)
            val actualReference = all[groupId]
            if (peerId in unsafe || actualReference != null && !sameScope(order, actualReference) || order.str("action") != peer.str("action")) {
                unsafe.add(id); unsafe.add(peerId)
                relations[id] = unresolved("OCO 方向或作用域不匹配"); relations[peerId] = relations.getValue(id); continue
            }
            val roles = listOf(exitRole(id), exitRole(peerId))
            if (roles.toSet() != setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT)) {
                relations[id] = unresolved("OCO 不构成唯一止损/止盈组合"); relations[peerId] = relations.getValue(id); continue
            }
            val parents = listOfNotNull(positive(order, "parentId"), positive(peer, "parentId")).distinct()
            if (parents.size > 1) {
                relations[id] = unresolved("OCO 父订单冲突"); relations[peerId] = relations.getValue(id); continue
            }
            val parent = parents.singleOrNull()?.let(all::get)
            if (parent != null && (!sameScope(order, parent) || parent.str("action") == order.str("action"))) {
                relations[id] = unresolved("OCO 父订单身份冲突"); relations[peerId] = relations.getValue(id); continue
            }
            val net = positions.firstOrNull { it.long("contractId") == order.long("contractId") }?.long("netPos") ?: 0
            val reduces = net != 0L && order.str("action") == (if (net > 0) "Sell" else "Buy")
            val parentActivated = parent == null || parent.str("ordStatus") in setOf("Filled", "Completed") || filledQuantity(parent.long("id")!!) > 0
            val activated = reduces && parentActivated
            val group = "oco:${minOf(id, peerId)}:${maxOf(id, peerId)}"
            for ((rowId, role) in listOf(id to roles[0]!!, peerId to roles[1]!!)) {
                relations[rowId] = OrderRelation(role, group, parents.singleOrNull(), activated,
                    if (activated) null else "当前没有可确认的同向净仓保护关联")
            }
        }
    }

    private fun classifyStrategies() {
        for ((strategyId, members) in links.groupBy { it.long("orderStrategyId") }) {
            val memberIds = members.mapNotNull { it.long("orderId") }.distinct()
            val affected = memberIds.filter { it in relations }
            if (affected.isEmpty()) continue
            fun reject(message: String) { affected.forEach { relations[it] = unresolved(message) } }
            if (memberIds.any { memberships[it].orEmpty().map { link -> link.long("orderStrategyId") }.distinct().size != 1 }) {
                reject("订单属于多个策略，关联不明确"); continue
            }
            val strategy = strategyId?.let { book.item("orderStrategy", it) }
            if (strategy == null || strategy.long("accountId") != accountId || strategy.long("orderStrategyTypeId") != 2L) {
                reject("策略信息缺失或不属于当前账户的 ATM"); continue
            }
            val params = (strategy["params"] as? JsonObject) ?: strategy.str("params")?.takeIf { it.length <= 32_768 }?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
            val brackets = params?.get("brackets") as? JsonArray
            val bracket = brackets?.singleOrNull() as? JsonObject
            if (bracket == null || (bracket.long("qty") ?: 0) <= 0 ||
                decimalUnits(bracket.str("profitTarget"), 6) in listOf(null, 0L) || decimalUnits(bracket.str("stopLoss"), 6) in listOf(null, 0L)) {
                reject("多级或不完整 ATM 保护，请在官网核对"); continue
            }
            val family = memberIds.mapNotNull(all::get)
            val action = strategy.str("action")
            if (family.size != memberIds.size || family.size > 3 || action !in setOf("Buy", "Sell") ||
                family.any { it.long("accountId") != accountId || it.long("contractId") != strategy.long("contractId") }) {
                reject("ATM 订单作用域冲突或关联记录未齐"); continue
            }
            val entries = family.filter { it.str("action") == action }
            val exits = family.filter { it.str("action") != action }
            if (entries.size != 1 || exits.size != 2 || exits.any { it.str("action") != if (action == "Buy") "Sell" else "Buy" } ||
                exits.map { exitRole(it.long("id")!!) }.toSet() != setOf(OrderRole.STOP_LOSS, OrderRole.TAKE_PROFIT)) {
                reject("ATM 的入场、止损、止盈关联尚未完整确认"); continue
            }
            val entry = entries.single()
            val entryId = entry.long("id")!!
            if (exits.any { child -> child.long("id") in unsafe || positive(child, "parentId")?.let { it != entryId } == true }) {
                reject("ATM 与原生父子关系冲突"); continue
            }
            if (exits.any { positive(it, "ocoId") != null } && !linkedOco(exits[0], exits[1])) {
                reject("ATM 与 OCO 关联冲突"); continue
            }
            if (entryId in relations) relations[entryId] = OrderRelation(OrderRole.ENTRY)
            val exitCompleted = exits.any { it.str("ordStatus") in setOf("Filled", "Completed") }
            val activated = (includeInactive || !exitCompleted) && (entry.str("ordStatus") in setOf("Filled", "Completed") || filledQuantity(entryId) > 0)
            val group = "atm:$strategyId"
            exits.filter { includeInactive || activeOrder(it) }.forEach { exit ->
                val id = exit.long("id")!!
                relations[id] = OrderRelation(exitRole(id)!!, group, entryId, activated,
                    when { exitCompleted -> "已有保护腿成交，等待净仓和关联订单同步"; !activated -> "ATM 入场尚未成交，保护单等待激活"; else -> null })
            }
        }
    }

    private fun exitRole(id: Long): OrderRole? = when (book.orderVersion(id)?.str("orderType")) {
        "Stop" -> OrderRole.STOP_LOSS
        "Limit" -> OrderRole.TAKE_PROFIT
        else -> null
    }
    private fun sameScope(a: JsonObject, b: JsonObject) = a.long("accountId") == accountId && b.long("accountId") == accountId &&
        a.long("contractId") != null && a.long("contractId") == b.long("contractId")
    private fun linkedOco(a: JsonObject, b: JsonObject): Boolean {
        val x = positive(a, "ocoId") ?: return false
        val y = positive(b, "ocoId") ?: return false
        return x == y || x == b.long("id") && y == a.long("id")
    }
    private fun positive(value: JsonObject, key: String) = value.long(key)?.takeIf { it > 0 }
    private fun unresolved(note: String) = OrderRelation(OrderRole.UNRESOLVED, note = note)
}
