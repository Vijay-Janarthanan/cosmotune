package com.cosmotune.music

import com.cosmotune.music.data.lyrics.EmbeddedLyrics
import com.cosmotune.music.data.lyrics.LEGACY_WORD_LYRICS_FIELD
import com.cosmotune.music.data.lyrics.WORD_LYRICS_FIELD
import com.cosmotune.music.data.lyrics.LrcLib
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import com.cosmotune.music.data.lyrics.toEnhancedLrc
import com.cosmotune.music.data.lyrics.toLrc
import com.cosmotune.music.download.FlacTagger
import com.cosmotune.music.download.Mp4Tagger
import com.cosmotune.music.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The reader and the three taggers have to agree, and nothing else checks that
 * they do: a download writes the file on a device and the player reads it back
 * on another run, so a disagreement between the two shows up as a downloaded
 * song that silently has no lyrics — which is exactly the bug this pair was
 * written for.
 *
 * Each test writes with the real tagger and reads with the real reader, so a
 * change to either side that breaks the pairing fails here rather than on a
 * phone. Placeholder text throughout; no real lyric appears in this file.
 */
class EmbeddedLyricsTest {

    private val lrc = "[00:01.00]first line\n[00:05.00]second line"

    @Test
    fun `an m4a written by the tagger reads back`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a flac written by the tagger reads back`() {
        val tagged = FlacTagger.tag(
            bytes = minimalFlac(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a webm written by the tagger reads back`() {
        val tagged = WebmTagger.tag(
            bytes = minimalWebm(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    /**
     * A cover is a `data` box too, and it sits in the same `ilst` as the lyrics.
     * Reading the first one that turns up rather than the lyrics' own would
     * hand a JPEG back as a string.
     */
    @Test
    fun `a cover alongside the lyrics is not mistaken for them`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = ByteArray(64) { 0x7F },
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    /**
     * The whole point of the second field: a word-synced download has to come
     * back word-synced, or a downloaded song silently drops to whole-line
     * highlighting while a streamed one keeps its syllables.
     */
    @Test
    fun `word timings survive the write and the read, in all three containers`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(startMs = 1_000L, endMs = 1_400L, text = "two"),
                    LyricWord(startMs = 1_400L, endMs = 2_000L, text = "words"),
                ),
            ),
        )
        val plain = words.toLrc()
        val enhanced = words.toEnhancedLrc()

        val written = listOf(
            Mp4Tagger.tag(minimalMp4(), "t", "a", null, plain, null, false, enhanced),
            FlacTagger.tag(minimalFlac(), "t", "a", null, plain, null, "image/jpeg", enhanced),
            WebmTagger.tag(minimalWebm(), "t", "a", null, plain, null, "image/jpeg", enhanced),
        )
        for (bytes in written) {
            // The word-timed field wins over the plain one sitting beside it.
            val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.fromBytes(bytes)))
            assertEquals(1, read.size)
            assertEquals("two words", read[0].text)
            assertEquals(listOf("two", "words"), read[0].words.map { it.text })
            assertEquals(listOf(1_000L, 1_400L), read[0].words.map { it.startMs })
            assertEquals(2_000L, read[0].endMs)
        }
    }

    /**
     * The standard field must stay plain whatever else is written beside it —
     * a reader without A2 shows `<00:01.00>` rather than skipping it, which is
     * the reason there are two fields at all.
     */
    @Test
    fun `the portable field never carries word stamps`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        assertEquals("[00:01.00]two words", words.toLrc())
    }

    @Test
    fun `a file with no lyrics at all reads back as nothing`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = null,
            cover = null,
            coverIsPng = false,
        )
        assertNull(EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `something that is none of the three containers is not guessed at`() {
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(512) { it.toByte() }))
    }

    // ---- Offline packages ---------------------------------------------------

    /**
     * The one download with no container to read: an HLS or DASH package is a
     * playlist over a directory of segments, so its lyrics are written beside
     * it. They were written and never read for as long as offline packages have
     * existed — a downloaded Tidal track showed no lyrics offline and went to
     * the network for them online, which looks like a lyrics bug rather than a
     * download one and so stayed hidden.
     */
    @Test
    fun `a package's lyrics come from the sidecar beside its playlist`() {
        val root = packageDir("lyrics.lrc" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path))
    }

    /** The A2 form is what the writers save, and it has to survive the trip whole. */
    @Test
    fun `word timings survive a sidecar too`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        val root = packageDir("lyrics.lrc" to words.toEnhancedLrc())
        val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path)))
        assertEquals(listOf("two", "words"), read.single().words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), read.single().words.map { it.startMs })
    }

    @Test
    fun `a package with no lyrics, and an ordinary file, are left to the container reader`() {
        // Saved without lyrics — the empty file the writer leaves behind must
        // not read as "there are lyrics, they are blank".
        assertNull(EmbeddedLyrics.sidecar(File(packageDir("lyrics.lrc" to "  \n"), "playlist.m3u8").path))
        assertNull(EmbeddedLyrics.sidecar(File(packageDir(), "playlist.m3u8").path))
        // A downloaded .flac has no sidecar and never should be given one.
        assertNull(EmbeddedLyrics.sidecar(File(packageDir("lyrics.lrc" to lrc), "song.flac").path))
        assertNull(EmbeddedLyrics.sidecar(null))
    }

    /**
     * Files saved before the rename carry the word-timed field under its old name
     * ([LEGACY_WORD_LYRICS_FIELD]); they must still light up word by word.
     */
    @Test
    fun `a flac saved before the rename keeps its word timings`() {
        val enhanced = "[00:01.00]<00:01.00>two <00:01.40>words<00:02.00>"
        val flac = flacWithComments("LYRICS=[00:01.00]two words", "$LEGACY_WORD_LYRICS_FIELD=$enhanced")
        assertEquals(enhanced, EmbeddedLyrics.fromBytes(flac))
    }

    @Test
    fun `a webm saved before the rename keeps its word timings`() {
        val enhanced = "[00:01.00]<00:01.00>two <00:01.40>words<00:02.00>"
        val written = WebmTagger.tag(minimalWebm(), "t", "a", null, "[00:01.00]two words", null, "image/jpeg", enhanced)
        // The same file as the tagger wrote it before the rename: the TagName element, its one-byte size and the name.
        val now = byteArrayOf((0x80 or WORD_LYRICS_FIELD.length).toByte()) + WORD_LYRICS_FIELD.toByteArray(Charsets.US_ASCII)
        val before = byteArrayOf((0x80 or LEGACY_WORD_LYRICS_FIELD.length).toByte()) + LEGACY_WORD_LYRICS_FIELD.toByteArray(Charsets.US_ASCII)
        val at = indexOf(written, now)
        require(at >= 0) { "the tagger wrote no ${WORD_LYRICS_FIELD} tag" }
        val legacy = written.copyOfRange(0, at) + before + written.copyOfRange(at + now.size, written.size)
        assertEquals(enhanced, EmbeddedLyrics.fromBytes(legacy))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1

    /** `fLaC` and one last-block VORBIS_COMMENT holding [comments] (lengths little-endian, as Vorbis has them). */
    private fun flacWithComments(vararg comments: String): ByteArray {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        val vendor = "test".toByteArray()
        var block = le(vendor.size) + vendor + le(comments.size)
        for (c in comments) c.toByteArray(Charsets.UTF_8).let { block += le(it.size) + it }
        return "fLaC".toByteArray() +
            byteArrayOf((0x80 or 4).toByte(), (block.size ushr 16).toByte(), (block.size ushr 8).toByte(), block.size.toByte()) +
            block + byteArrayOf(0xFF.toByte())
    }

    /** A package directory holding a playlist plus whatever [files] the case needs. */
    private fun packageDir(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("offline").toFile().also(File::deleteOnExit)
        File(root, "playlist.m3u8").writeText("#EXTM3U\n#EXT-X-ENDLIST\n")
        files.forEach { (name, text) -> File(root, name).writeText(text) }
        return root
    }

    // ---- Minimal containers, just enough shape for each tagger to accept ----

    /** `ftyp` then an empty `moov`, which is all [Mp4Tagger] looks for. */
    private fun minimalMp4(): ByteArray = box("ftyp", "isom".toByteArray()) + box("moov", ByteArray(0))

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    /** `fLaC`, a last-block STREAMINFO, then a byte standing in for the frames. */
    private fun minimalFlac(): ByteArray {
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() +
            byteArrayOf(0x80.toByte(), 0, 0, streamInfo.size.toByte()) + streamInfo +
            byteArrayOf(0xFF.toByte())
    }

    /** An EBML header and a Segment whose declared size covers the rest of the file. */
    private fun minimalWebm(): ByteArray {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) +
            byteArrayOf(0x84.toByte()) + ByteArray(4)
        val body = ByteArray(8)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67) +
            // An 8-byte length so there is room to grow it when tags are appended.
            byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, body.size.toByte()) + body
        return header + segment
    }
}
