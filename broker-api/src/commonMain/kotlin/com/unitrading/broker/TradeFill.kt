package com.unitrading.broker

/** A live, confirmed entry or protection fill, emitted once per order, never from history. */
data class TradeFill(
    val accountId: String,
    val orderId: String,
    val executionId: String,
    val role: OrderRole,
) {
    init { require(role != OrderRole.UNRESOLVED) }
}
