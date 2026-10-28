package com.unitrading.tradovate

import com.unitrading.broker.*
import kotlinx.serialization.json.*

/** Read numeric JSON lexemes without a Double round-trip. Average prices use six decimals. */
internal fun decimalUnits(value: String?, decimals: Int): Long? {
    if (value == null || value.length > 80) return null
    val match = Regex("([+-]?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?").matchEntire(value) ?: return null
    val exponent = match.groupValues[4].ifEmpty { "0" }.toIntOrNull()?.takeIf { it in -18..18 } ?: return null
    val fraction = match.groupValues[3]
    val digits = (match.groupValues[2] + fraction).trimStart('0').ifEmpty { "0" }
    val shift = decimals + exponent - fraction.length
    val rounded = if (shift >= 0) (digits + "0".repeat(shift)).toLongOrNull() else {
        val removed = -shift
        val kept = digits.dropLast(removed).ifEmpty { "0" }.toLongOrNull() ?: return null
        val round = if (digits.length >= removed) digits[digits.length - removed] >= '5' else false
        runCatching { checkedAdd(kept, if (round) 1 else 0) }.getOrNull()
    } ?: return null
    return if (match.groupValues[1] == "-") -rounded else rounded
}
internal fun JsonObject.moneyValue(key: String): Long? = decimalUnits(str(key), 6)
internal fun JsonObject.priceValue(key: String): Long? = decimalUnits(str(key), 6)
internal fun jsonNumber(value: Long, decimals: Int): JsonElement = Json.parseToJsonElement(formatFixed(value, decimals))

/** Chart channel identifiers are protocol numbers/strings, not database Long IDs. */
internal fun chartSubscriptionKey(value: JsonElement?): String? {
    val primitive = value as? JsonPrimitive ?: return null
    val text = primitive.contentOrNull?.takeIf { it.length in 1..128 } ?: return null
    val number = Regex("([+-]?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?").matchEntire(text)
        ?: return if (primitive.isString) "string:$text" else null
    val exponent = number.groupValues[4].ifEmpty { "0" }.toIntOrNull()?.takeIf { it in -1000..1000 } ?: return null
    val fraction = number.groupValues[3]
    val digits = (number.groupValues[2] + fraction).trimStart('0')
    if (digits.isEmpty()) return "number:0"
    val significant = digits.trimEnd('0')
    val power = exponent - fraction.length + digits.length - significant.length
    val sign = if (number.groupValues[1] == "-") "-" else ""
    return "number:$sign${significant}e$power"
}

internal class EntityBook {
    private val entities = mutableMapOf<String, MutableMap<Long, JsonObject>>()
    private val versionIds = mutableMapOf<Long, Long>()
    var revision: Long = 0
        private set
    fun list(type: String): List<JsonObject> = entities[type]?.values?.toList() ?: emptyList()
    fun item(type: String, id: Long): JsonObject? = entities[type]?.get(id)
    fun apply(type: String, value: JsonObject, deleted: Boolean = false) {
        val id = value.long("id") ?: return
        val table = entities.getOrPut(type) { linkedMapOf() }
        if (deleted) table.remove(id)
        else {
            val previous = table[id]
            val previousTime = previous?.str("timestamp")?.let(::timestamp)
            val nextTime = value.str("timestamp")?.let(::timestamp)
            if (previousTime != null && nextTime != null && nextTime < previousTime) return
            table[id] = JsonObject((previous ?: emptyMap()) + value)
            if (type == "orderVersion") {
                val orderId = value.long("orderId") ?: return
                versionIds[orderId] = maxOf(versionIds[orderId] ?: 0, id)
            }
        }
        revision++
    }
    fun orderVersion(orderId: Long): JsonObject? = versionIds[orderId]?.let { item("orderVersion", it) }
    fun clear() { entities.clear(); versionIds.clear(); revision++ }
    fun snapshot(data: JsonObject) {
        clear()
        mapOf("accounts" to "account", "positions" to "position", "orders" to "order", "orderVersions" to "orderVersion",
            "cashBalances" to "cashBalance", "fills" to "fill", "fillPairs" to "fillPair", "contracts" to "contract",
            "contractMaturities" to "contractMaturity", "products" to "product", "currencies" to "currency").forEach { (plural, type) ->
            data.array(plural).forEach { apply(type, it) }
        }
    }
    fun event(data: JsonObject) {
        val type = data.str("entityType") ?: return
        val entity = data["entity"] as? JsonObject ?: return
        apply(type, entity, data.str("eventType").equals("Deleted", true))
    }
}

internal fun mapInstrument(contract: JsonObject, maturity: JsonObject, product: JsonObject, currency: String): Instrument? = runCatching {
    require(product.str("productType") == "Futures" && currency == "USD")
    val tick = requireNotNull(product.priceValue("tickSize"))
    val point = requireNotNull(product.moneyValue("valuePerPoint"))
    require(tick > 0 && point > 0)
    val tickValue = multiplyDivide(tick, point, 1, MICROS_PER_USD)
    require(tickValue > 0)
    val tickText = formatFixed(tick, 6).trimEnd('0').trimEnd('.')
    val digits = tickText.substringAfter('.', "").length
    Instrument(requireNotNull(contract.str("name")), product.str("description") ?: product.str("name") ?: "期货合约",
        "期货 · ${maturity.str("expirationMonth") ?: ""}", 6, tick, tickValue,
        minLotsHundredths = 100, maxLotsHundredths = 100_000, lotStepHundredths = 100,
        minStopTicks = 1, currency = currency, quantityUnit = "张", quantityDecimals = 0,
        displayPriceDecimals = digits, estimatesIncludeFees = false)
}.getOrNull()

internal fun activeOrder(order: JsonObject) = order.str("ordStatus") in setOf("Working", "Suspended", "PendingNew", "PendingReplace", "PendingCancel")
