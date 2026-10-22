package com.cosmotune.lyrics

import com.cosmotune.design.LyricsMetrics
import com.cosmotune.music.data.lyrics.LyricLine

/**
 * The second voice under a line, an echo or an answer ([LyricLine.background]): when it is on show, and what it reads as. The old
 * panel's rules (`PlayerLyrics.kt`, `backingOpen` and `withoutBracketPunctuation`) as plain functions.
 */
object LyricBacking {
    /**
     * Whether the voice is on show at [positionMs]. A song with no timings shows it always, and so does a pick of lines (what
     * goes on the card is what is on the page). A voice that has words of its own opens on its own clock, just ahead of its
     * first word and gone at its last; one that has none has no clock to open on, so it borrows the lead's ([active]).
     */
    fun wanted(background: LyricLine, synced: Boolean, picking: Boolean, active: Boolean, positionMs: Long): Boolean =
        !synced || picking || if (background.isWordSynced) due(background, positionMs) else active

    /** Whether [positionMs] is within the voice's own words, with the lead-in that lets it open before the first. */
    fun due(background: LyricLine, positionMs: Long): Boolean =
        positionMs >= background.timeMs - LyricsMetrics.BACKING_LEAD_MS && positionMs < background.endMs

    /**
     * The voice as it is drawn: without the parentheses every text-only source wraps it in. Applied to the words as well as the
     * text, because the sweep measures the words against the text it draws and a word reading "(ooh" in a line reading "ooh"
     * would look for a substring that is no longer there. The LRC writer still gets the provider's own punctuation; this is for
     * display only. Builds a copy: call it once per line, not per frame.
     */
    fun display(background: LyricLine): LyricLine = background.copy(
        text = background.text.stripParens(),
        words = background.words.mapNotNull { word -> word.withoutChars { it == '(' || it == ')' } },
    )

    /** Whether a translation or romanisation says anything the [original] does not: a line the service handed back unchanged gets no second copy of itself. */
    fun differs(sub: String, original: String): Boolean = sub.trim().lowercase() != original.trim().lowercase()

    private fun String.stripParens(): String = replace("(", "").replace(")", "").trim()
}
