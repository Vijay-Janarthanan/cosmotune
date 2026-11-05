package com.cosmotune.music.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uncompressed local DSD, framed according to DoP Open Standard 1.1. No PCM decoding. */
internal class DesktopDopSource private constructor(private val file: RandomAccessFile, val info: Info) : DesktopSampleSource {
    data class Info(val rate: Int, val channels: Int, val bits: Long, val dataAt: Long, val dataBytes: Long,
        val planarBlock: Int = 0, val lsbFirst: Boolean = false) {
        val carrierRate get() = rate / 16
        val durationUs get() = bits * 1_000_000L / rate
        val frames get() = (bits + 15) / 16
    }
    val format = DesktopPcmFormat(info.carrierRate, info.channels, 3)
    val measured = DesktopStreamFormat("dsd", info.rate * info.channels / 1000, info.rate, 1, info.channels)
    private var frame = 0L
    private val block = FloatArray(4096 * info.channels)
    private val cache = ByteArray(if (info.planarBlock > 0) info.planarBlock * info.channels else 8192 * info.channels)
    private var cacheStart = -1L
    private var cacheSize = 0
    override var sampleCount = 0
        private set

    override fun readSamples(): FloatArray? {
        if (frame >= info.frames) { sampleCount = 0; return null }
        val count = minOf(4096L, info.frames - frame).toInt()
        for (i in 0 until count) for (ch in 0 until info.channels) {
            val at = (frame + i) * 2
            val first = byte(ch, at)
            val second = byte(ch, at + 1)
            block[i * info.channels + ch] = sample(first, second, (frame + i) and 1L == 0L)
        }
        frame += count
        sampleCount = count * info.channels
        return block
    }

    private fun byte(channel: Int, at: Long): Int {
        if (at * 8 >= info.bits) return 0x69
        val position = if (info.planarBlock > 0) {
            at / info.planarBlock * info.planarBlock * info.channels + channel * info.planarBlock + at % info.planarBlock
        } else at * info.channels + channel
        val group = position / cache.size * cache.size
        if (group != cacheStart) {
            cacheStart = group
            cacheSize = minOf(cache.size.toLong(), info.dataBytes - group).toInt()
            require(cacheSize > 0) { "Truncated DSD data" }
            file.seek(info.dataAt + group); file.readFully(cache, 0, cacheSize)
        }
        val index = (position - group).toInt()
        require(index in 0 until cacheSize) { "Truncated DSD channel" }
        var result = cache[index].toInt() and 255
        if (info.lsbFirst) result = Integer.reverse(result) ushr 24
        val valid = (info.bits - at * 8).coerceAtMost(8).toInt()
        if (valid < 8) {
            val mask = 255 shl (8 - valid) and 255
            result = result and mask or (0x69 and mask.inv())
        }
        return result
    }
    override fun seek(micros: Long): Boolean {
        frame = if (micros >= info.durationUs) info.frames else (micros.coerceAtLeast(0) * info.carrierRate / 1_000_000L).coerceAtMost(info.frames)
        return true
    }
    override fun close() = file.close()

    companion object {
        private val rates = setOf(2_822_400, 5_644_800, 11_289_600, 22_579_200, 45_158_400)
        /** The oldest DSD bit is bit 15; float32 exactly carries all signed 24-bit integers. */
        internal fun sample(firstMsbByte: Int, secondMsbByte: Int, firstMarker: Boolean): Float {
            val packed = ((if (firstMarker) 0x05 else 0xfa) shl 16) or ((firstMsbByte and 255) shl 8) or (secondMsbByte and 255)
            return ((packed shl 8) shr 8) / 8_388_608f
        }
        fun isCandidate(path: String): Boolean = File(path).let { it.isFile && it.extension.lowercase() in setOf("dsf", "dff") }
        fun open(path: String): Result<DesktopDopSource> = runCatching {
            val input = RandomAccessFile(path, "r")
            try {
                val length = input.length()
                require(length >= 28) { "Incomplete DSD file" }
                fun id() = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                fun number(little: Boolean): Long = input.readLong().let { if (little) java.lang.Long.reverseBytes(it) else it }
                fun payload(size: Long): ByteArray {
                    require(size in 0..65_536 && size <= length - input.filePointer) { "Invalid DSD metadata size" }
                    return ByteArray(size.toInt()).also(input::readFully)
                }
                val info = when (id()) {
                    "DSD " -> {
                        require(number(true) == 28L && number(true) == length) { "Invalid DSF file length" }
                        val metadata = number(true)
                        require(metadata == 0L || metadata in 28 until length) { "Invalid DSF metadata pointer" }
                        require(id() == "fmt ") { "DSF format is missing" }
                        val fmtSize = number(true)
                        require(fmtSize == 52L) { "Unsupported DSF format size" }
                        val fmt = ByteBuffer.wrap(payload(fmtSize - 12)).order(ByteOrder.LITTLE_ENDIAN)
                        require(fmt.int == 1 && fmt.int == 0) { "Unsupported DSF encoding" }
                        val layout = fmt.int; val channels = fmt.int; val rate = fmt.int; val order = fmt.int
                        val bits = fmt.long; val block = fmt.int; val reserved = fmt.int
                        require(layout == channels && channels in 1..2 && rate in rates && order in setOf(1, 8) &&
                            bits in 1..rate.toLong() * 86_400 && block == 4096 && reserved == 0) { "Unsupported DSF layout" }
                        require(id() == "data") { "DSF audio data is missing" }
                        val size = number(true) - 12
                        val expected = (((bits + 7) / 8 + block - 1) / block) * block * channels
                        require(size == expected && size <= length - input.filePointer) { "DSF audio length does not match its sample count" }
                        Info(rate, channels, bits, input.filePointer, size, block, order == 1)
                    }
                    "FRM8" -> {
                        require(number(false) == length - 12 && id() == "DSD ") { "Invalid DFF form" }
                        var rate = 0; var channels = 0; var compression = ""; var dataAt = 0L; var dataBytes = 0L; var chunks = 0
                        while (input.filePointer < length) {
                            require(++chunks <= 4096 && length - input.filePointer >= 12) { "Invalid DFF chunks" }
                            val kind = id(); val size = number(false); val start = input.filePointer
                            require(size >= 0 && size <= length - start && (size and 1L) <= length - start - size) { "Truncated DFF chunk" }
                            when (kind) {
                                "PROP" -> {
                                    val b = ByteBuffer.wrap(payload(size)).order(ByteOrder.BIG_ENDIAN)
                                    fun tag() = ByteArray(4).also(b::get).toString(Charsets.US_ASCII)
                                    require(b.remaining() >= 4 && tag() == "SND ")
                                    var fields = 0
                                    while (b.hasRemaining()) {
                                        require(++fields <= 128 && b.remaining() >= 12)
                                        val field = tag(); val bytes = b.long
                                        require(bytes >= 0 && bytes <= b.remaining() && bytes + (bytes and 1) <= b.remaining())
                                        val end = b.position() + bytes.toInt()
                                        when (field) {
                                            "FS  " -> { require(rate == 0 && bytes == 4L); rate = b.int }
                                            "CHNL" -> {
                                                require(channels == 0 && bytes >= 2)
                                                channels = b.short.toInt() and 65535
                                                require(channels in 1..2 && bytes == 2L + channels * 4)
                                                if (channels == 2) require(tag() == "SLFT" && tag() == "SRGT") else tag()
                                            }
                                            "CMPR" -> { require(compression.isEmpty() && bytes >= 5); compression = tag() }
                                        }
                                        b.position(end + (bytes and 1).toInt())
                                    }
                                }
                                "DSD " -> { require(dataAt == 0L); dataAt = start; dataBytes = size }
                                "DST " -> error("Compressed DST uses PCM conversion; DoP requires uncompressed DSD")
                            }
                            input.seek(start + size + (size and 1))
                        }
                        require(rate in rates && channels in 1..2 && compression == "DSD " && dataAt > 0 && dataBytes > 0 && dataBytes % channels == 0L) { "Incomplete DFF audio properties" }
                        val bits = dataBytes / channels * 8
                        require(bits in 1..rate.toLong() * 86_400)
                        Info(rate, channels, bits, dataAt, dataBytes)
                    }
                    else -> error("Not a DSF or DFF recording")
                }
                DesktopDopSource(input, info)
            } catch (failure: Throwable) { input.close(); throw failure }
        }
    }
}
