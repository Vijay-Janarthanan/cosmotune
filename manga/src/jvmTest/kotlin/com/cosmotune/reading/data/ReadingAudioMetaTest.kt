package com.cosmotune.reading.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReadingAudioMetaTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).apply { writeInt(body.size + 8); writeBytes(type) }; out.write(body) }.toByteArray()
    }
    private fun bytes(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private fun mvhd(scale: Int, duration: Int) = box("mvhd", bytes { writeInt(0); writeInt(0); writeInt(0); writeInt(scale); writeInt(duration); write(ByteArray(80)) })
    private fun item(type: String, value: ByteArray, kind: Int = 1) = box(type, box("data", bytes { writeInt(kind); writeInt(0) }, value))

    @Test fun anM4bGivesItsTitleAuthorLengthCoverAndNeroChapters() {
        val chpl = box("chpl", bytes {
            writeByte(1); write(ByteArray(3)); writeInt(0); writeByte(3)
            listOf(0L to "Opening", 600_000_000L to "The road", 18_000_000_000L to "Home").forEach { (start, title) ->
                writeLong(start); writeByte(title.toByteArray().size); write(title.toByteArray())
            }
        })
        val picture = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val meta = box("meta", ByteArray(4), box("ilst", item("©nam", "The Long Way".toByteArray()), item("©ART", "A. Reader".toByteArray()),
            item("covr", picture, kind = 13)))
        val moov = box("moov", mvhd(1000, 3_723_000), box("udta", chpl, meta))
        // The audio first, as most files have it, so moov is found after skipping it.
        val file = box("ftyp", "M4B ".toByteArray()) + box("mdat", ByteArray(50_000)) + moov
        val info = ReadingAudioMeta.read("book.m4b", file.size.toLong()) { file.inputStream() }
        assertEquals("The Long Way", info.title)
        assertEquals("A. Reader", info.author)
        assertEquals(3_723_000L, info.durationMs)
        assertEquals(listOf(ReadingAudioMeta.Chapter("Opening", 0), ReadingAudioMeta.Chapter("The road", 60_000), ReadingAudioMeta.Chapter("Home", 1_800_000)), info.chapters)
        assertContentEquals(picture, info.cover)
    }

    @Test fun anMp3GivesItsTagsAndLengthFromTheXingFrameOrItsBitRate() {
        fun frame(id: String, text: String) = bytes { writeBytes(id); writeInt(text.length + 1); writeShort(0); writeByte(3); write(text.toByteArray()) }
        val frames = frame("TIT2", "Chapter One") + frame("TPE1", "Jane Narrator")
        val tag = bytes { writeBytes("ID3"); writeByte(3); writeByte(0); writeByte(0)
            val n = frames.size; writeByte((n shr 21) and 0x7F); writeByte((n shr 14) and 0x7F); writeByte((n shr 7) and 0x7F); writeByte(n and 0x7F) } + frames
        // MPEG-1 layer III, 128 kbit/s, 44.1 kHz, stereo; a Xing header saying 3 445 frames (about 80 seconds).
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val xing = ByteArray(32) + bytes { writeBytes("Xing"); writeInt(1); writeInt(3445) }
        val withXing = tag + header + xing + ByteArray(2_000)
        val info = ReadingAudioMeta.read("01.mp3", withXing.size.toLong()) { withXing.inputStream() }
        assertEquals("Chapter One", info.title)
        assertEquals("Jane Narrator", info.author)
        assertEquals(3445L * 1152 * 1000 / 44100, info.durationMs)
        // Without one: 160 000 bytes of audio at 128 kbit/s is 10 seconds.
        val plain = header + ByteArray(160_000 - 4)
        assertEquals(10_000L, ReadingAudioMeta.read("02.mp3", plain.size.toLong()) { plain.inputStream() }.durationMs)
    }

    @Test fun somethingElseGivesNothingRatherThanFailing() {
        assertEquals(ReadingAudioMeta.Info(), ReadingAudioMeta.read("noise.m4b", 10) { ByteArray(10).inputStream() })
        assertEquals(ReadingAudioMeta.Info(), ReadingAudioMeta.read("song.flac", 10) { ByteArray(10).inputStream() })
    }
}
