package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The main window's title, which the taskbar, Alt+Tab and the Dock show (Metrolist's desktop window title): the playing song and
 * its artist, then Cosmotune; just Cosmotune while nothing plays.
 */
internal object DesktopWindowTitle {
    const val APP = "Cosmotune"
    private val current = MutableStateFlow(APP)
    val title: StateFlow<String> = current.asStateFlow()

    fun show(song: String?, artist: String?, playing: Boolean) {
        current.value = of(song, artist, playing)
    }

    /** "Song – Artist · Cosmotune" while a song plays, "Cosmotune" otherwise; a long title is cut to keep the taskbar readable. */
    fun of(song: String?, artist: String?, playing: Boolean): String {
        val name = song?.trim().orEmpty()
        if (!playing || name.isEmpty()) return APP
        val who = artist?.trim().orEmpty()
        val line = if (who.isEmpty()) name else "$name – $who"
        return (if (line.length > 90) line.take(89).trimEnd() + "…" else line) + " · " + APP
    }
}
