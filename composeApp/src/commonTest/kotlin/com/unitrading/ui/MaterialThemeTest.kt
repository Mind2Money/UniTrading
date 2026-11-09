package com.unitrading.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.unitrading.app.ThemePreset
import com.unitrading.app.ThemeSettings
import kotlin.math.abs
import kotlin.test.*

class MaterialThemeTest {
    private class Built(val name: String, val palette: TradingPalette, val scheme: ColorScheme)

    private fun built(colors: Map<String, String> = emptyMap()) = ThemePreset.entries.flatMap { preset -> listOf(true, false).map { systemDark ->
        val palette = themePalette(ThemeSettings(preset, colors), systemDark)
        Built("$preset (system dark: $systemDark, $colors)", palette, tradingColorScheme(palette, preset.isDark(systemDark)))
    } }

    private fun assertReads(ink: Color, background: Color, what: String, minimum: Float = 4.5f) {
        val ratio = contrast(ink, background)
        assertTrue(ratio >= minimum, "$what: ${colorHex(ink)} on ${colorHex(background)} is $ratio, needs $minimum")
    }

    private fun assertPairsRead(theme: Built) = with(theme.scheme) {
        listOf(
            "primary" to (onPrimary to primary), "brand" to (theme.palette.OnBrand to theme.palette.Brand),
            "primaryContainer" to (onPrimaryContainer to primaryContainer), "secondaryContainer" to (onSecondaryContainer to secondaryContainer),
            "tertiary" to (onTertiary to tertiary), "tertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
            "error" to (onError to error), "errorContainer" to (onErrorContainer to errorContainer),
        ).forEach { (role, pair) -> assertReads(pair.first, pair.second, "${theme.name} $role") }
    }

    /** Compose's stock purple-grey showed on every AlertDialog while these roles were left unmapped. */
    @Test fun everySurfaceRoleComesFromThePalette() {
        ThemePreset.entries.forEach { preset ->
            val dark = preset.isDark(systemDark = true)
            val palette = themePalette(ThemeSettings(preset), systemDark = true)
            val scheme = tradingColorScheme(palette, dark)
            val stock = if (dark) darkColorScheme() else lightColorScheme()
            listOf(scheme.surfaceContainerLow, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.secondaryContainer,
                scheme.primaryContainer, scheme.tertiaryContainer, scheme.errorContainer, scheme.outlineVariant).forEach { role ->
                assertFalse(role in listOf(stock.surfaceContainerLow, stock.surfaceContainerHigh, stock.surfaceContainerHighest, stock.secondaryContainer,
                    stock.primaryContainer, stock.tertiaryContainer, stock.errorContainer, stock.outlineVariant), "$preset uses a stock role")
            }
            assertEquals(palette.Accent, scheme.primary)
            assertEquals(palette.Loss, scheme.error)
            assertEquals(palette.Surface, scheme.surface)
        }
    }

    @Test fun userColoursReachTheDerivedContainers() {
        val plain = tradingColorScheme(themePalette(ThemeSettings(ThemePreset.DARK)), true)
        val custom = tradingColorScheme(themePalette(ThemeSettings(ThemePreset.DARK, mapOf("SURFACE" to "#402020", "ACCENT" to "#FFCC00"))), true)
        assertNotEquals(plain.surfaceContainerHigh, custom.surfaceContainerHigh)
        assertNotEquals(plain.secondaryContainer, custom.secondaryContainer)
    }

    @Test fun textOnFilledButtonsStaysReadable() {
        assertTrue(contentOn(themePalette(ThemeSettings(ThemePreset.DARK)).Accent).luminance() < .2f, "dark text on a light accent")
        assertTrue(contentOn(themePalette(ThemeSettings(ThemePreset.LIGHT)).Accent).luminance() > .8f, "light text on a deep accent")
    }

    @Test fun selectionStaysVisibleOnABlackPanel() {
        val scheme = tradingColorScheme(themePalette(ThemeSettings(ThemePreset.TRADINGV)), true)
        val contrast = (scheme.secondaryContainer.luminance() + .05f) / (scheme.surface.luminance() + .05f)
        assertTrue(contrast > 1.4f, "contrast $contrast")
    }

    @Test fun textReadsOnEveryPanelItCanSitOn() = built().forEach { theme ->
        with(theme.scheme) {
            listOf(surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh).forEach { panel ->
                listOf("onSurface" to onSurface, "onSurfaceVariant" to onSurfaceVariant, "primary" to primary, "error" to error).forEach { (role, ink) ->
                    assertReads(ink, panel, "${theme.name} $role")
                }
            }
        }
    }

    @Test fun contentReadsOnItsFill() = built().forEach(::assertPairsRead)

    /** A selected row in the market list carries red and green prices. */
    @Test fun pricesReadOnPanelsAndSelectedRows() = built().forEach { theme ->
        with(theme.palette) {
            listOf(Surface, Selection).forEach { background ->
                listOf("UpText" to UpText, "DownText" to DownText, "Profit" to Profit, "Loss" to Loss).forEach { (role, ink) ->
                    assertReads(ink, background, "${theme.name} $role")
                }
            }
        }
    }

    @Test fun controlOutlinesStandOutFromThePanel() = built().forEach { theme ->
        assertReads(theme.scheme.outline, theme.scheme.surface, "${theme.name} outline", 3f)
    }

    @Test fun panelsStepAwayFromThePanelColourInOrder() = built().forEach { theme ->
        with(theme.scheme) {
            val dark = surface.luminance() < .5f
            val tiers = listOf(surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest).map { it.tone }
            tiers.zipWithNext().forEach { (a, b) -> assertTrue(if (dark) b > a else b < a, "${theme.name} tiers $tiers") }
        }
    }

    @Test fun tradingVKeepsItsBlackPageAndBlueFillOnLiftedPanels() {
        val palette = themePalette(ThemeSettings(ThemePreset.TRADINGV))
        val scheme = tradingColorScheme(palette, true)
        assertEquals("#000000", colorHex(palette.Shell), "The page behind the panels stays black")
        assertEquals("#2962FF", colorHex(scheme.primaryContainer))
        assertEquals("#FFFFFF", colorHex(scheme.onPrimaryContainer))
        assertEquals("#2962FF", colorHex(palette.Brand))
        assertEquals(palette.Key, palette.Brand)
        assertTrue(palette.Surface.luminance() > palette.Shell.luminance(), "Panels are lifted off the page")
        assertTrue(contrast(palette.Accent, palette.Surface) > contrast(palette.Brand, palette.Surface), "Accent text is lighter than the fill")
    }

    @Test fun anyKeyColourKeepsEveryPairReadable() = listOf("#FFCC00", "#00FF00", "#FF00FF", "#00FFFF", "#7F0000", "#808080", "#000000", "#FFFFFF").forEach { key ->
        built(mapOf("ACCENT" to key)).forEach { theme ->
            assertPairsRead(theme)
            assertReads(theme.palette.Accent, theme.palette.Surface, "${theme.name} accent")
            assertEquals(key, colorHex(theme.palette.Key), "The theme dialog keeps showing what the user picked")
        }
    }

    @Test fun darkOrFaintCustomDirectionColoursStayReadableAsText() = built(mapOf("UP" to "#112233", "DOWN" to "#FFEEEE")).forEach { theme ->
        with(theme.palette) { listOf(Surface, Selection).forEach { assertReads(UpText, it, "${theme.name} UpText on ${colorHex(it)}") } }
    }

    @Test fun tonesLandWhereAsked() {
        listOf(Color(0xFF2962FF), Color(0xFF089981), Color(0xFFF23645), Color(0xFF808080)).forEach { color ->
            listOf(10f, 20f, 30f, 40f, 72f, 80f, 90f).forEach { tone ->
                assertTrue(abs(color.atTone(tone).tone - tone) < 1f, "${colorHex(color)} at tone $tone is ${color.atTone(tone).tone}")
            }
        }
        assertEquals(Color.White, Color(0xFF2962FF).atTone(100f))
        assertEquals(Color.Black, Color(0xFF2962FF).atTone(0f))
        assertEquals(0f, Color(0xFF131313).chroma, .003f)
    }

    @Test fun readableOnMovesOnlyAsFarAsItNeeds() {
        val ink = Color(0xFFEBEBEB)
        assertEquals(ink, ink.readableOn(Color(0xFF131313)), "Already readable")
        val lifted = Color(0xFF2962FF).readableOn(Color(0xFF131313))
        assertReads(lifted, Color(0xFF131313), "Lifted accent")
        assertTrue(contrast(lifted, Color(0xFF131313)) < 5.5f, "Stops near the target")
        assertReads(Color(0xFFFFCC00).readableOn(Color.White), Color.White, "Darkened on white")
    }

    @Test fun actionFillsCarryTheirContent() {
        listOf(0xFF089981, 0xFFF23645, 0xFF3987E5, 0xFFD95926, 0xFF2962FF, 0xFF81B7FF, 0xFF22AB94).forEach { value ->
            val fill = actionFill(Color(value))
            assertReads(contentOn(fill), fill, colorHex(Color(value)))
        }
        assertEquals("#2962FF", colorHex(actionFill(Color(0xFF2962FF))), "White already reads on it")
    }

    @Test fun shortIdKeepsTheReadableTail() {
        assertEquals("9", shortId("atlas-demo:position:9"))
        assertEquals("order:7".substringAfter(':'), shortId("order:7"))
        assertEquals("5551234", shortId("99955551234"))
    }
}
