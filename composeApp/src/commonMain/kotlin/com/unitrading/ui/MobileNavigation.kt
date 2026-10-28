package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val navigationIcons = listOf("行情", "图表", "交易", "账户").mapIndexed { index, name ->
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (index) {
                0 -> { moveTo(3f, 17f); lineTo(8f, 12f); lineTo(12f, 15f); lineTo(21f, 6f); moveTo(16f, 6f); lineTo(21f, 6f); lineTo(21f, 11f) }
                1 -> {
                    moveTo(7f, 3f); lineTo(7f, 6f); moveTo(7f, 15f); lineTo(7f, 20f)
                    moveTo(4f, 6f); lineTo(10f, 6f); lineTo(10f, 15f); lineTo(4f, 15f); close()
                    moveTo(17f, 4f); lineTo(17f, 10f); moveTo(17f, 18f); lineTo(17f, 21f)
                    moveTo(14f, 10f); lineTo(20f, 10f); lineTo(20f, 18f); lineTo(14f, 18f); close()
                }
                2 -> { moveTo(3f, 7f); lineTo(20f, 7f); lineTo(16f, 3f); moveTo(21f, 17f); lineTo(4f, 17f); lineTo(8f, 21f) }
                else -> {
                    moveTo(8f, 7f); curveTo(8f, 1.7f, 16f, 1.7f, 16f, 7f); curveTo(16f, 12.3f, 8f, 12.3f, 8f, 7f); close()
                    moveTo(4f, 21f); lineTo(4f, 18f); curveTo(4f, 12.5f, 20f, 12.5f, 20f, 18f); lineTo(20f, 21f); close()
                }
            }
        }
    }.build()
}

@Composable
internal fun MobileNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = TradingColors.Surface, tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.testTag("mobile-navigation")) {
        listOf("行情", "图表", "交易", "账户").forEachIndexed { index, label ->
            NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                icon = { Icon(navigationIcons[index], contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = { Text(label) }, alwaysShowLabel = true, modifier = Modifier.testTag("nav-$index"),
                colors = NavigationBarItemDefaults.colors(selectedIconColor = TradingColors.Accent,
                    selectedTextColor = TradingColors.Ink, indicatorColor = TradingColors.Raised,
                    unselectedIconColor = TradingColors.Muted, unselectedTextColor = TradingColors.Muted))
        }
    }
}
