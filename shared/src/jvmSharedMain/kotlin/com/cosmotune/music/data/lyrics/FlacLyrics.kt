package com.cosmotune.music.data.lyrics

/** Lyrics from the native FLAC Vorbis comments, in a bounded file prefix. */
object FlacLyrics {
    private val magic = "fLaC".toByteArray(Charsets.US_ASCII)
    private val timedTags = setOf("SYNCEDLYRICS", "SYNCED_LYRICS", "LYRICS_SYNCED")
    private val plainTags = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED_LYRICS")
    private val lineStamp = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")

    fun isFlac(bytes: ByteArray): Boolean = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    fun read(bytes: ByteArray): String? {
        if (!isFlac(bytes)) return null
        var pos = magic.size
        while (bytes.size - pos >= 4) {
            val flags = bytes[pos].toInt() and 0xff
            val length = ((bytes[pos + 1].toInt() and 0xff) shl 16) or
                ((bytes[pos + 2].toInt() and 0xff) shl 8) or (bytes[pos + 3].toInt() and 0xff)
            val start = pos + 4
            if (length > bytes.size - start) return null
            if (flags and 0x7f == 4) return comments(bytes, start, start + length)
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun comments(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun length(): Int? {
            if (end - pos < 4) return null
            val value = (bytes[pos].toInt() and 0xff) or ((bytes[pos + 1].toInt() and 0xff) shl 8) or
                ((bytes[pos + 2].toInt() and 0xff) shl 16) or ((bytes[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return value.takeIf { it >= 0 }
        }
        val vendor = length()?.takeIf { it <= end - pos } ?: return null
        pos += vendor
        val count = length() ?: return null
        var timed: String? = null
        var taggedTimed: String? = null
        var standard: String? = null
        var plain: String? = null
        repeat(count.coerceAtMost(4096)) {
            val size = length()?.takeIf { it <= end - pos } ?: return taggedTimed ?: timed ?: standard ?: plain
            val entry = String(bytes, pos, size, Charsets.UTF_8)
            pos += size
            val field = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "").takeIf(String::isNotBlank) ?: return@repeat
            when {
                field in WORD_LYRICS_FIELDS -> return value
                field in timedTags && taggedTimed == null && isTimed(value) -> taggedTimed = value
                field in plainTags -> {
                    if (timed == null && isTimed(value)) timed = value
                    if (field == "LYRICS" && standard == null) standard = value
                    if (plain == null) plain = value
                }
            }
        }
        return taggedTimed ?: timed ?: standard ?: plain
    }

    private fun isTimed(text: String) = lineStamp.containsMatchIn(text) || KaraokeLrc.looksLike(text)
}
