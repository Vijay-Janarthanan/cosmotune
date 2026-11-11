package com.cosmotune.lyrics.styles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pulse's motion, from the audio levels ([com.cosmotune.music.data.audio.AudioLevels]) or, where there are none, from a slow
 * breath of its own: bass swells the cover-colour glow, a kick nudges the line, and the highs make the moon sparkle. Everything
 * is a plain number of the moment, so a frame asks and allocates nothing. With reduced motion nothing here is used: the glow
 * holds still and only the words change colour.
 */
object PulseMotion {
    /** One breath of the fallback, in ms: slow, and on no tempo, so it never claims to be the beat. */
    const val BREATH_MS = 5_200L

    /** How fast a kick fades from the line, in ms (its time constant). */
    const val KICK_FADE_MS = 140f

    /** The fallback's breath at [ms] of the song, 0..1..0. */
    fun breath(ms: Long): Float = (0.5 - 0.5 * cos(2.0 * PI * (ms % BREATH_MS) / BREATH_MS)).toFloat()

    /** What is left of a kick [sinceMs] after it landed, 1 down to 0 (0 when there has been none). */
    fun kick(sinceMs: Float): Float = if (sinceMs < 0f || sinceMs.isNaN()) 0f else exp(-sinceMs / KICK_FADE_MS)

    /** The glow's scale: bass swells it (up to a third again), a kick adds a little more. */
    fun glowScale(bass: Float, kick: Float): Float = 1f + 0.32f * bass.coerceIn(0f, 1f) + 0.06f * kick.coerceIn(0f, 1f)

    /** The glow's strength. */
    fun glowAlpha(bass: Float): Float = 0.25f + 0.45f * bass.coerceIn(0f, 1f)

    /** The line's nudge on a kick: three percent at most, so it never moves the words out from under the eye. */
    fun lineScale(kick: Float): Float = 1f + 0.03f * kick.coerceIn(0f, 1f)

    /** How the moon sparkles with the highs: its halo's scale. */
    fun sparkle(high: Float): Float = 1f + 1.4f * high.coerceIn(0f, 1f)

    /**
     * The height of bar [index] of [count] at [ms], 0..1: the low bars follow the bass, the middle the mids and the top the highs,
     * each with a flicker of its own so a row of them does not move as one.
     */
    fun bar(index: Int, count: Int, bass: Float, mid: Float, high: Float, ms: Long): Float {
        val at = if (count <= 1) 0f else index.toFloat() / (count - 1)
        val band = when {
            at < 0.5f -> bass + (mid - bass) * (at / 0.5f)
            else -> mid + (high - mid) * ((at - 0.5f) / 0.5f)
        }
        val flicker = abs(sin(ms / (180.0 + index * 37.0) + index)).toFloat()
        return (0.18f + 0.82f * band.coerceIn(0f, 1f) * (0.55f + 0.45f * flicker)).coerceIn(0f, 1f)
    }
}
