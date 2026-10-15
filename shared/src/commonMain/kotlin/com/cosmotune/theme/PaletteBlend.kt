package com.cosmotune.theme

/**
 * Palette-to-palette blend in OKLab with premultiplied alpha (01-dynamic-colour.md section 4). Holds both ends as
 * plain arrays, so a frame of the cross-fade allocates nothing: the handoff's performance budget.
 */
class PaletteBlend(initial: IntArray) {
    private val size = initial.size
    private val from = DoubleArray(size * 4)
    private val to = DoubleArray(size * 4)

    init { fill(from, initial); fill(to, initial) }

    /**
     * Starts a blend towards [next] from what is on screen at [t] of the current one. What is on screen is worked out
     * exactly as [argbAt] does (premultiplied), so a retarget in the middle of a fade of a translucent colour starts
     * from the colour that was drawn and nothing jumps.
     */
    fun retarget(next: IntArray, t: Float) {
        require(next.size == size) { "palette size changed" }
        val x = t.coerceIn(0f, 1f).toDouble()
        for (field in 0 until size) {
            val k = field * 4
            val fromAlpha = from[k + 3]
            val toAlpha = to[k + 3]
            val alpha = fromAlpha + (toAlpha - fromAlpha) * x
            for (c in 0 until 3) {
                // With nothing visible (alpha 0) no colour is shown, so the old end's colour stands in: it cannot be seen.
                from[k + c] = if (alpha <= 0.0) to[k + c] else
                    (from[k + c] * fromAlpha + (to[k + c] * toAlpha - from[k + c] * fromAlpha) * x) / alpha
            }
            from[k + 3] = alpha
        }
        fill(to, next)
    }

    fun argbAt(field: Int, t: Float): Int {
        val x = t.coerceIn(0f, 1f).toDouble()
        val k = field * 4
        val alpha = from[k + 3] + (to[k + 3] - from[k + 3]) * x
        if (alpha <= 0.0) return 0
        fun ch(c: Int) = (from[k + c] * from[k + 3] + (to[k + c] * to[k + 3] - from[k + c] * from[k + 3]) * x) / alpha
        return OklabMath.toArgb(ch(0), ch(1), ch(2), alpha)
    }

    private fun fill(dst: DoubleArray, argb: IntArray) { for (i in argb.indices) OklabMath.fromArgb(argb[i], dst, i * 4) }
}
