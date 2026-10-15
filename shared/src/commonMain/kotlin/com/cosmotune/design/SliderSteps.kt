package com.cosmotune.design

import kotlin.math.floor

/**
 * A settings slider is the thick 0 to 1 slider of the B+C system laid over a value range with Material's meaning of `steps`:
 * that many stops between the two ends (so `steps + 2` stops in all, `steps + 1` equal intervals). The bar reports a fraction
 * wherever the finger is; [snap] turns it into the value of the nearest stop and [fractionOf] puts a value back on the bar.
 */
object SliderSteps {
    /**
     * The value of [fraction] (0 to 1, held to that) in [range], moved to the nearest of the [steps] + 2 evenly spaced stops; a
     * tie goes to the higher stop. With no steps (zero or fewer) the value is continuous. An empty range is its one value.
     */
    fun snap(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val start = range.start
        val span = range.endInclusive - start
        if (steps <= 0) return start + span * f
        val intervals = steps + 1
        val stop = floor(f * intervals + 0.5f).coerceIn(0f, intervals.toFloat())
        return start + span * stop / intervals
    }

    /** Where [value] sits on the bar: its share of [range], held between 0 and 1 (0 for an empty range). */
    fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (!(span > 0f)) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }
}
