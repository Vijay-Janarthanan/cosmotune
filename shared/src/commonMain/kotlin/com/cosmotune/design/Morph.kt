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

    // ---- the travelling cover and words: they start from the capsule's own geometry ([CapsuleGeometry]) ---------------------

    /**
     * Where something that sits [insetPx] in from the capsule's edge starts, on one axis, in the overlay's own coordinates: the
     * capsule's edge ([capsuleEdgePx], in the window), less where the overlay sits in the window ([originPx]), plus the inset.
     * The travelling cover's top-left corner and the start of the travelling words, each with its own inset.
     */
    fun insetFrom(capsuleEdgePx: Float, originPx: Float, insetPx: Float): Float = capsuleEdgePx - originPx + insetPx

    /**
     * The scale of the travelling cover, which is laid out once at the size of the player's cover ([sizePx]): from the
     * capsule's cover ([startSizePx]) at t = 0 to 1, the player's cover as it is, at t = 1.
     */
    fun coverScale(t: Float, startSizePx: Float, sizePx: Float): Float = lerp(startSizePx / sizePx, 1f, t)

    /**
     * The travelling cover's corner radius in its own, unscaled space ([sizePx] square): the capsule's cover's corner
     * ([startRadiusPx], which is what it is on screen at t = 0) to the player's ([endRadiusPx]) at t = 1.
     */
    fun coverRadius(t: Float, startRadiusPx: Float, startSizePx: Float, sizePx: Float, endRadiusPx: Float): Float =
        lerp(startRadiusPx * sizePx / startSizePx, endRadiusPx, t)

    /** The width the capsule gives its words: its own width less what is before them and what is after them. Never negative. */
    fun textWidth(capsuleWidth: Float, startInset: Float, endInset: Float): Float = (capsuleWidth - startInset - endInset).coerceAtLeast(0f)
}
