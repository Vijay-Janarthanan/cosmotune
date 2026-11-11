package com.cosmotune.music.data.lyrics

/** Script conversion, never translation. Instances are thread-confined by the platform factory. */
internal expect fun transliterateOffline(text: String, transform: String): String

internal object OfflineRomanization {
    data class Answer(val texts: List<String>, val language: String)

    fun romanize(texts: List<String>): Answer? {
        val scripts = texts.flatMap { it.codePoints().toArray().asIterable() }
            .filter(Character::isLetter).map { Character.UnicodeScript.of(it) }.toSet()
        val japanese = Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts
        // Kanji readings depend on the word. Never substitute Mandarin for Japanese.
        if (japanese && Character.UnicodeScript.HAN in scripts) return null
        val language = when {
            japanese -> "ja"
            Character.UnicodeScript.HANGUL in scripts -> "ko"
            Character.UnicodeScript.HAN in scripts -> "zh"
            Character.UnicodeScript.TAMIL in scripts -> "ta"
            Character.UnicodeScript.TELUGU in scripts -> "te"
            Character.UnicodeScript.DEVANAGARI in scripts -> "und"
            else -> "und"
        }
        val output = runCatching {
            texts.map { text ->
                val korean = Regex("[\\uAC00-\\uD7A3]+").replace(text) { HangulRomanization.romanise(it.value) }
                transliterateOffline(korean, if (japanese) "Hiragana-Latin; Katakana-Latin" else "Any-Latin")
            }
        }.getOrNull() ?: return null
        if (output == texts || output.any { value ->
                value.codePoints().anyMatch { Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN }
            }) return null
        return Answer(output, language)
    }
}
