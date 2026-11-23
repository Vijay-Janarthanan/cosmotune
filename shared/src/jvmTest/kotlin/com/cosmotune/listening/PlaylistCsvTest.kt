package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistCsvTest {

    @Test
    fun rowsKeepTheirOrderAndQuoteWhatNeedsIt() {
        val text = PlaylistCsv.export(listOf(
            PlaylistCsvRow("Hello, World", "Ann \"A\" Lee", "Débuts", "3:05", "dQw4w9WgXcQ"),
            PlaylistCsvRow("Local song", "Me", null, null, "local:/music/a.mp3"),
        ))
        val lines = text.removePrefix("\uFEFF").split("\r\n")
        assertEquals(PlaylistCsv.HEADER, lines[0])
        assertEquals("\"Hello, World\",\"Ann \"\"A\"\" Lee\",Débuts,3:05,https://music.youtube.com/watch?v=dQw4w9WgXcQ", lines[1])
        assertEquals("Local song,Me,,,", lines[2])
    }

    @Test
    fun formulasStayText() {
        val text = PlaylistCsv.export(listOf(PlaylistCsvRow("=HYPERLINK(\"x\")", "+1", null, null, null)))
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\",'+1,,,", text.removePrefix("\uFEFF").split("\r\n")[1])
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Road-trip-2026.csv", PlaylistCsv.fileName("Road trip / 2026!"))
        assertEquals("playlist.csv", PlaylistCsv.fileName("***"))
    }
}
