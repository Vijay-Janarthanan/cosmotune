package com.cosmotune.video.downloads

/** One caption of a WebVTT file: shown from [startMs] to [endMs], [text] without styling tags (lines joined by `\n`). */
data class VttCue(val startMs: Long, val endMs: Long, val text: String)

/** Reads the cues of a WebVTT file with their end times (what a subtitle track inside a video file needs). */
object VttCues {
    private val TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAG = Regex("""<[^>]*>""")

    fun parse(vtt: String): List<VttCue> {
        val lines = vtt.replace("\r\n", "\n").split('\n')
        val out = ArrayList<VttCue>()
        var i = 0
        while (i < lines.size) {
            val m = TIMING.find(lines[i])
            if (m == null) { i++; continue }
            val start = timeMs(m.groupValues[1])
            val end = timeMs(m.groupValues[2])
            i++
            val text = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                val clean = decode(TAG.replace(lines[i], "")).trim()
                if (clean.isNotEmpty()) text += clean
                i++
            }
            if (start != null && end != null && end > start && text.isNotEmpty()) out += VttCue(start, end, text.joinToString("\n"))
        }
        return out.sortedBy { it.startMs }
    }

    private fun timeMs(text: String): Long? {
        val parts = text.replace(',', '.').split(':')
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: 0 else 0
        return ((hours * 3600 + minutes * 60) * 1000) + kotlin.math.round(seconds * 1000).toLong()
    }

    private fun decode(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}
