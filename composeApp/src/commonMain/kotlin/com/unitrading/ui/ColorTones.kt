package com.unitrading.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * Material 3 builds a theme out of tones. A tone is CIE L*, so the distance between two tones decides their contrast
 * whatever their hues. Hue and chroma are held in Oklab, where a blue stays blue as it lightens.
 */

private class Lch(val lightness: Float, val chroma: Float, val hue: Float)

private fun toLinear(channel: Float) = if (channel <= .04045f) channel / 12.92f else ((channel + .055f) / 1.055f).pow(2.4f)
private fun toGamma(channel: Float) = if (channel <= .0031308f) channel * 12.92f else 1.055f * channel.pow(1f / 2.4f) - .055f
private fun toneOf(luminance: Float) = if (luminance > 216f / 24389f) 116f * cbrt(luminance) - 16f else luminance * 24389f / 27f

private fun Color.lch(): Lch {
    val r = toLinear(red); val g = toLinear(green); val b = toLinear(blue)
    val x = cbrt(.4122214708f * r + .5363325363f * g + .0514459929f * b)
    val y = cbrt(.2119034982f * r + .6806995451f * g + .1073969566f * b)
    val z = cbrt(.0883024619f * r + .2817188376f * g + .6299787005f * b)
    val a = 1.9779984951f * x - 2.4285922050f * y + .4505937099f * z
    val c = .0259040371f * x + .7827717662f * y - .8086757660f * z
    return Lch(.2104542553f * x + .7936177850f * y - .0040720468f * z, hypot(a, c), atan2(c, a))
}

private fun linearRgb(lightness: Float, chroma: Float, hue: Float, out: FloatArray) {
    val a = chroma * cos(hue)
    val b = chroma * sin(hue)
    val x = (lightness + .3963377774f * a + .2158037573f * b).pow(3)
    val y = (lightness - .1055613458f * a - .0638541728f * b).pow(3)
    val z = (lightness - .0894841775f * a - 1.2914855480f * b).pow(3)
    out[0] = 4.0767416621f * x - 3.3077115913f * y + .2309699292f * z
    out[1] = -1.2684380046f * x + 2.6097574011f * y - .3413193965f * z
    out[2] = -.0041960863f * x - .7034186147f * y + 1.7076147010f * z
}

/** Writes the colour at [lightness] with as much of [chroma] as sRGB can show there. */
private fun gamutRgb(lightness: Float, chroma: Float, hue: Float, out: FloatArray) {
    linearRgb(lightness, chroma, hue, out)
    if (out.all { it in -.0001f..1.0001f }) return
    var low = 0f
    var high = chroma
    repeat(14) {
        val middle = (low + high) / 2
        linearRgb(lightness, middle, hue, out)
        if (out.all { it in -.0001f..1.0001f }) low = middle else high = middle
    }
    linearRgb(lightness, low, hue, out)
}

/** How light the colour is on Material's 0 to 100 tone scale. */
internal val Color.tone: Float get() = toneOf(luminance())

/** How colourful the colour is, in Oklab units; a neutral grey is 0. */
internal val Color.chroma: Float get() = lch().chroma

/** This colour's hue at [tone]; [chroma] replaces its own colourfulness, and sRGB's limit at that tone caps either. */
internal fun Color.atTone(tone: Float, chroma: Float? = null): Color {
    if (tone <= 0f) return Color.Black
    if (tone >= 100f) return Color.White
    val key = lch()
    val wanted = chroma ?: key.chroma
    val rgb = FloatArray(3)
    var low = 0f
    var high = 1f
    repeat(24) {
        val middle = (low + high) / 2
        gamutRgb(middle, wanted, key.hue, rgb)
        if (toneOf(.2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2]) < tone) low = middle else high = middle
    }
    gamutRgb(high, wanted, key.hue, rgb)
    return Color(toGamma(rgb[0].coerceIn(0f, 1f)), toGamma(rgb[1].coerceIn(0f, 1f)), toGamma(rgb[2].coerceIn(0f, 1f)))
}

/** WCAG contrast ratio, from 1 to 21. */
internal fun contrast(first: Color, second: Color): Float {
    val a = first.luminance() + .05f
    val b = second.luminance() + .05f
    return if (a > b) a / b else b / a
}

/** This colour moved along its own tones, away from [background], only as far as it takes to reach [minimum] contrast. */
internal fun Color.readableOn(background: Color, minimum: Float = 4.5f): Color {
    if (contrast(this, background) >= minimum) return this
    val surface = background.luminance() + .05f
    val lighter = 1.05f / surface >= surface / .05f
    val target = if (lighter) minimum * surface - .05f else surface / minimum - .05f
    return atTone(toneOf(target.coerceIn(0f, 1f)) + if (lighter) .4f else -.4f)
}
