package com.cosmotune.watch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleSyncTest {
    @Test fun readsTheTimesOfEveryFormat() {
        val srt = "1\n00:01:02,345 --> 00:01:04,000\nHello\n\n2\n01:00:00,000 --> 01:00:01,500\nBye\n"
        assertEquals(listOf(CueTime(62_345, 64_000), CueTime(3_600_000, 3_601_500)), SubtitleSync.cueTimes(srt))
        val vtt = "WEBVTT\n\n01:02.345 --> 01:04.000 align:start\nHello\n"
        assertEquals(listOf(CueTime(62_345, 64_000)), SubtitleSync.cueTimes(vtt))
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:01:02.34,0:01:04.00,Default,,0,0,0,,Hello\n"
        assertEquals(listOf(CueTime(62_340, 64_000)), SubtitleSync.cueTimes(ass))
        val ttml = """<p begin="00:01:02.345" end="00:01:04.000">Hi</p><p begin="70s" end="71.5s">Yo</p>"""
        assertEquals(listOf(CueTime(62_345, 64_000), CueTime(70_000, 71_500)), SubtitleSync.cueTimes(ttml))
        assertTrue(SubtitleSync.cueTimes("no times here").isEmpty())
    }

    @Test fun findsHowLateTheSubtitlesShouldBe() {
        for (truth in listOf(2_400L, -7_300L, 0L, 41_000L)) {
            val (cues, clip) = scene(truth, seed = truth.toInt())
            val speech = assertNotNull(SubtitleSync.speech(clip))
            val found = assertNotNull(SubtitleSync.align(speech, clip.startMs, cues), "delay $truth")
            assertTrue(abs(found - truth) <= 100, "wanted $truth, got $found")
        }
    }

    @Test fun saysNothingWithoutVoices() {
        val rate = 8_000
        val samples = FloatArray(rate * 90) { (0.2 * sin(2 * PI * 110 * it / rate)).toFloat() }
        assertNull(SubtitleSync.speech(PcmClip(0, rate, samples)), "an even tone has no voices")
        val (cues, clip) = scene(0, seed = 3)
        val speech = assertNotNull(SubtitleSync.speech(clip))
        assertNull(SubtitleSync.align(speech, clip.startMs, cues.take(3)), "three lines are too few to judge by")
        assertNull(SubtitleSync.align(speech, clip.startMs, cues.map { CueTime(it.startMs + 3_600_000, it.endMs + 3_600_000) }), "lines an hour away")
    }

    /** Lines between 10 and 100 s, and a clip from 10 s whose voices come [delay] after each line's time, over a low score. */
    private fun scene(delay: Long, seed: Int): Pair<List<CueTime>, PcmClip> {
        val random = Random(seed)
        val cues = mutableListOf<CueTime>()
        var at = 0L
        while (at < 200_000) {
            at += 400 + random.nextLong(3_500)
            val length = 900 + random.nextLong(2_500)
            cues += CueTime(at, at + length)
            at += length
        }
        val rate = 8_000
        val start = 60_000L
        val samples = FloatArray(rate * 90) { i ->
            val t = i.toDouble() / rate
            (0.15 * sin(2 * PI * 80 * t) + 0.01 * (random.nextFloat() - 0.5)).toFloat()
        }
        cues.forEach { cue ->
            // A voice starts a little after its line and ends a little before.
            val a = cue.startMs + delay + random.nextLong(150) - start
            val b = cue.endMs + delay - random.nextLong(200) - start
            for (ms in a until b) {
                if (ms < 0 || ms >= 90_000) continue
                for (k in 0 until rate / 1000) samples[(ms * rate / 1000).toInt() + k] += 0.3f * (random.nextFloat() - 0.5f)
            }
        }
        return cues to PcmClip(start, rate, samples)
    }
}
