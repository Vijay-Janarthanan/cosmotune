package com.cosmotune.manga.data

import java.io.File

/** Tells a moving page (animated GIF, WebP or PNG, as Kotatsu plays them) from a still one by its bytes, whatever its name. */
object MangaAnimatedImage {
    private const val SCAN = 4 * 1024 * 1024

    fun isAnimated(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(minOf(SCAN.toLong(), file.length()).toInt())
            var filled = 0
            while (filled < buffer.size) { val read = input.read(buffer, filled, buffer.size - filled); if (read < 0) break; filled += read }
            isAnimated(if (filled == buffer.size) buffer else buffer.copyOf(filled))
        }
    }.getOrDefault(false)

    fun isAnimated(bytes: ByteArray): Boolean = when {
        bytes.startsWith("GIF8") -> gifFrames(bytes) >= 2
        bytes.startsWith("RIFF") && bytes.text(8, 4) == "WEBP" -> bytes.text(12, 4) == "VP8X" && bytes.size > 20 && (bytes[20].toInt() and 0x02) != 0
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes.text(1, 3) == "PNG" -> {
            val text = String(bytes, Charsets.ISO_8859_1)
            val control = text.indexOf("acTL"); val image = text.indexOf("IDAT")
            control >= 0 && (image < 0 || control < image)
        }
        else -> false
    }

    /** Graphic control blocks (one before each frame of an animated GIF), counted up to two. */
    private fun gifFrames(bytes: ByteArray): Int {
        var count = 0
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 0x04.toByte() && ++count >= 2) return count
        }
        return count
    }

    private fun ByteArray.startsWith(prefix: String) = size >= prefix.length && text(0, prefix.length) == prefix
    private fun ByteArray.text(from: Int, length: Int) = if (size < from + length) "" else String(this, from, length, Charsets.ISO_8859_1)
}
