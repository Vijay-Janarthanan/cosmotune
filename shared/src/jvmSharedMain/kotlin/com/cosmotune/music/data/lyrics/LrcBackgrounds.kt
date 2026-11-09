package com.cosmotune.music.data.lyrics

import com.cosmotune.lyrics.lrcOffsetMs
import com.cosmotune.lyrics.withLrcOffset

private val backgroundRow = Regex("""^\[bg\s*:(.*)]$""", RegexOption.IGNORE_CASE)
private val backgroundStamp = Regex("""<(\d{1,3}:[0-5]\d(?:[.:]\d{1,3})?)>""")

/** Mornye can place backing vocals away from their leads in the file, so attach them by audio time. */
internal fun List<LyricLine>.withExplicitLrcBackgrounds(lrc: String): List<LyricLine> {
    if (isEmpty() || !lrc.contains("[bg", ignoreCase = true)) return this
    val leads = indices.filter { !this[it].isGap }
    if (leads.isEmpty()) return this
    val offset = lrcOffsetMs(lrc)
    var leadPosition = 0
    val backing = lrc.lineSequence().mapNotNull { row ->
        val body = backgroundRow.matchEntire(row.trim())?.groupValues?.get(1) ?: return@mapNotNull null
        val stamp = backgroundStamp.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        LrcLib.parseLrc("[$stamp]$body").firstOrNull { it.isWordSynced }?.withLrcOffset(offset)
    }.sortedBy { it.timeMs }.groupBy { vocal ->
        while (leadPosition + 1 < leads.size && this[leads[leadPosition + 1]].timeMs - 20L <= vocal.timeMs) leadPosition++
        leads[leadPosition]
    }
    if (backing.isEmpty()) return this
    return mapIndexed { index, lead ->
        val voices = backing[index] ?: return@mapIndexed lead
        lead.copy(background = LyricLine(voices.first().timeMs, voices.joinToString(" ") { it.text },
            voices.flatMap { it.words }))
    }
}
