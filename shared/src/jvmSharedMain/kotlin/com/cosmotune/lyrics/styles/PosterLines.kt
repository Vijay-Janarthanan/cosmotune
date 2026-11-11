package com.cosmotune.lyrics.styles

import com.cosmotune.design.TypeScale

/**
 * How tightly Poster may stack its rows. Archivo's condensed caps sit at 0.88 of the type's size, as the board sets them, but
 * Archivo only covers Latin, Greek and Vietnamese: Chinese, Japanese, Korean, the Indian scripts and the rest fall back to the
 * system's fonts, whose glyphs are taller and whose marks reach above and below the line. Set at the tight height they ran into
 * the next row. [factor] is the row height to fit with; the poster then also measures each row's shaped text and never puts
 * two rows closer than their glyphs need.
 */
object PosterLines {
    /** The board's tight row, for text Archivo sets itself. */
    const val TIGHT = 0.88f

    /** Chinese, Japanese, Korean and other fallback scripts without marks above or below. */
    const val FALLBACK = 1.3f

    /** Scripts with vowel marks above and below the letters (Devanagari, Tamil, Thai and the others). */
    const val TALL = 1.6f

    /** Whether every letter of [text] is one Archivo draws itself (Latin, Greek, Cyrillic and their marks). */
    fun tight(text: String): Boolean {
        for (c in text) if (c.code >= 0x0530 && !c.isWhitespace()) return false
        return true
    }

    /** The row height, as a fraction of the size, to fit [text] with. */
    fun factor(text: String): Float = when {
        tight(text) -> TIGHT
        TypeScale.needsTallLines(text) || text.any { it.code in 0x0E00..0x0EFF || it.code in 0x0F00..0x109F } -> TALL
        else -> FALLBACK
    }
}
