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

    /**
     * Why [request], a new order or a working one as an edit leaves it, is not allowed; null when it is. [effect] is
     * what the action does to the account's exposure. Both limits only stop what adds exposure: an exit passes in a
     * blocked symbol, and the cap refuses nothing that leaves the total where it was or lower, so a stop, a target or
     * a partial close also goes through on an account that is already over a lowered cap.
     */
    fun refusal(request: OrderRequest, effect: ExposureEffect): String? {
        val codes = listOfNotNull(request.symbol, request.sourceSymbol).map { it.uppercase() }
        val blocked = blockedSymbols.firstOrNull { code ->
            val root = code.removePrefix("@")
            codes.any { candidate ->
                candidate == code || candidate.removePrefix("@") == root ||
                    futuresContract.matchEntire(candidate)?.groupValues?.get(1) == root
            }
        }
        if (blocked != null && !effect.neutral) return "该子账户禁止交易品种 $blocked。"
        val limit = maxLots ?: return null
        if (request.lotsHundredths <= 0) return "手数必须大于零。"
        return if (effect.after > limit && effect.after > effect.before) {
            "超过子账户最大手数 ${formatFixed(limit, 2)}（含持仓和未成交开仓挂单）。"
        } else null
    }
}
