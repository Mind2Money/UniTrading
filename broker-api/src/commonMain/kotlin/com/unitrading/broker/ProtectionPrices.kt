package com.unitrading.broker

/**
 * Why the stop loss and take profit of an order that has not filled contradict its direction, or null when they agree.
 * A pending order is measured from its entry price. A market order buys at one side of the spread and closes at the
 * other, so each line has to clear the side that would trigger it at once or leave it past the fill.
 */
fun entryProtectionError(instrument: Instrument, request: OrderRequest, quote: Quote): String? {
    val entry = request.entryPrice?.takeIf { request.kind != OrderKind.MARKET }?.let { Reference("入场价", it) }
    return gridError(instrument, request.stopLoss, request.takeProfit) ?: sideError(instrument, "${request.side.word}单", request.side,
        entry ?: Reference("当前 Bid", quote.bid), entry ?: Reference("当前 Ask", quote.ask), request.stopLoss, request.takeProfit)
}

/**
 * Why new prices cannot replace an open position's stop loss and take profit, or null when they can. Only a line that
 * changes is checked, against the price the position would close at now. Its entry plays no part, so a stop can move to
 * break-even or past it, and a line the market has already reached does not hold up moving the other one. Without a
 * [quote] only the prices themselves can be judged; a broker still needs a live one before it sends anything.
 */
fun positionProtectionError(instrument: Instrument, position: Position, quote: Quote?, stopLoss: Long?, takeProfit: Long?): String? {
    val stop = stopLoss.takeIf { it != position.stopLoss }
    val target = takeProfit.takeIf { it != position.takeProfit }
    if (stop == null && target == null) return null
    val buy = position.side == Side.BUY
    val close = quote?.let { Reference("当前 ${if (buy) "Bid" else "Ask"}", if (buy) it.bid else it.ask) }
    return gridError(instrument, stop, target)
        ?: close?.let { sideError(instrument, "${position.side.word}仓位", position.side, it, it, stop, target) }
        ?: if (stopLoss != null && takeProfit != null && (if (buy) stopLoss >= takeProfit else stopLoss <= takeProfit)) "止损价须${if (buy) "低于" else "高于"}止盈价" else null
}

private class Reference(val name: String, val price: Long)

private val Side.word get() = if (this == Side.BUY) "买入" else "卖出"

private fun gridError(instrument: Instrument, stopLoss: Long?, takeProfit: Long?): String? =
    if (listOfNotNull(stopLoss, takeProfit).any { it <= 0 || it % instrument.tickSize != 0L }) "止损/止盈须大于零并符合最小变动单位" else null

/** A buy's stop has to stay under [low] and its target over [high]; a sell's the other way round. */
private fun sideError(instrument: Instrument, subject: String, side: Side, low: Reference, high: Reference, stopLoss: Long?, takeProfit: Long?): String? {
    val buy = side == Side.BUY
    fun must(line: String, above: Boolean, limit: Reference) = "${subject}的${line}须${if (above) "高于" else "低于"}${limit.name} ${instrument.formatPrice(limit.price)}"
    return when {
        stopLoss != null && (if (buy) stopLoss >= low.price else stopLoss <= high.price) -> must("止损", !buy, if (buy) low else high)
        takeProfit != null && (if (buy) takeProfit <= high.price else takeProfit >= low.price) -> must("止盈", buy, if (buy) high else low)
        else -> null
    }
}
