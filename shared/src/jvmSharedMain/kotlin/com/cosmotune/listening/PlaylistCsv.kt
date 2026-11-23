package com.cosmotune.listening

/** One song as a row of [PlaylistCsv]. */
data class PlaylistCsvRow(val title: String, val artist: String, val album: String?, val duration: String?, val videoId: String?)

/**
 * A playlist as a CSV file a spreadsheet or another music app can read: Title, Artist, Album, Duration and a YouTube Music link,
 * one song a line in the playlist's order, UTF-8 with a byte-order mark so Excel reads the accents and scripts right. Cells that
 * a spreadsheet would run as a formula are kept as text. Local files get no link.
 */
object PlaylistCsv {

    const val HEADER = "Title,Artist,Album,Duration,Link"

    fun export(rows: List<PlaylistCsvRow>): String = buildString {
        append('\uFEFF').append(HEADER).append("\r\n")
        rows.forEach { row ->
            val link = row.videoId?.takeIf(::isYouTubeId)?.let { "https://music.youtube.com/watch?v=$it" }.orEmpty()
            append(listOf(row.title, row.artist, row.album.orEmpty(), row.duration.orEmpty(), link).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    /** A name for the file from the playlist's [title]: letters, digits and dashes, ".csv" at the end. */
    fun fileName(title: String): String {
        val base = title.trim().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').take(60).ifEmpty { "playlist" }
        return "$base.csv"
    }

    private fun cell(value: String): String {
        val clean = value.replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "").trim()
        val safe = if (clean.isNotEmpty() && clean[0] in "=+-@\t") "'$clean" else clean
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun isYouTubeId(id: String): Boolean = id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
