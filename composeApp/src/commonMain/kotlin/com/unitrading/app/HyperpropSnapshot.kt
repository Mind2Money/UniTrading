package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.rows(): List<JsonObject> {
    require((this["success"] as? JsonPrimitive)?.booleanOrNull == true) { "交易数据读取失败" }
    return (this["data"] as? JsonArray)?.map { it as? JsonObject ?: error("交易数据格式错误") }
        ?: error("交易数据不可用")
}
private fun specFor(contract: String, specs: List<Instrument>): Instrument =
    specs.firstOrNull { !it.isContinuous && it.symbol == contract }
        ?: specs.firstOrNull { it.executionSymbol == contract }
        ?: throw IllegalArgumentException("缺少 $contract 的行情合约规格")
private fun exactPrice(row: JsonObject, field: String, spec: Instrument): Long =
    row.text(field)?.let { parseFixed(it, spec.priceDecimals) }?.takeIf { it > 0 && it % spec.tickSize == 0L }
        ?: throw IllegalArgumentException("$field 价格不可用或与最小变动不匹配")
private fun time(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L

internal fun hyperpropAccountFigures(data: JsonObject, accountId: String, nowMs: Long): AccountSnapshot {
    val rows = data.rows().filter { it.text("id") == accountId }
    val row = rows.singleOrNull() ?: throw IllegalArgumentException("账户不存在或不唯一")
    fun money(field: String) = row.text(field)?.let { parseFixed(it, 6) }
    val balance = money("current_balance")
    val equity = money("equity")
    val daily = money("daily_pnl")
    return AccountSnapshot(accountId, balance ?: 0, equityMicros = equity ?: 0, balanceKnown = balance != null,
        equityKnown = equity != null, marginKnown = false,
        dailyPnl = daily?.let { DailyPnl(it, updatedAtMs = nowMs) })
}

/** Parse only confirmed server positions; unknown data fails the entire snapshot instead of appearing flat. */
internal fun hyperpropPositions(data: JsonObject, accountId: String, specs: List<Instrument>): List<Position> = data.rows().mapNotNull { row ->
    val returnedAccount = row.text("account_id") ?: throw IllegalArgumentException("持仓缺少账户标识")
    if (returnedAccount != accountId) return@mapNotNull null
    val qty = row.text("quantity")?.toLongOrNull() ?: throw IllegalArgumentException("持仓数量不可用")
    if (qty == 0L) return@mapNotNull null
    require(qty != Long.MIN_VALUE && kotlin.math.abs(qty) <= 1_000_000) { "持仓数量超出支持范围" }
    val contract = row.text("contract") ?: throw IllegalArgumentException("持仓缺少到期合约")
    val spec = specFor(contract, specs)
    Position(id = row.text("position_id") ?: "$accountId:$contract", symbol = contract, side = if (qty > 0) Side.BUY else Side.SELL,
        lotsHundredths = kotlin.math.abs(qty) * 100, entryPrice = exactPrice(row, "avg_entry_price", spec),
        openedAtMs = time(row.text("opened_at")), marginMicros = 0, protectionEditable = false, feesKnown = false,
        version = row.text("state_version")?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
        protectionNote = "保护单关系尚未确认")
}

/** Preserve broker-reported bracket types; unknown roles are never guessed. */
internal fun hyperpropWorkingOrders(data: JsonObject, accountId: String, specs: List<Instrument>): List<PendingOrder> = data.rows().mapNotNull { row ->
    val returnedAccount = row.text("account_id") ?: throw IllegalArgumentException("订单缺少账户标识")
    if (returnedAccount != accountId) return@mapNotNull null
    if (row.text("status") !in setOf("pending", "working", "partially_filled")) return@mapNotNull null
    val contract = row.text("contract") ?: throw IllegalArgumentException("订单缺少到期合约")
    val spec = specFor(contract, specs)
    val qty = row.text("quantity")?.toLongOrNull()?.takeIf { it in 1..1_000_000 } ?: throw IllegalArgumentException("订单数量不可用")
    val filled = row.text("filled_quantity")?.toLongOrNull() ?: 0L
    require(filled in 0..qty) { "订单成交数量不可用" }
    val kind = when (row.text("order_type")) { "limit" -> OrderKind.LIMIT; "stop" -> OrderKind.STOP; else -> throw IllegalArgumentException("未知挂单类型") }
    val side = when (row.text("side")) { "buy" -> Side.BUY; "sell" -> Side.SELL; else -> throw IllegalArgumentException("未知订单方向") }
    val price = exactPrice(row, if (kind == OrderKind.LIMIT) "limit_price" else "stop_price", spec)
    val id = row.text("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("订单缺少标识")
    val role = when (row.text("bracket_type")) { "stop_loss" -> OrderRole.STOP_LOSS; "take_profit" -> OrderRole.TAKE_PROFIT; else -> OrderRole.UNRESOLVED }
    PendingOrder(id = id, request = OrderRequest(contract, side, kind, qty * 100, entryPrice = price),
        createdAtMs = time(row.text("created_at") ?: row.text("processed_at")), role = role,
        positionId = if (role == OrderRole.UNRESOLVED) null else row.text("position_id"),
        version = time(row.text("updated_at")), editable = false, protectionEditable = false, filledLotsHundredths = filled * 100,
        note = "订单与保护关系待确认，请在 Hyperprop 核对")
}

/** Show a position's bracket only when one unambiguous server-reported leg covers the entire position. */
internal fun hyperpropBindProtections(positions: List<Position>, orders: List<PendingOrder>): List<Position> = positions.map { position ->
    val matching = orders.filter { it.positionId == position.id && it.request.symbol == position.symbol && it.request.side != position.side }
    fun unique(role: OrderRole): PendingOrder? {
        val legs = matching.filter { it.role == role }
        return legs.singleOrNull()?.takeIf { it.request.lotsHundredths - it.filledLotsHundredths == position.lotsHundredths }
    }
    val stop = unique(OrderRole.STOP_LOSS)
    val target = unique(OrderRole.TAKE_PROFIT)
    val complete = matching.size == listOfNotNull(stop, target).size && position.version > 0 &&
        listOfNotNull(stop, target).isNotEmpty() && listOfNotNull(stop, target).all { it.version > 0 }
    val revision = if (complete) listOfNotNull(stop, target).joinToString("|") { "${it.id}:${it.version}:${it.request.entryPrice}" } else null
    position.copy(stopLoss = stop?.request?.entryPrice, takeProfit = target?.request?.entryPrice,
        stopLossOrderId = stop?.id, takeProfitOrderId = target?.id, protectionEditable = complete, protectionRevision = revision,
        protectionNote = if (matching.isEmpty()) "未确认保护单" else if (complete) "按 Hyperprop 关联记录同步" else "保护单关联不完整，暂不可修改")
}
