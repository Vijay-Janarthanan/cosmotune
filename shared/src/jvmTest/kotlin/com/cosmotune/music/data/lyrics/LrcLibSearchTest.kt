package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LrcLibSearchTest {
    @Test
    fun `hits with lyrics come back, timed ones first`() {
        val body = """
            [
              {"trackName":"Plain Song","artistName":"A","albumName":"X","duration":201.5,"plainLyrics":"one\ntwo\nthree","syncedLyrics":null},
              {"trackName":"Timed Song","artistName":"B","duration":180,"plainLyrics":"x\ny","syncedLyrics":"[00:01.00] one\n[00:03.50] two"},
              {"trackName":"Instrumental","artistName":"C","duration":120,"plainLyrics":null,"syncedLyrics":null}
            ]
        """.trimIndent()
        val hits = LrcLib.parseSearch(body)
        assertEquals(listOf("Timed Song", "Plain Song"), hits.map { it.title })
        assertEquals(true, hits[0].synced)
        assertEquals(listOf(1_000L, 3_500L), hits[0].lines.filter { !it.isGap }.map { it.timeMs })
        assertEquals(201_500L, hits[1].durationMs)
        assertEquals(listOf("one", "two", "three"), hits[1].lines.map { it.text })
    }

    @Test
    fun `an answer that is not a list is no hits`() {
        assertEquals(emptyList(), LrcLib.parseSearch("""{"error":"x"}"""))
    }
}
