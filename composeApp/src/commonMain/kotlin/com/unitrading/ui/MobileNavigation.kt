package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private class Destination(val label: String, val icon: () -> ImageVector, val selectedIcon: () -> ImageVector)

private val destinations = listOf(
    Destination("行情", { AppIcons.TrendingUp }, { AppIcons.TrendingUp }),
    Destination("图表", { AppIcons.Candlestick }, { AppIcons.CandlestickFilled }),
    Destination("交易", { AppIcons.ReceiptLong }, { AppIcons.ReceiptLongFilled }),
    Destination("设置", { AppIcons.Settings }, { AppIcons.SettingsFilled }),
)

internal val MobileNavigationHeight = 64.dp

/** Bottom bar of compact windows: the 64dp bar leaves the chart more height than the 80dp default. */
@Composable
internal fun MobileNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(Modifier.height(MobileNavigationHeight).testTag("mobile-navigation"), tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
        destinations.forEachIndexed { index, destination ->
            NavigationBarItem(selected == index, { onSelect(index) }, {
                Icon(if (selected == index) destination.selectedIcon() else destination.icon(), contentDescription = null)
            }, Modifier.testTag("nav-$index"), label = { Text(destination.label) }, alwaysShowLabel = true)
        }
    }
}

/** Side navigation once a compact window is at least 600dp wide (landscape phones, portrait tablets). */
@Composable
internal fun MobileNavigationRail(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier.testTag("mobile-navigation"), windowInsets = WindowInsets(0, 0, 0, 0)) {
        Spacer(Modifier.weight(1f))
        destinations.forEachIndexed { index, destination ->
            NavigationRailItem(selected == index, { onSelect(index) }, {
                Icon(if (selected == index) destination.selectedIcon() else destination.icon(), contentDescription = null)
            }, Modifier.testTag("nav-$index"), label = { Text(destination.label) }, alwaysShowLabel = true)
        }
        Spacer(Modifier.weight(1f))
    }
}
