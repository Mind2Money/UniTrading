package com.unitrading.rithmic

import com.unitrading.broker.*

internal const val ORDER_FLOW_TRADE_LIMIT = 20_000
private const val ORDER_FLOW_DEPTH_LIMIT = 10_000

/** Mutated only under the broker lock. Lists are copied at publication, never on each market tick. */
internal class RithmicOrderFlow {
    var symbol: String? = null
        private set
    private val trades = ArrayDeque<MarketTrade>()
    private var bids = mutableMapOf<Long, Long>()
    private var asks = mutableMapOf<Long, Long>()
    private var pendingBids: MutableMap<Long, Long>? = null
    private var pendingAsks: MutableMap<Long, Long>? = null
    private var aggregateUnsupported = false
    private var bestBid: DepthLevel? = null
    private var bestAsk: DepthLevel? = null
    private var truncated = false
    private var depthStatus = DepthStatus.WAITING
    private var status = "等待订阅"

    fun reset(symbol: String?, message: String) {
        this.symbol = symbol
        trades.clear(); clearBook(); bestBid = null; bestAsk = null
        truncated = false; aggregateUnsupported = false; depthStatus = DepthStatus.WAITING; status = message
    }

    fun bbo(bid: Long?, bidSize: Long?, ask: Long?, askSize: Long?) {
        bestBid = if (bid != null && bidSize != null) DepthLevel(bid, bidSize) else null
        bestAsk = if (ask != null && askSize != null) DepthLevel(ask, askSize) else null
    }

    fun trade(price: Long, size: Long, time: Long, side: Int?) {
        if (size <= 0) return
        trades.addLast(MarketTrade(time, price, size, when (side) {
            RithmicEnums.BUY -> Side.BUY
            RithmicEnums.SELL -> Side.SELL
            else -> null
        }))
        if (trades.size > ORDER_FLOW_TRADE_LIMIT) { trades.removeFirst(); truncated = true }
    }

    fun unavailable(message: String) {
        clearBook(); depthStatus = DepthStatus.UNAVAILABLE; status = message
    }

    /** BEGIN/MIDDLE/END are one atomic change; the UI never observes a partly updated book. */
    fun book(message: ProtoMessage, spec: RithmicSpec): Boolean {
        val type = message.int(Rf.BOOK_UPDATE_TYPE) ?: return false
        when (type) {
            RithmicEnums.BOOK_CLEAR -> {
                clearBook(); aggregateUnsupported = false; depthStatus = DepthStatus.WAITING; status = "深度已清空，等待新批次"; return true
            }
            RithmicEnums.BOOK_NONE -> { unavailable("服务器未提供此合约的 L2 深度，请检查行情权限"); return true }
            RithmicEnums.BOOK_SNAPSHOT -> {
                aggregateUnsupported = true
                unavailable("聚合深度快照边界尚未验证，仅显示买一 / 卖一")
                return true
            }
            RithmicEnums.BOOK_BEGIN -> {
                if (aggregateUnsupported) return false
                pendingBids = bids.toMutableMap(); pendingAsks = asks.toMutableMap()
            }
            RithmicEnums.BOOK_MIDDLE, RithmicEnums.BOOK_END -> if (aggregateUnsupported || pendingBids == null) return false
            RithmicEnums.BOOK_SOLO -> if (aggregateUnsupported || pendingBids != null) return false
            else -> return false
        }
        val targetBids = pendingBids ?: bids
        val targetAsks = pendingAsks ?: asks
        if (!applySide(message, Rf.BOOK_BID_PRICE, Rf.BOOK_BID_SIZE, targetBids, spec) ||
            !applySide(message, Rf.BOOK_ASK_PRICE, Rf.BOOK_ASK_SIZE, targetAsks, spec) ||
            targetBids.size > ORDER_FLOW_DEPTH_LIMIT || targetAsks.size > ORDER_FLOW_DEPTH_LIMIT) {
            clearBook(); depthStatus = DepthStatus.WAITING; status = "深度更新不完整，等待有效快照"; return true
        }
        if (type == RithmicEnums.BOOK_BEGIN || type == RithmicEnums.BOOK_MIDDLE) return false
        pendingBids?.let { bids = it }; pendingAsks?.let { asks = it }
        pendingBids = null; pendingAsks = null
        depthStatus = DepthStatus.AVAILABLE; status = "实时逐笔与 L2 深度 · 仅本次订阅窗口"
        return true
    }

    private fun clearBook() { bids.clear(); asks.clear(); pendingBids = null; pendingAsks = null }

    private fun applySide(message: ProtoMessage, priceField: Int, sizeField: Int, target: MutableMap<Long, Long>, spec: RithmicSpec): Boolean {
        val prices = message.values(priceField)
        val sizes = message.values(sizeField)
        if (prices.size != sizes.size) return false
        val updates = prices.indices.map { index ->
            val price = (prices[index] as? ProtoValue.Fixed64)?.let { spec.scale.units(Double.fromBits(it.value)) } ?: return false
            val size = (sizes[index] as? ProtoValue.Varint)?.value ?: return false
            if (price <= 0 || price % spec.instrument.tickSize != 0L || size < 0) return false
            price to size
        }
        for ((price, size) in updates) if (size == 0L) target.remove(price) else target[price] = size
        return true
    }

    fun snapshot(connected: Boolean) = OrderFlowSnapshot(
        symbol = symbol,
        trades = trades.toList(),
        bids = bids.entries.sortedByDescending { it.key }.map { DepthLevel(it.key, it.value) },
        asks = asks.entries.sortedBy { it.key }.map { DepthLevel(it.key, it.value) },
        bestBid = bestBid, bestAsk = bestAsk,
        depthStatus = depthStatus, connected = connected,
        startedAtMs = trades.firstOrNull()?.timeMs, truncated = truncated, statusMessage = status,
    )
}
