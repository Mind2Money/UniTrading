package com.unitrading.broker

/** Exchange-reported contracts, independent of this user's account fills. Null side means unknown. */
data class MarketTrade(val timeMs: Long, val price: Long, val size: Long, val aggressor: Side? = null)

data class DepthLevel(val price: Long, val size: Long)

enum class DepthStatus { WAITING, AVAILABLE, UNAVAILABLE }

/** A bounded, contiguous live window. It never contains reconstructed OHLCV or historical fills. */
data class OrderFlowSnapshot(
    val symbol: String? = null,
    val trades: List<MarketTrade> = emptyList(),
    val bids: List<DepthLevel> = emptyList(),
    val asks: List<DepthLevel> = emptyList(),
    val bestBid: DepthLevel? = null,
    val bestAsk: DepthLevel? = null,
    val depthStatus: DepthStatus = DepthStatus.WAITING,
    val connected: Boolean = false,
    val startedAtMs: Long? = null,
    val truncated: Boolean = false,
    val statusMessage: String = "等待订阅",
)
