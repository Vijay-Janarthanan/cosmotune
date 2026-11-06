package com.cosmotune.lyrics.autosync

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsAutoOffsetTest {

    private val rate = 16_000

    /** Word-timed lyrics with uneven words and gaps, so only one shift lines them up. */
    private fun lyrics(seed: Int): List<LyricLine> {
        val random = Random(seed)
        var at = 4_000L
        return List(14) { index ->
            val words = List(3 + random.nextInt(4)) {
                val length = 180L + random.nextInt(420)
                val word = LyricWord(at, at + length, "w$index$it")
                at += length + random.nextInt(160)
                word
            }
            at += 600L + random.nextInt(1_800)
            LyricLine(words.first().startMs, words.joinToString(" ") { it.text }, words)
        }
    }

    /**
     * A "song" in which every word is sung [lateByMs] earlier than the lyrics say: a centred harmonic voice during each
     * word over quiet, wide noise (left and right uncorrelated, as reverb and pads are).
     */
    private fun song(lines: List<LyricLine>, lateByMs: Long): Pair<FloatArray, FloatArray> {
        val end = lines.last().words.last().endMs + 4_000
        val frames = (end * rate / 1_000).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val noise = Random(7)
        for (i in 0 until frames) {
            left[i] = (noise.nextFloat() - 0.5f) * 0.05f
            right[i] = (noise.nextFloat() - 0.5f) * 0.05f
        }
        for (line in lines) for (word in line.words) {
            val from = ((word.startMs - lateByMs) * rate / 1_000).toInt().coerceAtLeast(0)
            val to = ((word.endMs - lateByMs) * rate / 1_000).toInt().coerceAtMost(frames)
            val pitch = 180.0 + (word.startMs % 7) * 20
            for (i in from until to) {
                val t = i.toDouble() / rate
                var voice = 0.0
                for (h in 1..8) voice += sin(2 * PI * pitch * h * t) / h
                val sample = (voice * 0.25).toFloat()
                left[i] += sample
                right[i] += sample
            }
        }
        return left to right
    }

    private fun reader(left: FloatArray, right: FloatArray) = LyricsAutoOffset.AudioReader { feed ->
        val block = 4_096
        var at = 0
        while (at < left.size) {
            val count = minOf(block, left.size - at)
            feed(left.copyOfRange(at, at + count), right.copyOfRange(at, at + count), count, rate, at * 1_000_000L / rate)
            at += count
        }
        true
    }

    @Test
    fun `the offset between the lyrics and the singing is found and saved`() = runBlocking {
        LyricsAutoOffset.configure(Files.createTempDirectory("auto-offset").toFile())
        val lines = lyrics(seed = 3)
        val (left, right) = song(lines, lateByMs = 700)
        LyricsAutoOffset.audioReady("songA", reader(left, right))
        val offset = assertNotNull(LyricsAutoOffset.sync("songA", lines, force = true))
        assertTrue(abs(offset - 700) <= 40, "found $offset ms")
        assertEquals(offset, LyricsAutoOffset.savedOffset("songA", lines))
        // Moved by it, the first word lands where it is sung.
        val moved = LyricsAutoOffset.applied("songA", lines)
        assertTrue(abs(moved.first().words.first().startMs - (lines.first().words.first().startMs - 700)) <= 40)
    }

    @Test
    fun `lyrics already on time need no offset`() = runBlocking {
        LyricsAutoOffset.configure(Files.createTempDirectory("auto-offset").toFile())
        val lines = lyrics(seed = 11)
        val (left, right) = song(lines, lateByMs = 0)
        LyricsAutoOffset.audioReady("songB", reader(left, right))
        val offset = assertNotNull(LyricsAutoOffset.sync("songB", lines, force = true))
        assertTrue(abs(offset) <= 40, "found $offset ms")
    }

    @Test
    fun `untimed lyrics are not listened for`() = runBlocking {
        val plain = List(8) { LyricLine(0L, "line $it") }
        assertNull(LyricsAutoOffset.sync("songC", plain, force = true, waitForAudioMs = 10))
    }

    @Test
    fun `shifting moves words, syllable ends and background lines together`() {
        val line = LyricLine(
            timeMs = 2_000,
            text = "a b",
            words = listOf(LyricWord(2_000, 2_400, "a"), LyricWord(2_500, 2_900, "b")),
            sungUntilMs = 3_000,
            background = LyricLine(2_600, "(b)"),
        )
        val moved = LyricsAutoOffset.shift(listOf(line), 500).single()
        assertEquals(1_500, moved.timeMs)
        assertEquals(listOf(1_500L, 2_000L), moved.words.map { it.startMs })
        assertEquals(2_500L, moved.sungUntilMs)
        assertEquals(2_100L, moved.background?.timeMs)
        assertEquals(0L, LyricsAutoOffset.shift(listOf(LyricLine(200, "x")), 500).single().timeMs)
    }

    @Test
    fun `another set of lyrics for the same song has its own key`() {
        val first = lyrics(seed = 1)
        val second = lyrics(seed = 2)
        assertTrue(LyricsAutoOffset.key("v", first) != LyricsAutoOffset.key("v", second))
        assertEquals(LyricsAutoOffset.key("v", first), LyricsAutoOffset.key("v", lyrics(seed = 1)))
    }
}
