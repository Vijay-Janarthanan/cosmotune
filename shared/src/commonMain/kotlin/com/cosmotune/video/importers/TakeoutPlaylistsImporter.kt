package com.cosmotune.video.importers

import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.model.VideoItem

/** A row of Takeout's `playlists.csv` (the index of all playlists): what is known about one playlist. */
data class PlaylistIndexEntry(val id: String?, val title: String?, val createdMs: Long?, val updatedMs: Long?)

/**
 * Your playlists from Google Takeout (spec 2.7, 8.2): one CSV per playlist, in two layouts that the second line tells apart.
 *
 * - Current: `Video ID,Playlist Video Creation Timestamp`, then one video per line. The playlist has no name inside the file:
 *   it comes from the file name, the part before the last `-` and the `.csv` (Takeout names the files `<title>-videos.csv`).
 * - Older (before about 2024): a metadata block (`Playlist Id,Channel Id,Time Created,Time Updated,Title,Description,Visibility`
 *   and one line of values), a blank line, then `Video Id,Time Added` and the videos. The second line has more than two
 *   columns. The name is the block's title (read by header name, else by position), else the file name as above.
 *
 * The items are videos known by their id only: `title` is the id and the picture is the id's picture, until the video is
 * opened and the real item replaces it (spec 8.2). The playlist's created and updated times are the block's, else the
 * oldest and newest time added, else [nowMs].
 *
 * `playlists.csv`, the index of all playlists, is read by [parseIndex] as Google's data schema describes it (columns `Playlist
 * ID`, `Playlist Title (Original)`, `Playlist Create Timestamp`, `Playlist update timestamp`, found by their names), and only to
 * give a playlist the id and the times the index knows. The playlist's videos are never taken from it.
 */
object TakeoutPlaylistsImporter {
    /** The layouts a Takeout CSV can have. */
    internal enum class Layout { CURRENT, OLD, INDEX, OTHER }

    /** Reads the playlist CSV [text] that came as file [fileName]; [index] (from [parseIndex]) adds ids and times. */
    fun parse(fileName: String, text: String, nowMs: Long, index: List<PlaylistIndexEntry> = emptyList()): ImportPreview {
        val records = Csv.parse(text)
        val layout = layoutOf(records)
        if (layout != Layout.CURRENT && layout != Layout.OLD) return ImportPreview.failed(ImportReason.UNREADABLE)
        if (layout == Layout.OLD && records.size < 2) return ImportPreview.failed(ImportReason.UNREADABLE) // a header and no playlist

        var name = nameFromFileName(fileName)
        var playlistId: String? = null
        var created: Long? = null
        var updated: Long? = null
        var firstVideoRow = 0
        if (layout == Layout.OLD) {
            val header = records[0].map { it.trim().lowercase() }
            val values = records[1]
            fun column(label: String, position: Int): String? =
                values.getOrNull(header.indexOf(label).takeIf { it >= 0 } ?: position)?.trim()?.takeIf { it.isNotEmpty() }
            column("title", 4)?.let { name = it }
            playlistId = column("playlist id", 0)
            created = column("time created", 2)?.let(ImportSupport::parseTimeMs)
            updated = column("time updated", 3)?.let(ImportSupport::parseTimeMs)
            firstVideoRow = 2
        }

        var skipped = 0
        val seen = HashSet<String>()
        val items = ArrayList<VideoItem>()
        var oldest: Long? = null
        var newest: Long? = null
        var headerAllowed = true // the first line that is not a video id is the column header, not a lost entry
        for (record in records.drop(firstVideoRow)) {
            if (record.isBlankRecord()) continue
            val id = record[0].trim()
            if (!ImportSupport.isVideoId(id)) {
                if (headerAllowed) headerAllowed = false else skipped += 1
                continue
            }
            headerAllowed = false
            if (!seen.add(id)) {
                skipped += 1
                continue
            }
            record.getOrNull(1)?.let(ImportSupport::parseTimeMs)?.let { time ->
                oldest = minOf(oldest ?: time, time)
                newest = maxOf(newest ?: time, time)
            }
            items.add(itemOf(id))
        }

        val known = index.firstOrNull { it.title != null && it.title.trim().equals(name, ignoreCase = true) }
        val playlist = LocalPlaylist(
            id = "takeout-" + (playlistId ?: known?.id ?: fingerprint(name)),
            name = name,
            items = items,
            createdMs = created ?: known?.createdMs ?: oldest ?: nowMs,
            updatedMs = updated ?: known?.updatedMs ?: newest ?: nowMs,
        )
        return ImportPreview(playlists = listOf(playlist), skipped = skipped)
    }

    /** Reads Takeout's `playlists.csv`; empty when the text is not that file. */
    fun parseIndex(text: String): List<PlaylistIndexEntry> {
        val records = Csv.parse(text)
        if (layoutOf(records) != Layout.INDEX) return emptyList()
        val header = records[0].map { it.trim().lowercase() }
        val idColumn = header.indexOfFirst { it == "playlist id" }
        val titleColumn = header.indexOfFirst { it.startsWith("playlist title") && it.contains("original") }
            .takeIf { it >= 0 }
            ?: header.indexOfFirst { it.startsWith("playlist title") }.takeIf { it >= 0 }
            ?: header.indexOfFirst { it.contains("title") && !it.contains("translated") }
        val createdColumn = header.indexOfFirst { it.startsWith("playlist create") }
        val updatedColumn = header.indexOfFirst { it.startsWith("playlist update") }
        return records.drop(1).filterNot { it.isBlankRecord() }.map { row ->
            fun cell(column: Int): String? = if (column < 0) null else row.getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }
            PlaylistIndexEntry(
                id = cell(idColumn),
                title = cell(titleColumn),
                createdMs = cell(createdColumn)?.let(ImportSupport::parseTimeMs),
                updatedMs = cell(updatedColumn)?.let(ImportSupport::parseTimeMs),
            )
        }
    }

    /** The playlist's name from its file name: the part before the last `-` and the `.csv` (the whole name when it has no dash). */
    fun nameFromFileName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = if (base.lowercase().endsWith(".csv")) base.dropLast(4) else base
        val dash = withoutExtension.lastIndexOf('-')
        val suffix = if (dash >= 0) withoutExtension.substring(dash + 1) else ""
        val name = if (dash > 0 && withoutExtension.substring(0, dash).isNotBlank() && suffix.isNotBlank() && !suffix.contains(' ')) {
            withoutExtension.substring(0, dash)
        } else {
            withoutExtension
        }
        return name.trim().ifEmpty { "Playlist" }
    }

    /** Which layout [records] are. A subscriptions CSV (channel ids in the first column) is not a playlist. */
    internal fun layoutOf(records: List<List<String>>): Layout {
        val first = records.firstOrNull() ?: return Layout.OTHER
        val head = first.map { it.trim().lowercase() }
        val second = records.getOrNull(1)
        val secondId = second?.firstOrNull()?.trim().orEmpty()
        if (ImportSupport.isChannelId(first[0].trim()) || ImportSupport.isChannelId(secondId)) return Layout.OTHER
        return when {
            head[0] == "playlist id" && head.any { it == "channel id" } -> Layout.OLD
            head.any { it.contains("playlist") && it.contains("title") } -> Layout.INDEX
            second != null && second.size > 2 && head.size > 2 -> Layout.OLD
            head[0] == "video id" -> Layout.CURRENT
            ImportSupport.isVideoId(first[0].trim()) || ImportSupport.isVideoId(secondId) -> Layout.CURRENT
            else -> Layout.OTHER
        }
    }

    private fun itemOf(id: String) = VideoItem(
        id = id,
        title = id,
        channelId = null,
        channelName = null,
        channelAvatarUrl = null,
        thumbnailUrl = ImportSupport.thumbnailOf(id),
        durationSec = null,
        viewCount = null,
        uploadedAtMs = null,
        uploadedText = null,
    )

    /** A short stable text for [name], so importing the same file twice gives the same playlist id. */
    private fun fingerprint(name: String): String {
        var hash = 2_166_136_261L
        for (c in name.lowercase()) hash = ((hash xor c.code.toLong()) * 16_777_619L) and 0xFFFFFFFFL
        return hash.toString(16)
    }
}
