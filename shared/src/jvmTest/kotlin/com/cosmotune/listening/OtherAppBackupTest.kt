package com.cosmotune.listening

import com.cosmotune.media.sqlite.SqliteRows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OtherAppBackupTest {

    /** Answers `sqlite_master` with the tables' names and `SELECT *` with their rows, as the platform readers do. */
    private class Tables(private val tables: Map<String, List<Map<String, Any?>>>) : SqliteRows {
        override fun query(sql: String): List<Map<String, Any?>> =
            if ("sqlite_master" in sql) tables.keys.map { mapOf("name" to it) }
            else tables.entries.first { "\"${it.key.lowercase()}\"" in sql.lowercase() }.value
        override fun close() = Unit
    }

    @Test
    fun innerTuneLikesAndPlaylistsComeAcrossWithTheirArtists() {
        val backup = OtherAppBackup.read(Tables(mapOf(
            "song" to listOf(
                mapOf("id" to "aaaaaaaaaaa", "title" to "One", "duration" to 185L, "liked" to 1L, "likedDate" to 5L),
                mapOf("id" to "bbbbbbbbbbb", "title" to "Two", "duration" to 0L, "liked" to 0L),
                mapOf("id" to "local:file", "title" to "Mine", "liked" to 1L),
            ),
            "artist" to listOf(mapOf("id" to "A", "name" to "Asha"), mapOf("id" to "K", "name" to "Kishore")),
            "song_artist_map" to listOf(mapOf("songId" to "aaaaaaaaaaa", "artistId" to "K", "position" to 1L),
                mapOf("songId" to "aaaaaaaaaaa", "artistId" to "A", "position" to 0L)),
            "playlist" to listOf(mapOf("id" to "p1", "name" to "Drive"), mapOf("id" to "p2", "name" to "Empty")),
            "playlist_song_map" to listOf(mapOf("playlistId" to "p1", "songId" to "bbbbbbbbbbb", "position" to 1L),
                mapOf("playlistId" to "p1", "songId" to "aaaaaaaaaaa", "position" to 0L)),
        )))
        assertEquals("InnerTune", backup.app)
        assertEquals(listOf("aaaaaaaaaaa"), backup.liked.map { it.videoId })
        assertEquals("Asha, Kishore", backup.liked.single().artist)
        assertEquals("3:05", backup.liked.single().durationText)
        assertEquals(listOf("Drive"), backup.playlists.map { it.name })
        assertEquals(listOf("One", "Two"), backup.playlists.single().songs.map { it.title })
    }

    @Test
    fun viMusicLikesAreTheSongsWithALikedTime() {
        val backup = OtherAppBackup.read(Tables(mapOf(
            "Song" to listOf(
                mapOf("id" to "ccccccccccc", "title" to "Three", "artistsText" to "Ilaiyaraaja", "durationText" to "4:10", "likedAt" to 20L),
                mapOf("id" to "ddddddddddd", "title" to "Four", "artistsText" to "", "likedAt" to null),
            ),
            "Playlist" to listOf(mapOf("id" to 1L, "name" to "Evening")),
            "SongPlaylistMap" to listOf(mapOf("songId" to "ddddddddddd", "playlistId" to 1L, "position" to 0L)),
        )))
        assertEquals("ViMusic", backup.app)
        assertEquals(listOf("Three"), backup.liked.map { it.title })
        assertEquals("Unknown Artist", backup.playlists.single().songs.single().artist)
    }

    @Test
    fun simpMusicReadsItsListColumns() {
        val backup = OtherAppBackup.read(Tables(mapOf(
            "song" to listOf(mapOf("videoId" to "eeeeeeeeeee", "title" to "Five", "artistName" to "[\"A\",\"B\"]", "liked" to 1L)),
            "local_playlist" to listOf(mapOf("id" to 1L, "title" to "Mix", "tracks" to "[\"eeeeeeeeeee\",\"fffffffffff\"]")),
        )))
        assertEquals("SimpMusic", backup.app)
        assertEquals("A, B", backup.liked.single().artist)
        assertEquals(listOf("eeeeeeeeeee", "fffffffffff"), backup.playlists.single().songs.map { it.videoId })
    }

    @Test
    fun anUnknownDatabaseSaysSo() {
        assertFailsWith<IllegalStateException> { OtherAppBackup.read(Tables(mapOf("things" to emptyList()))) }
    }

    @Test
    fun theDatabaseIsFoundInsideAZip() {
        val db = "SQLite format 3\u0000rest".toByteArray(Charsets.ISO_8859_1)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("settings.preferences_pb")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
                z.putNextEntry(ZipEntry("song.db")); z.write(db); z.closeEntry()
            }
        }.toByteArray()
        assertContentEquals(db, OtherAppBackup.database(zip))
        assertContentEquals(db, OtherAppBackup.database(db))
        assertNull(OtherAppBackup.database("hello".toByteArray()))
    }
}
