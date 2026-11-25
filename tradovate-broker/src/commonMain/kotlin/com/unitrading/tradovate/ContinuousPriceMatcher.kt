package com.unitrading.tradovate

import kotlinx.serialization.json.JsonObject

/** Select the first three unexpired broker-listed maturities, ordered by expiration. */
internal fun nearestThreeMaturities(rows: List<JsonObject>, productId: Long, now: Long): List<JsonObject> {
    require(rows.all { it.long("productId") == productId }) { "月份所属品种不一致" }
    val dated = rows.map { row ->
        val id = requireNotNull(row.long("id")) { "月份标识缺失" }
        val end = requireNotNull(row.str("expirationDate")?.let(::timestamp)) { "月份到期时间缺失" }
        Triple(end, id, row)
    }
    require(dated.map { it.second }.distinct().size == dated.size) { "月份重复" }
    val active = dated.filter { it.first > now }.sortedWith(compareBy({ it.first }, { it.second })).take(3).map { it.third }
    require(active.isNotEmpty()) { "没有可用月份" }
    return active
}

/** Match one current continuous-chart price against the three dated last-trade prices. */
internal class ContinuousPriceMatcher {
    private data class Sample(val price: Long, val time: Long)
    private var chart: Sample? = null
    private val trades = mutableMapOf<String, Sample>()
    private var binding: String? = null

    fun chart(price: Long, time: Long) {
        if (binding == null && price > 0 && chart?.let { time < it.time } != true) chart = Sample(price, time)
    }
    fun trade(symbol: String, price: Long, time: Long) {
        if (binding == null && price > 0 && trades[symbol]?.let { time < it.time } != true) trades[symbol] = Sample(price, time)
    }
    /** An established binding is not re-matched until the caller creates a fresh matcher. */
    fun unique(candidates: Set<String>, now: Long): String? {
        binding?.let { return it.takeIf { name -> name in candidates } }
        val latest = chart?.takeIf { now - it.time in 0..15_000 } ?: return null
        if (candidates.isEmpty() || candidates.size > 3 || candidates.any { trades[it]?.let { value -> now - value.time in 0..15_000 } != true }) return null
        val result = candidates.filter { trades[it]?.price == latest.price }.singleOrNull() ?: return null
        binding = result
        return result
    }
}

private val indexRoots = setOf("ES", "MES", "NES", "NQ", "MNQ", "NNQ", "YM", "MYM", "NDOW", "RTY", "M2K", "NKD", "NIY", "EMD", "TWE", "VIX", "VX", "VXM")
internal fun usesContinuousPriceMatching(symbol: String): Boolean = symbol.startsWith("@") && symbol.removePrefix("@") !in indexRoots
