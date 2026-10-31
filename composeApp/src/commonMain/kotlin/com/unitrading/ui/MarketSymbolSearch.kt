package com.unitrading.ui

import com.unitrading.broker.Instrument

internal fun marketSearchResults(instruments: List<Instrument>, query: String): List<Instrument> {
    val text = query.trim().uppercase()
    val continuousOnly = text.startsWith("@") || text.endsWith("1!")
    val root = text.removePrefix("@").removeSuffix("1!")
    return instruments.filter { instrument ->
        (!continuousOnly || instrument.isContinuous) &&
            (root.isEmpty() || instrument.symbol.removePrefix("@").startsWith(root, true) ||
                (!continuousOnly && instrument.name.contains(root, true)))
    }.sortedWith(compareBy<Instrument> {
        when {
            it.isContinuous && it.symbol.removePrefix("@").equals(root, true) -> 0
            it.symbol.equals(root, true) -> 1
            it.isContinuous -> 2
            else -> 3
        }
    }.thenBy { it.symbol })
}
