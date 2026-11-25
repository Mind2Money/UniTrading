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
        assertEquals("#22AB94", colorHex(palette.Up))
        assertEquals("#F7525F", colorHex(palette.Down))
        assertEquals("#089981", colorHex(palette.UpBody))
        assertEquals("#F23645", colorHex(palette.DownBody))
        assertEquals("#22AB94", colorHex(palette.Profit))
        assertEquals("#F7525F", colorHex(palette.Loss))
        assertEquals("#F23645", colorHex(palette.Stop))
        assertEquals("#089981", colorHex(palette.Target))
        assertTrue(ThemePreset.TRADINGV.isDark(systemDark = false))
        assertEquals("TradingV", ThemePreset.TRADINGV.label)
    }

    @Test fun overridesReplaceSingleRolesAndOtherPresetsKeepTheirColours() {
        val custom = themePalette(ThemeSettings(ThemePreset.TRADINGV, mapOf("UP" to "#112233")))
        assertEquals("#112233", colorHex(custom.UpBody))
        assertEquals("#F7525F", colorHex(custom.Down))
        val dark = themePalette(ThemeSettings(ThemePreset.DARK))
        assertEquals("#0C0D10", colorHex(dark.Shell))
        assertEquals("#282A2F", colorHex(dark.Raised))
        assertEquals("#8CD6B2", colorHex(dark.Up))
        assertEquals("#94C9B4", colorHex(dark.Target))
        assertEquals("#0B57D0", colorHex(dark.Key))
        val light = themePalette(ThemeSettings(ThemePreset.LIGHT))
        assertEquals("#F0F4F9", colorHex(light.Shell))
        assertEquals("#C4C7CF", colorHex(light.Border))
        assertEquals("#176B45", colorHex(light.Up))
        assertEquals("#207553", colorHex(light.Target))
        assertEquals("#0B57D0", colorHex(light.Key))
        assertEquals(colorHex(dark.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = true).Chart))
        assertEquals(colorHex(light.Chart), colorHex(themePalette(ThemeSettings(ThemePreset.SYSTEM), systemDark = false).Chart))
    }

    @Test fun candlePartsCanBeCustomizedIndependentlyAndOldColorsRemainCompatible() {
        val old = themePalette(ThemeSettings(ThemePreset.LIGHT, mapOf("UP" to "#112233", "DOWN" to "#445566")))
        assertEquals("#112233", colorHex(old.UpBorder))
        assertEquals("#112233", colorHex(old.UpWick))
        assertEquals("#445566", colorHex(old.DownWick))
        val custom = themePalette(ThemeSettings(ThemePreset.LIGHT, mapOf("UP" to "#112233", "UP_BORDER" to "#ABCDEF", "UP_WICK" to "#123456")))
        assertEquals("#112233", colorHex(custom.UpBody))
        assertEquals("#ABCDEF", colorHex(custom.UpBorder))
        assertEquals("#123456", colorHex(custom.UpWick))
    }

    @Test fun directionAndCandlePartColoursAreIndependent() {
        val base = ThemeSettings(ThemePreset.LIGHT).withIndependentCandleParts()
        val initial = themePalette(base)
        val body = themePalette(base.copy(colors = mapOf("UP" to "#FFFFFF", "DOWN" to "#000000")))
        assertEquals(initial.Up, body.Up)
        assertEquals(initial.Down, body.Down)
        assertEquals(initial.UpBorder, body.UpBorder)
        assertEquals(initial.UpWick, body.UpWick)
        assertEquals("#FFFFFF", colorHex(body.UpBody))
        val direction = themePalette(base.copy(colors = mapOf("RISE" to "#00AABB", "FALL" to "#EE3300")))
        assertEquals("#00AABB", colorHex(direction.Up))
        assertEquals("#EE3300", colorHex(direction.Down))
        assertEquals(initial.UpBody, direction.UpBody)
        assertEquals(initial.DownBody, direction.DownBody)
        assertEquals(initial.UpBorder, direction.UpBorder)
        assertEquals(initial.UpWick, direction.UpWick)
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
