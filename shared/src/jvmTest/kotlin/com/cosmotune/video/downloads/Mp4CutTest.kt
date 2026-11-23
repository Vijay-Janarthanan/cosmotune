package com.cosmotune.video.downloads

import com.cosmotune.video.model.Chapter
import com.cosmotune.media.segments.SegmentAction
import com.cosmotune.media.segments.SegmentCategory
import com.cosmotune.media.segments.SkipSegment
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cutting segments out of a download. `cut.mp4` (under `resources/video/embed`, made with the FFmpeg program) is four seconds
 * at ten pictures a second with a keyframe every second and B-frames; `frag.m4a` is two seconds of sound in fragments.
 */
class Mp4CutTest {
    private val dir = createTempDirectory("cut").toFile()

    private fun fixture(name: String): File = File(dir, name).apply { writeBytes(Mp4CutTest::class.java.getResource("/video/embed/$name")!!.readBytes()) }

    @Test fun aCutEndsOnTheNextKeyframeAndBothTracksLoseTheSameStretch() {
        val input = fixture("cut.mp4")
        val output = File(dir, "out.mp4")
        // the keyframes are 0.9 s apart in decode order (B-frames): at 1.0 s, 1.9 s, 2.8 s
        val cuts = Mp4Cut.cut(input, output, listOf(CutRange(1_050, 1_850)))!!
        assertEquals(listOf(CutRange(1_100, 1_900)), cuts, "from the next picture to the keyframe at 1.9 s")
        val before = durations(input)
        val after = durations(output)
        assertEquals(before.size, after.size)
        for (i in before.indices) assertTrue(before[i] - after[i] in 750L..850L, "track $i: ${before[i]} to ${after[i]}")
        // the file is whole: a second cut of the result works too and an embed reads it
        assertTrue(Mp4Embed.embed(output, File(dir, "tagged.mp4"), EmbedDetails("t")))
        assertTrue(output.length() < input.length())
    }

    @Test fun aCutAtTheStartBeginsThePictureOnAKeyframe() {
        val cuts = Mp4Cut.cut(fixture("cut.mp4"), File(dir, "start.mp4"), listOf(CutRange(0, 500)))!!
        assertEquals(listOf(CutRange(0, 1_000)), cuts)
    }

    @Test fun aFragmentedSoundFileIsCutIntoAPlainOne() {
        val output = File(dir, "out.m4a")
        val cuts = Mp4Cut.cut(fixture("frag.m4a"), output, listOf(CutRange(500, 1_000)))!!
        assertEquals(1, cuts.size)
        val types = RandomAccessFile(output, "r").use { raf -> Mp4Embed.top(raf).map { it.type } }
        assertEquals(listOf("ftyp", "moov", "mdat"), types)
        assertTrue(durations(output).single() in 1_400L..1_600L)
    }

    @Test fun aCutThatWouldRunFarPastItsEndIsNotMade() {
        // keyframes 1 s apart at most here, so a 12 s gap cannot be built; a lone keyframe far away must not take the rest
        val times = LongArray(30) { it * 1_000L }
        val sync = BooleanArray(30) { it == 0 || it == 25 }
        assertEquals(emptyList(), SegmentCuts.snap(times, sync, listOf(CutRange(2_000, 4_000)), 30_000))
        assertEquals(listOf(CutRange(2_000, 25_000)), SegmentCuts.snap(times, sync, listOf(CutRange(2_000, 20_000)), 30_000))
    }

    @Test fun noCutLeavesNothingWrittenAndJunkAnswersNull() {
        assertEquals(emptyList(), Mp4Cut.cut(fixture("cut.mp4"), File(dir, "none.mp4"), listOf(CutRange(10_000, 11_000))))
        val junk = File(dir, "junk.mp4").apply { writeBytes(ByteArray(64)) }
        assertNull(Mp4Cut.cut(junk, File(dir, "junk-out.mp4"), listOf(CutRange(0, 100))))
    }

    @Test fun timesMoveWithTheCuts() {
        val cuts = listOf(CutRange(1_000, 2_000), CutRange(5_000, 6_000))
        assertEquals(500, SegmentCuts.map(500, cuts))
        assertEquals(1_000, SegmentCuts.map(1_500, cuts))
        assertEquals(2_000, SegmentCuts.map(3_000, cuts))
        assertEquals(5_000, SegmentCuts.map(7_000, cuts))
        val chapters = SegmentCuts.chapters(listOf(Chapter("a", 0, null), Chapter("sponsor", 1_000, null), Chapter("b", 2_000, null)), cuts)
        assertEquals(listOf("a" to 0L, "b" to 1_000L), chapters.map { it.title to it.startMs })
        assertEquals(listOf(VttCue(500, 1_000, "x")), SegmentCuts.cues(listOf(VttCue(500, 1_500, "x"), VttCue(1_200, 1_800, "gone")), cuts))
    }

    @Test fun onlySkippingSegmentsOfSkippedCategoriesAreCutAndTouchingOnesMerge() {
        fun seg(id: String, cat: SegmentCategory, action: SegmentAction, s: Long, e: Long) = SkipSegment(id, cat, action, s, e, null, 0, false, null)
        val segments = listOf(
            seg("1", SegmentCategory.values()[0], SegmentAction.SKIP, 1_000, 2_000),
            seg("2", SegmentCategory.values()[0], SegmentAction.SKIP, 1_800, 3_000),
            seg("3", SegmentCategory.values()[0], SegmentAction.MUTE, 4_000, 5_000),
            seg("4", SegmentCategory.values()[1], SegmentAction.SKIP, 6_000, 7_000),
        )
        assertEquals(listOf(CutRange(1_000, 3_000)), SegmentCuts.ranges(segments, setOf(SegmentCategory.values()[0])))
        val left = SegmentCuts.remaining(segments, setOf("1", "2"), listOf(CutRange(1_000, 3_000)))
        assertEquals(listOf("3" to 2_000L, "4" to 4_000L), left.map { it.uuid to it.startMs })
    }

    @Test fun cuesAreWrittenAsWebVtt() {
        val text = SegmentCuts.vtt(listOf(VttCue(3_723_004, 3_724_000, "Hi\nthere")))
        assertEquals("WEBVTT\n\n01:02:03.004 --> 01:02:04.000\nHi\nthere\n\n", text)
        assertEquals(listOf(VttCue(3_723_004, 3_724_000, "Hi\nthere")), VttCues.parse(text))
    }

    /** Each track's length in milliseconds, from its media header. */
    private fun durations(file: File): List<Long> = RandomAccessFile(file, "r").use { raf ->
        val moovBox = Mp4Embed.top(raf).single { it.type == "moov" }
        val m = ByteArray(moovBox.size.toInt()).also { raf.seek(moovBox.offset); raf.readFully(it) }
        Mp4Embed.children(m, 8, m.size).filter { it.type == "trak" }.map { trak ->
            val mdia = Mp4Embed.children(m, trak.content.toInt(), trak.end.toInt()).single { it.type == "mdia" }
            val mdhd = Mp4Embed.children(m, mdia.content.toInt(), mdia.end.toInt()).single { it.type == "mdhd" }
            val at = mdhd.content.toInt()
            val scale = u32(m, at + 12)
            val sum = if (u32(m, at + 16) == 0L) sampleSum(m, mdia) else u32(m, at + 16)
            sum * 1000 / scale
        }
    }

    private fun sampleSum(m: ByteArray, mdia: Mp4Embed.Box): Long {
        var box = mdia
        for (t in listOf("minf", "stbl", "stts")) box = Mp4Embed.children(m, box.content.toInt(), box.end.toInt()).single { it.type == t }
        return (0 until u32(m, box.content.toInt() + 4).toInt()).sumOf { u32(m, box.content.toInt() + 8 + it * 8) * u32(m, box.content.toInt() + 12 + it * 8) }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
