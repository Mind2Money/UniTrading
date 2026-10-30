package com.unitrading.ui

import com.unitrading.broker.Instrument
import kotlin.math.roundToLong

/** Slider coordinates never become order quantities directly: every update lands on the contract's integer grid. */
internal data class QuantitySliderScale(val minimum: Long, val maximum: Long, val step: Long) {
    val intervals = (maximum - minimum) / step
    fun fraction(quantity: Long): Float = if (intervals == 0L) 0f else
        ((quantity.coerceIn(minimum, maximum) - minimum).toDouble() / (maximum - minimum)).toFloat()

    fun quantity(fraction: Float): Long? {
        if (!fraction.isFinite()) return null
        val index = (fraction.coerceIn(0f, 1f).toDouble() * intervals).roundToLong().coerceIn(0, intervals)
        return minimum + index * step
    }

    companion object {
        fun from(instrument: Instrument): QuantitySliderScale? {
            val min = instrument.minLotsHundredths
            val max = instrument.maxLotsHundredths
            val step = instrument.lotStepHundredths
            if (min <= 0 || max < min || step <= 0 || min % step != 0L) return null
            return QuantitySliderScale(min, min + (max - min) / step * step, step)
        }
    }
}
