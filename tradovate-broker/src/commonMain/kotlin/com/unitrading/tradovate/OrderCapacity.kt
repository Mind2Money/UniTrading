package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private val quantityRejections = setOf("MaxOrderQtyLimitReached", "MaxPosLimitReached", "MaxTotalPosLimitReached", "NotEnoughLiquidity",
    "LiquidationOnly", "LiquidationOnlyBeforeExpiration")
private val blockedRejections = setOf("AccountClosed", "TradingLocked", "SessionClosed")

/** Find the last accepted integer quantity using only order/dryrun, never a live order endpoint.
 * Other failures cannot establish a quantity bound (e.g. NoQuote/Unauthorized/timeout).
 * Recheck both sides of the boundary to catch risk/margin changes during the search.
 */
internal suspend fun discoverOrderCapacity(check: suspend (Long) -> JsonObject): OrderCapacity {
    var acceptedAny = false
    suspend fun accepts(quantity: Long): Boolean {
        val response = check(quantity)
        require(response.str("errorText").isNullOrBlank()) { "服务器数量校验失败" }
        return when (val reason = response.str("rejectReason")) {
            "Success" -> true.also { acceptedAny = true }
            in quantityRejections -> false
            in blockedRejections -> throw CapacityBlocked()
            else -> throw IllegalArgumentException("可下单数量未知（${reason ?: "响应缺少校验结果"}）")
        }
    }
    return try {
        if (!accepts(1)) return OrderCapacity(0, "当前资金或账户限额不足以开仓")
        var low = 1L
        var high = 2L
        val ceiling = Int.MAX_VALUE.toLong()
        while (accepts(high)) {
            low = high
            if (high == ceiling) return OrderCapacity(ceiling * 100)
            high = (high * 2).coerceAtMost(ceiling)
        }
        while (high - low > 1) {
            val middle = low + (high - low) / 2
            if (accepts(middle)) low = middle else high = middle
        }
        if (!accepts(low) || accepts(high)) OrderCapacity(message = "账户可用额度已变化，请刷新数量上限")
        else OrderCapacity(low * 100)
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: CapacityBlocked) {
        if (acceptedAny) OrderCapacity(message = "账户交易状态已变化，请刷新数量上限")
        else OrderCapacity(0, "当前账户或交易时段不允许该方向下单")
    }
    catch (_: Exception) { OrderCapacity(message = "可下单数量暂时无法确认，请刷新") }
}

private class CapacityBlocked : Exception()

internal fun capacityBody(accountId: Long, contractId: Long, request: OrderRequest, quantity: Long) = buildJsonObject {
    put("accountId", accountId)
    putJsonArray("orders") {
        add(buildJsonObject {
            put("contractId", contractId)
            put("action", if (request.side == Side.BUY) "Buy" else "Sell")
            put("orderQty", quantity)
            put("orderType", when (request.kind) { OrderKind.MARKET -> "Market"; OrderKind.LIMIT -> "Limit"; OrderKind.STOP -> "Stop" })
            if (request.kind != OrderKind.MARKET) put(if (request.kind == OrderKind.STOP) "stopPrice" else "price", jsonNumber(requireNotNull(request.entryPrice), 6))
        })
    }
}
