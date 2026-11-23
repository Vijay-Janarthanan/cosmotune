package com.cosmotune.video.downloads

import com.cosmotune.video.model.Chapter
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The details put inside a download: the tiny files under `resources/video/embed` were made with the FFmpeg program (a test
 * picture and a tone, two seconds): `front.mp4` with its index first, `back.mp4` with its index last, `frag.m4a` in fragments
 * as the site serves audio. Each test checks the boxes written and that every track's first sample is still where its table
 * says.
 */
class Mp4EmbedTest {
    private val dir = createTempDirectory("embed").toFile()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val details = EmbedDetails(
        title = "Night drive",
        channel = "Some channel",
        description = "A long drive.\nTwo lines.",
        date = "2024-05-01",
        url = "https://example.com/watch?v=fakevideo01",
        chapters = listOf(Chapter("Intro", 0, null), Chapter("The road ünd more", 1_000, null)),
        subtitles = EmbedSubtitles(listOf(VttCue(200, 700, "Hello"), VttCue(900, 1_500, "Two\nlines")), "en"),
        cover = jpeg,
    )

    private fun fixture(name: String): File = File(dir, name).apply { writeBytes(Mp4EmbedTest::class.java.getResource("/video/embed/$name")!!.readBytes()) }

    @Test fun theIndexFirstFileGetsTagsChaptersSubtitlesAndCoverAndItsSamplesStayPut() = check("front.mp4", subtitles = true)

    @Test fun theIndexLastFileToo() = check("back.mp4", subtitles = true)

    @Test fun aFragmentedFileGetsTheTagsButNoSubtitleTrack() = check("frag.m4a", subtitles = false)

    @Test fun somethingThatIsNoMp4IsLeftAlone() {
        val input = File(dir, "x.webm").apply { writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0)) }
        val output = File(dir, "out.webm")
        assertFalse(Mp4Embed.isMp4(input))
        assertFalse(Mp4Embed.embed(input, output, details))
        assertFalse(output.exists())
    }

    @Test fun subtitleSamplesFillTheGapsAndStopAtTheEnd() {
        val samples = Mp4Embed.subtitleSamples(listOf(VttCue(200, 700, "a"), VttCue(600, 900, "b"), VttCue(1_800, 2_500, "c")), 2_000)
        assertEquals(listOf(200L, 500L, 200L, 900L, 200L), samples.map { it.durationMs })
        assertContentEquals(byteArrayOf(0, 1, 'a'.code.toByte()), samples[1].bytes)
        assertContentEquals(byteArrayOf(0, 0), samples[0].bytes)
    }

    @Test fun vttCuesKeepTheirEndsAndLines() {
        val cues = VttCues.parse(
            "WEBVTT\n\n00:00:01.500 --> 00:00:03.000 align:start\n<c>Hi</c> &amp; bye\nline two\n\n" +
                "00:00.200 --> 00:00.900\nfirst\n\n00:00:05.000 --> 00:00:04.000\nbackwards\n",
        )
        assertEquals(listOf(VttCue(200, 900, "first"), VttCue(1_500, 3_000, "Hi & bye\nline two")), cues)
    }

    @Test fun theCoverIsTheThumbnailThenTheSitesJpeg() {
        val item = com.cosmotune.video.model.VideoItem("abcdefghijk", "t", null, null, null, "https://example.com/x.webp", null, null, null, null)
        val candidates = CoverFetcher.candidates(item)
        assertEquals("https://example.com/x.webp", candidates[0])
        assertTrue(candidates[1].endsWith("/vi/abcdefghijk/hqdefault.jpg"), "then the site's own JPEG")
        assertTrue(CoverFetcher.candidates(item.copy(id = "short", thumbnailUrl = null)).isEmpty(), "no JPEG for an id that is no video id")
        assertTrue(CoverFetcher.isPicture(jpeg))
        assertFalse(CoverFetcher.isPicture("RIFF....WEBP".toByteArray()))
    }

    @Test fun languagesArePackedAsThreeLetters() {
        fun unpack(v: Int) = String(charArrayOf(Char(((v shr 10) and 31) + 0x60), Char(((v shr 5) and 31) + 0x60), Char((v and 31) + 0x60)))
        assertEquals("eng", unpack(Mp4Embed.packedLanguage("en")))
        assertEquals("tam", unpack(Mp4Embed.packedLanguage("ta")))
        assertEquals("und", unpack(Mp4Embed.packedLanguage("")))
    }

    private fun check(name: String, subtitles: Boolean) {
        val input = fixture(name)
        val output = File(dir, "out-$name")
        assertTrue(Mp4Embed.isMp4(input))
        assertTrue(Mp4Embed.embed(input, output, details))
        val before = read(input)
        val after = read(output)
        assertEquals(before.tracks.size + if (subtitles) 1 else 0, after.tracks.size)
        // every old track's first sample holds the same bytes as before
        for ((i, track) in before.tracks.withIndex()) {
            val first = track.firstOffset ?: continue
            assertContentEquals(bytesAt(input, first, 16), bytesAt(output, after.tracks[i].firstOffset!!, 16), "track $i")
        }
        val text = String(after.moov, Charsets.ISO_8859_1)
        for (word in listOf("udta", "meta", "ilst", "©nam", "Night drive", "Some channel", "2024-05-01", "covr", "chpl", "Intro")) {
            assertTrue(word in text, "has $word")
        }
        if (subtitles) {
            assertTrue("tx3g" in text && "sbtl" in text)
            val sub = after.tracks.last()
            assertContentEquals(byteArrayOf(0, 0), bytesAt(output, sub.firstOffset!!, 2), "the first sample is the gap before Hello")
            assertContentEquals(byteArrayOf(0, 5) + "Hello".toByteArray(), bytesAt(output, sub.firstOffset + 2, 7))
        } else {
            assertFalse("tx3g" in text)
        }
        // a second pass replaces the tags instead of adding more
        val again = File(dir, "again-$name")
        assertTrue(Mp4Embed.embed(output, again, details))
        assertEquals(1, Regex("ilst").findAll(String(read(again).moov, Charsets.ISO_8859_1)).count())
    }

    private class Track(val firstOffset: Long?)

    private class Parsed(val moov: ByteArray, val tracks: List<Track>)

    private fun read(file: File): Parsed = RandomAccessFile(file, "r").use { raf ->
        val moovBox = Mp4Embed.top(raf).single { it.type == "moov" }
        val moov = ByteArray(moovBox.size.toInt()).also { raf.seek(moovBox.offset); raf.readFully(it) }
        val tracks = Mp4Embed.children(moov, 8, moov.size).filter { it.type == "trak" }.map { trak ->
            var box = trak
            for (type in listOf("mdia", "minf", "stbl")) box = Mp4Embed.children(moov, box.content.toInt(), box.end.toInt()).single { it.type == type }
            val stco = Mp4Embed.children(moov, box.content.toInt(), box.end.toInt()).firstOrNull { it.type == "stco" || it.type == "co64" }!!
            val count = u32(moov, stco.content.toInt() + 4)
            val first = if (count == 0L) null else if (stco.type == "co64") (u32(moov, stco.content.toInt() + 8) shl 32) or u32(moov, stco.content.toInt() + 12) else u32(moov, stco.content.toInt() + 8)
            Track(first)
        }
        Parsed(moov, tracks)
    }

    private fun bytesAt(file: File, at: Long, n: Int): ByteArray = RandomAccessFile(file, "r").use { raf -> ByteArray(n).also { raf.seek(at); raf.readFully(it) } }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
