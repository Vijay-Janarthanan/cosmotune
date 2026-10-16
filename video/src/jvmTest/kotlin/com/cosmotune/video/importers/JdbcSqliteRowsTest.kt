package com.cosmotune.video.importers

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop reader of the NewPipe database, on databases the test writes itself (no network, nothing outside the temp folder). */
class JdbcSqliteRowsTest {
    private val work: File = Files.createTempDirectory("jdbc-sqlite-rows-test").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun database(vararg statements: String): String {
        val file = File(work, "test-${System.nanoTime()}.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
        return file.path
    }

    @Test
    fun readsEveryTypeTheWayTheInterfaceSays() {
        val path = database(
            "CREATE TABLE t (i INTEGER, r REAL, s TEXT, b BLOB, n TEXT)",
            "INSERT INTO t VALUES (7, 2.5, 'text ä中', x'0102ff', NULL)",
            "INSERT INTO t VALUES (9000000000, 0.0, '', x'', NULL)",
        )
        val rows = JdbcSqliteRows(path)
        try {
            val result = rows.query("SELECT i, r, s, b, n, i + 1 AS next FROM t ORDER BY i")
            assertEquals(2, result.size)
            val first = result[0]
            assertEquals(7L, first["i"], "integers are Long, even the small ones")
            assertEquals(2.5, first["r"])
            assertEquals("text ä中", first["s"])
            assertContentEquals(byteArrayOf(1, 2, -1), first["b"] as ByteArray)
            assertTrue(first.containsKey("n") && first["n"] == null)
            assertEquals(8L, first["next"], "columns are named by their label")
            assertEquals(9_000_000_000L, result[1]["i"])
            assertEquals(listOf("i", "r", "s", "b", "n", "next"), first.keys.toList(), "in column order")
        } finally {
            rows.close()
        }
    }

    @Test
    fun anEmptyResultIsAnEmptyList() {
        val rows = JdbcSqliteRows(database("CREATE TABLE t (i INTEGER)"))
        try {
            assertEquals(emptyList(), rows.query("SELECT * FROM t"))
        } finally {
            rows.close()
        }
    }

    @Test
    fun aMissingTableThrowsSoTheImporterCanSkipThatPart() {
        val rows = JdbcSqliteRows(database("CREATE TABLE t (i INTEGER)"))
        try {
            assertFailsWith<Exception> { rows.query("SELECT * FROM nope") }
            assertEquals(emptyList(), rows.query("SELECT * FROM t"), "the connection still works")
        } finally {
            rows.close()
        }
    }

    @Test
    fun theFileIsOpenedReadOnly() {
        val path = database("CREATE TABLE t (i INTEGER)", "INSERT INTO t VALUES (1)")
        val rows = JdbcSqliteRows(path)
        try {
            assertFailsWith<Exception> { rows.query("INSERT INTO t VALUES (2)") }
            assertFailsWith<Exception> { rows.query("DROP TABLE t") }
            assertEquals(1, rows.query("SELECT * FROM t").size)
        } finally {
            rows.close()
        }
    }

    @Test
    fun aMissingFileIsAnErrorAndIsNotCreated() {
        val path = File(work, "missing.db").path
        assertFailsWith<Exception> {
            val rows = JdbcSqliteRows(path)
            try {
                rows.query("SELECT 1")
            } finally {
                rows.close()
            }
        }
        assertFalse(File(path).exists(), "opening for reading never makes an empty database")
    }

    @Test
    fun closingReleasesTheFileSoItCanBeDeleted() {
        val path = database("CREATE TABLE t (i INTEGER)")
        val rows = JdbcSqliteRows(path)
        rows.query("SELECT * FROM t")
        rows.close()
        assertTrue(File(path).delete())
        assertFailsWith<Exception> { rows.query("SELECT * FROM t") }
    }

    @Test
    fun readsWhatTheNewPipeImporterAsksFor() {
        val path = database(
            "CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, service_id INTEGER NOT NULL, url TEXT, name TEXT, avatar_url TEXT)",
            "CREATE TABLE streams (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, service_id INTEGER NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, stream_type TEXT NOT NULL, duration INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_url TEXT, thumbnail_url TEXT, view_count INTEGER, textual_upload_date TEXT, upload_date INTEGER)",
            "CREATE TABLE stream_history (stream_id INTEGER NOT NULL, access_date INTEGER NOT NULL, repeat_count INTEGER NOT NULL, PRIMARY KEY(stream_id, access_date))",
            "CREATE TABLE stream_state (stream_id INTEGER NOT NULL, progress_time INTEGER NOT NULL, PRIMARY KEY(stream_id))",
            "INSERT INTO subscriptions (service_id, url, name, avatar_url) VALUES (0, 'https://www.youtube.com/channel/UCDVWqyfkrFAZd6_6X-yZl_D', 'Northern Lights Workshop', NULL)",
            "INSERT INTO streams (uid, service_id, url, title, stream_type, duration, uploader) VALUES (1, 0, 'https://www.youtube.com/watch?v=PMTLnz_VWNA', 'A video', 'VIDEO_STREAM', 600, 'Someone')",
            "INSERT INTO stream_history VALUES (1, 1760000000000, 2)",
            "INSERT INTO stream_state VALUES (1, 125000)",
        )
        val rows = JdbcSqliteRows(path)
        val preview = try {
            NewPipeDatabaseImporter.parse(rows, 1_792_152_000_000L)
        } finally {
            rows.close()
        }
        assertEquals(listOf("UCDVWqyfkrFAZd6_6X-yZl_D"), preview.subscriptions.map { it.channelId })
        val entry = preview.history.single()
        assertEquals("PMTLnz_VWNA", entry.item.id)
        assertEquals(125_000L, entry.positionMs)
        assertEquals(600_000L, entry.durationMs)
        assertEquals(2, entry.watchCount)
        assertNull(preview.reason)
    }

    @Test
    fun worksThroughTheTempFileOpenerAsTheImportScreenUsesIt() {
        val path = database("CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY, service_id INTEGER NOT NULL, url TEXT, name TEXT)", "INSERT INTO subscriptions VALUES (1, 0, 'https://www.youtube.com/user/OldTimersTV', 'Old')")
        val zip = ZipInput(zipOf("newpipe.db" to File(path).readBytes(), "newpipe.settings" to byteArrayOf(1, 2, 3)))
        val cache = File(work, "cache")
        val preview = NewPipeDatabaseImporter.parseBackup(zip, TempFileSqliteOpener(cache, ::JdbcSqliteRows), 1L)
        assertEquals(listOf("https://www.youtube.com/user/OldTimersTV"), preview.unresolvedChannels)
        assertEquals(emptyList(), cache.listFiles()?.toList().orEmpty(), "the temporary copy is gone")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
