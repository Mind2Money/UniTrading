package com.unitrading.ui

import androidx.compose.runtime.*
import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class QuantityCapacityState(val maximum: Long? = null, val message: String? = null, val loading: Boolean = false)

/** Quantity and protection edits do not restart the server search. Changed account/order context does. */
@Composable
internal fun rememberQuantityCapacity(session: AccountSession, instrument: Instrument, draft: OrderDraft,
    account: AccountSnapshot, ready: Boolean, localDemo: Boolean): Pair<QuantityCapacityState, (() -> Unit)?> {
    if (localDemo) return QuantityCapacityState(instrument.maxLotsHundredths) to null
    val entry = if (draft.kind == OrderKind.MARKET) null else instrument.parsePrice(draft.entry)
    val context = listOf(session, instrument.symbol, instrument.executionSymbol, draft.side, draft.kind, entry, account.positions, account.orders,
        account.balanceMicros, account.marginMicros, account.balanceKnown, account.marginKnown, ready)
    var refresh by remember { mutableIntStateOf(0) }
    var capacity by remember(context, refresh) { mutableStateOf(QuantityCapacityState(message = "正在确认可下单数量", loading = true)) }
    var checkedEquity by remember(context, refresh) { mutableStateOf<Long?>(null) }
    LaunchedEffect(context, refresh) {
        if (!ready || instrument.executionSymbol == null || (draft.kind != OrderKind.MARKET && entry == null)) {
            capacity = QuantityCapacityState(message = if (!ready) "账户或行情尚未就绪" else "请先输入有效的订单价格")
            return@LaunchedEffect
        }
        delay(400) // Coalesce typing and account pushes; never query on each slider movement or quote tick.
        val result = try { session.orderCapacity(OrderRequest(instrument.executionSymbol!!, draft.side, draft.kind, instrument.minLotsHundredths, entry,
            sourceSymbol = instrument.symbol.takeIf { instrument.isContinuous })) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { OrderCapacity(message = "可下单数量暂时无法确认，请刷新") }
        checkedEquity = session.state.value.equityMicros.takeIf { session.state.value.equityKnown }
        capacity = QuantityCapacityState(result.maximumLotsHundredths, result.message)
        if (result.maximumLotsHundredths != null) {
            delay(60_000)
            capacity = QuantityCapacityState(message = "数量上限已过期，请刷新")
        }
    }
    val fundsDecreased = checkedEquity?.let { !account.equityKnown || account.equityMicros < it } == true
    return (if (fundsDecreased) QuantityCapacityState(message = "可用资金已变化，请刷新数量上限") else capacity) to { refresh++ }
}

internal fun OrderPreview.withCapacity(request: OrderRequest?, capacity: QuantityCapacityState): OrderPreview {
    val maximum = capacity.maximum ?: return this
    if (request == null || request.lotsHundredths <= maximum) return this
    return copy(errors = errors + "下单数量超过当前可下单上限，请调整数量")
}
