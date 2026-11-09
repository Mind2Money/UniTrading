package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.ThemeColor.*

data class TradingPalette(
    val Shell: Color, val Surface: Color, val Raised: Color, val Border: Color,
    val Chart: Color, val Grid: Color, val Ink: Color, val Muted: Color,
    /** Text, icons and thin marks in the theme's colour, readable on every panel: Material's primary role. */
    val Accent: Color,
    val Up: Color, val Down: Color, val Loss: Color, val Profit: Color,
    val Stop: Color, val Target: Color,
    /** The colour the user picks; every accent tone is derived from it. */
    val Key: Color,
    /** Fill of a screen's main action, and the content on it. */
    val Brand: Color, val OnBrand: Color,
    /** A selected row, kept quiet so that coloured prices stay readable on it. */
    val Selection: Color,
    /** [Up] and [Down] as text instead of a fill: readable on panels and on [Selection]. */
    val UpText: Color, val DownText: Color,
)

/**
 * Built-in colours of a preset. Entries in ThemeSettings.colors override single roles; raised/border are not editable.
 * [accentTone] and [selectionTone] place the accent and a selected row on the key colour's tones; [brandIsKey] fills main
 * actions with the key colour itself instead of the accent tone.
 */
private class BasePalette(
    val raised: Long, val border: Long, val accentTone: Float, val selectionTone: Float, val brandIsKey: Boolean,
    val roles: Map<com.unitrading.app.ThemeColor, Long>,
)

private val NightBase = BasePalette(0xFF282A2F, 0xFF44474E, accentTone = 80f, selectionTone = 16f, brandIsKey = false, mapOf(
    PAGE to 0xFF0C0D10, SURFACE to 0xFF14161A, CHART to 0xFF111317, GRID to 0xFF282B30,
    TEXT to 0xFFE3E3E8, MUTED to 0xFFC4C6D0, ACCENT to 0xFF0B57D0, UP to 0xFF3987E5, DOWN to 0xFFD95926,
    LOSS to 0xFFFF9B86, PROFIT to 0xFF8CD6B2, STOP to 0xFFECA492, TARGET to 0xFF94C9B4,
))
private val DayBase = BasePalette(0xFFE9EEF6, 0xFFC4C7CF, accentTone = 40f, selectionTone = 92f, brandIsKey = false, mapOf(
    PAGE to 0xFFF0F4F9, SURFACE to 0xFFFFFFFF, CHART to 0xFFFFFFFF, GRID to 0xFFE6EAF0,
    TEXT to 0xFF1B1C1F, MUTED to 0xFF44474E, ACCENT to 0xFF0B57D0, UP to 0xFF2169BE, DOWN to 0xFFBD4421,
    LOSS to 0xFFB3261E, PROFIT to 0xFF176B45, STOP to 0xFFB83D31, TARGET to 0xFF207553,
))
/**
 * TradingView's dark tokens, read from its chart page: a black page behind #131313 panels, #2962FF filling main actions,
 * accent text in the #82B1FF range. Text sits one step above TradingView's own #B8B8B8 and #8C8C8C for readability.
 */
private val TradingVBase = BasePalette(0xFF222222, 0xFF444444, accentTone = 72f, selectionTone = 13f, brandIsKey = true, mapOf(
    PAGE to 0xFF000000, SURFACE to 0xFF131313, CHART to 0xFF0F0F0F, GRID to 0xFF1C1C1C,
    TEXT to 0xFFEBEBEB, MUTED to 0xFFB8B8B8, ACCENT to 0xFF2962FF, UP to 0xFF089981, DOWN to 0xFFF23645,
    LOSS to 0xFFF7525F, PROFIT to 0xFF22AB94, STOP to 0xFFF23645, TARGET to 0xFF089981,
))

private const val SelectionChroma = .055f
private const val DirectionTextContrast = 5f

fun themePalette(settings: com.unitrading.app.ThemeSettings, systemDark: Boolean = true): TradingPalette {
    val base = when {
        settings.preset == com.unitrading.app.ThemePreset.TRADINGV -> TradingVBase
        settings.preset.isDark(systemDark) -> NightBase
        else -> DayBase
    }
    fun color(role: com.unitrading.app.ThemeColor): Color =
        settings.colors[role.name]?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: Color(base.roles.getValue(role))
    val key = color(ACCENT)
    val accent = key.atTone(base.accentTone).readableOn(color(SURFACE))
    val brand = if (base.brandIsKey) actionFill(key) else accent
    val selection = key.atTone(base.selectionTone, SelectionChroma)
    return TradingPalette(
        color(PAGE), color(SURFACE), Color(base.raised), Color(base.border), color(CHART), color(GRID), color(TEXT), color(MUTED),
        accent, color(UP), color(DOWN), color(LOSS), color(PROFIT), color(STOP), color(TARGET),
        Key = key, Brand = brand, OnBrand = contentOn(brand), Selection = selection,
        UpText = color(UP).readableOn(selection, DirectionTextContrast), DownText = color(DOWN).readableOn(selection, DirectionTextContrast),
    )
}

private val LocalTradingPalette = androidx.compose.runtime.staticCompositionLocalOf { themePalette(com.unitrading.app.ThemeSettings()) }
val TradingColors: TradingPalette @Composable get() = LocalTradingPalette.current

fun TradingPalette.pnl(value: Long?): Color = when { value == null || value == 0L -> Ink; value > 0 -> Profit; else -> Loss }

/** Amber between profit and loss. The palette has no role for it, so it follows the panel's brightness. */
internal val TradingPalette.Caution: Color get() = if (Surface.luminance() > .45f) Color(0xFF8A5A00) else Color(0xFFFFB74D)

/** Below this luminance a fill takes white content; white on saturated reds and greens reads better than its ratio says. */
private const val LightFill = .3f

/** Readable text colour on a filled [color]. */
internal fun contentOn(color: Color): Color = if (color.luminance() > LightFill) Color(0xFF101820) else Color.White

/** A filled action in [color]: darkened only as far as white text on it needs, and left alone when it takes dark text. */
internal fun actionFill(color: Color): Color = if (color.luminance() > LightFill) color else color.readableOn(Color.White)

/**
 * Every Material 3 colour role from the palette. Accent roles are tones of the key colour, surface roles step up from the
 * panel colour by Material's own distances, so a custom accent or panel colour reshapes the whole scheme.
 */
internal fun tradingColorScheme(palette: TradingPalette, dark: Boolean): ColorScheme = with(palette) {
    val neutral = if (Surface.chroma >= Shell.chroma) Surface else Shell
    fun tier(darkStep: Float, lightStep: Float) = neutral.atTone(Surface.tone + if (dark) darkStep else lightStep)
    fun Color.role(darkTone: Float, lightTone: Float, chroma: Float? = null) = atTone(if (dark) darkTone else lightTone, chroma)
    val fidelity = Brand != Accent
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = Accent, onPrimary = Key.role(18f, 100f), inversePrimary = Key.role(40f, 80f),
        primaryContainer = if (fidelity) Brand else Key.role(30f, 90f),
        onPrimaryContainer = if (fidelity) OnBrand else Key.role(90f, 10f),
        secondary = Key.role(80f, 40f, .05f), onSecondary = Key.role(20f, 100f, .05f),
        secondaryContainer = Key.role(22f, 90f, .08f), onSecondaryContainer = Key.role(88f, 12f, .07f),
        tertiary = Target.atTone(Accent.tone), onTertiary = Target.role(18f, 100f),
        tertiaryContainer = Target.role(28f, 90f), onTertiaryContainer = Target.role(90f, 10f),
        error = Loss, onError = Loss.role(10f, 100f), errorContainer = Loss.role(28f, 90f), onErrorContainer = Loss.role(90f, 10f),
        background = Shell, onBackground = Ink,
        surface = Surface, onSurface = Ink, surfaceTint = Accent,
        surfaceVariant = Raised, onSurfaceVariant = Muted,
        surfaceDim = if (dark) Shell else tier(0f, -13f), surfaceBright = if (dark) tier(16f, 0f) else Surface,
        surfaceContainerLowest = if (dark) tier(-2f, 0f) else Color.White,
        surfaceContainerLow = tier(3f, -2f),
        surfaceContainer = tier(5.5f, -4f),
        surfaceContainerHigh = tier(9f, -6.5f),
        surfaceContainerHighest = tier(14f, -10f),
        inverseSurface = Ink, inverseOnSurface = Surface,
        outline = neutral.role(48f, 50f), outlineVariant = Border,
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
    val palette = remember(settings, systemDark) { themePalette(settings, systemDark) }
    val scheme = remember(palette) { tradingColorScheme(palette, settings.preset.isDark(systemDark)) }
    androidx.compose.runtime.CompositionLocalProvider(LocalTradingPalette provides palette) {
        MaterialTheme(scheme, shapes = TradingShapes, typography = TradingTypography, content = content)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
