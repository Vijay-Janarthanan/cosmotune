package com.cosmotune.lyrics

/**
 * Which way a lyric line reads, from the line's own text and never from the app's language (BC-33, upstream `0693381f`,
 * `2db15519` and the RTL lyric commits after them). The rule is upstream's `PlayerLyrics.isRtl`, shared here so the classic view
 * and the new look decide alike: a Hebrew presentation form anywhere, or more strong right-to-left characters (Hebrew, Arabic,
 * Syriac, Thaana, N'Ko and the rest the JVM's bidi classes mark R or AL) than strong left-to-right ones. Digits, spaces and
 * punctuation are neutral, so "123" reads left to right and "שלום 123" right to left.
 */
object LyricTextDirection {

    fun isRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            // Hebrew presentation forms.
            if (ch.code in 0xFB1D..0xFB4F) return true
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > ltr
    }

    /**
     * Whether a line is written from the right edge of the lyrics: a right-to-left line is, and a duet's second voice
     * ([onEnd]) stands at the far end of its own reading direction, so a right-to-left second voice is written from the left.
     */
    fun fromRight(textRtl: Boolean, onEnd: Boolean): Boolean = textRtl != onEnd
}
