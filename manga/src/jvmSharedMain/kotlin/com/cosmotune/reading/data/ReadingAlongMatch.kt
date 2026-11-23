package com.cosmotune.reading.data

/**
 * Pairing a book's chapters with an audiobook's, for listening along: by chapter number where both say one ("Chapter 3",
 * "III", "Three", "3."), by the same name, and otherwise by their place in the book.
 */
object ReadingAlongMatch {
    /** For each of the book's chapters ([sections], their titles in reading order), the audiobook chapter it goes with. */
    fun chapters(sections: List<String>, audio: List<String>): List<Int> {
        if (sections.isEmpty() || audio.isEmpty()) return sections.map { 0 }
        val audioNumbers = audio.map(::number)
        val audioNames = audio.map(::name)
        val matched = sections.map { title ->
            val n = number(title)
            val byNumber = if (n == null) -1 else audioNumbers.indexOfFirst { it == n }.takeIf { audioNumbers.count { a -> a == n } == 1 } ?: -1
            if (byNumber >= 0) byNumber else name(title).takeIf { it.length >= 3 }?.let { key -> audioNames.indexOf(key) } ?: -1
        }.toMutableList()
        // The ones without a match take their share of the way between the matched ones around them.
        sections.indices.forEach { i ->
            if (matched[i] >= 0) return@forEach
            val before = (i - 1 downTo 0).firstOrNull { matched[it] >= 0 }
            val after = (i + 1 until sections.size).firstOrNull { matched[it] >= 0 }
            val (from, fromValue) = if (before != null) before to matched[before] else -1 to -1
            val (to, toValue) = if (after != null) after to matched[after] else sections.size to audio.size
            val share = (i - from).toDouble() / (to - from)
            matched[i] = (fromValue + share * (toValue - fromValue)).toInt().coerceIn(maxOf(fromValue, 0), audio.lastIndex)
        }
        // Never going backwards: a later chapter of the book never plays an earlier part of the recording.
        for (i in 1 until matched.size) if (matched[i] < matched[i - 1]) matched[i] = matched[i - 1]
        return matched
    }

    /** The book chapter that goes with audiobook chapter [audioIndex]: the first one paired with it, or the nearest before. */
    fun section(pairs: List<Int>, audioIndex: Int): Int? = pairs.indexOfFirst { it == audioIndex }.takeIf { it >= 0 }
        ?: pairs.indexOfLast { it < audioIndex }.takeIf { it >= 0 }

    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)

    /** The chapter number a title gives, if it gives one. */
    internal fun number(title: String): Int? {
        val text = title.lowercase().trim()
        Regex("""(?:chapter|ch\.?|part|book|section|kapitel|chapitre|capítulo|capitolo)\s+([0-9]{1,4}|[ivxlc]{1,8}\b|[a-z]+(?:[- ][a-z]+)?)""").find(text)?.let { m ->
            return value(m.groupValues[1])
        }
        Regex("""^([0-9]{1,4})(?:[.:)\s-]|$)""").find(text)?.let { return it.groupValues[1].toInt() }
        return Regex("""^([ivxlc]{1,8})[.:)]""").find(text)?.let { roman(it.groupValues[1]) }
    }

    private fun value(token: String): Int? = token.toIntOrNull() ?: roman(token) ?: spelled(token) ?: spelled(token.substringBefore(' '))

    private fun spelled(token: String): Int? = token.split('-', ' ').map { words[it] ?: return null }.sum().takeIf { it > 0 }

    private fun roman(text: String): Int? {
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        if (text.isEmpty() || text.any { it !in values }) return null
        var total = 0
        text.forEachIndexed { i, c -> val v = values.getValue(c); total += if (i + 1 < text.length && values.getValue(text[i + 1]) > v) -v else v }
        return total.takeIf { it in 1..400 }
    }

    private fun name(title: String) = title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
