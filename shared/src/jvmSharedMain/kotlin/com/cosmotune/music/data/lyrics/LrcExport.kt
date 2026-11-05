package com.cosmotune.music.data.lyrics

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis

/**
 * A song's lyrics as a standard `.lrc` file the listener can keep: `[ti:]`, `[ar:]`, `[al:]` and
 * `[length:]` headers, one `[mm:ss.xx]` stamp per line and, where the provider timed words, the
 * enhanced `<mm:ss.xx>` word stamps most players read. Background vocals become their own timed
 * lines in parentheses. Untimed lyrics are written as plain lines.
 */
object LrcExport {
    fun render(song: Song, lines: List<LyricLine>, subLines: List<LyricLine>? = null): String {
        require(lines.any { it.text.isNotBlank() }) { "There are no lyrics to save." }
        val timed = lines.any { it.timeMs > 0 }
        val out = StringBuilder()
        fun header(tag: String, value: String?) { value?.replace(Regex("[\\[\\]\\r\\n]"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("[$tag:$it]\n") } }
        header("ti", song.title)
        header("ar", song.artist)
        header("al", song.albumName)
        song.durationMillis().takeIf { it > 0 }?.let { header("length", stamp(it).substringBefore('.')) }
        header("by", "Cosmotune")
        out.append('\n')
        // Portable bilingual LRC uses the original timestamp again for its translation. Only
        // index-aligned, equally timed sublines can be paired; stale/partial translations are omitted.
        val aligned = subLines?.takeIf { it.size == lines.size }
        fun translation(original: LyricLine, sub: LyricLine?): LyricLine? = sub?.takeIf {
            original.text.isNotBlank() && it.text.isNotBlank() && it.text.trim() != original.text.trim() &&
                it.timeMs == original.timeMs
        }?.copy(timeMs = original.timeMs, words = emptyList(), background = null, timingSource = null)
        val entries = lines.flatMapIndexed { index, line ->
            val background = line.background?.let { bg ->
                bg.copy(text = bg.text.trim().let { if (it.startsWith("(")) it else "($it)" }, words = emptyList())
            }
            listOfNotNull(line, translation(line, aligned?.get(index)), background,
                line.background?.let { translation(it, aligned?.get(index)?.background) })
        }.let { all -> if (timed) all.sortedBy { it.timeMs } else all }
        entries.forEach { line ->
            val text = line.text.replace('\n', ' ').replace('\r', ' ')
            if (!timed) { if (text.isNotBlank()) out.append(text).append('\n'); return@forEach }
            out.append('[').append(stamp(line.timeMs)).append(']')
            if (line.words.isNotEmpty() && line.words.joinToString("") { it.text }.isNotBlank()) {
                // Word stamps with the text between them, so a player without word support still shows the line.
                var at = 0
                line.words.forEachIndexed { index, word ->
                    val span = line.wordSpans.getOrNull(index)
                    if (span != null && span.first >= at && span.first <= text.length) { out.append(text, at, span.first); at = span.first }
                    out.append('<').append(stamp(word.startMs)).append('>').append(word.text)
                    at = (span?.last?.plus(1) ?: at).coerceAtMost(text.length)
                }
                if (at < text.length) out.append(text, at, text.length)
                line.words.last().endMs.takeIf { it > line.words.last().startMs }?.let { out.append('<').append(stamp(it)).append('>') }
            } else out.append(text)
            out.append('\n')
        }
        return out.toString()
    }

    /** A file name for the export: "Artist - Title.lrc", without characters the common file systems refuse. */
    fun fileName(song: Song): String =
        "${song.artist} - ${song.title}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(150).ifBlank { "lyrics" } + ".lrc"

    internal fun stamp(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 10
        return "%02d:%02d.%02d".format(java.util.Locale.ROOT, total / 6000, (total / 100) % 60, total % 100)
    }
}
