package com.cosmotune.music.data.lyrics

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented lines only: these fixtures stand in for the shape, never for real lyrics. */
class YouTubeMusicLyricsTest {

    private fun page(entries: String) = Json.parseToJsonElement(
        """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{
          "lyricsData":{"sourceMessage":"Source: Example","timedLyricsData":[$entries]}}}}}}}}}""",
    )

    private fun cue(text: String, start: String, end: String) =
        """{"lyricLine":"$text","cueRange":{"startTimeMilliseconds":"$start","endTimeMilliseconds":"$end","metadata":{"id":"1"}}}"""

    @Test
    fun `timed cues become line-synced lines in order`() {
        val lines = YouTubeMusicLyrics.parseTimedPage(
            page(
                listOf(
                    cue("Second paper lantern", "9000", "12500"),
                    cue("First paper lantern", "4200", "8800"),
                    cue("Third paper lantern", "13000", "16000"),
                ).joinToString(","),
            ),
        )!!.filterNot { it.isGap }
        assertEquals(listOf(4200L, 9000L, 13000L), lines.map { it.timeMs })
        assertEquals("First paper lantern", lines.first().text)
        assertEquals(8800L, lines.first().sungUntilMs)
        assertTrue(lines.none { it.isWordSynced })
    }

    @Test
    fun `blank and note-only cues are dropped`() {
        val lines = YouTubeMusicLyrics.parseTimedPage(
            page(
                listOf(
                    cue("", "0", "3000"),
                    cue("Lanterns on the river", "3000", "6000"),
                    cue("♪", "6000", "9000"),
                    cue("Boats across the water", "9000", "12000"),
                ).joinToString(","),
            ),
        )!!.filterNot { it.isGap }
        assertEquals(listOf("Lanterns on the river", "Boats across the water"), lines.map { it.text })
    }

    @Test
    fun `numeric cue times are read too`() {
        val entries = """{"lyricLine":"One kite","cueRange":{"startTimeMilliseconds":1000,"endTimeMilliseconds":2000}},""" +
            """{"lyricLine":"Two kites","cueRange":{"startTimeMilliseconds":2500,"endTimeMilliseconds":3500}}"""
        assertEquals(listOf(1000L, 2500L), YouTubeMusicLyrics.parseTimedPage(page(entries))!!.filterNot { it.isGap }.map { it.timeMs })
    }

    @Test
    fun `a long silence after a line is marked as a break`() {
        val lines = YouTubeMusicLyrics.parseTimedPage(
            page(listOf(cue("Before the solo", "1000", "3000"), cue("After the solo", "30000", "33000")).joinToString(",")),
        )!!
        assertEquals(3_000L, lines.single { it.isGap }.timeMs)
    }

    @Test
    fun `a page without timing is not taken as timed`() {
        assertNull(YouTubeMusicLyrics.parseTimedPage(page(cue("Only a single line", "0", "0"))))
        assertNull(
            YouTubeMusicLyrics.parseTimedPage(
                Json.parseToJsonElement("""{"contents":{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Plain"}]}}}}"""),
            ),
        )
    }
}
