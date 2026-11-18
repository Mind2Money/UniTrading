package com.unitrading.tradovate

import com.unitrading.broker.DepthLevel
import com.unitrading.broker.Instrument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A DOM message is accepted only as a complete two-sided book. Invalid packets never leave old depth displayed. */
internal data class TradovateDomBook(val timeMs: Long, val bids: List<DepthLevel>, val asks: List<DepthLevel>)

internal fun parseTradovateDom(row: JsonObject, instrument: Instrument): TradovateDomBook? {
    if (instrument.tickSize <= 0) return null
    val time = row.str("timestamp")?.let(::timestamp) ?: return null
    fun side(name: String): List<DepthLevel>? {
        val values = row[name] as? JsonArray ?: return null
        if (values.size > 10_000) return null
        val levels = values.map { value ->
            val item = value as? JsonObject ?: return null
            val price = item.priceValue("price")?.takeIf { it > 0 && it % instrument.tickSize == 0L } ?: return null
            // Futures depth sizes are whole contracts; never round a fractional or malformed value.
            val rawSize = item.str("size")?.takeIf { it.matches(Regex("[0-9]+(?:\\.0+)?")) } ?: return null
            val size = rawSize.substringBefore('.').toLongOrNull()?.takeIf { it > 0 } ?: return null
            DepthLevel(price, size)
        }
        if (levels.map { it.price }.distinct().size != levels.size) return null
        return levels
    }
    val bids = side("bids")?.sortedByDescending { it.price } ?: return null
    val asks = side("offers")?.sortedBy { it.price } ?: return null
    if (bids.isNotEmpty() && asks.isNotEmpty() && bids.first().price >= asks.first().price) return null
    return TradovateDomBook(time, bids, asks)
}
