package com.cosmotune.ui

import java.nio.ByteBuffer

/**
 * The code points a TrueType/OpenType font has glyphs for, read from its `cmap` table (the Unicode subtables, formats 4 and 12).
 * Read from the bytes, not through Skia or AWT, so the answer is the font file's own and the same on every machine.
 */
object FontCharacterMap {

    fun codePoints(font: ByteArray): Set<Int> {
        val b = ByteBuffer.wrap(font)
        val cmap = tableOffset(b, "cmap") ?: error("the font has no cmap table")
        val subtables = u16(b, cmap + 2)
        val found = HashSet<Int>()
        var read = false
        for (i in 0 until subtables) {
            val record = cmap + 4 + i * 8
            val platform = u16(b, record)
            val encoding = u16(b, record + 2)
            val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
            if (!unicode) continue
            val at = cmap + u32(b, record + 4).toInt()
            when (u16(b, at)) {
                4 -> { format4(b, at, found); read = true }
                12 -> { format12(b, at, found); read = true }
            }
        }
        check(read) { "the font has no Unicode cmap subtable of format 4 or 12" }
        return found
    }

    private fun tableOffset(b: ByteBuffer, tag: String): Int? {
        val tables = u16(b, 4)
        for (i in 0 until tables) {
            val record = 12 + i * 16
            val name = String(ByteArray(4) { b.get(record + it) }, Charsets.US_ASCII)
            if (name == tag) return u32(b, record + 8).toInt()
        }
        return null
    }

    private fun format4(b: ByteBuffer, at: Int, out: MutableSet<Int>) {
        val segments = u16(b, at + 6) / 2
        val ends = at + 14
        val starts = ends + segments * 2 + 2
        val deltas = starts + segments * 2
        val rangeOffsets = deltas + segments * 2
        for (s in 0 until segments) {
            val end = u16(b, ends + s * 2)
            val start = u16(b, starts + s * 2)
            val delta = u16(b, deltas + s * 2)
            val rangeOffsetAt = rangeOffsets + s * 2
            val rangeOffset = u16(b, rangeOffsetAt)
            for (c in start..end) {
                if (c == 0xFFFF) continue
                val glyph = if (rangeOffset == 0) {
                    (c + delta) and 0xFFFF
                } else {
                    val g = u16(b, rangeOffsetAt + rangeOffset + 2 * (c - start))
                    if (g == 0) 0 else (g + delta) and 0xFFFF
                }
                if (glyph != 0) out += c
            }
        }
    }

    private fun format12(b: ByteBuffer, at: Int, out: MutableSet<Int>) {
        val groups = u32(b, at + 12).toInt()
        for (g in 0 until groups) {
            val group = at + 16 + g * 12
            val start = u32(b, group).toInt()
            val end = u32(b, group + 4).toInt()
            val firstGlyph = u32(b, group + 8)
            for (c in start..end) if (firstGlyph + (c - start) != 0L) out += c
        }
    }

    private fun u16(b: ByteBuffer, at: Int): Int = b.getShort(at).toInt() and 0xFFFF
    private fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xFFFFFFFFL
}
