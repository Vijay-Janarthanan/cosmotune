package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import com.cosmotune.media.sqlite.SqliteOpener
import com.cosmotune.media.sqlite.SqliteRows
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A backup from another YouTube Music app, read for its liked songs and playlists:
 * - InnerTune and the apps built on it (OuterTune, Metrolist): a `.backup` zip holding `song.db` (`song`, `artist`,
 *   `song_artist_map`, `playlist`, `playlist_song_map`, liked as `liked`);
 * - ViMusic and its forks (RiMusic, Kreate): the exported `.db` itself (`Song` with `artistsText` and `likedAt`, `Playlist`,
 *   `SongPlaylistMap`);
 * - SimpMusic: a backup zip holding its Room database (`song` with `videoId`, `artistName` and `liked`, `local_playlist` with
 *   its `tracks`).
 * Only YouTube songs come across (an id of 11 letters, digits, `-` and `_`); the other app's local files are not on this
 * device. Read with the platform's SQLite through [SqliteOpener]; nothing is written to the other app's file.
 */
object OtherAppBackup {

    data class Playlist(val name: String, val songs: List<Song>)

    data class Contents(val app: String, val liked: List<Song>, val playlists: List<Playlist>) {
        val isEmpty: Boolean get() = liked.isEmpty() && playlists.all { it.songs.isEmpty() }
    }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    /** The SQLite database in [bytes]: the bytes themselves, or the first database inside a zip. Null when there is none. */
    fun database(bytes: ByteArray): ByteArray? {
        if (bytes.startsWith(SQLITE_MAGIC)) return bytes
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.isDirectory) continue
                val data = zip.readBytes()
                if (data.startsWith(SQLITE_MAGIC)) return data
            }
        }
    }

    /** Reads [bytes] (the file the listener picked); fails with a plain message when it is not a backup this knows. */
    fun read(bytes: ByteArray, opener: SqliteOpener): Contents {
        val db = requireNotNull(database(bytes)) { "That file is not a music app backup." }
        val rows = opener.open(db)
        try {
            return read(rows)
        } finally {
            rows.close()
        }
    }

    fun read(rows: SqliteRows): Contents {
        val tables = rows.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .mapNotNull { (it["name"] as? String)?.lowercase() }.toSet()
        fun all(table: String): List<Map<String, Any?>> =
            if (table in tables) rows.query("SELECT * FROM \"$table\"").map { row -> row.mapKeys { it.key.lowercase() } } else emptyList()
        return when {
            "local_playlist" in tables -> simpMusic(all("song"), all("local_playlist"))
            "songplaylistmap" in tables -> viMusic(all("song"), all("playlist"), all("songplaylistmap"))
            "song_artist_map" in tables || "playlist_song_map" in tables ->
                innerTune(all("song"), all("artist"), all("song_artist_map"), all("playlist"), all("playlist_song_map"))
            else -> error("That backup is from an app Cosmotune cannot read yet.")
        }
    }

    private fun innerTune(
        songs: List<Map<String, Any?>>,
        artists: List<Map<String, Any?>>,
        songArtists: List<Map<String, Any?>>,
        playlists: List<Map<String, Any?>>,
        entries: List<Map<String, Any?>>,
    ): Contents {
        val artistName = artists.associate { it.text("id") to it.text("name") }
        val credits = songArtists.sortedBy { it.long("position") ?: 0 }.groupBy({ it.text("songid") }, { artistName[it.text("artistid")] })
        val byId = songs.mapNotNull { row ->
            val id = row.text("id")
            song(id, row.text("title"), credits[id].orEmpty().filterNotNull().joinToString(", "),
                row.long("duration")?.takeIf { it > 0 }?.let(::clock), row.text("thumbnailurl"), row.text("albumname"))?.let { id to (it to row) }
        }.toMap()
        val liked = byId.values.filter { (_, row) -> (row.long("liked") ?: 0) != 0L }
            .sortedByDescending { (_, row) -> row.long("likeddate") ?: 0 }.map { it.first }
        return Contents("InnerTune", liked, playlistsOf(playlists, entries, "playlistid", "songid") { byId[it]?.first })
    }

    private fun viMusic(songs: List<Map<String, Any?>>, playlists: List<Map<String, Any?>>, entries: List<Map<String, Any?>>): Contents {
        val byId = songs.mapNotNull { row ->
            val id = row.text("id")
            song(id, row.text("title"), row.text("artiststext"), row.text("durationtext"), row.text("thumbnailurl"), null)?.let { id to (it to row) }
        }.toMap()
        val liked = byId.values.filter { (_, row) -> (row.long("likedat") ?: 0) > 0 }
            .sortedByDescending { (_, row) -> row.long("likedat") ?: 0 }.map { it.first }
        return Contents("ViMusic", liked, playlistsOf(playlists, entries, "playlistid", "songid") { byId[it]?.first })
    }

    private fun simpMusic(songs: List<Map<String, Any?>>, playlists: List<Map<String, Any?>>): Contents {
        val byId = songs.mapNotNull { row ->
            val id = row.text("videoid")
            song(id, row.text("title"), list(row.text("artistname")).joinToString(", "),
                row.long("durationseconds")?.takeIf { it > 0 }?.let(::clock), row.text("thumbnails"), row.text("albumname"))?.let { id to (it to row) }
        }.toMap()
        val liked = byId.values.filter { (_, row) -> row.truthy("liked") }.map { it.first }
        val lists = playlists.mapNotNull { row ->
            val name = row.text("title").ifBlank { return@mapNotNull null }
            Playlist(name, list(row.text("tracks")).mapNotNull { byId[it]?.first ?: song(it, "", "", null, null, null) })
        }
        return Contents("SimpMusic", liked, lists.filter { it.songs.isNotEmpty() })
    }

    private fun playlistsOf(
        playlists: List<Map<String, Any?>>,
        entries: List<Map<String, Any?>>,
        playlistColumn: String,
        songColumn: String,
        songFor: (String) -> Song?,
    ): List<Playlist> {
        val byPlaylist = entries.sortedBy { it.long("position") ?: 0 }.groupBy { it.text(playlistColumn) }
        return playlists.mapNotNull { row ->
            val name = row.text("name").ifBlank { return@mapNotNull null }
            val songs = byPlaylist[row.text("id")].orEmpty().mapNotNull { songFor(it.text(songColumn)) }.distinctBy(Song::videoId)
            Playlist(name, songs).takeIf { songs.isNotEmpty() }
        }
    }

    private fun song(id: String, title: String, artist: String, duration: String?, thumbnail: String?, album: String?): Song? {
        if (!YOUTUBE_ID.matches(id)) return null
        return Song(videoId = id, title = title.ifBlank { id }, artist = artist.ifBlank { "Unknown Artist" }, thumbnailUrl = thumbnail?.ifBlank { null },
            durationText = duration, albumName = album?.ifBlank { null })
    }

    /** A Room list column: JSON (`["a","b"]`) or comma separated. */
    internal fun list(value: String): List<String> {
        val body = value.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return body.split(',').map { it.trim().removeSurrounding("\"").trim() }.filter { it.isNotEmpty() }
    }

    private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun Map<String, Any?>.text(key: String): String = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
    private fun Map<String, Any?>.truthy(key: String): Boolean = when (val v = this[key]) {
        is Number -> v.toLong() != 0L
        is String -> v == "1" || v.equals("true", true)
        is Boolean -> v
        else -> false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
