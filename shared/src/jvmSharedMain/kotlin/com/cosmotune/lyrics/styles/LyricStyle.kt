package com.cosmotune.lyrics.styles

import com.cosmotune.lyrics.LyricDuet
import com.cosmotune.lyrics.SungFraction
import com.cosmotune.music.data.lyrics.LyricLine

/**
 * The six ways the lyrics can be drawn (the approved board, `docs/superpowers/design/cosmotune/lyrics-titles/board.html`). [key]
 * is what the settings keep, so a renamed title does not lose anyone's choice.
 */
enum class LyricStyle(val key: String, val title: String, val detail: String) {
    /** The list: one big line, the moon under it, the last line shrunk above and the next waiting below. The default. */
    ORBIT_READ("orbit_read", "Orbit Read", "One big line with the moon under it"),

    /** The line round the cover disc, the moon lighting each word, the next line on an outer ring. */
    ORBITAL("orbital", "Orbital", "The line wraps round the cover"),

    /** The word being sung widens and gets heavier for as long as it is held, then settles. */
    STRETCH("stretch", "Stretch", "Held words grow wide"),

    /** Each singer on their own side and colour; backing vocals between them. */
    CALL_AND_ANSWER("call_answer", "Call & Answer", "Each singer on their own side"),

    /** The line breathes with the music: bass swells the glow, a kick nudges the line. */
    PULSE("pulse", "Pulse", "Breathes with the music"),

    /** Each line as a poster in the cover's colour, condensed caps filling the frame. */
    POSTER("poster", "Poster", "Condensed caps in the cover's colour"),
    ;

    companion object {
        /** The style kept as [key], or null for none (or one this version does not know). */
        fun ofKey(key: String?): LyricStyle? = if (key.isNullOrEmpty()) null else entries.firstOrNull { it.key == key }
    }
}

/** Where the lyrics are being drawn, which decides the styles offered there (the board's "Where they go"). */
enum class LyricSurface { PHONE, DESKTOP, TV, WATCH, SHARE_VIDEO }

/** The decisions about which style a song is drawn in. Pure, so the rules are tested without a window. */
object LyricStyles {
    private val everyStyle = LyricStyle.entries.toList()
    private val shareStyles = listOf(LyricStyle.POSTER, LyricStyle.STRETCH, LyricStyle.ORBITAL)

    /** The styles a [surface] offers, in the order its picker lists them. */
    fun on(surface: LyricSurface): List<LyricStyle> = when (surface) {
        LyricSurface.PHONE, LyricSurface.DESKTOP, LyricSurface.TV -> everyStyle
        LyricSurface.WATCH -> listOf(LyricStyle.ORBITAL)
        LyricSurface.SHARE_VIDEO -> shareStyles
    }

    /** On the TV these take the whole screen (the disc in the middle of Orbital, or no disc at all); the rest sit beside the disc. */
    fun fullScreenOnTv(style: LyricStyle): Boolean =
        style == LyricStyle.ORBITAL || style == LyricStyle.STRETCH || style == LyricStyle.POSTER

    /** Whether the song carries a second voice: a duet's other side, or backing vocals timed on their own. */
    fun hasVoices(lines: List<LyricLine>): Boolean {
        if (LyricDuet.isDuet(lines)) return true
        for (i in lines.indices) if (lines[i].background != null) return true
        return false
    }

    /**
     * The style [lines] are drawn in on [surface].
     *
     * The song's own choice ([forSong]) comes first, then the one the listener chose for every song ([chosen]). With neither, a
     * song that has duet or backing-vocal timing is drawn in Call & Answer by itself, and anything else in Orbit Read. Lyrics
     * without timings are always the list: every other style follows the singer, and there is nothing to follow. A style the
     * surface does not offer falls back to the surface's first.
     */
    fun resolve(chosen: LyricStyle?, forSong: LyricStyle?, lines: List<LyricLine>, surface: LyricSurface): LyricStyle {
        val offered = on(surface)
        if (!SungFraction.isSynced(lines)) return if (LyricStyle.ORBIT_READ in offered) LyricStyle.ORBIT_READ else offered.first()
        val pick = forSong ?: chosen ?: if (hasVoices(lines)) LyricStyle.CALL_AND_ANSWER else LyricStyle.ORBIT_READ
        return if (pick in offered) pick else offered.first()
    }
}
