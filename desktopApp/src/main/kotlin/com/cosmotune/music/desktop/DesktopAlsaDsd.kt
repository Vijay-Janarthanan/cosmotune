package com.cosmotune.music.desktop

internal enum class AlsaDsdWord(val bytes: Int, val littleEndian: Boolean) {
    DSD_U32_BE(4, false), DSD_U32_LE(4, true), DSD_U16_BE(2, false), DSD_U16_LE(2, true);

    val carrierFramesPerWord get() = bytes / 2

    fun pack(carrier: FloatArray, count: Int, channels: Int): ByteArray {
        require(channels in 1..2 && count in 0..carrier.size && count % channels == 0)
        val frames = count / channels
        val words = (frames + carrierFramesPerWord - 1) / carrierFramesPerWord
        val output = ByteArray(words * channels * bytes)
        for (word in 0 until words) for (channel in 0 until channels) {
            val at = (word * channels + channel) * bytes
            for (part in 0 until carrierFramesPerWord) {
                val frame = word * carrierFramesPerWord + part
                val bits = if (frame < frames) (carrier[frame * channels + channel] * 8_388_608f).toInt() else 0x6969
                val offset = part * 2
                val high = if (littleEndian) bytes - 1 - offset else offset
                val low = if (littleEndian) bytes - 2 - offset else offset + 1
                output[at + high] = (bits shr 8).toByte()
                output[at + low] = bits.toByte()
            }
        }
        return output
    }
}
