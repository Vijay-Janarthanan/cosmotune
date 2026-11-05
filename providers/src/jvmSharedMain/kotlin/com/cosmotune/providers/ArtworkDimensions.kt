package com.cosmotune.providers

/** Static cover formats only. Validate dimensions before a native image decoder allocates pixels. */
internal object ArtworkDimensions {
    fun read(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        fun u8(at: Int) = bytes[at].toInt() and 255
        fun be16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        fun le16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun le24(at: Int) = le16(at) or (u8(at + 2) shl 16)
        fun be32(at: Int) = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
        fun le32(at: Int) = (u8(at).toLong()) or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)
        fun tag(at: Int, value: String) = at + value.length <= bytes.size && value.indices.all { u8(at + it) == value[it].code }
        val result = when {
            bytes.size >= 24 && u8(0) == 137 && tag(1, "PNG\r\n\u001a\n") && tag(12, "IHDR") && be32(8) == 13L -> {
                // Reject animated PNGs; motion artwork uses a separate bounded video path.
                var at = 8
                var ended = false
                while (at + 12 <= bytes.size) {
                    val length = be32(at)
                    require(length <= bytes.size - at - 12)
                    require(!tag(at + 4, "acTL"))
                    at += length.toInt() + 12
                    if (tag(at - 8, "IEND")) { require(length == 0L); ended = true; break }
                }
                require(ended && at == bytes.size)
                require(be32(16) <= Int.MAX_VALUE && be32(20) <= Int.MAX_VALUE)
                be32(16).toInt() to be32(20).toInt()
            }
            bytes.size >= 25 && tag(0, "RIFF") && tag(8, "WEBP") -> {
                require(le32(4) + 8 == bytes.size.toLong())
                var at = 12
                var canvas: Pair<Int, Int>? = null
                var image: Pair<Int, Int>? = null
                while (at + 8 <= bytes.size) {
                    val length = le32(at + 4)
                    require(length <= bytes.size - at - 8)
                    val data = at + 8
                    when {
                        tag(at, "ANIM") || tag(at, "ANMF") -> error("Animated cover")
                        tag(at, "VP8X") -> {
                            require(at == 12 && length == 10L && u8(data) and 2 == 0)
                            canvas = (le24(data + 4) + 1) to (le24(data + 7) + 1)
                        }
                        tag(at, "VP8L") -> {
                            require(image == null && length >= 5 && u8(data) == 47 && u8(data + 4) shr 5 == 0)
                            val bits = le32(data + 1)
                            image = ((bits and 0x3fff).toInt() + 1) to (((bits shr 14) and 0x3fff).toInt() + 1)
                        }
                        tag(at, "VP8 ") -> {
                            require(image == null && length >= 10 && u8(data) and 1 == 0)
                            require(u8(data + 3) == 157 && u8(data + 4) == 1 && u8(data + 5) == 42)
                            image = (le16(data + 6) and 0x3fff) to (le16(data + 8) and 0x3fff)
                        }
                    }
                    at += 8 + length.toInt() + (length.toInt() and 1)
                }
                require(at == bytes.size && image != null && (canvas == null || canvas == image))
                requireNotNull(image)
            }
            bytes.size >= 4 && u8(0) == 255 && u8(1) == 216 -> {
                var at = 2
                var size: Pair<Int, Int>? = null
                for (segment in 0 until 10_000) {
                    if (size != null || at >= bytes.size) break
                    require(u8(at++) == 255)
                    while (at < bytes.size && u8(at) == 255) at++
                    val marker = u8(at++)
                    require(marker != 218 && marker != 217)
                    if (marker != 1 && marker !in 208..216) {
                        val length = be16(at)
                        require(length >= 2 && length <= bytes.size - at)
                        if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)) {
                            require(length >= 8)
                            size = be16(at + 5) to be16(at + 3)
                        }
                        at += length
                    }
                }
                requireNotNull(size)
            }
            else -> error("Unsupported cover format")
        }
        require(result.first in 1..8192 && result.second in 1..8192 && result.first.toLong() * result.second <= 40_000_000)
        result
    }.getOrNull()
}
