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

/**
 * The unit a login's position cap is shown and typed in. Where every instrument the login lists trades whole contracts
 * (Tradovate, Rithmic) it is whole numbers of their own unit, 张; where any trades fractions, two decimals of 手 as on
 * the local demo. A login that lists none yet is a remote futures login whose contract specs have not loaded (the demo
 * always lists its instruments), so it is whole 张 too and the unit does not change under an open form. The cap itself
 * is kept in hundredths either way.
 */
internal class LotCapUnit private constructor(val whole: Boolean, val label: String) {
    /** [hundredths] in this unit. A whole unit drops a fraction, which whole contracts could not reach anyway. */
    fun format(hundredths: Long): String = if (whole) (hundredths / 100).toString() else formatFixed(hundredths, 2)

    /** A cap as typed, in hundredths; null for anything else, including a fraction where whole units are required. */
    fun parse(text: String): Long? =
        (if (whole) parseFixed(text, 0)?.let { runCatching { checkedMultiply(it, 100) }.getOrNull() } else parseFixed(text, 2))
            ?.takeIf { it in 1..999_999_999L }

    companion object {
        val LOTS = LotCapUnit(false, "手")

        fun of(instruments: List<Instrument>): LotCapUnit =
            if (instruments.isEmpty()) LotCapUnit(true, "张")
            else if (instruments.all { it.quantityDecimals == 0 }) LotCapUnit(true, instruments.first().quantityUnit)
            else LOTS
    }
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
     * what the action does to the account's exposure. Both limits let an exit through: in a blocked symbol, and on an
     * account whose total would be over the cap, where nothing else passes. So a stop, a target or a partial close also
     * goes through on an account that is already over a lowered cap, while an order that would reverse the position
     * does not, even when it leaves the total where it was. An exit never raises the total. [unit] only words the cap.
     */
    fun refusal(request: OrderRequest, effect: ExposureEffect, unit: LotCapUnit = LotCapUnit.LOTS): String? {
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
        return if (effect.after > limit && (effect.after > effect.before || !effect.neutral)) {
            "超过子账户最大持仓 ${unit.format(limit)} ${unit.label}（按持仓和全部挂单成交后的最大持仓计）。"
        } else null
    }
}
