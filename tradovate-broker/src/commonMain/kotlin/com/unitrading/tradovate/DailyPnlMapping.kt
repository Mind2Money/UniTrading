package com.unitrading.tradovate

import com.unitrading.broker.DailyPnl
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Use totalPnL exactly as supplied; deposits, missing fees and partial history cannot produce a substitute total. */
internal fun mapDailyPnl(snapshot: JsonObject, cashBalance: JsonObject?, receivedAtMs: Long): DailyPnl? {
    if (!snapshot.str("errorText").isNullOrBlank()) return null
    val total = snapshot.moneyValue("totalPnL") ?: return null
    val day = (cashBalance?.get("tradeDate") as? JsonObject)?.let { date ->
        val year = date.long("year")?.takeIf { it in 1970..9999 } ?: return@let null
        val month = date.long("month")?.takeIf { it in 1..12 } ?: return@let null
        val day = date.long("day")?.takeIf { it in 1..31 } ?: return@let null
        val text = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
        runCatching { Instant.parse("${text}T00:00:00Z"); text }.getOrNull()
    }
    return DailyPnl(total, snapshot.moneyValue("realizedPnL"), snapshot.moneyValue("openPnL"), day, receivedAtMs)
}
