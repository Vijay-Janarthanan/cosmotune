package com.cosmotune.canvas

import com.opencanvas.core.sync.Mp4FrameSizes
import com.opencanvas.core.sync.RangeReader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The progressive MP4 YouTube served for "Chinna Thamarai" had a sample table of 0, 65536, 100663296,
 * 131072 and so on (FFmpeg refuses the same file: "Sample size 2114701148 is too large"). Read as frame
 * sizes they made the aligner report a silent song; the file has to count as unreadable instead, so
 * that the same pair can be measured through the other client.
 */
class Mp4JunkTableTest {

    private fun u32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun u32(value: Int) = u32(value.toLong())
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        return u32(8 + payload.size) + type.encodeToByteArray() + payload
    }

    private fun trak(id: Int, handler: String, timescale: Int, sizes: IntArray, delta: Int): ByteArray {
        val tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(id), ByteArray(68))
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(timescale), u32(0), ByteArray(4))
        val hdlr = box("hdlr", u32(0), u32(0), handler.encodeToByteArray(), ByteArray(13))
        val stts = box("stts", u32(0), u32(1), u32(sizes.size), u32(delta))
        val stsz = box("stsz", u32(0), u32(0), u32(sizes.size), sizes.fold(ByteArray(0)) { acc, size -> acc + u32(size) })
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", box("stbl", stts, stsz))))
    }

    private fun file(audio: IntArray): ByteArray =
        box("ftyp", "isom".encodeToByteArray(), u32(0), "isomiso2".encodeToByteArray()) +
            box("moov", trak(1, "vide", 90_000, IntArray(300) { 7_000 + it }, 3_000), trak(2, "soun", 44_100, audio, 1_024)) +
            box("mdat", ByteArray(2048))

    private class Memory(private val data: ByteArray) : RangeReader {
        override val length: Long get() = data.size.toLong()
        override suspend fun read(from: Long, count: Int): ByteArray =
            if (from >= data.size) ByteArray(0) else data.copyOfRange(from.toInt(), minOf(data.size.toLong(), from + count).toInt())
    }

    @Test fun anOrdinaryTableIsReadAsSizes() = runBlocking {
        val real = IntArray(600) { 300 + (it * 37) % 211 }
        assertContentEquals(real, assertNotNull(Mp4FrameSizes.read(Memory(file(real)))).sizes)
    }

    @Test fun aTableOfOtherNumbersIsNotReadAsSizes() = runBlocking {
        val junk = IntArray(600) { listOf(0, 65_536, 100_663_296, 131_072)[it % 4] }
        assertNull(Mp4FrameSizes.read(Memory(file(junk))))
    }

    @Test fun aTableWithNegativeOrHugeEntriesIsNotReadAsSizes() = runBlocking {
        assertNull(Mp4FrameSizes.read(Memory(file(IntArray(600) { if (it == 300) -2_137_391_054 else 350 }))))
        assertNull(Mp4FrameSizes.read(Memory(file(IntArray(600) { if (it == 7) 5_124_558 else 350 }))))
    }

    @Test fun veryQuietAudioWithTinyFramesIsStillRead() = runBlocking {
        // digital silence codes to a handful of bytes a frame (a stray empty one is fine); that is still a table of sizes
        val quiet = IntArray(600) { if (it % 300 == 0) 0 else 6 + it % 3 }
        assertNotNull(Mp4FrameSizes.read(Memory(file(quiet))))
        Unit
    }
}
