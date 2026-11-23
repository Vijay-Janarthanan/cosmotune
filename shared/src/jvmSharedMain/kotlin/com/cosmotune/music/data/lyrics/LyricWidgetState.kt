package com.cosmotune.music.data.lyrics

/** What the lyrics widget shows: the song, the line being sung (or a plain note), and the line after it. */
data class LyricWidgetState(val song: String, val line: String, val next: String) {
    companion object {
        fun idle() = LyricWidgetState("", "Play a song to see its lyrics here", "")

        /**
         * The state for [title] by [artist] at [positionMs] into a song with [lines] (null while none were found). Between lines
         * (a gap, or before the first) the coming line is shown dimmed under a quiet mark, so the widget never goes blank.
         */
        fun at(title: String, artist: String, lines: List<LyricLine>?, positionMs: Long): LyricWidgetState {
            val song = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")
            val sung = lines?.filter { it.isGap || it.text.isNotBlank() }.orEmpty()
            if (sung.isEmpty()) return LyricWidgetState(song, "No lyrics for this song", "")
            if (sung.size > 1 && sung.all { it.timeMs == 0L }) return LyricWidgetState(song, "These lyrics are not timed", "Open the player to read them")
            val index = sung.indexOfLast { it.timeMs <= positionMs }
            val current = sung.getOrNull(index)?.takeIf { !it.isGap }
            val next = sung.drop(index + 1).firstOrNull { !it.isGap }?.text.orEmpty()
            return LyricWidgetState(song, current?.text ?: "♪", next)
        }
    }
}
