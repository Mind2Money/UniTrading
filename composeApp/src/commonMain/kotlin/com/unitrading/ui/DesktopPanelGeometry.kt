package com.unitrading.ui

import com.unitrading.app.DesktopLayoutPreferences

internal const val PANEL_DIVIDER = 8f

/** The icon column at the window's right edge that folds and unfolds the panels. */
internal const val PANEL_RAIL = 48f

/** The title row every desktop panel shares; it is all that stays of the trade list when folded. */
internal const val PANEL_BAR = 48f
private const val CHART_MIN_WIDTH = 320f

internal data class PanelDimension(val value: Float, val min: Float, val max: Float)
internal data class DesktopPanelGeometry(
    val sidebar: PanelDimension,
    val order: PanelDimension,
    val accounts: PanelDimension,
    val terminal: PanelDimension,
)

/** Sizes for the panels of a [width] by [height] workspace; a folded panel takes no width, so the chart gets its space. */
internal fun desktopPanelGeometry(width: Float, height: Float, wide: Boolean, preferred: DesktopLayoutPreferences): DesktopPanelGeometry {
    fun dimension(value: Float, minimum: Float, maximum: Float): PanelDimension {
        val max = maximum.coerceAtLeast(0f)
        val min = minimum.coerceAtMost(max)
        return PanelDimension(value.coerceIn(min, max), min, max)
    }
    val closed = PanelDimension(0f, 0f, 0f)
    val orderOpen = wide && preferred.orderOpen
    val room = width - PANEL_RAIL - PANEL_DIVIDER * listOf(preferred.marketOpen, orderOpen).count { it } - CHART_MIN_WIDTH
    val sidebar = if (preferred.marketOpen) dimension(preferred.sidebarWidth, 280f, minOf(520f, room - if (orderOpen) 260f else 0f)) else closed
    val order = if (orderOpen) dimension(preferred.orderWidth, 260f, minOf(560f, room - sidebar.value)) else closed
    val availableHeight = (height - PANEL_DIVIDER).coerceAtLeast(0f)
    val terminalMin = minOf(128f, availableHeight * .35f)
    val accountsMin = minOf(100f, availableHeight * .4f)
    return DesktopPanelGeometry(
        if (preferred.marketOpen) sidebar.copy(max = minOf(sidebar.max, room - order.value).coerceAtLeast(sidebar.min)) else closed, order,
        dimension(preferred.accountsHeight, accountsMin, minOf(1000f, maxOf(accountsMin, availableHeight - 140f))),
        dimension(preferred.terminalHeight, terminalMin, minOf(1000f, maxOf(terminalMin, availableHeight - 240f))),
    )
}
