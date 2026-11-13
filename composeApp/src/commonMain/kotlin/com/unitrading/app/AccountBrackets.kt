package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.Serializable

/**
 * The cash amounts of one sub-account, each for a whole order. [loss] and [profit] put the stop loss and the take
 * profit where the order loses or makes that much. [sizingRisk] sizes the order from the stop the user gives instead,
 * and needs [sizingMaxLots] beside it: the most one order may have, in hundredths of a lot. A stop one tick from the
 * entry would otherwise size an order as large as the broker's protocol allows.
 *
 * The amounts are for orders that open or add. One that only reduces a position is neither priced nor checked here:
 * see [AccountRiskRule.bracketsApply], and [AccountRiskRule.reductionRefusal] for when it may go without a stop.
 */
@Serializable
internal data class AccountBrackets(
    val loss: Long? = null, val profit: Long? = null, val sizingRisk: Long? = null, val sizingMaxLots: Long? = null,
) {
    val enabled get() = loss != null || profit != null || sizingRisk != null

    fun valid() = (sizingRisk == null || (loss == null && profit == null)) &&
        listOfNotNull(loss, profit, sizingRisk).all { it in 1..999_999_999_990_000L } &&
        if (sizingRisk == null) sizingMaxLots == null else sizingMaxLots != null && sizingMaxLots in 1..999_999_999L
    fun controls(stop: Boolean) = (if (stop) loss else profit) != null

    /**
     * [order] with the stop loss and take profit the cash amounts give it, or with the quantity [sizingRisk] buys at
     * its stop. The amounts apply to the entire order, by the contract's gross value per tick. A sized quantity also
     * stays under [lotRoom], what the account's lot cap still leaves, and under [capacity], the server's order limit;
     * null where there is none or it is not known.
     */
    fun prices(order: OrderRequest, spec: Instrument, currency: String, quote: Quote?, lotRoom: Long? = null, capacity: Long? = null): OrderRequest {
        if (!enabled) return order
        checkCurrency(spec, currency)
        val entry = entryPrice(order, quote)
        if (sizingRisk != null) return order.copy(lotsHundredths = sizedQuantity(order, spec, entry, sizingRisk, lotRoom, capacity).first)
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

    /**
     * The ceiling that keeps the quantity [prices] sizes for [order] under what the loss amount would buy, by the name
     * the order form gives it; null when the amount alone decides, and when nothing can be sized.
     */
    fun sizingLimit(order: OrderRequest, spec: Instrument, quote: Quote?, lotRoom: Long? = null, capacity: Long? = null): String? = try {
        sizingRisk?.let { sizedQuantity(order, spec, entryPrice(order, quote), it, lotRoom, capacity).second }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun entryPrice(order: OrderRequest, quote: Quote?): Long {
        val entry = order.entryPrice ?: quote?.let { if (order.side == Side.BUY) it.ask else it.bid }
        require(entry != null && entry > 0) { "需要有效入场价或报价" }
        return entry
    }

    /** The lots [risk] buys at the order's stop, never above a ceiling, and the name of the ceiling when one decided. */
    private fun sizedQuantity(
        order: OrderRequest, spec: Instrument, entry: Long, risk: Long, lotRoom: Long? = null, capacity: Long? = null,
    ): Pair<Long, String?> {
        val ceiling = requireNotNull(sizingMaxLots) { "请先在设置中填写风险计算手数的单笔最大数量" }
        val stop = order.stopLoss
        require(stop != null && stop > 0) { "请输入有效止损价 SL" }
        val tick = spec.tickSize
        val step = spec.lotStepHundredths
        require(tick > 0 && spec.tickValueMicrosPerLot > 0 && step > 0) { "合约每跳价值或手数步长无效" }
        require(entry % tick == 0L && stop % tick == 0L) { "入场价和止损价须符合最小变动单位" }
        val distance = if (order.side == Side.BUY) entry - stop else stop - entry
        require(distance > 0) { if (order.side == Side.BUY) "买入止损价须低于入场价" else "卖出止损价须高于入场价" }
        val ticks = distance / tick
        require(ticks >= maxOf(1L, spec.minStopTicks)) { "止损距离小于合约允许的最小距离" }
        val affordable = checkedMultiply(risk, 100) / spec.tickValueMicrosPerLot / ticks / step * step
        val (limit, name) = listOfNotNull(ceiling to "单笔最大数量", lotRoom?.let { it to "子账户最大持仓" },
            capacity?.let { it to "可下单上限" }, spec.maxLotsHundredths to "合约最大手数").minBy { it.first }
        val lots = minOf(affordable, limit / step * step)
        // Name what left too little: the amount, or the ceiling that cut the quantity below one lot.
        require(lots > 0 && lots >= spec.minLotsHundredths) {
            if (lots < affordable) "受${name}限制，可下数量不足最小手数" else "风险金额不足以下最小手数，请调整风险金额或止损价"
        }
        return lots to name.takeIf { lots < affordable }
    }

    /**
     * Check that the submitted prices meet the configured amounts at the latest estimated entry. A sized quantity is
     * held to the loss amount and to [sizingMaxLots]; the lot cap and the server's limit have checks of their own.
     */
    fun refusal(order: OrderRequest, spec: Instrument, currency: String, entry: Long?): String? {
        if (!enabled) return null
        return try {
            checkCurrency(spec, currency)
            require(entry != null && entry > 0) { "无法复核金额止损止盈的入场价" }
            sizingRisk?.let { risk ->
                require(order.lotsHundredths <= sizedQuantity(order, spec, entry, risk).first) {
                    "当前预估风险超过子账户设置，请重新检查订单"
                }
            }
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

    /**
     * Why an edit may not turn [before] into [after], or null. Both describe one pending entry, or one position written
     * as the order that would open it: the price it enters at, its lots, and the stop loss and take profit it carries.
     *
     * The loss at the stop may grow no further than the loss amount, or than it already was, and a stop may not be
     * taken away; the profit at the target may fall no lower than the profit amount, or than it already was, and a
     * target may not be taken away. A missing stop or target counts as one without a bound: any stop tightens a stopless
     * order, while a first target has to make the profit amount. Both are gross, as when the order was priced.
     */
    fun editRefusal(before: OrderRequest, after: OrderRequest, spec: Instrument, currency: String): String? {
        if (!enabled) return null
        return try {
            fun result(order: OrderRequest, price: Long?): Long? = price?.let {
                val entry = order.entryPrice
                require(entry != null && entry > 0 && it > 0 && order.lotsHundredths > 0) { "无法复核子账户止损止盈" }
                grossPnl(spec, order.side, order.lotsHundredths, entry, it)
            }
            // An amount in another currency cannot be compared with the contract's: then only "no worse than it was" holds.
            val comparable = spec.currency.equals(currency, ignoreCase = true)
            (loss ?: sizingRisk)?.let { maximum ->
                val was = result(before, before.stopLoss)
                val now = result(after, after.stopLoss)
                if (was != null) {
                    requireNotNull(now) { "该子账户设有风险金额，止损不能移除" }
                    require(-now <= maxOf(-was, if (comparable) maximum else Long.MIN_VALUE)) {
                        "修改后的预估亏损超出子账户风险设置；可以收紧止损或减少数量"
                    }
                }
            }
            profit?.let { minimum ->
                val was = result(before, before.takeProfit)
                val now = result(after, after.takeProfit)
                require(now != null || was == null) { "该子账户设有利润金额，止盈不能移除" }
                val least = listOfNotNull(was, minimum.takeIf { comparable }).minOrNull()
                require(now == null || least == null || now >= least) { "修改后的预估利润低于子账户设置；止盈只能设得更远" }
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
