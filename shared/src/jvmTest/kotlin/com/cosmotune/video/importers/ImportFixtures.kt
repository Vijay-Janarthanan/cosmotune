package com.cosmotune.video.importers

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Helpers of the importer tests: the fixture files, ZIPs and NewPipe databases built in memory, and a JDBC [SqliteRows]. */
internal object ImportFixtures {
    /** The time the tests import at (the same instant as `FakeVideoSource.NOW_MS`, 2026-10-16 12:00 UTC). */
    const val NOW = 1_792_152_000_000L

    // Real-shaped ids (24 and 11 characters); the fakes of FakeVideoSource are 23 and must not be used here.
    const val C0 = "UCDVWqyfkrFAZd6_6X-yZl_D"
    const val C1 = "UCcI08WoHmiXWnhREl-Asie_"
    const val C2 = "UCys64KT2rumOT-zNtTKjCSF"
    const val C3 = "UCbLRCIe2GWM1wYovdzuMobW"
    const val C4 = "UCRv3Dr0501kiKmeknUt_tiy"
    const val C5 = "UCcrRXpMSPUa-AhewwR34pkz"
    const val V0 = "PMTLnz_VWNA"
    const val V1 = "LYXTU2z_kIu"
    const val V2 = "i-I1oJLJIJ0"
    const val V3 = "FUFF6FDzV-n"
    const val V4 = "oXkv2frcyHl"
    const val V5 = "m76KlqTiOek"
    const val V6 = "rUjDeOCLTPi"
    const val V7 = "oS3uuGXt3im"

    /** The bytes of fixture [name] in `src/jvmTest/resources/video/importers/`. */
    fun bytes(name: String): ByteArray =
        (ImportFixtures::class.java.getResourceAsStream("/video/importers/$name") ?: error("missing fixture $name")).use { it.readBytes() }

    /** The text of fixture [name]. */
    fun text(name: String): String = bytes(name).decodeToString()

    /** The milliseconds since 1970 of the ISO instant [iso]. */
    fun ms(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    /** A ZIP of the given entries, in order. [charset] is how the names are written (UTF-8 sets the UTF-8 flag). */
    fun zip(vararg entries: Pair<String, ByteArray>, charset: Charset = Charsets.UTF_8): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A ZIP archive as [ZipEntries] that remembers which entries were read. */
    class RecordingZip(private val inner: ZipEntries) : ZipEntries {
        val reads = ArrayList<String>()
        override fun names(): List<String> = inner.names()
        override fun read(name: String): ByteArray? {
            reads += name
            return inner.read(name)
        }
    }

    /** A JDBC [SqliteRows] for the tests (the real one is `JdbcSqliteRows` in :video). */
    class JdbcTestRows(path: String) : SqliteRows {
        private val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        var closed = false
            private set

        override fun query(sql: String): List<Map<String, Any?>> = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val meta = result.metaData
                val labels = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val rows = ArrayList<Map<String, Any?>>()
                while (result.next()) {
                    rows += labels.indices.associate { labels[it] to normalised(result.getObject(it + 1)) }
                }
                rows
            }
        }

        override fun close() {
            closed = true
            connection.close()
        }

        private fun normalised(value: Any?): Any? = if (value is Int) value.toLong() else value
    }

    /**
     * A NewPipe database (Room schema version 9: the tables and columns the importer reads, as `AppDatabase/9.json` declares
     * them) with rows written for these tests, as the bytes of the file. [fill] adds the rows (the sample by default).
     */
    fun newPipeDatabase(workDir: File, fill: java.sql.Statement.() -> Unit = { fillSample() }): ByteArray {
        val file = File.createTempFile("newpipe-test-", ".db", workDir)
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
                connection.createStatement().use { statement ->
                    for (ddl in NEWPIPE_SCHEMA) statement.execute(ddl)
                    statement.fill()
                }
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private val NEWPIPE_SCHEMA = listOf(
        "CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, service_id INTEGER NOT NULL, url TEXT, name TEXT, avatar_url TEXT, subscriber_count INTEGER, description TEXT, notification_mode INTEGER NOT NULL)",
        "CREATE TABLE search_history (creation_date INTEGER, service_id INTEGER NOT NULL, search TEXT, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
        "CREATE TABLE streams (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, service_id INTEGER NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, stream_type TEXT NOT NULL, duration INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_url TEXT, thumbnail_url TEXT, view_count INTEGER, textual_upload_date TEXT, upload_date INTEGER, is_upload_date_approximation INTEGER)",
        "CREATE TABLE stream_history (stream_id INTEGER NOT NULL, access_date INTEGER NOT NULL, repeat_count INTEGER NOT NULL, PRIMARY KEY(stream_id, access_date))",
        "CREATE TABLE stream_state (stream_id INTEGER NOT NULL, progress_time INTEGER NOT NULL, PRIMARY KEY(stream_id))",
        "CREATE TABLE playlist_stream_join (playlist_id INTEGER NOT NULL, stream_id INTEGER NOT NULL, join_index INTEGER NOT NULL, PRIMARY KEY(playlist_id, join_index))",
        "CREATE TABLE playlists (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT, is_thumbnail_permanent INTEGER NOT NULL, thumbnail_stream_id INTEGER NOT NULL, display_index INTEGER NOT NULL)",
        "CREATE TABLE remote_playlists (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, service_id INTEGER NOT NULL, name TEXT, url TEXT, thumbnail_url TEXT, uploader TEXT, display_index INTEGER NOT NULL, stream_count INTEGER)",
    )

    /**
     * The sample content (NewPipeDatabaseImporterTest says what it should read as): 4 subscriptions (one of another service,
     * one `/user/` address), 6 streams (one of another service, one live), a history of 4 YouTube streams and one of another
     * service (one watched in two rows, one with a repeat count of 0), 3 playlists, 2 saved playlists, 5 searches.
     */
    private fun java.sql.Statement.fillSample() {
        val c0 = "https://www.youtube.com/channel/$C0"
        execute("INSERT INTO subscriptions (service_id, url, name, avatar_url, notification_mode) VALUES (0, '$c0', 'Northern Lights Workshop', 'https://images.example/c0.jpg', 0)")
        execute("INSERT INTO subscriptions (service_id, url, name, avatar_url, notification_mode) VALUES (0, 'https://www.youtube.com/user/OldTimersTV', 'Old Timers', NULL, 0)")
        execute("INSERT INTO subscriptions (service_id, url, name, avatar_url, notification_mode) VALUES (3, 'https://framatube.example/video-channels/maker', 'A PeerTube channel', NULL, 0)")
        execute("INSERT INTO subscriptions (service_id, url, name, avatar_url, notification_mode) VALUES (0, 'https://www.youtube.com/channel/$C1', 'Paper, Scissors & Stone', 'https://images.example/c1.jpg', 0)")

        fun stream(uid: Int, service: Int, url: String, title: String, type: String, duration: Int, views: Long?, uploaded: Long?) =
            execute(
                "INSERT INTO streams (uid, service_id, url, title, stream_type, duration, uploader, uploader_url, thumbnail_url, view_count, textual_upload_date, upload_date, is_upload_date_approximation) " +
                    "VALUES ($uid, $service, '$url', '${title.replace("'", "''")}', '$type', $duration, 'Northern Lights Workshop', '$c0', 'https://images.example/s$uid.jpg', ${views ?: "NULL"}, 'a while ago', ${uploaded ?: "NULL"}, 1)",
            )
        stream(1, 0, "https://www.youtube.com/watch?v=$V0", "How to plane a board by hand", "VIDEO_STREAM", 600, 1200, 1_700_000_000_000)
        stream(2, 0, "https://www.youtube.com/watch?v=$V1", "Folding paper, scissors & stone", "VIDEO_STREAM", 263, 99, null)
        stream(3, 0, "https://www.youtube.com/watch?v=$V2", "A live build", "LIVE_STREAM", 0, null, null)
        stream(4, 3, "https://framatube.example/videos/watch/6U8A1AZ8nEkzgZXwf2kdW7", "A PeerTube video", "VIDEO_STREAM", 120, 5, 1_703_000_000_000)
        stream(5, 0, "https://www.youtube.com/watch?v=$V3", "Only in a playlist", "VIDEO_STREAM", 90, 7, null)
        stream(6, 0, "https://www.youtube.com/watch?v=$V4", "Seen long ago", "VIDEO_STREAM", 45, 8, null)

        execute("INSERT INTO stream_history VALUES (1, 1760000000000, 1)")
        execute("INSERT INTO stream_history VALUES (1, 1760100000000, 2)")
        execute("INSERT INTO stream_history VALUES (2, 1759000000000, 0)")
        execute("INSERT INTO stream_history VALUES (3, 1758000000000, 1)")
        execute("INSERT INTO stream_history VALUES (4, 1757000000000, 1)")
        execute("INSERT INTO stream_history VALUES (6, 1750000000000, 1)")
        execute("INSERT INTO stream_state VALUES (1, 125000)")
        execute("INSERT INTO stream_state VALUES (4, 5000)")

        execute("INSERT INTO playlists (uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index) VALUES (1, 'Watch these', 0, 1, 1)")
        execute("INSERT INTO playlists (uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index) VALUES (2, 'Empty one', 0, -1, 0)")
        execute("INSERT INTO playlists (uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index) VALUES (3, NULL, 0, -1, 2)")
        // joined out of order on purpose: the join_index decides the order; the PeerTube stream is skipped
        execute("INSERT INTO playlist_stream_join VALUES (1, 2, 2)")
        execute("INSERT INTO playlist_stream_join VALUES (1, 1, 0)")
        execute("INSERT INTO playlist_stream_join VALUES (1, 5, 1)")
        execute("INSERT INTO playlist_stream_join VALUES (1, 4, 3)")

        execute("INSERT INTO remote_playlists (service_id, name, url, thumbnail_url, uploader, display_index, stream_count) VALUES (0, 'Workshop tips', 'https://www.youtube.com/playlist?list=PLfixtureotherplaylist00000000000', 'https://images.example/p.jpg', 'Northern Lights Workshop', 0, 12)")
        execute("INSERT INTO remote_playlists (service_id, name, url, thumbnail_url, uploader, display_index, stream_count) VALUES (3, 'A PeerTube playlist', 'https://framatube.example/video-playlists/abc', NULL, 'Someone', 1, 3)")

        execute("INSERT INTO search_history (creation_date, service_id, search) VALUES (1760000000000, 0, 'wood planes')")
        execute("INSERT INTO search_history (creation_date, service_id, search) VALUES (1760200000000, 0, 'Wood Planes')")
        execute("INSERT INTO search_history (creation_date, service_id, search) VALUES (1750000000000, 0, 'chisels')")
        execute("INSERT INTO search_history (creation_date, service_id, search) VALUES (1740000000000, 3, 'peertube thing')")
        execute("INSERT INTO search_history (creation_date, service_id, search) VALUES (1730000000000, 0, '   ')")
    }
}
