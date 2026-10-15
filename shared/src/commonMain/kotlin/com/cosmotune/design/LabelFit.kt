package com.cosmotune.design

/**
 * Fitting a short label into its box on whole words (a mood tile's name, `.mtile b`): the sizes to try, largest first; whether a
 * laid-out label broke its lines only between words; and how many lines it may take. A long name ("Energise", "Konzentration",
 * "Сосредоточенность") shrinks instead of breaking in the middle of a word, down to a floor; past the floor a one-word name is cut
 * with an ellipsis on its one line rather than broken.
 */
object LabelFit {
    /** The sizes from [maxSp] down to [minSp], [stepSp] apart, largest first; the last is always [minSp], and none is below it. */
    fun sizes(maxSp: Float, minSp: Float, stepSp: Float): FloatArray {
        if (maxSp <= minSp || stepSp <= 0f) return floatArrayOf(minSp)
        val steps = ((maxSp - minSp) / stepSp).toInt()
        val above = (0..steps).map { maxSp - it * stepSp }.filter { it > minSp + 1e-4f }
        return (above + minSp).toFloatArray()
    }

    /**
     * Whether a label laid out in lines that end at [lineEnds] (the end offset of each line, exclusive, as a text layout reports it)
     * breaks only between words: every line but the last ends at a space or a hyphen, or is followed by a space. One line breaks nothing.
     */
    fun breaksOnlyAtSpaces(text: String, lineEnds: IntArray): Boolean {
        for (i in 0 until lineEnds.size - 1) {
            val end = lineEnds[i]
            if (end <= 0 || end >= text.length) continue
            val before = text[end - 1]
            if (!(before.isWhitespace() || before == '-' || before == '‐' || text[end].isWhitespace())) return false
        }
        return true
    }

    /** One line for a one-word label (it shrinks, then is cut, but never breaks), two for a label of several words. */
    fun maxLines(text: String): Int = if (text.trim().any { it.isWhitespace() }) 2 else 1
}
