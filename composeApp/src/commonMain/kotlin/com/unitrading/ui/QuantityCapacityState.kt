package com.unitrading.ui

import androidx.compose.runtime.*
import com.unitrading.app.CachedCapacity
import com.unitrading.app.OrderCapacityCache
import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

internal data class QuantityCapacityState(val maximum: Long? = null, val message: String? = null, val loading: Boolean = false)

/**
 * Cache then network: the last limit the server gave for this account, contract and side shows at once, and a check runs
 * behind it. Only a different answer from the server replaces it; a failed check changes nothing. The check runs when the
 * order context or the account's positions and orders change, never on a quantity or protection edit or a balance update.
 */
@Composable
internal fun rememberQuantityCapacity(cache: OrderCapacityCache, brokerId: String, session: AccountSession, instrument: Instrument, draft: OrderDraft,
    account: AccountSnapshot, ready: Boolean, localDemo: Boolean): Pair<QuantityCapacityState, (() -> Unit)?> {
    if (localDemo) return QuantityCapacityState(instrument.maxLotsHundredths) to null
    val entry = if (draft.kind == OrderKind.MARKET) null else instrument.parsePrice(draft.entry)
    val symbol = instrument.executionSymbol
    val cacheKey = symbol?.let { OrderCapacityCache.key(brokerId, session.account.id, it, draft.side) }
    val cached = cache.entries.collectAsState().value[cacheKey.orEmpty()]
    val holdings = account.positions.map { listOf(it.symbol, it.side, it.lotsHundredths) } +
        account.orders.map { listOf(it.id, it.request.side, it.request.lotsHundredths, it.filledLotsHundredths) }
    val latestHoldings by rememberUpdatedState(holdings)
    val context = listOf(session, instrument.symbol, symbol, draft.side, draft.kind, entry, ready)
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // Why there is no limit to show yet; a limit already known outranks it.
    var unavailable by remember(context, refresh) { mutableStateOf<String?>(null) }
    LaunchedEffect(context, refresh) {
        if (symbol == null || cacheKey == null || !ready || (draft.kind != OrderKind.MARKET && entry == null)) {
            unavailable = if (!ready) "账户或行情尚未就绪" else "请先输入有效的订单价格"
            refreshing = false
            return@LaunchedEffect
        }
        // Conflated, not restarted: an account push during a check must not throw the check away.
        snapshotFlow { latestHoldings }.conflate().collect {
            delay(400) // Coalesce typing and account pushes.
            val request = OrderRequest(symbol, draft.side, draft.kind, instrument.minLotsHundredths, entry, sourceSymbol = instrument.symbol.takeIf { instrument.isContinuous })
            val result = try { session.orderCapacity(request, cache.hint(brokerId, session.account.id, symbol, draft.side)) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { OrderCapacity(message = "可下单数量暂时无法确认，请刷新") }
            val maximum = result.maximumLotsHundredths
            if (maximum != null) cache.put(cacheKey, CachedCapacity(maximum, result.message)) else unavailable = result.message ?: "可下单数量未知"
            refreshing = false
        }
    }
    val state = when {
        cached != null -> QuantityCapacityState(cached.maximum, cached.message, loading = refreshing)
        unavailable != null -> QuantityCapacityState(message = unavailable)
        else -> QuantityCapacityState(message = "正在确认可下单数量", loading = true)
    }
    return state to { refreshing = true; refresh++ }
}

internal fun OrderPreview.withCapacity(request: OrderRequest?, capacity: QuantityCapacityState): OrderPreview {
    val maximum = capacity.maximum ?: return this
    if (request == null || request.lotsHundredths <= maximum) return this
    return copy(errors = errors + "下单数量超过当前可下单上限，请调整数量")
}
