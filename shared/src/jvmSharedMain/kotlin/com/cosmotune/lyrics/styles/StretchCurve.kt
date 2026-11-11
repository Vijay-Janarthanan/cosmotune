package com.cosmotune.lyrics.styles

import kotlin.math.roundToInt

/**
 * Stretch: how wide and how heavy each word is drawn at a moment of the song, on Archivo's two axes (`wdth` 62..125, `wght`
 * 100..900).
 *
 * The board's three poses lie on one straight path through the axes, so a word's whole state is one number, its place `x` on
 * that path: 0 is a word still to come (`wdth` 62, `wght` 300, narrow and light), 1 is the peak of a carried note (125, 900) and
 * [DONE] is a word that has been sung (`wdth` 101, `wght` 675; the board's 100 and 700, rounded to the nearest instance).
 *
 * The word being sung rises along the path as it is sung, following its syllables: a short word pops to its peak at once, a long
 * note pops to a little past the sung words' width and then widens for as long as it is held, so a held note becomes a wide word. Once it ends it
 * settles to [DONE] over [SETTLE_MS], on the board's `cubic-bezier(.2,.8,.2,1)` (an ease out).
 *
 * The axes are not moved per frame. Compose makes a typeface per set of variation settings, and one per frame would be a new
 * font every 16 ms on both platforms; the app's rule is to step between instances made once. So `x` is drawn with the nearest of
 * [INSTANCES] instances cut along the path, made when the style first shows: nine steps of about 8 `wdth` and 75 `wght`.
 */
object StretchCurve {
    const val REST_WIDTH = 62f
    const val PEAK_WIDTH = 125f
    const val REST_WEIGHT = 300f
    const val PEAK_WEIGHT = 900f

    /** How many instances are cut along the path, from rest (0) to peak (`INSTANCES - 1`). */
    const val INSTANCES = 9

    /** Where a sung word rests: instance 5 of 9. */
    const val DONE = 5f / (INSTANCES - 1)

    /** How long a word takes to settle once it has been sung. */
    const val SETTLE_MS = 350L

    /** A word's first jump, how long it takes. */
    const val POP_MS = 120L

    /** At or under this a word just pops; at or over [LONG_MS] it is a carried note that grows for as long as it is held. */
    const val SHORT_MS = 180L
    const val LONG_MS = 900L

    /** How far up the path the shortest words reach (a long note goes all the way). */
    const val SHORT_PEAK = 0.82f

    /** Where a carried note jumps to at once (a little past [DONE]); it grows from there to its peak as it is held. */
    const val LONG_BASE = 0.7f

    /** How held a word of [durationMs] is: 0 for a short word, 1 for a carried note. */
    fun heldness(durationMs: Long): Float = ((durationMs - SHORT_MS).toFloat() / (LONG_MS - SHORT_MS)).coerceIn(0f, 1f)

    /** The top of a word's rise: the longer it is held, the further up the path it goes. */
    fun peakFor(durationMs: Long): Float = SHORT_PEAK + (1f - SHORT_PEAK) * heldness(durationMs)

    /**
     * The place on the path of a word sung from [startMs] to [endMs], at [positionMs], with [sung] (0..1) of its letters sung by
     * its syllables' own timing. With [reduced] motion the word never moves: it is drawn at [DONE] from the start, and only its
     * colour says where the song is.
     */
    fun amount(startMs: Long, endMs: Long, sung: Float, positionMs: Long, reduced: Boolean = false): Float {
        if (reduced) return DONE
        if (positionMs < startMs) return 0f
        val duration = (endMs - startMs).coerceAtLeast(0L)
        val peak = peakFor(duration)
        if (positionMs < endMs) {
            // It pops (eased) to where it starts from, then widens evenly as its syllables are sung: a short word starts at its
            // peak; a carried note starts just past the sung words' width, so it is never narrower than they are, and grows from there.
            val pop = easeOut(((positionMs - startMs).toFloat() / POP_MS).coerceIn(0f, 1f))
            val base = peak + (LONG_BASE - peak) * heldness(duration)
            return pop * (base + (peak - base) * sung.coerceIn(0f, 1f))
        }
        val settle = ((positionMs - endMs).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
        return peak + (DONE - peak) * easeOut(settle)
    }

    /** The `wdth` at [x]. */
    fun width(x: Float): Float = REST_WIDTH + (PEAK_WIDTH - REST_WIDTH) * x.coerceIn(0f, 1f)

    /** The `wght` at [x]. */
    fun weight(x: Float): Int = (REST_WEIGHT + (PEAK_WEIGHT - REST_WEIGHT) * x.coerceIn(0f, 1f)).roundToInt()

    /** The instance that draws [x]: the nearest one. */
    fun instance(x: Float): Int = (x.coerceIn(0f, 1f) * (INSTANCES - 1)).roundToInt().coerceIn(0, INSTANCES - 1)

    /** Where instance [index] sits on the path. */
    fun instanceAmount(index: Int): Float = index.coerceIn(0, INSTANCES - 1).toFloat() / (INSTANCES - 1)

    /** The board's ease, `cubic-bezier(.2,.8,.2,1)`, as near as a cubic ease out comes to it. */
    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }
}
