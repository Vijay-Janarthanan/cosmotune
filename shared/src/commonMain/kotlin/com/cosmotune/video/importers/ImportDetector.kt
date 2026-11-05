package com.cosmotune.video.importers

/** The files the import screen can recognise (spec 8.2). */
enum class ImportFormat {
    NEWPIPE_SUBSCRIPTIONS,
    NEWPIPE_DATABASE,
    TAKEOUT_ZIP,
    TAKEOUT_SUBSCRIPTIONS_CSV,
    TAKEOUT_SUBSCRIPTIONS_JSON,
    TAKEOUT_HISTORY_JSON,
    TAKEOUT_PLAYLIST_CSV,
    OPML,
    COSMOTUNE_EXPORT,
    UNKNOWN,
}

/**
 * Tells which importer a picked file is for, from its name and the first [HEAD_BYTES] bytes: the file is not read whole to
 * decide. The decision is made on what the head holds; the name only matters for the one file whose head says too little (a
 * ZIP with an unfamiliar first entry). The Cosmotune export is only recognised here: `LibraryExport` reads it (Y-7).
 *
 * - ZIP: the first entry's name. A root-level `newpipe.db`, `newpipe.settings` or `preferences.json` (or a `newpipe.db` or
 *   `newpipe.settings` in a folder) is a NewPipe backup (the database comes first in the files NewPipe writes); a `Takeout/` folder, or a name with "takeout" or "youtube" in the
 *   archive's or the first entry's path, is a Takeout archive; any other ZIP is [ImportFormat.UNKNOWN].
 * - XML with an `<opml` tag: OPML. JSON object: the Cosmotune export (`"format": "cosmotune-video"`), else NewPipe's
 *   subscriptions (`service_id`, `app_version` or `subscriptions`), but not a LibreTube backup. JSON array: the older
 *   Takeout subscriptions (`youtube#subscription`) or the watch history (`titleUrl`).
 * - CSV: channel ids in the first column of the first two lines are Takeout subscriptions; the playlist layouts are Takeout
 *   playlists. A raw SQLite file is not recognised (only the backup ZIP is read, spec 8.2).
 */
object ImportDetector {
    /** How much of the start of a file [detect] wants. */
    const val HEAD_BYTES = 8192

    private val NEWPIPE_ENTRIES = setOf("newpipe.db", "newpipe.settings", "preferences.json")

    /** The format of the file called [fileName] whose first bytes are [head]. */
    fun detect(fileName: String, head: ByteArray): ImportFormat {
        if (head.isEmpty()) return ImportFormat.UNKNOWN
        if (isZip(head)) return detectZip(fileName, head)
        if (head.take(16).containsNul()) return ImportFormat.UNKNOWN

        val text = head.decodeToString().removePrefix("\uFEFF")
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("<") -> if (text.contains("<opml", ignoreCase = true)) ImportFormat.OPML else ImportFormat.UNKNOWN
            trimmed.startsWith("{") -> detectJsonObject(text)
            trimmed.startsWith("[") -> detectJsonArray(text)
            else -> detectCsv(text)
        }
    }

    private fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    private fun List<Byte>.containsNul(): Boolean = any { it == 0.toByte() }

    /** The name of the first entry from its local file header (30 bytes, then the name), or null when the head cuts it. */
    private fun firstEntryName(head: ByteArray): String? {
        if (head.size < 30) return null
        val length = (head[26].toInt() and 0xFF) or ((head[27].toInt() and 0xFF) shl 8)
        if (length == 0 || 30 + length > head.size) return null
        return head.copyOfRange(30, 30 + length).decodeToString()
    }

    private fun detectZip(fileName: String, head: ByteArray): ImportFormat {
        val entry = firstEntryName(head)?.replace('\\', '/')?.removePrefix("./")
        val lower = entry?.lowercase().orEmpty()
        val last = lower.substringAfterLast('/')
        if (lower in NEWPIPE_ENTRIES || last == "newpipe.db" || last == "newpipe.settings") return ImportFormat.NEWPIPE_DATABASE
        val fileLower = fileName.lowercase()
        val takeout = lower.startsWith("takeout/") || lower.contains("takeout") || lower.contains("youtube") ||
            fileLower.contains("takeout")
        return if (takeout) ImportFormat.TAKEOUT_ZIP else ImportFormat.UNKNOWN
    }

    private fun detectJsonObject(text: String): ImportFormat = when {
        text.contains("\"cosmotune-video\"") -> ImportFormat.COSMOTUNE_EXPORT
        text.contains("\"watchHistory\"") || text.contains("\"localPlaylists\"") -> ImportFormat.UNKNOWN
        text.contains("\"service_id\"") || text.contains("\"app_version\"") || text.contains("\"subscriptions\"") ->
            ImportFormat.NEWPIPE_SUBSCRIPTIONS
        else -> ImportFormat.UNKNOWN
    }

    private fun detectJsonArray(text: String): ImportFormat = when {
        text.contains("youtube#subscription") || text.contains("\"resourceId\"") -> ImportFormat.TAKEOUT_SUBSCRIPTIONS_JSON
        text.contains("\"titleUrl\"") -> ImportFormat.TAKEOUT_HISTORY_JSON
        else -> ImportFormat.UNKNOWN
    }

    private fun detectCsv(text: String): ImportFormat {
        val records = Csv.parse(text).take(3)
        if (records.isEmpty()) return ImportFormat.UNKNOWN
        val firstColumn = records.take(2).map { it.firstOrNull()?.trim().orEmpty() }
        if (firstColumn.any { ImportSupport.isChannelId(it) }) return ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV
        return when (TakeoutPlaylistsImporter.layoutOf(records)) {
            TakeoutPlaylistsImporter.Layout.CURRENT, TakeoutPlaylistsImporter.Layout.OLD -> ImportFormat.TAKEOUT_PLAYLIST_CSV
            else -> ImportFormat.UNKNOWN
        }
    }
}
