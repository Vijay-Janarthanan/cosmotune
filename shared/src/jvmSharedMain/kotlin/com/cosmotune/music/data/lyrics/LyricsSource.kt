package com.cosmotune.music.data.lyrics

/**
 * The databases [LyricsRepository] can ask, in the order it asks them.
 *
 * Exposed in Settings because every one of them is a third-party service
 * reached on the user's connection, and anyone who would rather not talk to a
 * given one should be able to say so.
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether it can return per-word timings, or only whole lines. */
    val wordSynced: Boolean,
    /**
     * Kept in code but off the list: not shown in Settings or the provider
     * picker, and never asked — even by someone whose saved choices still name
     * it. For a host that has stopped answering, where deleting the provider
     * outright would only mean writing it again if it comes back.
     */
    val hidden: Boolean = false,
) {
    // Declaration order is the default priority — [AppSettings.lyricsSourceOrder]
    // and [AppSettings.lyricsSources] both fall back to [LyricsSource.entries]
    // verbatim, so this list *is* the out-of-the-box experience. Stored by
    // name rather than position, so this can be rearranged without disturbing
    // an order somebody has already chosen for themselves.
    //
    // Measured in November 2026 (docs/lyrics-sources-2026-11.md): every source
    // here answers, and each one has songs nobody else on the list times as
    // well. Word-timed sources lead; [YOUTUBE_MUSIC] is the line-timed answer
    // for the very song playing, and [LRCLIB] the one that still finds a
    // music-video upload or a local file by its name.
    LRC_RED(
        label = "lrc.red",
        detail = "Apple Music timings, syllable by syllable",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        label = "BetterLyrics",
        detail = "Apple Music timings, word by word",
        wordSynced = true,
    ),
    BETTER_LYRICS_PORTATO(
        label = "BetterLyrics Portato",
        detail = "QQ Music karaoke timings through BetterLyrics",
        wordSynced = true,
    ),
    YOUTUBE_MUSIC(
        label = "YouTube Music",
        detail = "The playing song's own lyrics, timed line by line where YouTube has it",
        wordSynced = false,
    ),
    LRCLIB(
        label = "LRCLIB",
        detail = "Whole lines, from a free community library",
        wordSynced = false,
    ),
    ;

    companion object {
        /** Every source a listener can see and choose, in default priority. */
        val offered: List<LyricsSource> = entries.filterNot { it.hidden }

        /**
         * A saved order brought up to date with this build: hidden sources
         * dropped, and any source added since it was saved slotted in right
         * after the one it is declared behind — at the very top if nothing is
         * declared ahead of it. So a new source lands where a fresh install
         * puts it relative to its neighbours, rather than under every
         * line-only fallback where it would never get to answer.
         */
        fun ordered(saved: List<LyricsSource>): List<LyricsSource> {
            val result = saved.filter { it in offered }.distinct().toMutableList()
            offered.forEachIndexed { index, source ->
                if (source in result) return@forEachIndexed
                val before = offered.subList(0, index).lastOrNull { it in result }
                result.add(before?.let { result.indexOf(it) + 1 } ?: 0, source)
            }
            return result
        }

        /**
         * The sources a saved choice switches on, read by name so that a name
         * this build no longer has falls out quietly. [chosen] is what was
         * ticked; [seen] is every source that was on the list when it was
         * ticked, so a source added since is switched on rather than taken as
         * turned off.
         *
         * A choice can end up naming only sources that have since been
         * removed. Nobody can untick the last source by hand, so that is never
         * a choice of "no lyrics": it starts over with every source instead.
         */
        fun enabled(chosen: Collection<String>, seen: Collection<String>): Set<LyricsSource> {
            val picked = offered.filter { it.name in chosen || it.name !in seen }.toSet()
            return picked.ifEmpty { offered.toSet() }
        }
    }
}
