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

    /** Whether [c] belongs to a script written without spaces between words (Han, kana, Thai, Lao, Khmer and Myanmar). */
    fun spaceless(c: Char): Boolean = c.code in 0x3040..0x30FF || c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF ||
        c.code in 0xF900..0xFAFF || c.code in 0xFF66..0xFF9F || c.code in 0x0E00..0x0EFF || c.code in 0x1000..0x109F || c.code in 0x1780..0x17FF

    /** The most letters a poster row of such a script holds before the word is broken. */
    const val ROW_MAX = 5

    /**
     * [word] cut into poster rows of balanced length, about four letters each and never one alone on the last row, when it is a
     * word in a script with no spaces that is longer than [ROW_MAX] letters. Cuts fall between letters as a reader sees them
     * (a letter and its marks stay together). Any other word comes back whole.
     */
    fun chunks(word: String): List<String> {
        if (word.length <= ROW_MAX || word.any { it.isWhitespace() }) return listOf(word)
        val spaceless = word.count { spaceless(it) }
        if (spaceless * 2 < word.length) return listOf(word)
        val thai = word.count { it.code in 0x0E00..0x0EFF } * 2 >= word.length
        val letters = pieces(word, java.text.BreakIterator.getCharacterInstance())
        if (letters.size <= ROW_MAX) return listOf(word)
        // Thai is cut only where a word ends (a vowel written in front of its letter must not be left behind); other scripts
        // between letters, which are words in themselves.
        val units = if (thai) pieces(word, java.text.BreakIterator.getWordInstance(java.util.Locale.forLanguageTag("th"))) else letters
        val weights = units.map { pieces(it, java.text.BreakIterator.getCharacterInstance()).size }
        val rows = ((letters.size + 2) / 4).coerceAtLeast(2)
        val out = ArrayList<String>(rows)
        var at = 0
        var left = letters.size
        for (r in 0 until rows) {
            if (at >= units.size) break
            val target = left.toFloat() / (rows - r)
            var row = units[at]
            var weight = weights[at]
            at++
            // Take whole units while that brings the row nearer its share, and always leave a unit for each row still to come.
            while (at < units.size && units.size - at > rows - r - 1 &&
                kotlin.math.abs(weight + weights[at] - target) <= kotlin.math.abs(weight - target)
            ) { row += units[at]; weight += weights[at]; at++ }
            if (r == rows - 1) { while (at < units.size) { row += units[at]; at++ } }
            out += row
            left -= weight
        }
        return out
    }

    private fun pieces(text: String, breaks: java.text.BreakIterator): List<String> {
        breaks.setText(text)
        val out = ArrayList<String>()
        var from = breaks.first()
        var to = breaks.next()
        while (to != java.text.BreakIterator.DONE) { out += text.substring(from, to); from = to; to = breaks.next() }
        return out
    }
}
