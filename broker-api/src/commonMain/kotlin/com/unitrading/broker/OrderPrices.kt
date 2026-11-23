package com.unitrading.broker

const val MARKETABLE_LIMIT_WARNING = "按当前报价，这张限价单可能立即成交"

/** A buy limit crosses at or above the ask, a sell limit at or below the bid. */
fun OrderRequest.isMarketableLimit(quote: Quote): Boolean {
    if (kind != OrderKind.LIMIT) return false
    val limit = entryPrice ?: return false
    return if (side == Side.BUY) limit >= quote.ask else limit <= quote.bid
}
