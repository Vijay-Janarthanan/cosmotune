package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsDraftTest {
    private val words = "Hello there\n\n  General Kenobi  \nYou are a bold one"

    @Test
    fun `typed words become one line per non-empty line`() {
        val draft = LyricsDraft.fromText(words)
        assertEquals(listOf("Hello there", "General Kenobi", "You are a bold one"), draft.lines.map { it.text })
        assertEquals(false, draft.started)
    }

    @Test
    fun `tapping stamps each line in turn and undo takes it back`() {
        var draft = LyricsDraft.fromText(words).tap(1_000).tap(3_000)
        assertEquals(listOf(1_000L, 3_000L, null), draft.lines.map { it.startMs })
        assertEquals(2, draft.line)
        draft = draft.undo()
        assertEquals(listOf(1_000L, null, null), draft.lines.map { it.startMs })
        assertEquals(1, draft.line)
        draft = draft.tap(2_500).tap(6_000)
        assertTrue(draft.timed)
        assertTrue(draft.finished)
    }

    @Test
    fun `a stamp never runs backwards`() {
        val draft = LyricsDraft.fromText(words).tap(5_000).tap(4_000)
        assertEquals(5_000L, draft.lines[1].startMs)
    }

    @Test
    fun `word mode stamps every word and gives each an end`() {
        val draft = LyricsDraft.fromText("Hello there\nGeneral Kenobi").withMode(LyricsDraft.Mode.WORDS)
            .tap(1_000).tap(1_500).tap(3_000).tap(3_600)
        val lines = draft.toLines(durationMs = 10_000)
        assertEquals(listOf(1_000L, 3_000L), lines.map { it.timeMs })
        assertEquals(listOf("Hello", "there"), lines[0].words.map { it.text })
        assertEquals(listOf(1_000L to 1_500L, 1_500L to 3_000L), lines[0].words.map { it.startMs to it.endMs })
        // The song's last word is held at most a few seconds, never past the song.
        assertEquals(3_600L + LyricsDraft.MAX_LAST_WORD_MS, lines[1].words.last().endMs)
        assertTrue(lines.all { it.isWordSynced })
    }

    @Test
    fun `undo in word mode goes back one word`() {
        val draft = LyricsDraft.fromText("Hello there").withMode(LyricsDraft.Mode.WORDS).tap(1_000).undo()
        assertEquals(0, draft.word)
        assertEquals(null, draft.lines[0].startMs)
    }

    @Test
    fun `untimed lines fall between their stamped neighbours`() {
        val draft = LyricsDraft.fromText(words).tap(1_000).moveTo(2).tap(5_000)
        assertEquals(listOf(1_000L, 3_000L, 5_000L), draft.toLines().map { it.timeMs })
    }

    @Test
    fun `a draft with no stamps is plain lyrics`() {
        val lines = LyricsDraft.fromText(words).toLines()
        assertTrue(lines.all { it.timeMs == 0L && !it.isWordSynced })
    }

    @Test
    fun `a second voice line is drawn on the other side`() {
        val lines = LyricsDraft.fromText(words).toggleSecondVoice(1).toLines()
        assertEquals(listOf(LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start), lines.map { it.alignment })
    }

    @Test
    fun `existing timed lyrics open with their stamps, ready to correct`() {
        val existing = listOf(LyricLine(0, ""), LyricLine(2_000, "one two"), LyricLine(4_000, "three", alignment = LyricAlignment.End))
        val draft = LyricsDraft.fromLines(existing)
        assertEquals(listOf(2_000L, 4_000L), draft.lines.map { it.startMs })
        assertTrue(draft.lines[1].secondVoice)
        assertTrue(draft.finished)
        assertEquals(existing.drop(1).map { it.timeMs }, draft.toLines().map { it.timeMs })
    }

    @Test
    fun `changing the words keeps the stamps of unchanged lines`() {
        val draft = LyricsDraft.fromText(words).tap(1_000).tap(2_000).withText("Hello there\nGeneral Grievous\nYou are a bold one")
        assertEquals(listOf(1_000L, null, null), draft.lines.map { it.startMs })
    }
}
