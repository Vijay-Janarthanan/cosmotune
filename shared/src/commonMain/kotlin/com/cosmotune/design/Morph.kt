package com.cosmotune.design

/**
 * The mini player growing into Now Playing (`v2.js` `V2.morph`), one progress value `t` from 0 (the capsule) to 1 (the full
 * window). The caller interpolates the rect with [lerp] and fades the two sets of content with the alphas below.
 */
object Morph {
    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** The capsule's own text is gone by `t = 0.45`. */
    fun capsuleTextAlpha(t: Float): Float = (1f - 2.2f * t).coerceIn(0f, 1f)

    /** The player's content fades in over the last fifth. */
    fun playerContentAlpha(t: Float): Float = ((t - 0.8f) * 5f).coerceIn(0f, 1f)

    /** The corner radius runs from the capsule's own down to square. */
    fun cornerRadius(t: Float, capsuleRadius: Float): Float = capsuleRadius * (1f - t)
}
