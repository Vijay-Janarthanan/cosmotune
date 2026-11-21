package com.cosmotune.music.data.lyrics

import java.text.BreakIterator

/**
 * Where a word may be cut so its letters can rise one after another ([LyricLine.letterLift]). Cut at whole letters as a reader
 * sees them (a letter and its accents stay together), and only in scripts whose letters stand apart: Latin, Greek, Cyrillic,
 * Hebrew, Chinese, Japanese, Korean and the like. Where letters join or reorder (Tamil, Hindi and the other Indian scripts,
 * Arabic, Thai) the whole word lifts, as before: some Tamil vowel signs are drawn before the letter they follow, and a cut
 * between the two would split them.
 */
object LetterLift {
    private val apart = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
        // Digits, punctuation, spaces and the marks that ride on a letter go with whatever they stand among.
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED,
    )

    /**
     * The offsets in [text] where its letters start, with [text]'s length at the end: n + 1 numbers for n letters. Null when
     * the word lifts as one, because it has a single letter or its script joins them.
     */
    fun cuts(text: String): IntArray? {
        if (text.length < 2) return null
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            if (Character.UnicodeScript.of(code) !in apart) return null
            i += Character.charCount(code)
        }
        val letters = BreakIterator.getCharacterInstance()
        letters.setText(text)
        val out = ArrayList<Int>()
        var at = letters.first()
        while (at != BreakIterator.DONE) {
            out += at
            at = letters.next()
        }
        return if (out.size < 3) null else out.toIntArray()
    }
}
