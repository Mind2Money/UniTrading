package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlin.math.abs
import kotlin.math.roundToLong

/** One account's auto-liquidation figures, from its account row (303), RMS settings (305) and RMS updates (356). */
internal data class RithmicRisk(
    val criteria: String? = null,
    val thresholdMicros: Long? = null,
    val maxMinBalanceMicros: Long? = null,
    val lossLimitMicros: Long? = null,
    val minBalanceMicros: Long? = null,
    val currentMicros: Long? = null,
    val peakMicros: Long? = null,
)

private fun Long?.positive(): Long? = this?.takeIf { it > 0 }
private fun cleanText(value: String?): String? = value?.filterNot(Char::isISOControl)?.trim()?.take(300)?.takeIf { it.isNotEmpty() }

/** The account's RMS settings (template 305), over the line [previous] holds; without a row [previous] stands. */
internal fun parseRiskSettings(parts: List<ProtoMessage>, accountId: String, previous: RithmicRisk?): RithmicRisk? {
    val row = parts.lastOrNull { it.str(Rf.ACCOUNT_ID)?.trim() == accountId }
        ?: parts.lastOrNull { !it.has(Rf.ACCOUNT_ID) && it.has(Rf.AUTO_LIQUIDATE_CRITERIA) } ?: return previous
    fun money(field: Int): Long? = row.double(field)?.takeIf { it.isFinite() && abs(it) < 9.0e12 }?.let { (it * MICROS_PER_USD).roundToLong() }
    return RithmicRisk(cleanText(row.str(Rf.AUTO_LIQUIDATE_CRITERIA)), money(Rf.AUTO_LIQUIDATE_THRESHOLD), money(Rf.AUTO_LIQUIDATE_MAX_MIN_BALANCE),
        money(Rf.LOSS_LIMIT), money(Rf.MIN_ACCOUNT_BALANCE), previous?.currentMicros, previous?.peakMicros)
}

/** The line as it stands: an account row (303) or an RMS update (356). Absent or empty fields keep their value. */
internal fun parseRiskUpdate(message: ProtoMessage, previous: RithmicRisk?): RithmicRisk {
    fun money(field: Int, old: Long?): Long? = (if (message.has(field)) moneyMicros(message.str(field)) else null) ?: old
    return (previous ?: RithmicRisk()).copy(currentMicros = money(Rf.AUTO_LIQ_THRESHOLD_CURRENT_VALUE, previous?.currentMicros),
        peakMicros = money(Rf.AUTO_LIQ_PEAK_BALANCE, previous?.peakMicros))
}

/** The balance at the last close: today's closed P&L taken out, so a day's gains are no close. Unknown without that P&L. */
internal fun closingBalanceMicros(money: RithmicFinancials?): Long? {
    if (money == null) return null
    val balance = runCatching { accountValueMicros(money) }.getOrNull()?.first ?: return null
    val day = money.dayClosedPnlMicros ?: return null
    return runCatching { checkedAdd(balance, -day) }.getOrNull()
}

/**
 * The drawdown line Rithmic reports. `auto_liq_threshold_current_value` is the balance floor as it stands now, so the
 * broker's own figure is the line. What `auto_liquidate_threshold` holds depends on the criterion: the floor under a plain
 * Minimum Account Balance, the distance under the trailing and end-of-day criteria. Multiple Simultaneous Criteria names no
 * sub-rule, its limits being in R | Trader's Set dialog; on the real account seen with it, EOD by its owner's word, no
 * current value came, the threshold was the drawdown and `auto_liquidate_max_min_account_balance` its cap, so such a rule
 * reads as EOD. An end-of-day line without a current value is the last close less the drawdown, read live with nothing
 * kept, so the room is the drawdown plus the day's P&L. It stays at or above `min_account_balance` under that criterion and
 * is held at the cap. A real end-of-day line does not fall and this one falls with a losing day, so the room then reads too
 * large. The percentage criteria hold a percentage, and a loss or profit limit is an amount of money even with a current
 * value: no line. A rule without a line keeps a null level and its figures for the display to name.
 */
internal fun rithmicDrawdownRule(risk: RithmicRisk?, money: RithmicFinancials?): DrawdownRule? {
    if (risk == null) return null
    val name = risk.criteria.orEmpty()
    val criteria = name.lowercase()
    val floor = criteria == "minimum account balance"
    val several = "multiple" in criteria
    val endOfDay = "end of day" in criteria || several
    val trailing = "trail" in criteria || endOfDay
    val threshold = risk.thresholdMicros.positive()
    val minBalance = money?.minBalanceMicros.positive() ?: risk.minBalanceMicros.positive()
    val cap = risk.maxMinBalanceMicros.positive()
    val limit = if ("limited" in criteria || several) cap else null
    val close = if (endOfDay) closingBalanceMicros(money).positive() else null
    val trailed = if (close != null && threshold != null) (close - threshold).let { if (limit != null) minOf(it, limit) else it }.positive() else null
    val current = if ("loss" in criteria || "profit" in criteria) null else risk.currentMicros.positive()
    val level = current ?: listOfNotNull(if (floor) threshold else if (several) minBalance else null, trailed).maxOrNull()
    if (level == null && criteria.isEmpty()) return null
    val fromClose = current == null && trailed != null && level == trailed
    val peak = risk.peakMicros.positive() ?: if (fromClose) close else null
    val amount = (if (trailing) threshold else null) ?: level?.let { line -> peak?.let { it - line } }?.takeIf { it > 0 }
    return DrawdownRule(level, amount, peak, limit, limit != null && level != null && level >= limit,
        when { endOfDay -> DrawdownMode.END_OF_DAY; trailing -> DrawdownMode.REAL_TIME; floor -> DrawdownMode.FIXED; criteria.isEmpty() -> null; else -> DrawdownMode.OTHER },
        reported = true,
        lineSource = when { level == null -> null; current != null -> DrawdownLineSource.CURRENT_VALUE; fromClose -> DrawdownLineSource.LAST_CLOSE
            floor -> DrawdownLineSource.THRESHOLD; else -> DrawdownLineSource.MIN_BALANCE },
        criteria = name.ifEmpty { null }, thresholdMicros = threshold, balanceCapMicros = cap, lossLimitMicros = risk.lossLimitMicros.positive(),
        minBalanceMicros = minBalance, lossPercentMicros = money?.lossPercentMicros)
}

/**
 * The value the room stands on: the balance plus every position's own open P&L while all are known, so the room follows
 * the P&L plant, and a flat account's balance alone. Otherwise the account-level figure, and with neither it stays unknown.
 */
internal fun rithmicAccountValue(money: RithmicFinancials?, positions: List<RithmicPosition>): DrawdownAccountValue? {
    val values = money?.let { runCatching { accountValueMicros(it) }.getOrNull() }
    val balance = values?.first
    val open = positions.mapNotNull { it.openPnlMicros }
    if (balance != null && open.size == positions.size) {
        val value = runCatching { open.fold(balance, ::checkedAdd) }.getOrNull()
        if (value != null) return DrawdownAccountValue(value, if (positions.isEmpty()) DrawdownValueSource.CASH else DrawdownValueSource.BROKER)
    }
    return values?.second?.let { DrawdownAccountValue(it, DrawdownValueSource.SNAPSHOT) }
}
