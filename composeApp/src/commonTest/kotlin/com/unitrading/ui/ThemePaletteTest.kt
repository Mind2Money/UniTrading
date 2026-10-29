package com.unitrading.ui

import com.unitrading.app.ThemePreset
import com.unitrading.app.ThemeSettings
import kotlin.test.*

class ThemePaletteTest {
    @Test fun tradingVPresetUsesTradingViewBlackColours() {
        val palette = themePalette(ThemeSettings(ThemePreset.TRADINGV), systemDark = false)
        assertEquals("#000000", colorHex(palette.Shell))
        assertEquals("#000000", colorHex(palette.Surface))
        assertEquals("#1A1A1A", colorHex(palette.Raised))
        assertEquals("#2B2B2B", colorHex(palette.Border))
        assertEquals("#0F0F0F", colorHex(palette.Chart))
        assertEquals("#1C1C1C", colorHex(palette.Grid))
        assertEquals("#B8B8B8", colorHex(palette.Ink))
        assertEquals("#8C8C8C", colorHex(palette.Muted))
        assertEquals("#2962FF", colorHex(palette.Accent))
        assertEquals("#089981", colorHex(palette.Up))
        assertEquals("#F23645", colorHex(palette.Down))
        assertEquals("#22AB94", colorHex(palette.Profit))
        assertEquals("#F23645", colorHex(palette.Loss))
        assertEquals("#F23645", colorHex(palette.Stop))
        assertEquals("#089981", colorHex(palette.Target))
        assertTrue(ThemePreset.TRADINGV.isDark(systemDark = false))
        assertEquals("TradingV", ThemePreset.TRADINGV.label)
    }

    @Test fun overridesReplaceSingleRolesAndOtherPresetsKeepTheirColours() {
        val custom = themePalette(ThemeSettings(ThemePreset.TRADINGV, mapOf("UP" to "#112233")))
        assertEquals("#112233", colorHex(custom.Up))
        assertEquals("#F23645", colorHex(custom.Down))
        val dark = themePalette(ThemeSettings(ThemePreset.DARK))
        assertEquals("#0F1B2D", colorHex(dark.Shell))
        assertEquals("#24364D", colorHex(dark.Raised))
        assertEquals("#3987E5", colorHex(dark.Up))
        assertEquals("#94C9B4", colorHex(dark.Target))
        val light = themePalette(ThemeSettings(ThemePreset.LIGHT))
        assertEquals("#F4F7FC", colorHex(light.Shell))
        assertEquals("#C5CFDD", colorHex(light.Border))
        assertEquals("#2169BE", colorHex(light.Up))
        assertEquals("#207553", colorHex(light.Target))
        assertEquals(colorHex(dark.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = true).Chart))
        assertEquals(colorHex(light.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = false).Chart))
    }
}
