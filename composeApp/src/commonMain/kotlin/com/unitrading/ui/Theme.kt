package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
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

/** Amber between profit and loss. The palette has no role for it, so it follows the panel's brightness. */
internal val TradingPalette.Caution: Color get() = if (Surface.luminance() > .45f) Color(0xFF8A5A00) else Color(0xFFFFB74D)

/** Readable text colour on a filled [color]. */
internal fun contentOn(color: Color): Color = if (color.luminance() > .45f) Color(0xFF101820) else Color.White

/**
 * Every Material 3 colour role, derived from the palette so user colours stay coherent: containers tint the panel colour
 * with their role, and the surface ramp steps from the panel colour towards the text colour.
 */
internal fun tradingColorScheme(palette: TradingPalette, dark: Boolean): ColorScheme = with(palette) {
    fun container(role: Color, amount: Float) = lerp(Surface, role, amount)
    // On a dark panel a deep accent needs a stronger mix to show at all.
    val selection = container(Accent, if (dark && Accent.luminance() < .25f) .6f else .16f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = Accent, onPrimary = contentOn(Accent), inversePrimary = lerp(Accent, Surface, .35f),
        primaryContainer = container(Accent, .22f), onPrimaryContainer = Ink,
        secondary = Accent, onSecondary = contentOn(Accent),
        secondaryContainer = selection, onSecondaryContainer = Ink,
        tertiary = Target, onTertiary = contentOn(Target),
        tertiaryContainer = container(Target, .18f), onTertiaryContainer = Ink,
        error = Loss, onError = contentOn(Loss), errorContainer = container(Loss, .18f), onErrorContainer = Ink,
        background = Shell, onBackground = Ink,
        surface = Surface, onSurface = Ink, surfaceTint = Accent,
        surfaceVariant = Raised, onSurfaceVariant = Muted,
        surfaceDim = Shell, surfaceBright = lerp(Surface, Ink, .12f),
        surfaceContainerLowest = if (dark) Shell else Surface,
        surfaceContainerLow = if (dark) lerp(Shell, Surface, .5f) else lerp(Surface, Shell, .5f),
        surfaceContainer = Surface,
        surfaceContainerHigh = lerp(Surface, Ink, .05f),
        surfaceContainerHighest = lerp(Surface, Ink, .09f),
        inverseSurface = Ink, inverseOnSurface = Surface,
        outline = Border, outlineVariant = lerp(Surface, Border, .6f),
    )
}

/** Material 3 baseline type scale, with tabular figures everywhere so prices and amounts line up in columns. */
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp, fontFeatureSettings = "tnum")

private val TradingTypography = Typography(
    displayLarge = type(57, 64), displayMedium = type(45, 52), displaySmall = type(36, 44),
    headlineLarge = type(32, 40), headlineMedium = type(28, 36), headlineSmall = type(24, 32),
    titleLarge = type(20, 28, FontWeight.Medium), titleMedium = type(16, 24, FontWeight.Medium), titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 16, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium),
)

/** Material 3 shape scale. */
private val TradingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TradingTheme(settings: com.unitrading.app.ThemeSettings = com.unitrading.app.ThemeSettings(), content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val palette = themePalette(settings, systemDark)
    androidx.compose.runtime.CompositionLocalProvider(LocalTradingPalette provides palette) {
        MaterialTheme(tradingColorScheme(palette, settings.preset.isDark(systemDark)), shapes = TradingShapes,
            typography = TradingTypography, content = content)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
