package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.serialization.json.*

/** Only server fillPair evidence establishes a closed trade. Never invent FIFO matches. */
internal fun pairedTrades(book: EntityBook, accountId: Long, specs: Map<Long, Instrument>, fees: Map<Long, Long?>): List<ClosedTrade> {
    data class PairRow(val id: Long, val position: Long, val buy: JsonObject, val sell: JsonObject, val qty: Long, val spec: Instrument,
        val buyPrice: Long, val sellPrice: Long, val buyTime: Long, val sellTime: Long)
    val rows = book.list("fillPair").filter { it.bool("active") == true }.mapNotNull { pair ->
        val id = pair.long("id") ?: return@mapNotNull null
        val buy = book.item("fill", pair.long("buyFillId") ?: return@mapNotNull null) ?: return@mapNotNull null
        val sell = book.item("fill", pair.long("sellFillId") ?: return@mapNotNull null) ?: return@mapNotNull null
        if (buy.bool("active") != true || sell.bool("active") != true || buy.str("action") != "Buy" || sell.str("action") != "Sell") return@mapNotNull null
        val contract = buy.long("contractId") ?: return@mapNotNull null
        if (sell.long("contractId") != contract) return@mapNotNull null
        if (listOf(buy, sell).any { book.item("order", it.long("orderId") ?: 0)?.long("accountId") != accountId }) return@mapNotNull null
        val spec = specs[contract] ?: return@mapNotNull null
        val qty = pair.long("qty")?.takeIf { it > 0 } ?: return@mapNotNull null
        if (qty > (buy.long("qty") ?: 0) || qty > (sell.long("qty") ?: 0)) return@mapNotNull null
        val buyPrice = pair.priceValue("buyPrice")?.takeIf { it > 0 && it == buy.priceValue("price") } ?: return@mapNotNull null
        val sellPrice = pair.priceValue("sellPrice")?.takeIf { it > 0 && it == sell.priceValue("price") } ?: return@mapNotNull null
        val buyTime = buy.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null
        val sellTime = sell.str("timestamp")?.let(::timestamp) ?: return@mapNotNull null
        PairRow(id, pair.long("positionId") ?: return@mapNotNull null, buy, sell, qty, spec, buyPrice, sellPrice, buyTime, sellTime)
    }.sortedBy { it.id }
    val quantities = mutableMapOf<Long, Long>()
    val invalid = mutableSetOf<Long>()
    rows.forEach { row -> listOf(row.buy, row.sell).forEach { fill ->
        val id = fill.long("id")!!
        val total = runCatching { checkedAdd(quantities[id] ?: 0, row.qty) }.getOrNull()
        if (total == null || total > fill.long("qty")!!) invalid += id else quantities[id] = total
    } }
    val allocated = mutableMapOf<Long, Long>()
    fun allocation(fill: JsonObject, qty: Long): Long? {
        val id = fill.long("id")!!
        val prior = allocated[id] ?: 0
        allocated[id] = prior + qty
        val fee = fees[id] ?: return null
        // Cumulative integer allocation preserves every micro-unit across partial closes.
        return checkedAdd(multiplyDivide(fee, prior + qty, 1, fill.long("qty")!!), -multiplyDivide(fee, prior, 1, fill.long("qty")!!))
    }
    return rows.filter { it.buy.long("id") !in invalid && it.sell.long("id") !in invalid }.mapNotNull { row -> runCatching {
        val buyFee = allocation(row.buy, row.qty)
        val sellFee = allocation(row.sell, row.qty)
        val fee = if (buyFee != null && sellFee != null) checkedAdd(buyFee, sellFee) else null
        val quantity = checkedMultiply(row.qty, 100)
        val gross = grossPnl(row.spec, Side.BUY, quantity, row.buyPrice, row.sellPrice)
        val long = row.buyTime <= row.sellTime
        ClosedTrade("pair-${row.id}", row.position.toString(), row.spec.symbol, if (long) Side.BUY else Side.SELL, quantity,
            if (long) row.buyPrice else row.sellPrice, if (long) row.sellPrice else row.buyPrice,
            minOf(row.buyTime, row.sellTime), maxOf(row.buyTime, row.sellTime), fee?.let { checkedAdd(gross, -it) },
            "服务器成交配对", gross, fee, directionKnown = row.buyTime != row.sellTime)
    }.getOrNull() }.sortedBy { it.closedAtMs }
}

/** The caller binds a /fillFee/deps response to its explicit fill ID; no ID-equality assumption. */
internal fun usdFillFees(rows: List<JsonObject>, book: EntityBook): Long? {
    if (rows.isEmpty()) return null
    val fields = listOf("clearingFee" to "clearingCurrencyId", "exchangeFee" to "exchangeCurrencyId",
        "nfaFee" to "nfaCurrencyId", "brokerageFee" to "brokerageCurrencyId", "ipFee" to "ipCurrencyId",
        "commission" to "commissionCurrencyId", "orderRoutingFee" to "orderRoutingCurrencyId")
    return runCatching {
        var total = 0L
        for (row in rows) {
            val present = fields.filter { row[it.first] != null && row[it.first] != JsonNull }
            require(present.isNotEmpty())
            for ((fee, currency) in present) {
                val amount = row.moneyValue(fee) ?: error("fee missing")
                if (amount != 0L) require(book.item("currency", row.long(currency) ?: error("currency missing"))?.str("name") == "USD")
                total = checkedAdd(total, amount)
            }
        }
        total
    }.getOrNull()
}
