package com.cosmotune.video.segments

import com.cosmotune.video.settings.TitleCase

/**
 * DeArrow titles (spec 7.2). A `>` before a word means "do not auto-format this word"; the marker is removed in every
 * case (the extension's `/(^|\s)>(\S)/g`). SENTENCE and TITLE casing then re-case the rest of the title and leave alone:
 *  - the words after a `>`;
 *  - acronyms: words of all capitals with 2 to 5 letters (`NASA`, `PC`), possessive `'s` ignored. A title that has no
 *    lower-case letter at all is a shout, not a list of acronyms, so there they are re-cased like any other word;
 *  - words with a digit and no lower-case letter (`4K`, `PS5`), a single capital letter other than `A` and `I` (`Plan B`),
 *    and brand names with a capital inside (`iPhone`, `YouTube`, `McDonald's`);
 *  - spacing, punctuation, digits and text without case (emoji, Japanese, Hindi).
 *
 * SENTENCE capitalises the first word, the word after `.`, `!`, `?` and after a lone dash or bar between parts, and the
 * pronoun I. TITLE capitalises every word except the short ones (`a`, `of`, `the`, ...), which stay small unless they are
 * first, last or follow a colon, a bar or the end of a sentence; the parts of `well-known` are each capitalised.
 * Pure, no allocation beyond the result.
 */
object DeArrowTitles {
    private val MARKER = Regex("""(^|\s)>(\S)""")
    private val WORD = Regex("""\S+""")

    /** Words TITLE keeps small in the middle of a title. */
    private val SMALL_WORDS = setOf(
        "a", "an", "and", "as", "at", "but", "by", "for", "in", "nor", "of", "on", "or", "per", "so", "the", "to", "up", "via", "vs", "yet",
    )

    /** [title] with the `>` markers removed and [case] applied. */
    fun clean(title: String, case: TitleCase): String {
        val cased = when (case) {
            TitleCase.AS_GIVEN -> title
            TitleCase.SENTENCE -> recase(title, sentence = true)
            TitleCase.TITLE -> recase(title, sentence = false)
        }
        return MARKER.replace(cased) { it.groupValues[1] + it.groupValues[2] }
    }

    private fun recase(title: String, sentence: Boolean): String {
        val words = WORD.findAll(title).toList()
        if (words.isEmpty()) return title
        val shouting = title.none { it.isLowerCase() }
        val out = StringBuilder(title.length)
        var previousEnd = 0
        var capitalizeNext = true
        for ((index, match) in words.withIndex()) {
            out.appendRange(title, previousEnd, match.range.first)
            previousEnd = match.range.last + 1
            val word = match.value
            val separator = isSeparator(word)
            val hasAlnum = !separator && word.any { it.isLetterOrDigit() }
            val capitalize = capitalizeNext || (!sentence && index == words.lastIndex)
            out.append(
                when {
                    word.length > 1 && word[0] == '>' -> word
                    separator || !hasAlnum -> word
                    isLeftAlone(word, shouting) -> word
                    sentence -> sentenceCase(word, capitalize)
                    else -> titleCase(word, capitalize)
                },
            )
            if (separator) {
                capitalizeNext = true
            } else if (hasAlnum) {
                capitalizeNext = endsSentence(word) || (!sentence && endsClause(word))
            }
        }
        out.appendRange(title, previousEnd, title.length)
        return out.toString()
    }

    /** A word the casing leaves as it is (the rules in the class comment, except the marker). */
    private fun isLeftAlone(word: String, shouting: Boolean): Boolean {
        val core = word.trim { !it.isLetterOrDigit() }
        if (core.isEmpty()) return true
        val stem = if (core.length > 2 && core.endsWith("s", ignoreCase = true) && (core[core.length - 2] == '\'' || core[core.length - 2] == '’')) {
            core.substring(0, core.length - 2)
        } else {
            core
        }
        if (stem.any { it.isLowerCase() }) {
            // a capital right after a lower-case letter: iPhone, YouTube, eBay, McDonald
            for (i in 1 until stem.length) if (stem[i].isUpperCase() && stem[i - 1].isLowerCase()) return true
            return false
        }
        val letters = stem.count { it.isLetter() }
        if (letters == 0) return true                                   // digits only
        if (stem.any { it.isDigit() }) return true                      // 4K, PS5, RTX4090
        if (letters == 1) return stem != "A" && stem != "I"             // Plan B, Vitamin D
        return !shouting && letters in 2..5                             // NASA, PC
    }

    private fun sentenceCase(word: String, capitalize: Boolean): String {
        val lower = word.lowercase()
        val first = lower.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0 || !lower[first].isLetter()) return lower
        val pronoun = lower[first] == 'i' && (first + 1 == lower.length || !lower[first + 1].isLetterOrDigit())
        return if (capitalize || pronoun) lower.substring(0, first) + lower[first].uppercaseChar() + lower.substring(first + 1) else lower
    }

    private fun titleCase(word: String, capitalize: Boolean): String {
        val lower = word.lowercase()
        if (!capitalize && lower.trim { !it.isLetterOrDigit() } in SMALL_WORDS) return lower
        val out = StringBuilder(lower.length)
        var startOfPart = true
        for (c in lower) {
            if (c.isLetter()) {
                out.append(if (startOfPart) c.uppercaseChar() else c)
                startOfPart = false
            } else {
                out.append(c)
                if (c == '-' || c == '/') startOfPart = true else if (c.isDigit()) startOfPart = false
            }
        }
        return out.toString()
    }

    /** A lone dash or bar between the parts of a title ("Artist - Song", "Title | Channel"). */
    private fun isSeparator(word: String) = word.all { it == '|' || it == '-' || it == '–' || it == '—' || it == '•' || it == '·' }

    private fun endsSentence(word: String): Boolean {
        val last = word.trimEnd { it in CLOSERS }.lastOrNull() ?: return false
        return last == '.' || last == '!' || last == '?' || last == '…'
    }

    private fun endsClause(word: String): Boolean = word.trimEnd { it in CLOSERS }.lastOrNull() == ':'

    private const val CLOSERS = "\"')]}”’»*"
}
