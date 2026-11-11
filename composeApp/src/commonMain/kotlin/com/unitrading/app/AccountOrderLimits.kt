package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.serialization.Serializable

private val symbolCode = Regex("[@A-Z0-9_./-]{1,48}")
private val futuresContract = Regex("([A-Z0-9]+?)[FGHJKMNQUVXZ][0-9]{1,4}")
private val symbolSeparators = Regex("[,，、;；\\s]+")

internal fun parseRestrictedSymbols(text: String): Set<String>? {
    val codes = text.uppercase().split(symbolSeparators).filter(String::isNotEmpty).toSet()
    return codes.takeIf { it.size <= 200 && it.all(symbolCode::matches) }
}

@Serializable
internal data class AccountOrderLimits(
    val maxLots: Long? = null,
    val blockedSymbols: Set<String> = emptySet(),
) {
    fun valid() = (maxLots == null || maxLots in 1..999_999_999L) &&
        blockedSymbols.size <= 200 && blockedSymbols.all(symbolCode::matches)

    fun refusal(snapshot: AccountSnapshot, request: OrderRequest, replacing: PendingOrder? = null): String? {
        val codes = listOfNotNull(request.symbol, request.sourceSymbol).map { it.uppercase() }
        val blocked = blockedSymbols.firstOrNull { code ->
            val root = code.removePrefix("@")
            codes.any { candidate ->
                candidate == code || candidate.removePrefix("@") == root ||
                    futuresContract.matchEntire(candidate)?.groupValues?.get(1) == root
            }
        }
        if (blocked != null) return "该子账户禁止交易品种 $blocked。"
        val limit = maxLots ?: return null
        if (request.lotsHundredths <= 0) return "手数必须大于零。"
        val error = "超过子账户最大手数 ${formatFixed(limit, 2)}（含持仓和未成交开仓挂单）。"
        val proposed = (request.lotsHundredths - (replacing?.filledLotsHundredths ?: 0)).coerceAtLeast(0)
        if (proposed > limit) return error
        var remaining = limit - proposed
        for (position in snapshot.positions) {
            if (position.lotsHundredths > remaining) return error
            remaining -= position.lotsHundredths
        }
        for (order in snapshot.orders) {
            if (order.isProtection || order.id == replacing?.id) continue
            val openLots = (order.request.lotsHundredths - order.filledLotsHundredths).coerceAtLeast(0)
            if (openLots > remaining) return error
            remaining -= openLots
        }
        return null
    }
}
