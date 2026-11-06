package com.cosmotune.lyrics.video

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsVideoPlanTest {

    private val lines = listOf(
        LyricLine(10_000, "first line"),
        LyricLine(14_000, "hello world", words = listOf(LyricWord(14_000, 14_500, "hello"), LyricWord(14_600, 15_400, "world"))),
        LyricLine(18_000, "third line"),
        LyricLine(40_000, "much later"),
    )

    @Test
    fun `the chosen lines set the stretch, with a lead-in`() {
        val plan = assertNotNull(LyricsVideoPlan.forSelection(lines, listOf("hello world", "third line")))
        assertEquals(14_000 - LyricsVideoPlan.LEAD_IN_MS, plan.startMs)
        // At least the minimum, so a two-line choice is still worth watching.
        assertTrue(plan.durationMs >= LyricsVideoPlan.MIN_MS)
        assertTrue(plan.durationMs <= LyricsVideoPlan.MAX_MS)
    }

    @Test
    fun `a long choice is cut to the maximum`() {
        val plan = assertNotNull(LyricsVideoPlan.forSelection(lines, listOf("first line", "much later")))
        assertEquals(LyricsVideoPlan.MAX_MS, plan.durationMs)
    }

    @Test
    fun `word timing lights the line word by word`() {
        val plan = LyricsVideoPlan(lines, startMs = 13_000, endMs = 20_000)
        val before = plan.frameAt(500)
        assertEquals("first line", before.current)
        val halfway = plan.frameAt(14_550 - 13_000)
        assertEquals("hello world", halfway.current)
        assertEquals("first line", halfway.previous)
        assertEquals("third line", halfway.next)
        // "hello" is sung, "world" not yet: five of eleven characters.
        assertEquals(5f / 11f, halfway.sung, 0.01f)
        assertEquals(1f, plan.frameAt(15_500 - 13_000).sung, 0.01f)
    }

    @Test
    fun `line timing lights the line evenly`() {
        val plan = LyricsVideoPlan(lines, startMs = 0, endMs = 30_000)
        val frame = plan.frameAt(12_000)
        assertEquals("first line", frame.current)
        assertEquals(0.5f, frame.sung, 0.01f)
        assertNull(plan.frameAt(1_000).current)
    }

    @Test
    fun `untimed lyrics make no video`() {
        assertNull(LyricsVideoPlan.forSelection(listOf(LyricLine(0, "a"), LyricLine(0, "b")), emptyList()))
    }
}
