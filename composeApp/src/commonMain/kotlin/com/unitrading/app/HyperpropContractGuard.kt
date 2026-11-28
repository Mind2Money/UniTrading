package com.unitrading.app

import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote

/** A contract pair reviewed by the user or verified against Hyperprop's catalogue for one chart and one data source. */
internal data class HyperpropContractBinding(
    val sourceId: String,
    val chartSymbol: String,
    val datedSymbol: String,
    val priceDecimals: Int,
    val tickSize: Long,
)

/** Never derives a contract month by comparing prices or by using a quote-only fallback. */
internal fun bindHyperpropContract(sourceId: String, chart: Instrument, confirmedHyperpropSymbol: String): HyperpropContractBinding {
    require(sourceId.isNotBlank()) { "请先选择行情来源" }
    val dated = chart.executionSymbol ?: throw IllegalArgumentException("图表合约月份尚未确认")
    require(dated.matches(Regex("[A-Z]{1,8}[FGHJKMNQUVXZ][0-9]{1,2}"))) { "行情来源没有提供可交易的到期合约" }
    require(confirmedHyperpropSymbol == dated) { "行情合约与 Hyperprop 合约不一致" }
    require(chart.priceDecimals in 0..8 && chart.tickSize > 0) { "行情合约规格无效" }
    return HyperpropContractBinding(sourceId, chart.symbol, dated, chart.priceDecimals, chart.tickSize)
}

/** Recheck at submission, not only at preview: a rollover or data-source switch invalidates a previously reviewed order. */
internal fun hyperpropContractError(
    binding: HyperpropContractBinding, sourceId: String, chart: Instrument, requestedDatedSymbol: String,
    quote: Quote?, nowMs: Long, maximumQuoteAgeMs: Long = 15_000,
): String? {
    if (binding.sourceId != sourceId || binding.chartSymbol != chart.symbol) return "行情来源或图表品种已切换，请重新检查订单"
    if (chart.executionSymbol != binding.datedSymbol || requestedDatedSymbol != binding.datedSymbol) return "合约月份已变化或尚未确认，请重新检查订单"
    if (chart.priceDecimals != binding.priceDecimals || chart.tickSize != binding.tickSize) return "合约规格已变化，请重新检查订单"
    if (quote == null || quote.bid <= 0 || quote.ask <= 0 || quote.bid > quote.ask ||
        quote.timeMs > nowMs + 5_000 || quote.timeMs < nowMs - maximumQuoteAgeMs) return "行情不可用或已延迟，请重新检查报价"
    return null
}
