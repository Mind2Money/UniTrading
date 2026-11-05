package com.unitrading.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import com.unitrading.app.ThemePreset
import com.unitrading.app.ThemeSettings
import kotlin.test.*

class MaterialThemeTest {
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

    @Test fun shortIdKeepsTheReadableTail() {
        assertEquals("9", shortId("atlas-demo:position:9"))
        assertEquals("order:7".substringAfter(':'), shortId("order:7"))
        assertEquals("5551234", shortId("99955551234"))
    }
}
