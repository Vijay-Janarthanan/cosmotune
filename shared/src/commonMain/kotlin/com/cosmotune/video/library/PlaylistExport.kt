package com.cosmotune.video.library

import com.cosmotune.video.model.VideoItem

/** One playlist as a file: a list of links (one video address a line), or the CSV Google Takeout writes for a playlist. */
object PlaylistExport {
    enum class Kind(val extension: String, val mimeType: String) { LINKS("txt", "text/plain"), CSV("csv", "text/csv") }

    /** The videos of [items] (playlist cards and repeats left out), as [kind]. [nowMs] stamps each CSV row. */
    fun text(items: List<VideoItem>, kind: Kind, nowMs: Long): String {
        val videos = items.filter { !it.isPlaylistCard && it.id.isNotBlank() }.distinctBy { it.id }
        return when (kind) {
            Kind.LINKS -> videos.joinToString("") { "https://www.youtube.com/watch?v=${it.id}\n" }
            Kind.CSV -> buildString {
                append("Video ID,Playlist Video Creation Timestamp\n")
                val stamp = isoUtc(nowMs)
                for (v in videos) append(v.id).append(',').append(stamp).append('\n')
            }
        }
    }

    /** "Playlist name.csv": the name without the characters file systems refuse. */
    fun fileName(name: String, kind: Kind): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().take(80).ifEmpty { "Playlist" }
        return "$clean.${kind.extension}"
    }

    /** "2023-11-14T22:13:20+00:00" for [ms] since 1970. */
    internal fun isoUtc(ms: Long): String {
        val secs = ms.floorDiv(1000L)
        val days = secs.floorDiv(86_400L)
        val rem = secs - days * 86_400L
        // days to a civil date (Howard Hinnant's algorithm)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "$y-${two(m)}-${two(d)}T${two(rem / 3600)}:${two(rem % 3600 / 60)}:${two(rem % 60)}+00:00"
    }
}
