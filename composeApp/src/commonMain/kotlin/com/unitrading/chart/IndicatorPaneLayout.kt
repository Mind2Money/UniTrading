package com.unitrading.chart

/** Pane fractions use the drawable chart height, excluding the time axis. Keep a quarter for price. */
internal const val MAX_PANE_AREA_FRACTION = .75f

internal data class IndicatorPaneBounds(val id: String, val top: Float, val height: Float) {
    val bottom: Float get() = top + height
}

internal fun minimumPaneFraction(count: Int, chartHeight: Float = 1f, minimumHeight: Float = 0f): Float =
    minOf(maxOf(.08f, minimumHeight / chartHeight.coerceAtLeast(1f)), .5f / count.coerceAtLeast(1))

/** Preserve equal legacy heights until a pane is resized; fit saved sizes when more panes are enabled. */
internal fun indicatorPaneLayout(top: Float, bottom: Float, ids: List<String>, preferred: Map<String, Float>, minimumHeight: Float = 0f): List<IndicatorPaneBounds> {
    if (ids.isEmpty() || bottom <= top) return emptyList()
    val minimum = minimumPaneFraction(ids.size, bottom - top, minimumHeight)
    val default = maxOf(minimum, minOf(.2f, .5f / ids.size))
    val fractions = ids.map { id -> preferred[id]?.takeIf { it.isFinite() }?.coerceIn(minimum, MAX_PANE_AREA_FRACTION) ?: default }
    val excess = fractions.sum() - minimum * ids.size
    val available = MAX_PANE_AREA_FRACTION - minimum * ids.size
    val fitted = if (excess > available) fractions.map { minimum + (it - minimum) * available / excess } else fractions
    val height = bottom - top
    var cursor = bottom - fitted.sum() * height
    return ids.mapIndexed { index, id ->
        IndicatorPaneBounds(id, cursor, fitted[index] * height).also { cursor = it.bottom }
    }
}

/** Resize only the selected pane, taking space from price while keeping every other pane's height. */
internal fun resizeIndicatorPane(
    layout: List<IndicatorPaneBounds>, id: String, fraction: Float, chartHeight: Float, preferred: Map<String, Float>, minimumHeight: Float = 0f,
): Map<String, Float> {
    if (chartHeight <= 0f || !fraction.isFinite() || layout.none { it.id == id }) return preferred
    val current = layout.associate { it.id to it.height / chartHeight }
    val minimum = minimumPaneFraction(layout.size, chartHeight, minimumHeight)
    val maximum = (MAX_PANE_AREA_FRACTION - current.filterKeys { it != id }.values.sum()).coerceAtLeast(minimum)
    return preferred + current + (id to fraction.coerceIn(minimum, maximum))
}
