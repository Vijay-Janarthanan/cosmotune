package com.cosmotune.lyrics

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The motes that rise off a line's translation or romanisation when it changes (the old panel's `lyricParticles`,
 * `PlayerLyrics.kt`): which letters they start on, where they drift to, and how they move. The numbers are in dp and in
 * fractions of the motion, so the Compose side only places them.
 */
class ParticleSet(
    /** The character each mote rises from. */
    val charIndex: IntArray,
    /** How far each drifts sideways and up (negative), in dp. */
    val driftX: FloatArray,
    val driftY: FloatArray,
    /** Its radius in dp. */
    val radius: FloatArray,
    /** How far into the motion it sets off, as a fraction of it: later letters later. */
    val delay: FloatArray,
) {
    val size: Int get() = charIndex.size
}

object LyricParticles {
    /** A line gets at most this many motes. */
    const val PER_VOICE = 18

    /** The whole motion lasts this long. */
    const val MOTION_MS = 540

    /** The faint halo round a mote and its core, as opacities; the halo is this many times the radius. */
    const val HALO_ALPHA = 0.07f
    const val CORE_ALPHA = 0.58f
    const val HALO_RADIUS_RATIO = 2.7f

    /** A mote runs over this share of the motion, after its delay. */
    private const val RUN = 0.84f

    /** The latest delay: the last letter of the line sets off this far in. */
    private const val STAGGER = 0.16f

    /**
     * The motes for [text]. The same text always gets the same motes, so a line that is drawn twice (a scroll, a recomposition)
     * does not shimmer into a different pattern. Only letters and digits carry one, each letter at most once.
     */
    fun generate(text: String): ParticleSet {
        val candidates = text.indices.filter { text[it].isLetterOrDigit() }
        val random = Random(text.hashCode())
        val picked = candidates.shuffled(random).take(PER_VOICE)
        val charIndex = IntArray(picked.size)
        val driftX = FloatArray(picked.size)
        val driftY = FloatArray(picked.size)
        val radius = FloatArray(picked.size)
        val delay = FloatArray(picked.size)
        val length = text.length.coerceAtLeast(1)
        for (i in picked.indices) {
            val index = picked[i]
            charIndex[i] = index
            // Drawn in this order, as the old panel did: sideways, up, size.
            driftX[i] = (random.nextFloat() - 0.5f) * 12f
            driftY[i] = -(5f + random.nextFloat() * 11f)
            radius[i] = 0.65f + random.nextFloat() * 0.65f
            delay[i] = STAGGER * index / length
        }
        return ParticleSet(charIndex, driftX, driftY, radius, delay)
    }

    /** Where a mote with [delay] is in its own run, 0 to 1, when the whole motion is [progress] of the way. */
    fun phase(progress: Float, delay: Float): Float = ((progress - delay) / RUN).coerceIn(0f, 1f)

    /** How visible a mote is at [t] of its run: nothing at either end, full in the middle. */
    fun envelope(t: Float): Float = sin(PI * t).toFloat()

    /** How far along its drift a mote is at [t]: quick at first, slowing as it rises. */
    fun ease(t: Float): Float = 1f - (1f - t) * (1f - t)
}
