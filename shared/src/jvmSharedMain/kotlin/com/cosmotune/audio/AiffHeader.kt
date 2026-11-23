package com.cosmotune.audio

/**
 * What an AIFF or AIFF-C file's COMM chunk says, written from Apple's AIFF 1.3 and AIFF-C specifications: the channels, frames,
 * sample size and rate, and how the samples are stored. Only uncompressed audio is described: big-endian PCM ("NONE", and plain
 * AIFF), little-endian PCM ("sowt") and 32-bit float ("fl32"); anything else is refused.
 */
data class AiffFormat(val channels: Int, val frames: Long, val bits: Int, val sampleRate: Int, val littleEndian: Boolean, val float: Boolean) {
    val bytesPerSample: Int get() = (bits + 7) / 8
    val blockAlign: Int get() = bytesPerSample * channels
    val durationUs: Long get() = if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L

    companion object {
        /** The form type after "FORM" and its size: "AIFF" or "AIFC", else null. */
        fun formType(head: ByteArray): String? {
            if (head.size < 12 || String(head, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
            return String(head, 8, 4, Charsets.ISO_8859_1).takeIf { it == "AIFF" || it == "AIFC" }
        }

        /** The format in a COMM chunk's [body] for a file of [form] type; null for what cannot be played as PCM. */
        fun parseComm(body: ByteArray, form: String): AiffFormat? {
            if (body.size < 18) return null
            fun u16(at: Int) = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
            fun u32(at: Int) = ((u16(at).toLong()) shl 16) or u16(at + 2).toLong()
            val channels = u16(0)
            val frames = u32(2)
            val bits = u16(6)
            val rate = extended80(body, 8)
            val compression = if (form == "AIFC" && body.size >= 22) String(body, 18, 4, Charsets.ISO_8859_1) else "NONE"
            val (little, float) = when (compression) {
                "NONE", "twos" -> false to false
                "sowt" -> true to false
                "fl32", "FL32" -> false to true
                else -> return null
            }
            if (channels !in 1..8 || rate !in 1_000..768_000) return null
            if (float && bits != 32) return null
            if (!float && bits !in setOf(8, 16, 24, 32)) return null
            return AiffFormat(channels, frames, bits, rate, little, float)
        }

        /** The 80-bit IEEE extended float at [at] (the sample rate), rounded to whole hertz. */
        fun extended80(bytes: ByteArray, at: Int): Int {
            val exponent = (((bytes[at].toInt() and 0x7F) shl 8) or (bytes[at + 1].toInt() and 0xFF)) - 16383
            var mantissa = 0L
            for (i in 0 until 8) mantissa = (mantissa shl 8) or (bytes[at + 2 + i].toLong() and 0xFF)
            if (mantissa == 0L) return 0
            val shift = 63 - exponent
            if (shift !in 1..63) return 0
            val whole = mantissa ushr shift
            val half = (mantissa ushr (shift - 1)) and 1L
            return (whole + half).toInt()
        }

        /**
         * Turns one block of [format]'s samples in [data] (from [offset], [length] bytes) into what the player takes: little-endian
         * signed PCM (8-bit samples become unsigned, as WAV's are) or little-endian float, in place.
         */
        fun toPlayerOrder(format: AiffFormat, data: ByteArray, offset: Int, length: Int) {
            val size = format.bytesPerSample
            if (size == 1) {
                for (i in offset until offset + length) data[i] = (data[i].toInt() xor 0x80).toByte()
                return
            }
            if (format.littleEndian) return
            var i = offset
            while (i + size <= offset + length) {
                var a = i
                var b = i + size - 1
                while (a < b) { val t = data[a]; data[a] = data[b]; data[b] = t; a++; b-- }
                i += size
            }
        }
    }
}
