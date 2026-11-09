package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.serialization.json.JsonObject

/** Entities that only feed the drawdown display: each row replaces the last, and neither can touch the trading state. */
internal val DRAWDOWN_ENTITY_TYPES = setOf("accountRiskStatus", "userAccountAutoLiq")

/**
 * Tradovate's "Drawdown Auto Liq Level", as its web trader computes that column in every trailing mode: the peak net
 * liquidation value the broker keeps (`accountRiskStatus.maxNetLiq`) less the trailing amount
 * (`userAccountAutoLiq.trailingMaxDrawdown`), held at `trailingMaxDrawdownLimit` once the trail reaches it. A real-time
 * (TDD) mode raises the broker's peak during the day and an end-of-day (EOD) mode at the close; neither changes the sum.
 * Without both figures no line is known: an account without the rule has no line, not a line at zero.
 */
internal fun tradovateDrawdownRule(status: JsonObject?, autoLiq: JsonObject?): DrawdownRule? {
    val peak = status?.moneyValue("maxNetLiq") ?: return null
    val amount = autoLiq?.moneyValue("trailingMaxDrawdown")?.takeIf { it > 0 } ?: return null
    val limit = autoLiq.moneyValue("trailingMaxDrawdownLimit")
    val trail = runCatching { checkedAdd(peak, -amount) }.getOrNull() ?: return null
    val held = limit != null && trail >= limit
    return DrawdownRule(if (held) limit else trail, amount, peak, limit, held, tradovateDrawdownMode(autoLiq.str("trailingMaxDrawdownMode")))
}

private fun tradovateDrawdownMode(mode: String?): DrawdownMode? = when (mode) {
    "RealTime" -> DrawdownMode.REAL_TIME
    "EOD" -> DrawdownMode.END_OF_DAY
    "SteppedRealTime" -> DrawdownMode.STEPPED_REAL_TIME
    "SteppedEOD" -> DrawdownMode.STEPPED_END_OF_DAY
    "Static" -> DrawdownMode.FIXED
    "Other" -> DrawdownMode.OTHER
    else -> null
}

/**
 * The value the room stands on: the equity beside it. That is cash plus open P&L while the account is flat or every position
 * has a fresh quote, otherwise the broker's snapshot, and with neither the room stays unknown.
 */
internal fun tradovateAccountValue(markedEquity: Long?, snapshotEquity: Long?, flat: Boolean): DrawdownAccountValue? = when {
    markedEquity != null -> DrawdownAccountValue(markedEquity, if (flat) DrawdownValueSource.CASH else DrawdownValueSource.QUOTES)
    snapshotEquity != null -> DrawdownAccountValue(snapshotEquity, DrawdownValueSource.SNAPSHOT)
    else -> null
}
