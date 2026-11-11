// Adapted from AETHER, GHOSTxASR, GPL-3.0-only.
// https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/core/lyrics/src/main/kotlin/app/aether/core/lyrics/Hangul.kt
package com.cosmotune.music.data.lyrics

/**
 * The Revised Romanization of Korean (2000), with the sound changes that make it read as Korean is
 * said: a final consonant carried over to a following vowel (liaison), the nasals (ㄱㄷㅂ before ㄴㅁ
 * become ㅇㄴㅁ), ㄹ beside ㄴ or ㄹ, ㅎ that breathes into the consonant after it, and ㄷ ㅌ before 이
 * softening to j and ch. Word by word: a space ends every change.
 */
internal object HangulRomanization {
    private val INITIAL = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val VOWEL = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")

    // Final consonants, by their index in a syllable (0 is none), as the two letters they're made of
    // (initial indices; -1 none): what's said at the end, and what carries over to a vowel after it.
    private val FINAL_PARTS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(0, 9), intArrayOf(2, -1), intArrayOf(2, 12), intArrayOf(2, 18),
        intArrayOf(3, -1), intArrayOf(5, -1), intArrayOf(5, 0), intArrayOf(5, 6), intArrayOf(5, 7), intArrayOf(5, 9), intArrayOf(5, 16),
        intArrayOf(5, 17), intArrayOf(5, 18), intArrayOf(6, -1), intArrayOf(7, -1), intArrayOf(7, 9), intArrayOf(9, -1), intArrayOf(10, -1),
        intArrayOf(11, -1), intArrayOf(12, -1), intArrayOf(14, -1), intArrayOf(15, -1), intArrayOf(16, -1), intArrayOf(17, -1), intArrayOf(18, -1),
    )

    /** What a final is said as before a consonant or at a word's end. */
    private enum class Sound(val latin: String) { K("k"), N("n"), T("t"), L("l"), M("m"), P("p"), NG("ng"), NONE("") }

    private fun sound(final: Int): Sound = when (final) {
        0 -> Sound.NONE
        1, 2, 3, 24, 9 -> Sound.K // ㄱ ㄲ ㄳ ㅋ ㄺ
        4, 5, 6 -> Sound.N // ㄴ ㄵ ㄶ
        7, 19, 20, 22, 23, 25, 27 -> Sound.T // ㄷ ㅅ ㅆ ㅈ ㅊ ㅌ ㅎ
        8, 11, 12, 13, 15 -> Sound.L // ㄹ ㄼ ㄽ ㄾ ㅀ
        16, 10 -> Sound.M // ㅁ ㄻ
        17, 26, 14, 18 -> Sound.P // ㅂ ㅍ ㄿ ㅄ
        21 -> Sound.NG // ㅇ
        else -> Sound.NONE
    }

    private class Syllable(var initial: Int, val vowel: Int, var final: Int)

    fun romanise(word: String): String {
        val syllables = word.mapNotNull { c ->
            val code = c.code - 0xAC00
            if (code < 0 || code > 11171) null else Syllable(code / 588, (code % 588) / 28, code % 28)
        }
        if (syllables.isEmpty()) return word
        val out = StringBuilder()
        // What the previous syllable's final became, said before this syllable's initial.
        var carried: String? = null
        for ((i, s) in syllables.withIndex()) {
            val next = syllables.getOrNull(i + 1)
            var initial = INITIAL[s.initial]
            // What came over from before this syllable decides its initial.
            if (carried != null) initial = carried
            carried = null
            out.append(initial).append(VOWEL[s.vowel])
            val parts = FINAL_PARTS[s.final]
            if (next == null) {
                out.append(sound(s.final).latin)
                break
            }
            val n = next.initial
            when {
                s.final == 0 -> Unit
                // Before a vowel, the final carries over and is said as an initial; ㅎ falls silent.
                n == 11 -> {
                    if (s.final == 21) {
                        out.append("ng")
                    } else {
                        val stays = parts[1] != -1
                        val moving = if (stays) parts[1] else parts[0]
                        if (stays) out.append(sound(firstOf(s.final)).latin)
                        carried = when {
                            moving == 18 -> "" // ㅎ: silent before a vowel
                            moving == 3 && next.vowel == 20 -> "j" // 굳이: guji
                            moving == 16 && next.vowel == 20 -> "ch" // 같이: gachi
                            moving == 5 -> "r"
                            else -> INITIAL[moving]
                        }
                    }
                }
                // ㅎ before ㄱ ㄷ ㅈ: breathed into it (좋다: jota, 많고: manko).
                parts.contains(18) && n in intArrayOf(0, 3, 12) -> {
                    if (parts[0] != 18) out.append(sound(firstOf(s.final)).latin)
                    carried = when (n) { 0 -> "k"; 3 -> "t"; else -> "ch" }
                }
                // ㄱ ㄷ ㅂ before ㅎ: breathed (축하: chuka).
                n == 18 && sound(s.final) in setOf(Sound.K, Sound.T, Sound.P) -> {
                    carried = when (sound(s.final)) { Sound.K -> "k"; Sound.T -> "t"; else -> "p" }
                }
                // ㄹ before ㄴ: ll (설날: seollal).
                n == 2 && sound(s.final) == Sound.L -> { out.append("l"); carried = "l" }
                // The nasals: ㄱㄷㅂ before ㄴ ㅁ (합니다: hamnida, 국물: gungmul).
                n == 2 || n == 6 -> out.append(
                    when (sound(s.final)) {
                        Sound.K -> "ng"
                        Sound.T -> "n"
                        Sound.P -> "m"
                        else -> sound(s.final).latin
                    },
                )
                // ㄹ after ㄴ or ㄹ: ll (신라: silla, 설날 below); after the other sounds, n (심리: simni, 독립: dongnip).
                n == 5 -> when (sound(s.final)) {
                    Sound.N, Sound.L -> { out.append("l"); carried = "l" }
                    Sound.K -> { out.append("ng"); carried = "n" }
                    Sound.T -> { out.append("n"); carried = "n" }
                    Sound.P -> { out.append("m"); carried = "n" }
                    else -> { out.append(sound(s.final).latin); carried = "n" }
                }
                else -> out.append(sound(s.final).latin)
            }
        }
        return out.toString()
    }

    /** The final a cluster leaves behind when its second letter carries over. */
    private fun firstOf(final: Int): Int = when (final) {
        3 -> 1 // ㄳ → ㄱ
        5, 6 -> 4 // ㄵ ㄶ → ㄴ
        9, 10, 11, 12, 13, 14, 15 -> 8 // ㄺ… → ㄹ
        18 -> 17 // ㅄ → ㅂ
        else -> final
    }
}
