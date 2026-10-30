package com.unitrading.ui

import com.unitrading.app.DesktopLayoutPreferences

internal const val PANEL_DIVIDER = 8f
internal data class PanelDimension(val value: Float, val min: Float, val max: Float)
internal data class DesktopPanelGeometry(
    val sidebar: PanelDimension,
    val order: PanelDimension,
    val accounts: PanelDimension,
    val terminal: PanelDimension,
)

internal fun desktopPanelGeometry(width: Float, height: Float, wide: Boolean, preferred: DesktopLayoutPreferences): DesktopPanelGeometry {
    fun dimension(value: Float, minimum: Float, maximum: Float): PanelDimension {
        val max = maximum.coerceAtLeast(0f)
        val min = minimum.coerceAtMost(max)
        return PanelDimension(value.coerceIn(min, max), min, max)
    }
    val dividers = if (wide) PANEL_DIVIDER * 2 else PANEL_DIVIDER
    val sidebar = dimension(preferred.sidebarWidth, 180f, minOf(520f, width - dividers - 320f - if (wide) 260f else 0f))
    val order = if (wide) dimension(preferred.orderWidth, 260f, minOf(560f, width - sidebar.value - dividers - 320f)) else PanelDimension(0f, 0f, 0f)
    val availableHeight = (height - PANEL_DIVIDER).coerceAtLeast(0f)
    val terminalMin = minOf(128f, availableHeight * .35f)
    val accountsMin = minOf(100f, availableHeight * .4f)
    return DesktopPanelGeometry(
        sidebar.copy(max = minOf(sidebar.max, width - dividers - order.value - 320f).coerceAtLeast(sidebar.min)), order,
        dimension(preferred.accountsHeight, accountsMin, minOf(1000f, maxOf(accountsMin, availableHeight - 140f))),
        dimension(preferred.terminalHeight, terminalMin, minOf(1000f, maxOf(terminalMin, availableHeight - 240f))),
    )
}
