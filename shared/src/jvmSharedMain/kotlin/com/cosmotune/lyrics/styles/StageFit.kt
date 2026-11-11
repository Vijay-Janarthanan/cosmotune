package com.cosmotune.lyrics.styles

/**
 * How the words of a line are broken into rows and sized on the stages that set them as blocks of type: Stretch (words that
 * change width as they are sung, centred) and Poster (condensed caps filling the frame). Widths are measured once per line; these
 * take the measurements and answer with plain numbers, so the rules are tested here and a frame allocates nothing.
 */
object StageFit {
    /**
     * Breaks [count] words into rows for Stretch, filling [rowStart] with the first word of each row and answering the number of
     * rows. A row is kept to what fits [maxWidth] with every word at [settled] width plus room for any one of them to grow to its
     * [peak]: only one word is sung at a time, so that is the most a row ever needs, and a row never has to break again while it
     * is being sung. A word too wide on its own gets a row to itself.
     */
    fun stretchRows(settled: FloatArray, peak: FloatArray, count: Int, gap: Float, maxWidth: Float, rowStart: IntArray): Int {
        if (count <= 0) return 0
        var rows = 0
        var i = 0
        while (i < count) {
            rowStart[rows++] = i
            var width = settled[i]
            var grow = (peak[i] - settled[i]).coerceAtLeast(0f)
            i++
            while (i < count) {
                val nextWidth = width + gap + settled[i]
                val nextGrow = maxOf(grow, peak[i] - settled[i])
                if (nextWidth + nextGrow > maxWidth) break
                width = nextWidth
                grow = nextGrow
                i++
            }
        }
        return rows
    }

    /**
     * Packs words of [widths] (at some size) into rows no wider than [maxWidth], [gap] apart, filling [rowStart] and answering how
     * many rows that takes. With [onePerRow] every word has its own row, the way the board's poster sets a line.
     */
    fun packRows(widths: FloatArray, count: Int, gap: Float, maxWidth: Float, onePerRow: Boolean, rowStart: IntArray): Int {
        if (count <= 0) return 0
        var rows = 0
        var i = 0
        while (i < count) {
            rowStart[rows++] = i
            var width = widths[i]
            i++
            if (onePerRow) continue
            while (i < count && width + gap + widths[i] <= maxWidth) {
                width += gap + widths[i]
                i++
            }
        }
        return rows
    }

    /** What [posterSize] settled on: the type's size, whether each word has a row of its own, and how many rows there are. */
    class Poster(val size: Float, val onePerRow: Boolean, val rows: Int)

    /**
     * The largest size for a Poster: [widthsAtRef] are the words measured at [refSize], [gapAtRef] the space between two of them,
     * [lineHeight] a row's height as a fraction of the size. Answers the size at which the rows fit [boxWidth] by [boxHeight],
     * held between [minSize] and [maxSize]; [rowStart] is left holding the rows at that size.
     *
     * One word to a row is the poster's look, kept unless packing several to a row would let the type be much bigger
     * ([ONE_PER_ROW_KEEP] of the packed size or more keeps one per row): a twelve-word line on twelve rows would be tiny.
     */
    fun posterSize(
        widthsAtRef: FloatArray,
        count: Int,
        gapAtRef: Float,
        refSize: Float,
        lineHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
        minSize: Float,
        maxSize: Float,
        rowStart: IntArray,
    ): Poster {
        if (count <= 0) return Poster(maxSize, true, 0)
        val packed = fit(widthsAtRef, count, gapAtRef, refSize, lineHeight, boxWidth, boxHeight, minSize, maxSize, false, rowStart)
        val single = fit(widthsAtRef, count, gapAtRef, refSize, lineHeight, boxWidth, boxHeight, minSize, maxSize, true, rowStart)
        if (single >= packed * ONE_PER_ROW_KEEP) return Poster(single, true, count)
        val size = fit(widthsAtRef, count, gapAtRef, refSize, lineHeight, boxWidth, boxHeight, minSize, maxSize, false, rowStart)
        val k = size / refSize
        val scaled = FloatArray(count) { widthsAtRef[it] * k }
        return Poster(size, false, packRows(scaled, count, gapAtRef * k, boxWidth, false, rowStart))
    }

    /** One word to a row is kept while it gives at least this much of the packed size. */
    const val ONE_PER_ROW_KEEP = 0.7f

    private fun fit(
        widthsAtRef: FloatArray,
        count: Int,
        gapAtRef: Float,
        refSize: Float,
        lineHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
        minSize: Float,
        maxSize: Float,
        onePerRow: Boolean,
        rowStart: IntArray,
    ): Float {
        val scaled = FloatArray(count)
        fun fits(size: Float): Boolean {
            val k = size / refSize
            var widest = 0f
            for (i in 0 until count) {
                scaled[i] = widthsAtRef[i] * k
                if (scaled[i] > widest) widest = scaled[i]
            }
            if (widest > boxWidth) return false
            val rows = packRows(scaled, count, gapAtRef * k, boxWidth, onePerRow, rowStart)
            return rows * size * lineHeight <= boxHeight
        }
        var low = minSize
        var high = maxSize
        if (fits(high)) low = high
        else repeat(24) {
            val mid = (low + high) / 2f
            if (fits(mid)) low = mid else high = mid
        }
        fits(low)
        return low
    }
}
