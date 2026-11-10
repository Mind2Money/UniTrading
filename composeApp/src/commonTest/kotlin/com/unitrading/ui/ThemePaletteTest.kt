package com.unitrading.ui

import com.unitrading.app.ThemePreset
import com.unitrading.app.ThemeSettings
import kotlin.test.*

class ThemePaletteTest {
    @Test fun tradingVPresetUsesTradingViewBlackColours() {
        val palette = themePalette(ThemeSettings(ThemePreset.TRADINGV), systemDark = false)
        assertEquals("#000000", colorHex(palette.Shell))
        assertEquals("#131313", colorHex(palette.Surface))
        assertEquals("#222222", colorHex(palette.Raised))
        assertEquals("#444444", colorHex(palette.Border))
        assertEquals("#0F0F0F", colorHex(palette.Chart))
        assertEquals("#1C1C1C", colorHex(palette.Grid))
        assertEquals("#EBEBEB", colorHex(palette.Ink))
        assertEquals("#B8B8B8", colorHex(palette.Muted))
        assertEquals("#2962FF", colorHex(palette.Key))
        assertEquals("#2962FF", colorHex(palette.Brand))
        assertEquals("#089981", colorHex(palette.Up))
        assertEquals("#F23645", colorHex(palette.Down))
        assertEquals("#22AB94", colorHex(palette.Profit))
        assertEquals("#F7525F", colorHex(palette.Loss))
        assertEquals("#F23645", colorHex(palette.Stop))
        assertEquals("#089981", colorHex(palette.Target))
        assertTrue(ThemePreset.TRADINGV.isDark(systemDark = false))
        assertEquals("TradingV", ThemePreset.TRADINGV.label)
    }

    @Test fun overridesReplaceSingleRolesAndStandardPresetsUseUpdatedColours() {
        val custom = themePalette(ThemeSettings(ThemePreset.TRADINGV, mapOf("UP" to "#112233")))
        assertEquals("#112233", colorHex(custom.Up))
        assertEquals("#F23645", colorHex(custom.Down))
        val dark = themePalette(ThemeSettings(ThemePreset.DARK))
        assertEquals("#101114", colorHex(dark.Shell))
        assertEquals("#2C2C2E", colorHex(dark.Raised))
        assertEquals("#3987E5", colorHex(dark.Up))
        assertEquals("#94C9B4", colorHex(dark.Target))
        assertEquals("#007AFF", colorHex(dark.Key))
        val light = themePalette(ThemeSettings(ThemePreset.LIGHT))
        assertEquals("#F5F5F7", colorHex(light.Shell))
        assertEquals("#DEDEE3", colorHex(light.Border))
        assertEquals("#2169BE", colorHex(light.Up))
        assertEquals("#207553", colorHex(light.Target))
        assertEquals("#007AFF", colorHex(light.Key))
        assertEquals(colorHex(dark.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = true).Chart))
        assertEquals(colorHex(light.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = false).Chart))
    }

    /** The accent the panels show is a tone of the key colour; the key colour itself stays what the user picked. */
    @Test fun accentToneIsDerivedFromTheKeyColour() {
        val palette = themePalette(ThemeSettings(ThemePreset.DARK, mapOf("ACCENT" to "#7F00FF")))
        assertEquals("#7F00FF", colorHex(palette.Key))
        assertNotEquals("#7F00FF", colorHex(palette.Accent))
        assertTrue(palette.Accent.tone > 70f, "A dark theme's accent is a light tone")
        assertTrue(themePalette(ThemeSettings(ThemePreset.LIGHT, mapOf("ACCENT" to "#7F00FF"))).Accent.tone < 50f, "A light theme's accent is a deep tone")
    }
}
