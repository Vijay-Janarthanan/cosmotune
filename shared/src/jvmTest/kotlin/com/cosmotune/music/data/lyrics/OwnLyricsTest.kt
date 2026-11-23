package com.cosmotune.music.data.lyrics

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class OwnLyricsTest {
    private val dir = Files.createTempDirectory("own-lyrics").toFile()
    private val lines = listOf(LyricLine(1_000, "first line"), LyricLine(4_000, "second line"))

    @AfterTest
    fun cleanUp() {
        OwnLyrics.reset()
        LyricsStore.reset()
        dir.deleteRecursively()
    }

    @Test
    fun `saved lyrics come back after a restart`() {
        OwnLyrics.reset(dir)
        OwnLyrics.save("abc", lines, from = "LRCLIB")
        OwnLyrics.reset(dir)
        assertEquals(lines, OwnLyrics.lines("abc"))
        assertEquals("LRCLIB", OwnLyrics.origin("abc"))
    }

    @Test
    fun `removing them lets the providers answer again`() {
        OwnLyrics.reset(dir)
        OwnLyrics.save("abc", lines)
        OwnLyrics.remove("abc")
        OwnLyrics.reset(dir)
        assertNull(OwnLyrics.lines("abc"))
    }

    @Test
    fun `lyrics with no words are not kept`() {
        OwnLyrics.reset(dir)
        OwnLyrics.save("abc", listOf(LyricLine(0, "")))
        assertNull(OwnLyrics.lines("abc"))
    }

    @Test
    fun `ids that are not safe file names still get their own files`() {
        assertEquals("abc_DEF-1", OwnLyrics.safeName("abc_DEF-1"))
        assertNotEquals(OwnLyrics.safeName("a/b"), OwnLyrics.safeName("a:b"))
    }

    @Test
    fun `the store answers with them before any provider`() = runBlocking {
        OwnLyrics.reset(dir)
        LyricsStore.configure(null)
        var asked = false
        LyricsStore.lookup = { _, _ -> asked = true; null }
        OwnLyrics.save("abc", lines)
        val found = LyricsStore.lyrics(LyricsStore.Request("abc", "Song", "Artist", 200_000))
        assertEquals(LyricsSource.OWN, found?.source)
        assertEquals(lines, found?.lines)
        assertEquals(false, asked)
    }

    @Test
    fun `the listener's own lyrics are never offered as a provider`() {
        assertEquals(false, LyricsSource.OWN in LyricsSource.offered)
        assertEquals(false, LyricsSource.OWN in LyricsSource.ordered(LyricsSource.entries))
    }
}
