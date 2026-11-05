package com.unitrading.rithmic

import com.unitrading.broker.*
import kotlin.math.abs
import kotlin.math.roundToLong

/** Price scale of a contract: integer units per full-price unit, derived from the tick size. */
internal data class PriceScale(val decimals: Int) {
    val factor: Long = POWERS[decimals]
    fun units(price: Double): Long? {
        if (!price.isFinite()) return null
        val scaled = price * factor
        if (abs(scaled) > 9.0e15) return null
        return scaled.roundToLong()
    }
    fun price(units: Long): Double = units.toDouble() / factor
    companion object {
        private val POWERS = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
        /** Smallest scale that represents [tick] exactly (within floating-point noise), at most eight decimals. */
        fun forTick(tick: Double): PriceScale? {
            if (!tick.isFinite() || tick <= 0 || tick > 1.0e9) return null
            for (decimals in 0..8) {
                val scaled = tick * POWERS[decimals]
                if (abs(scaled - scaled.roundToLong()) < 1e-6 && scaled.roundToLong() >= 1) return PriceScale(decimals)
            }
            return null
        }
    }
}

/** Decimal text ("1234.5", "-12.25") to integer units with [decimals] places; rejects exponents and junk. */
internal fun decimalUnits(text: String?, decimals: Int): Long? {
    if (text == null || text.length > 40) return null
    val match = Regex("\\s*([+-]?)([0-9]+)(?:\\.([0-9]*))?\\s*").matchEntire(text) ?: return null
    val fraction = match.groupValues[3]
    val kept = fraction.take(decimals).padEnd(decimals, '0')
    val digits = (match.groupValues[2] + kept).trimStart('0').ifEmpty { "0" }
    val value = digits.toLongOrNull() ?: return null
    val roundUp = fraction.length > decimals && fraction[decimals] >= '5'
    val rounded = runCatching { checkedAdd(value, if (roundUp) 1 else 0) }.getOrNull() ?: return null
    return if (match.groupValues[1] == "-") -rounded else rounded
}
internal fun moneyMicros(text: String?): Long? = decimalUnits(text, 6)

/** Contract specification as the UI needs it, with the exchange Rithmic requires on every request. */
internal data class RithmicSpec(val instrument: Instrument, val exchange: String, val scale: PriceScale, val productCode: String?, val expiration: String?)

/** Reference data (template 15) to an instrument; only USD futures with a usable tick and point value qualify. */
internal fun mapInstrument(data: ProtoMessage): RithmicSpec? = runCatching {
    val symbol = requireNotNull(data.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 })
    val exchange = requireNotNull(data.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 })
    val type = data.str(Rf.INSTRUMENT_TYPE)?.trim()?.lowercase() ?: "future"
    require(type.isEmpty() || type == "future" || type == "futures")
    val currency = data.str(Rf.CURRENCY)?.trim()?.uppercase()?.ifEmpty { null } ?: "USD"
    require(currency == "USD")
    // Market data, bars and order prices are all quoted (q) prices, so the display scale and tick value use the
    // quoted tick. min_fprice_change is the f-price tick (e.g. 25.0 where the q-tick is 0.25); f*ftoq = q.
    val pointValue = requireNotNull(data.double(Rf.SINGLE_POINT_VALUE))
    val fTick = data.double(Rf.MIN_FPRICE_CHANGE)?.takeIf { it > 0 && it.isFinite() }
    val ftoq = data.double(Rf.FTOQ_PRICE)?.takeIf { it > 0 && it.isFinite() }
    val tick = data.double(Rf.MIN_QPRICE_CHANGE)?.takeIf { it > 0 && it.isFinite() }
        ?: fTick?.let { f -> ftoq?.let { f * it } } ?: requireNotNull(fTick)
    require(tick > 0 && pointValue > 0 && pointValue.isFinite())
    val scale = requireNotNull(PriceScale.forTick(tick))
    val tickUnits = requireNotNull(scale.units(tick))
    val tickValue = (tick * pointValue * MICROS_PER_USD).roundToLong()
    require(tickValue > 0)
    val name = data.str(Rf.SYMBOL_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: symbol
    val expiration = data.str(Rf.EXPIRATION_DATE)?.trim()?.takeIf { it.isNotEmpty() }
    val tradable = data.str(Rf.IS_TRADABLE)?.trim()?.lowercase()
    require(tradable == null || tradable.isEmpty() || tradable == "true" || tradable == "1" || tradable == "yes")
    RithmicSpec(
        Instrument(symbol, name, "期货 · $exchange${expiration?.let { " · $it" } ?: ""}", scale.decimals, tickUnits, tickValue,
            minLotsHundredths = 100, maxLotsHundredths = Int.MAX_VALUE.toLong() * 100, lotStepHundredths = 100,
            minStopTicks = 1, currency = currency, quantityUnit = "张", quantityDecimals = 0,
            displayPriceDecimals = scale.decimals, estimatesIncludeFees = false),
        exchange, scale, data.str(Rf.PRODUCT_CODE)?.trim()?.takeIf { it.isNotEmpty() }, expiration)
}.getOrNull()

/** Order state merged from Rithmic (351) and exchange (352) notifications and order snapshots. */
internal data class RithmicOrder(
    val basketId: String,
    val accountId: String,
    val symbol: String,
    val exchange: String,
    val status: String,
    val side: Side?,
    val quantity: Long,
    val price: Double?,
    val triggerPrice: Double?,
    val priceType: Int,
    val bracketType: Int,
    val originalBasketId: String?,
    val linkedBasketIds: String?,
    val userTag: String?,
    val filled: Long,
    val unfilled: Long?,
    val avgFillPrice: Double?,
    val completionReason: String?,
    val timeMs: Long,
    val sequence: String?,
) {
    val isBracketChild: Boolean get() = !originalBasketId.isNullOrBlank() && originalBasketId != basketId
    val active: Boolean get() = orderStatusActive(status, completionReason)
}

private val ACTIVE_STATUSES = setOf("open", "pending", "trigger pending", "open pending", "modify pending", "cancel pending",
    "modified", "modified, trigger pending", "partial", "partially filled", "partially_filled")
private val TERMINAL_STATUSES = setOf("complete", "completed", "filled", "cancelled", "canceled", "rejected", "expired")

internal fun orderStatusActive(status: String, completionReason: String?): Boolean {
    val normalized = status.trim().lowercase()
    if (normalized in TERMINAL_STATUSES) return false
    if (normalized in ACTIVE_STATUSES) return true
    return completionReason.isNullOrBlank()
}

internal fun parseOrderNotification(data: ProtoMessage, previous: RithmicOrder?, fallbackTime: Long): RithmicOrder? {
    val basketId = data.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val accountId = data.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.accountId ?: return null
    val symbol = data.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.symbol ?: return null
    val exchange = data.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.exchange ?: return null
    val status = data.str(Rf.STATUS)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.status ?: ""
    val side = when (data.int(Rf.TRANSACTION_TYPE)) { RithmicEnums.BUY -> Side.BUY; RithmicEnums.SELL -> Side.SELL; else -> previous?.side }
    val quantity = data.size(Rf.QUANTITY_64, Rf.QUANTITY)?.takeIf { it > 0 } ?: previous?.quantity ?: 0
    val priceType = data.int(Rf.PRICE_TYPE)?.takeIf { it > 0 } ?: previous?.priceType ?: 0
    return RithmicOrder(basketId, accountId, symbol, exchange, status, side, quantity,
        data.double(Rf.PRICE) ?: previous?.price, data.double(Rf.TRIGGER_PRICE) ?: previous?.triggerPrice, priceType,
        data.int(Rf.BRACKET_TYPE)?.takeIf { it > 0 } ?: previous?.bracketType ?: 0,
        data.str(Rf.ORIGINAL_BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.originalBasketId,
        data.str(Rf.LINKED_BASKET_IDS)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.linkedBasketIds,
        data.str(Rf.USER_TAG)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.userTag,
        data.size(Rf.TOTAL_FILL_SIZE_64, Rf.TOTAL_FILL_SIZE) ?: previous?.filled ?: 0, data.size(Rf.TOTAL_UNFILLED_SIZE_64, Rf.TOTAL_UNFILLED_SIZE) ?: previous?.unfilled,
        data.double(Rf.AVG_FILL_PRICE) ?: previous?.avgFillPrice,
        data.str(Rf.COMPLETION_REASON)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.completionReason,
        data.timeMs() ?: previous?.timeMs ?: fallbackTime, data.str(Rf.SEQUENCE_NUMBER) ?: previous?.sequence)
}

internal data class RithmicFill(val id: String, val basketId: String, val accountId: String, val symbol: String, val side: Side, val quantity: Long, val price: Double, val timeMs: Long)

internal fun parseFill(data: ProtoMessage, fallbackTime: Long): RithmicFill? {
    val id = data.str(Rf.FILL_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val basket = data.str(Rf.BASKET_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val account = data.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val symbol = data.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val side = when (data.int(Rf.TRANSACTION_TYPE)) { RithmicEnums.BUY -> Side.BUY; RithmicEnums.SELL -> Side.SELL; else -> when (data.str(Rf.TRANSACTION_TYPE)?.trim()?.lowercase()) { "buy", "b", "1" -> Side.BUY; "sell", "s", "ss", "2" -> Side.SELL; else -> null } } ?: return null
    val size = data.size(Rf.FILL_SIZE_64, Rf.FILL_SIZE)?.takeIf { it > 0 } ?: return null
    val price = data.double(Rf.FILL_PRICE)?.takeIf { it.isFinite() && it > 0 } ?: return null
    return RithmicFill(id, basket, account, symbol, side, size, price, data.timeMs() ?: fallbackTime)
}

/** Net position per account and contract from InstrumentPnLPositionUpdate (450). */
internal data class RithmicPosition(val accountId: String, val symbol: String, val exchange: String, val net: Long, val avgPrice: Double?, val openPnlMicros: Long?, val timeMs: Long)

/** proto3 drops zero quantities, so a record with no quantity field at all keeps the previous net rather than flattening it. */
internal fun parsePosition(data: ProtoMessage, previous: RithmicPosition?, fallbackTime: Long): RithmicPosition? {
    val account = data.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val symbol = data.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val exchange = data.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.exchange ?: ""
    val quantityFields = listOf(Rf.NET_QUANTITY, Rf.OPEN_POSITION_QUANTITY, Rf.FILL_BUY_QTY, Rf.FILL_SELL_QTY, Rf.BUY_QTY, Rf.SELL_QTY)
    val net = when {
        data.has(Rf.NET_QUANTITY) -> data.long(Rf.NET_QUANTITY) ?: 0
        data.bool(Rf.IS_SNAPSHOT) == true || quantityFields.any(data::has) || previous == null -> 0
        else -> previous.net
    }
    val avg = data.double(Rf.AVG_OPEN_FILL_PRICE)?.takeIf { it.isFinite() && it > 0 } ?: previous?.avgPrice?.takeIf { net != 0L && net == previous.net }
    return RithmicPosition(account, symbol, exchange, net, avg,
        if (data.has(Rf.OPEN_POSITION_PNL)) moneyMicros(data.str(Rf.OPEN_POSITION_PNL)) else previous?.openPnlMicros, data.timeMs() ?: fallbackTime)
}

/** Account money fields from AccountPnLPositionUpdate (451); every value stays unknown unless the server sent it. */
internal data class RithmicFinancials(
    val accountId: String,
    val balanceMicros: Long?,
    val cashMicros: Long?,
    val openPnlMicros: Long?,
    val closedPnlMicros: Long?,
    val dayPnlMicros: Long?,
    val dayOpenPnlMicros: Long?,
    val dayClosedPnlMicros: Long?,
    val usedBuyingPowerMicros: Long?,
    val availableBuyingPowerMicros: Long?,
    val timeMs: Long,
)

/**
 * Account value. The dev kit does not say whether account_balance already holds the open P&L, so the reading that sits
 * closer to cash + closed + open wins; adding it to a balance that holds it would count the open P&L twice.
 */
internal fun accountEquityMicros(money: RithmicFinancials): Long? {
    val balance = money.balanceMicros ?: money.cashMicros ?: return null
    val open = money.openPnlMicros ?: return null
    val added = checkedAdd(balance, open)
    val cash = money.cashMicros ?: return added
    val closed = money.closedPnlMicros ?: return added
    val gross = checkedAdd(checkedAdd(cash, closed), open)
    return if (abs(balance - gross) < abs(added - gross)) balance else added
}

internal fun parseFinancials(data: ProtoMessage, previous: RithmicFinancials?, fallbackTime: Long): RithmicFinancials? {
    val account = data.str(Rf.ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    fun money(field: Int, old: Long?): Long? = if (data.has(field)) moneyMicros(data.str(field)) ?: old else old
    return RithmicFinancials(account,
        money(Rf.ACCOUNT_BALANCE, previous?.balanceMicros), money(Rf.CASH_ON_HAND, previous?.cashMicros),
        money(Rf.OPEN_POSITION_PNL, previous?.openPnlMicros), money(Rf.CLOSED_POSITION_PNL, previous?.closedPnlMicros),
        money(Rf.DAY_PNL, previous?.dayPnlMicros), money(Rf.DAY_OPEN_PNL, previous?.dayOpenPnlMicros), money(Rf.DAY_CLOSED_PNL, previous?.dayClosedPnlMicros),
        money(Rf.USED_BUYING_POWER, previous?.usedBuyingPowerMicros), money(Rf.AVAILABLE_BUYING_POWER, previous?.availableBuyingPowerMicros),
        data.timeMs() ?: fallbackTime)
}

/**
 * Nearest unexpired contract of a product among search rows (template 110): the stand-in when the
 * front-month request answers "no data", as Rithmic Test does. Placeholder expirations (20991231) and the bare
 * product row Rithmic Paper Trading lists as a front-month alias are skipped.
 */
internal fun nearestContract(rows: List<ProtoMessage>, productCode: String, exchange: String, today: Int): Pair<String, String>? = rows.mapNotNull { row ->
    val symbol = row.str(Rf.SYMBOL)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 && it != productCode } ?: return@mapNotNull null
    val rowExchange = row.str(Rf.EXCHANGE)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    if (row.str(Rf.PRODUCT_CODE)?.trim() != productCode || !rowExchange.equals(exchange, ignoreCase = true)) return@mapNotNull null
    val type = row.str(Rf.INSTRUMENT_TYPE)?.trim()?.lowercase()
    if (!type.isNullOrEmpty() && type != "future" && type != "futures") return@mapNotNull null
    val expiration = row.str(Rf.EXPIRATION_DATE)?.trim()?.toIntOrNull()?.takeIf { it in 19_000_101..20_991_230 && it >= today } ?: return@mapNotNull null
    Triple(symbol, rowExchange, expiration)
}.minByOrNull { it.third }?.let { it.first to it.second }

/** Bracket levels of an entry order, in ticks from its price, as ShowBrackets/ShowBracketStops/BracketUpdates report them. */
internal data class BracketLevels(val stopTicks: Long? = null, val stopQuantity: Long? = null, val targetTicks: Long? = null, val targetQuantity: Long? = null)

internal fun parseTicks(data: ProtoMessage, field: Int): Long? = data.long(field) ?: data.str(field)?.trim()?.toLongOrNull()

/** Minute bars arrive with their close time; daily bars carry a YYYYMMDD date. Both become the bar's open time. */
internal fun barOpenTimeMs(marker: Long, timeframe: Timeframe): Long? {
    val daily = timeframe.minutes % 1440 == 0
    if (daily) {
        if (marker !in 19_000_101L..21_991_231L) return null
        val year = (marker / 10_000).toInt(); val month = (marker / 100 % 100).toInt(); val day = (marker % 100).toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching { kotlin.time.Instant.parse("${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T00:00:00Z").toEpochMilliseconds() }.getOrNull()
    }
    if (marker <= 0 || marker > 253_402_300_799L) return null
    return marker * 1_000 - timeframe.minutes * 60_000L
}

internal fun yyyymmdd(timeMs: Long): Int {
    val text = kotlin.time.Instant.fromEpochMilliseconds(timeMs).toString()
    return text.substring(0, 4).toInt() * 10_000 + text.substring(5, 7).toInt() * 100 + text.substring(8, 10).toInt()
}

/** Aligns a trade time to the start of its bar. */
internal fun barStart(timeMs: Long, timeframe: Timeframe): Long {
    val span = timeframe.minutes * 60_000L
    return timeMs - ((timeMs % span) + span) % span
}
