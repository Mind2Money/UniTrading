package com.unitrading.broker

const val MICROS_PER_USD = 1_000_000L

fun parseFixed(text: String, decimals: Int): Long? {
    if (decimals !in 0..8) return null
    val value = text.trim()
    if (!Regex("[+-]?[0-9]+(?:\\.[0-9]+)?").matches(value)) return null
    val negative = value.startsWith('-')
    val parts = value.removePrefix("-").removePrefix("+").split('.')
    val fraction = parts.getOrElse(1) { "" }
    if (fraction.length > decimals) return null
    val digits = parts[0] + fraction.padEnd(decimals, '0')
    return (if (negative) "-$digits" else digits).toLongOrNull()
}

fun formatFixed(value: Long, decimals: Int): String {
    require(decimals in 0..8)
    val sign = if (value < 0) "-" else ""
    val digits = value.toString().removePrefix("-").padStart(decimals + 1, '0')
    return if (decimals == 0) "$sign$digits" else "$sign${digits.dropLast(decimals)}.${digits.takeLast(decimals)}"
}

fun money(micros: Long, signed: Boolean = false): String {
    val cents = micros / 10_000
    return (if (signed && micros > 0) "+" else "") + formatFixed(cents, 2)
}

fun checkedMultiply(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    require(!(a == Long.MIN_VALUE && b == -1L) && !(b == Long.MIN_VALUE && a == -1L)) { "数值超过支持范围" }
    val result = a * b
    require(result / b == a) { "数值超过支持范围" }
    return result
}

fun checkedAdd(a: Long, b: Long): Long {
    val result = a + b
    require((a xor result) and (b xor result) >= 0) { "数值超过支持范围" }
    return result
}

fun grossPnl(instrument: Instrument, side: Side, lotsHundredths: Long, entry: Long, exit: Long): Long {
    require(entry > 0 && exit > 0 && lotsHundredths > 0 && instrument.tickSize > 0)
    val delta = if (side == Side.BUY) exit - entry else entry - exit
    require(delta % instrument.tickSize == 0L) { "价格不符合最小变动单位" }
    return checkedMultiply(checkedMultiply(delta / instrument.tickSize, instrument.tickValueMicrosPerLot), lotsHundredths) / 100
}

fun commission(instrument: Instrument, lotsHundredths: Long): Long =
    checkedMultiply(instrument.commissionMicrosPerLot, lotsHundredths) / 100

fun requiredMargin(instrument: Instrument, lotsHundredths: Long, entry: Long): Long {
    require(entry > 0 && entry % instrument.tickSize == 0L && instrument.leverage > 0)
    val numerator = checkedMultiply(checkedMultiply(entry / instrument.tickSize, instrument.tickValueMicrosPerLot), lotsHundredths)
    val denominator = 100L * instrument.leverage
    return numerator / denominator + if (numerator % denominator == 0L) 0 else 1
}

fun positionPnl(instrument: Instrument, position: Position, quote: Quote): Long = grossPnl(
    instrument, position.side, position.lotsHundredths, position.entryPrice,
    if (position.side == Side.BUY) quote.bid else quote.ask,
)

fun clockLabel(timeMs: Long, seconds: Boolean = false): String {
    val total = ((timeMs / 1000) % 86_400 + 86_400) % 86_400
    val hours = (total / 3600).toString().padStart(2, '0')
    val minutes = (total / 60 % 60).toString().padStart(2, '0')
    val secs = (total % 60).toString().padStart(2, '0')
    return "$hours:$minutes" + if (seconds) ":$secs" else ""
}
