package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.Serializable

@Serializable
internal data class AccountBrackets(val loss: Long? = null, val profit: Long? = null) {
    val enabled get() = loss != null || profit != null

    fun valid() = listOfNotNull(loss, profit).all { it in 1..999_999_999_990_000L }
    fun controls(stop: Boolean) = (if (stop) loss else profit) != null

    /** Cash amounts apply to the entire order, based on the contract's gross value per tick. */
    fun prices(order: OrderRequest, spec: Instrument, currency: String, quote: Quote?): OrderRequest {
        if (!enabled) return order
        checkCurrency(spec, currency)
        val entry = order.entryPrice ?: quote?.let { if (order.side == Side.BUY) it.ask else it.bid }
        require(entry != null && entry > 0) { "金额止损止盈需要有效入场价或报价" }
        require(spec.tickSize > 0 && spec.tickValueMicrosPerLot > 0 && order.lotsHundredths > 0) {
            "金额止损止盈需要有效手数和合约每跳价值"
        }
        require(entry % spec.tickSize == 0L) { "入场价不符合最小变动单位" }
        val tickValue = checkedMultiply(spec.tickValueMicrosPerLot, order.lotsHundredths)
        fun protection(amount: Long, stop: Boolean): Long {
            val numerator = checkedMultiply(amount, 100)
            val ticks = numerator / tickValue + if (!stop && numerator % tickValue != 0L) 1 else 0
            require(ticks >= maxOf(1L, spec.minStopTicks)) {
                if (stop) "风险金额不足以覆盖该手数的最小止损距离" else "利润金额对应的价格低于最小止盈距离"
            }
            val direction = if ((order.side == Side.BUY) != stop) 1L else -1L
            val price = checkedAdd(entry, checkedMultiply(ticks, spec.tickSize) * direction)
            require(price > 0) { "金额止损止盈超出有效价格范围" }
            return price
        }
        return order.copy(stopLoss = loss?.let { protection(it, true) } ?: order.stopLoss,
            takeProfit = profit?.let { protection(it, false) } ?: order.takeProfit)
    }

    /** Check that the submitted prices meet the configured amounts at the latest estimated entry. */
    fun refusal(order: OrderRequest, spec: Instrument, currency: String, entry: Long?): String? {
        if (!enabled) return null
        return try {
            checkCurrency(spec, currency)
            require(entry != null && entry > 0) { "无法复核金额止损止盈的入场价" }
            loss?.let { maximum ->
                val price = requireNotNull(order.stopLoss) { "该子账户的订单必须带止损" }
                require(grossPnl(spec, order.side, order.lotsHundredths, entry, price) in -maximum until 0L) {
                    "当前预估亏损超出子账户风险设置，请重新检查订单"
                }
            }
            profit?.let { minimum ->
                val price = requireNotNull(order.takeProfit) { "该子账户的订单必须带止盈" }
                require(grossPnl(spec, order.side, order.lotsHundredths, entry, price) >= minimum) {
                    "当前预估利润低于子账户设置，请重新检查订单"
                }
            }
            null
        } catch (error: IllegalArgumentException) {
            error.message ?: "无法复核子账户止损止盈"
        }
    }

    private fun checkCurrency(spec: Instrument, currency: String) {
        require(spec.currency.equals(currency, ignoreCase = true)) { "合约与子账户币种不同，无法换算金额止损止盈" }
    }
}
