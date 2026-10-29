package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.ThemeColor.*

data class TradingPalette(
    val Shell: Color, val Surface: Color, val Raised: Color, val Border: Color,
    val Chart: Color, val Grid: Color, val Ink: Color, val Muted: Color,
    val Accent: Color, val Up: Color, val Down: Color, val Loss: Color, val Profit: Color,
    val Stop: Color, val Target: Color,
)

/** Built-in colours of a preset. Entries in ThemeSettings.colors override single roles; raised/border are not editable. */
private class BasePalette(val raised: Long, val border: Long, val roles: Map<com.unitrading.app.ThemeColor, Long>)

private val NightBase = BasePalette(0xFF24364D, 0xFF40536B, mapOf(
    PAGE to 0xFF0F1B2D, SURFACE to 0xFF16263B, CHART to 0xFF132237, GRID to 0xFF2A3B50,
    TEXT to 0xFFE9F0F7, MUTED to 0xFFA9B8CD, ACCENT to 0xFF81B7FF, UP to 0xFF3987E5, DOWN to 0xFFD95926,
    LOSS to 0xFFFF9B86, PROFIT to 0xFF8CD6B2, STOP to 0xFFECA492, TARGET to 0xFF94C9B4,
))
private val DayBase = BasePalette(0xFFE4EBF5, 0xFFC5CFDD, mapOf(
    PAGE to 0xFFF4F7FC, SURFACE to 0xFFFFFFFF, CHART to 0xFFFFFFFF, GRID to 0xFFE4E9F0,
    TEXT to 0xFF16263B, MUTED to 0xFF526276, ACCENT to 0xFF245EA8, UP to 0xFF2169BE, DOWN to 0xFFBD4421,
    LOSS to 0xFFB3261E, PROFIT to 0xFF176B45, STOP to 0xFFB83D31, TARGET to 0xFF207553,
))
/** TradingView black theme as sampled from its chart page: black shell, #0F0F0F chart, grey text, blue accent, teal/red candles. */
private val TradingVBase = BasePalette(0xFF1A1A1A, 0xFF2B2B2B, mapOf(
    PAGE to 0xFF000000, SURFACE to 0xFF000000, CHART to 0xFF0F0F0F, GRID to 0xFF1C1C1C,
    TEXT to 0xFFB8B8B8, MUTED to 0xFF8C8C8C, ACCENT to 0xFF2962FF, UP to 0xFF089981, DOWN to 0xFFF23645,
    LOSS to 0xFFF23645, PROFIT to 0xFF22AB94, STOP to 0xFFF23645, TARGET to 0xFF089981,
))

fun themePalette(settings: com.unitrading.app.ThemeSettings, systemDark: Boolean = true): TradingPalette {
    val base = when {
        settings.preset == com.unitrading.app.ThemePreset.TRADINGV -> TradingVBase
        settings.preset.isDark(systemDark) -> NightBase
        else -> DayBase
    }
    fun color(role: com.unitrading.app.ThemeColor): Color =
        settings.colors[role.name]?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: Color(base.roles.getValue(role))
    return TradingPalette(
        color(PAGE), color(SURFACE), Color(base.raised), Color(base.border), color(CHART), color(GRID), color(TEXT), color(MUTED),
        color(ACCENT), color(UP), color(DOWN), color(LOSS), color(PROFIT), color(STOP), color(TARGET),
    )
}

private val LocalTradingPalette = androidx.compose.runtime.staticCompositionLocalOf { themePalette(com.unitrading.app.ThemeSettings()) }
val TradingColors: TradingPalette @Composable get() = LocalTradingPalette.current

fun TradingPalette.pnl(value: Long?): Color = when { value == null || value == 0L -> Ink; value > 0 -> Profit; else -> Loss }

@Composable
fun TradingTheme(settings: com.unitrading.app.ThemeSettings = com.unitrading.app.ThemeSettings(), content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val palette = themePalette(settings, systemDark)
    val dark = settings.preset.isDark(systemDark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalTradingPalette provides palette) {
        MaterialTheme(
            colorScheme = base.copy(
                primary = palette.Accent,
                onPrimary = if (palette.Accent.luminance() > .45f) Color(0xFF101820) else Color.White,
                primaryContainer = palette.Raised, onPrimaryContainer = palette.Ink,
                secondary = palette.Accent, secondaryContainer = palette.Raised, onSecondaryContainer = palette.Ink,
                background = palette.Shell, onBackground = palette.Ink,
                surface = palette.Surface, surfaceVariant = palette.Raised, surfaceContainer = palette.Surface,
                onSurface = palette.Ink, onSurfaceVariant = palette.Muted, outline = palette.Border, error = palette.Loss,
            ),
            typography = Typography(
                bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            ),
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(24.dp)),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (subtitle != null) Text(subtitle, color = TradingColors.Muted, fontSize = 11.sp)
    }
}

@Composable
fun Badge(text: String, color: Color = TradingColors.Accent) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = TradingColors.Ink, fontSize = 10.sp)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = TradingColors.Border.copy(alpha = 0.65f))

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = TradingColors.Muted, fontSize = 12.sp)
    }
}
