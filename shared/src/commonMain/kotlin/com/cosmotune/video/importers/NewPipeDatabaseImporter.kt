package com.cosmotune.video.importers

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem

/**
 * NewPipe's database backup (`NewPipeData-<date>.zip`, spec 2.7, 8.2). Only the ZIP's `newpipe.db` (Room, SQLite) is read;
 * `newpipe.settings` is a Java-serialised file and is **never opened, read or deserialised** (NewPipe's own importer has had
 * remote-code-execution reports for it), and `preferences.json` is of no use here.
 *
 * Read through [SqliteRows], YouTube (`service_id` 0) only; rows of other services are skipped and counted:
 * - `subscriptions`: `/channel/UC...` addresses to channels, `/user/...` and `/@...` to [ImportPreview.unresolvedChannels];
 * - history: `stream_history` joined to `streams`, one entry per stream with the newest `access_date`, the sum of
 *   `repeat_count` (a 0 counts as 1) as the watch count, `stream_state.progress_time` as the position and `duration`
 *   (seconds) as the duration in milliseconds. NewPipe's own statistics page reads it the same way;
 * - your playlists: `playlists` with `playlist_stream_join` in `join_index` order;
 * - saved playlists: `remote_playlists`;
 * - searches: `search_history`, newest first.
 *
 * A table the file does not have (an old backup) gives nothing for its part; the other parts are still read.
 */
object NewPipeDatabaseImporter {
    /** The one entry of the backup that is read (at the root of the ZIP, or in a folder when the backup was zipped again). */
    const val DATABASE_ENTRY = "newpipe.db"

    private const val YOUTUBE_SERVICE = 0L

    /**
     * Reads a backup ZIP: opens `newpipe.db` through [opener], reads it with [parse] and closes it. A ZIP without the entry
     * gives an empty preview with [ImportReason.NO_DATABASE]; a database that cannot be opened,
     * [ImportReason.DATABASE_UNREADABLE]. No other entry of [zip] is read.
     */
    fun parseBackup(zip: ZipEntries, opener: SqliteOpener, nowMs: Long): ImportPreview {
        val entry = zip.names().firstOrNull { it == DATABASE_ENTRY || it.endsWith("/$DATABASE_ENTRY") }
            ?: return ImportPreview.failed(ImportReason.NO_DATABASE)
        val bytes = zip.read(entry) ?: return ImportPreview.failed(ImportReason.DATABASE_UNREADABLE)
        val rows = try {
            opener.open(bytes)
        } catch (e: Exception) {
            return ImportPreview.failed(ImportReason.DATABASE_UNREADABLE)
        }
        try {
            return parse(rows, nowMs)
        } finally {
            try {
                rows.close()
            } catch (e: Exception) {
                // the file is read; a failure to release it changes nothing about what was read
            }
        }
    }

    /** Reads an open `newpipe.db`. [nowMs] is the follow time of the channels (the database has none). Does not close [rows]. */
    fun parse(rows: SqliteRows, nowMs: Long): ImportPreview {
        var failures = 0
        var queries = 0

        // The first statement that runs gives the rows; a table or column an old backup lacks gives nothing for its part.
        fun rowsOf(vararg statements: String): List<Map<String, Any?>> {
            queries += 1
            for (sql in statements) {
                try {
                    return rows.query(sql)
                } catch (e: Exception) {
                    // try the next form of the statement
                }
            }
            failures += 1
            return emptyList()
        }

        var skipped = 0

        val collector = SubscriptionCollector(nowMs)
        for (row in rowsOf("SELECT * FROM subscriptions ORDER BY uid")) {
            val url = row["url"].text()
            if (row["service_id"].long() != YOUTUBE_SERVICE || url == null) {
                collector.skip()
                continue
            }
            collector.addByAddress(url, row["name"].text(), row["avatar_url"].text())
        }
        skipped += collector.skipped

        val history = ArrayList<HistoryEntry>()
        val lastViews = """
            JOIN (
                SELECT stream_id, MAX(access_date) AS latest_access,
                       SUM(CASE WHEN repeat_count < 1 THEN 1 ELSE repeat_count END) AS watch_count
                FROM stream_history GROUP BY stream_id
            ) h ON s.uid = h.stream_id
        """.trimIndent()
        val historySql = "SELECT s.*, h.latest_access AS latest_access, h.watch_count AS watch_count, st.progress_time AS progress_time " +
            "FROM streams s $lastViews LEFT JOIN stream_state st ON st.stream_id = s.uid ORDER BY h.latest_access DESC"
        val historyWithoutPositions = "SELECT s.*, h.latest_access AS latest_access, h.watch_count AS watch_count " +
            "FROM streams s $lastViews ORDER BY h.latest_access DESC"
        for (row in rowsOf(historySql, historyWithoutPositions)) {
            val item = videoOf(row)
            if (item == null) {
                skipped += 1
                continue
            }
            history.add(
                HistoryEntry(
                    item = item,
                    lastWatchedMs = row["latest_access"].long() ?: 0L,
                    positionMs = (row["progress_time"].long() ?: 0L).coerceAtLeast(0L),
                    durationMs = (item.durationSec ?: 0L) * 1000L,
                    watchCount = (row["watch_count"].long() ?: 1L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
                ),
            )
        }

        val playlists = ArrayList<LocalPlaylist>()
        val itemsByPlaylist = HashMap<Long, MutableList<VideoItem>>()
        val joinSql = "SELECT j.playlist_id AS playlist_id, j.join_index AS join_index, s.* " +
            "FROM playlist_stream_join j JOIN streams s ON s.uid = j.stream_id ORDER BY j.playlist_id, j.join_index"
        for (row in rowsOf(joinSql)) {
            val playlistId = row["playlist_id"].long() ?: continue
            val item = videoOf(row)
            if (item == null) {
                skipped += 1
                continue
            }
            val list = itemsByPlaylist.getOrPut(playlistId) { ArrayList() }
            if (list.none { it.id == item.id }) list.add(item) else skipped += 1
        }
        val playlistRows = rowsOf("SELECT * FROM playlists").sortedWith(
            compareBy({ it["display_index"].long() ?: 0L }, { it["uid"].long() ?: 0L }),
        )
        for (row in playlistRows) {
            val uid = row["uid"].long() ?: continue
            val name = row["name"].text()?.trim().orEmpty().ifEmpty { "Playlist ${playlists.size + 1}" }
            playlists.add(LocalPlaylist("newpipe-$uid", name, itemsByPlaylist[uid].orEmpty(), nowMs, nowMs))
        }

        val saved = ArrayList<PlaylistItem>()
        val remoteRows = rowsOf("SELECT * FROM remote_playlists").sortedWith(
            compareBy({ it["display_index"].long() ?: 0L }, { it["uid"].long() ?: 0L }),
        )
        for (row in remoteRows) {
            val id = row["url"].text()?.let(ImportSupport::playlistIdOf)
            if (row["service_id"].long() != YOUTUBE_SERVICE || id == null || saved.any { it.id == id }) {
                skipped += 1
                continue
            }
            saved.add(
                PlaylistItem(
                    id = id,
                    title = row["name"].text()?.trim().orEmpty().ifEmpty { id },
                    thumbnailUrl = row["thumbnail_url"].text()?.takeIf { it.isNotBlank() },
                    ownerName = row["uploader"].text()?.takeIf { it.isNotBlank() },
                    ownerId = null,
                    videoCount = row["stream_count"].long()?.takeIf { it >= 0 },
                ),
            )
        }

        val searches = ArrayList<String>()
        for (row in rowsOf("SELECT search, service_id FROM search_history ORDER BY creation_date DESC")) {
            val query = row["search"].text()?.trim().orEmpty()
            if (row["service_id"].long() != YOUTUBE_SERVICE || query.isEmpty() || searches.any { it.equals(query, ignoreCase = true) }) {
                skipped += 1
            } else {
                searches.add(query)
            }
        }

        if (failures == queries) return ImportPreview.failed(ImportReason.DATABASE_UNREADABLE)
        val channels = collector.preview()
        return ImportPreview(
            subscriptions = channels.subscriptions,
            history = history,
            playlists = playlists,
            savedPlaylists = saved,
            searches = searches,
            unresolvedChannels = channels.unresolvedChannels,
            skipped = skipped,
        ).explainedIfEmpty()
    }

    /** The video of a `streams` row, or null for another service's stream or an address that holds no video id. */
    private fun videoOf(row: Map<String, Any?>): VideoItem? {
        if (row["service_id"].long() != YOUTUBE_SERVICE) return null
        val id = row["url"].text()?.let(ImportSupport::videoIdOf) ?: return null
        val duration = row["duration"].long()?.takeIf { it > 0 }
        val uploadDate = row["upload_date"].long()?.takeIf { it > 0 }
        val type = row["stream_type"].text().orEmpty()
        return VideoItem(
            id = id,
            title = row["title"].text()?.trim().orEmpty().ifEmpty { id },
            channelId = (row["uploader_url"].text()?.let(ImportSupport::channelRefOf) as? ChannelRef.Id)?.id,
            channelName = row["uploader"].text()?.trim()?.takeIf { it.isNotEmpty() },
            channelAvatarUrl = null,
            thumbnailUrl = row["thumbnail_url"].text()?.takeIf { it.isNotBlank() } ?: ImportSupport.thumbnailOf(id),
            durationSec = duration,
            viewCount = row["view_count"].long()?.takeIf { it >= 0 },
            uploadedAtMs = uploadDate,
            uploadedText = if (uploadDate == null) row["textual_upload_date"].text()?.takeIf { it.isNotBlank() } else null,
            isLive = type == "LIVE_STREAM" || type == "AUDIO_LIVE_STREAM",
        )
    }

    private fun Any?.text(): String? = when (this) {
        null -> null
        is String -> this
        is Number -> toString()
        else -> null
    }

    private fun Any?.long(): Long? = when (this) {
        is Long -> this
        is Number -> toLong()
        is String -> trim().toLongOrNull()
        else -> null
    }
}
