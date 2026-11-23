package com.cosmotune.watch

/** Tells a font file from anything else by its first bytes. */
object SubtitleFonts {
    /** "ttf", "otf" or "ttc" for a TrueType, OpenType or collection file; null for anything else. */
    fun extensionOf(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val head = bytes.copyOfRange(0, 4)
        return when {
            head.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(head, Charsets.ISO_8859_1) == "true" -> "ttf"
            String(head, Charsets.ISO_8859_1) == "OTTO" -> "otf"
            String(head, Charsets.ISO_8859_1) == "ttcf" -> "ttc"
            else -> null
        }
    }
}
