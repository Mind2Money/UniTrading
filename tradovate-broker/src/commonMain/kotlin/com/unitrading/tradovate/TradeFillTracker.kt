package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.serialization.json.JsonObject

/** Runs under the broker lock. Only live fill messages can become sound notifications. */
internal class TradeFillTracker {
    private data class Binding(val signature: String, val role: OrderRole)
    private data class EntryIntent(val account: Long, val contract: Long, val action: String, val closingQuantity: Long)
    private val entryTags = mutableMapOf<Pair<Long, String>, EntryIntent>()
    private val entryOrders = mutableMapOf<Long, EntryIntent>()
    private val bindings = mutableMapOf<Long, Binding>()
    private val observedOrders = mutableSetOf<Long>()
    private val possibleEntries = mutableSetOf<Long>()
    private val pending = mutableSetOf<Long>()
    private val seenFills = mutableSetOf<Long>()
    private val notifiedOrders = mutableSetOf<Long>()
    private var liveAfterMs = Long.MAX_VALUE

    fun prepareEntry(book: EntityBook, account: Long, contract: Long, action: String, quantity: Long, tag: String) {
        val net = book.list("position").firstOrNull { it.long("accountId") == account && it.long("contractId") == contract }?.long("netPos") ?: 0
        val closing = if (net > 0 && action == "Sell" || net < 0 && action == "Buy") kotlin.math.abs(net) else 0
        if (quantity > closing) entryTags[account to tag] = EntryIntent(account, contract, action, closing)
    }

    fun acceptEntry(account: Long, tag: String, orderId: Long) {
        entryTags[account to tag]?.let { entryOrders[orderId] = it }
    }

    fun rejectEntry(account: Long, tag: String) { entryTags.remove(account to tag) }

    fun baseline(book: EntityBook, accounts: Set<Long>, now: Long) {
        pending.clear()
        bindings.clear()
        observedOrders.clear()
        possibleEntries.clear()
        liveAfterMs = now / 1_000 * 1_000
        for (fill in book.list("fill")) {
            fill.long("id")?.let(seenFills::add)
            fill.long("orderId")?.let(notifiedOrders::add)
        }
        remember(book, accounts)
    }

    fun event(book: EntityBook, accounts: Set<Long>, data: JsonObject): List<TradeFill> {
        if (data.str("entityType") == "fill") {
            val id = (data["entity"] as? JsonObject)?.long("id")
            if (id != null && data.str("eventType").equals("Deleted", true)) {
                pending.remove(id); seenFills.add(id)
            } else if (id != null && id !in seenFills) pending.add(id)
        }
        return drain(book, accounts)
    }

    fun drain(book: EntityBook, accounts: Set<Long>): List<TradeFill> {
        remember(book, accounts)
        if (pending.isEmpty()) return emptyList()
        val classifiers = mutableMapOf<Long, ProtectionClassifier>()
        val result = mutableListOf<TradeFill>()
        for (id in pending.toList()) {
            val fill = book.item("fill", id) ?: continue
            val time = fill.str("timestamp")?.let(::timestamp) ?: continue
            if (fill.bool("active") == false || time < liveAfterMs) {
                pending.remove(id); seenFills.add(id); continue
            }
            if (fill.bool("active") != true || (fill.long("qty") ?: 0) <= 0 || (fill.priceValue("price") ?: 0) <= 0) continue
            val orderId = fill.long("orderId") ?: continue
            if (orderId in notifiedOrders) { pending.remove(id); seenFills.add(id); continue }
            val order = book.item("order", orderId) ?: continue
            val account = order.long("accountId")?.takeIf { it in accounts } ?: continue
            if (order.long("contractId") != fill.long("contractId") || order.str("action") != fill.str("action")) continue
            val signature = signature(book, order)
            val cached = bindings[orderId]?.takeIf { it.signature == signature }?.role
            // A fill can precede its parent/strategy metadata, or follow the order becoming terminal.
            val relation = classifiers.getOrPut(account) { ProtectionClassifier(book, account, includeInactive = true) }.relation(orderId)
            val intent = entryOrders[orderId] ?: order.str("clOrdId")?.let { entryTags[account to it] }
            val opening = intent?.takeIf { it.account == account && it.contract == order.long("contractId") && it.action == order.str("action") }
                ?.let { expected ->
                    val quantity = book.list("fill").filter {
                        val filledAt = it.str("timestamp")?.let(::timestamp)
                        it.long("orderId") == orderId && it.bool("active") == true &&
                            it.long("contractId") == expected.contract && it.str("action") == expected.action &&
                            filledAt != null && (filledAt < time || filledAt == time && (it.long("id") ?: Long.MAX_VALUE) <= id)
                    }.sumOf { (it.long("qty") ?: 0).coerceAtLeast(0) }
                    quantity > expected.closingQuantity
                } == true
            val strategyEntry = relation.role == OrderRole.ENTRY && book.list("orderStrategyLink").any { it.long("orderId") == orderId }
            val role = when {
                relation.activated && relation.role.isProtection() -> relation.role
                relation.role == cached -> cached
                opening || strategyEntry -> OrderRole.ENTRY
                else -> null
            }
            if (role == null) continue
            notifiedOrders.add(orderId)
            seenFills.add(id)
            pending.remove(id)
            result.add(TradeFill(account.toString(), orderId.toString(), id.toString(), role))
        }
        return result
    }

    private fun remember(book: EntityBook, accounts: Set<Long>) {
        val classifiers = mutableMapOf<Long, ProtectionClassifier>()
        for (order in book.list("order")) {
            val id = order.long("id") ?: continue
            val account = order.long("accountId")?.takeIf { it in accounts } ?: continue
            val signature = signature(book, order)
            if (bindings[id]?.signature != signature) bindings.remove(id)
            if (!activeOrder(order)) { observedOrders.add(id); continue }
            if (order.long("contractId") == null || order.str("action") !in setOf("Buy", "Sell")) continue
            val classifier = classifiers.getOrPut(account) { ProtectionClassifier(book, account) }
            val relation = classifier.relation(id)
            val unfilled = classifier.filledQuantity(id) == 0L
            if (observedOrders.add(id) && unfilled && relation.role == OrderRole.ENTRY) possibleEntries.add(id)
            // A reducing order must not become an entry just because a position update went flat first.
            if (unfilled && relation.role != OrderRole.ENTRY) {
                possibleEntries.remove(id)
                if (bindings[id]?.role == OrderRole.ENTRY) bindings.remove(id)
            }
            if (relation.activated && relation.role.isProtection() ||
                relation.role == OrderRole.ENTRY && unfilled && id in possibleEntries) {
                bindings[id] = Binding(signature, relation.role)
            }
        }
    }

    private fun signature(book: EntityBook, order: JsonObject): String {
        val id = order.long("id")
        val links = book.list("orderStrategyLink").filter { it.long("orderId") == id }
            .map { it.long("orderStrategyId") }.distinct().sortedBy { it ?: 0 }
        return listOf(order.long("accountId"), order.long("contractId"), order.str("action"),
            order.long("parentId"), order.long("ocoId"), id?.let { book.orderVersion(it)?.str("orderType") },
            links.map { it to it?.let { strategy -> book.item("orderStrategy", strategy) } }).joinToString("|")
    }

    private fun OrderRole.isProtection() = this == OrderRole.STOP_LOSS || this == OrderRole.TAKE_PROFIT
}
