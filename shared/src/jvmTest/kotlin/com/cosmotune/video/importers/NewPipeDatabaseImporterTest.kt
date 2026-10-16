package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.NOW
import com.cosmotune.video.importers.ImportFixtures.V0
import com.cosmotune.video.importers.ImportFixtures.V1
import com.cosmotune.video.importers.ImportFixtures.V2
import com.cosmotune.video.importers.ImportFixtures.V3
import com.cosmotune.video.importers.ImportFixtures.V4
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewPipeDatabaseImporterTest {
    private val work: File = Files.createTempDirectory("newpipe-import-test").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private val opener = TempFileSqliteOpener(File(work, "cache")) { ImportFixtures.JdbcTestRows(it) }

    private fun backup(vararg extra: Pair<String, ByteArray>, database: ByteArray = ImportFixtures.newPipeDatabase(work)): ByteArray =
        ImportFixtures.zip("newpipe.db" to database, "newpipe.settings" to "MUST NEVER BE READ".encodeToByteArray(), "preferences.json" to "{}".encodeToByteArray(), *extra)

    private val preview by lazy { NewPipeDatabaseImporter.parseBackup(ImportFixtures.RecordingZip(ZipInput(backup())), opener, NOW) }

    @Test
    fun readsTheSubscriptionsOfService0AndLeavesUserAddressesToBeResolved() {
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.channelId })
        assertEquals(listOf("Northern Lights Workshop", "Paper, Scissors & Stone"), preview.subscriptions.map { it.name })
        assertEquals(listOf("https://images.example/c0.jpg", "https://images.example/c1.jpg"), preview.subscriptions.map { it.avatarUrl })
        assertTrue(preview.subscriptions.all { it.subscribedAtMs == NOW })
        assertEquals(listOf("https://www.youtube.com/user/OldTimersTV"), preview.unresolvedChannels)
    }

    @Test
    fun historyIsTheNewestAccessPerStreamWithItsCountPositionAndDuration() {
        assertEquals(listOf(V0, V1, V2, V4), preview.history.map { it.item.id }, "newest first; the stream of another service is left out")
        val first = preview.history[0]
        assertEquals(1_760_100_000_000L, first.lastWatchedMs, "the newer of the two rows")
        assertEquals(3, first.watchCount, "the repeat counts of the rows added: 1 + 2")
        assertEquals(125_000L, first.positionMs, "stream_state.progress_time")
        assertEquals(600_000L, first.durationMs, "duration is in seconds in the database")
        assertEquals(600L, first.item.durationSec)
        assertEquals("How to plane a board by hand", first.item.title)
        assertEquals(C0, first.item.channelId)
        assertEquals("Northern Lights Workshop", first.item.channelName)
        assertEquals("https://images.example/s1.jpg", first.item.thumbnailUrl)
        assertEquals(1200L, first.item.viewCount)
        assertEquals(1_700_000_000_000L, first.item.uploadedAtMs)
        assertNull(first.item.uploadedText)

        val second = preview.history[1]
        assertEquals(1, second.watchCount, "a repeat count of 0 counts as 1")
        assertEquals(0L, second.positionMs, "no stream_state row: not started")
        assertEquals(263_000L, second.durationMs)
        assertNull(second.item.uploadedAtMs)
        assertEquals("a while ago", second.item.uploadedText, "the source's own text when the row has no time")

        val live = preview.history[2]
        assertTrue(live.item.isLive)
        assertNull(live.item.durationSec)
        assertEquals(0L, live.durationMs)
    }

    @Test
    fun playlistsKeepTheirOrderByDisplayIndexAndTheirVideosByJoinIndex() {
        assertEquals(listOf("Empty one", "Watch these", "Playlist 3"), preview.playlists.map { it.name }, "a playlist without a name is numbered")
        assertEquals(listOf("newpipe-2", "newpipe-1", "newpipe-3"), preview.playlists.map { it.id })
        val watchThese = preview.playlists[1]
        assertEquals(listOf(V0, V3, V1), watchThese.items.map { it.id }, "join_index order; the stream of another service is skipped")
        assertTrue(preview.playlists[0].items.isEmpty())
        assertTrue(preview.playlists.all { it.createdMs == NOW && it.updatedMs == NOW })
    }

    @Test
    fun savedPlaylistsAreTheRemotePlaylistsOfService0() {
        val saved = preview.savedPlaylists.single()
        assertEquals("PLfixtureotherplaylist00000000000", saved.id)
        assertEquals("Workshop tips", saved.title)
        assertEquals("https://images.example/p.jpg", saved.thumbnailUrl)
        assertEquals("Northern Lights Workshop", saved.ownerName)
        assertEquals(12L, saved.videoCount)
    }

    @Test
    fun searchesAreNewestFirstWithoutRepeatsOrOtherServices() {
        assertEquals(listOf("Wood Planes", "chisels"), preview.searches)
    }

    @Test
    fun everythingThatCouldNotBeTakenIsCountedOnce() {
        // PeerTube: a channel, a history stream, a playlist stream, a saved playlist and a search; plus a repeated search and a blank one
        assertEquals(7, preview.skipped)
        assertNull(preview.reason)
    }

    @Test
    fun onlyTheDatabaseEntryIsEverRead() {
        val zip = ImportFixtures.RecordingZip(ZipInput(backup()))
        NewPipeDatabaseImporter.parseBackup(zip, opener, NOW)
        assertEquals(listOf("newpipe.db"), zip.reads, "newpipe.settings is Java-serialised and is never opened; preferences.json is of no use")
        assertEquals(setOf("newpipe.db", "newpipe.settings", "preferences.json"), zip.names().toSet(), "the archive does hold them")
    }

    @Test
    fun theTemporaryDatabaseIsDeletedWhenTheImportIsDone() {
        NewPipeDatabaseImporter.parseBackup(ZipInput(backup()), opener, NOW)
        assertEquals(emptyList(), File(work, "cache").listFiles()?.toList().orEmpty())
    }

    @Test
    fun aBackupWithoutTheDatabaseGivesAnEmptyPreviewWithAReason() {
        val zip = ImportFixtures.RecordingZip(ZipInput(ImportFixtures.zip("preferences.json" to "{}".encodeToByteArray(), "newpipe.settings" to ByteArray(4))))
        val result = NewPipeDatabaseImporter.parseBackup(zip, opener, NOW)
        assertTrue(result.isEmpty)
        assertEquals(ImportReason.NO_DATABASE, result.reason)
        assertEquals(emptyList(), zip.reads, "nothing is read from such a ZIP")
        assertEquals(ImportReason.NO_DATABASE, NewPipeDatabaseImporter.parseBackup(ZipInput(ByteArray(0)), opener, NOW).reason)
    }

    @Test
    fun aBackupZippedAgainInAFolderIsStillFoundButOnlyByTheExactName() {
        val inFolder = ImportFixtures.RecordingZip(
            ZipInput(ImportFixtures.zip("NewPipeData/newpipe.db" to ImportFixtures.newPipeDatabase(work), "NewPipeData/newpipe.settings" to ByteArray(3))),
        )
        val result = NewPipeDatabaseImporter.parseBackup(inFolder, opener, NOW)
        assertEquals(4, result.history.size)
        assertEquals(listOf("NewPipeData/newpipe.db"), inFolder.reads)

        val lookalike = ImportFixtures.zip("other/mynewpipe.db" to ByteArray(3), "newpipe.db.bak" to ByteArray(3))
        assertEquals(ImportReason.NO_DATABASE, NewPipeDatabaseImporter.parseBackup(ZipInput(lookalike), opener, NOW).reason)
    }

    @Test
    fun aFileThatIsNotADatabaseGivesAReasonAndIsStillCleanedUp() {
        val result = NewPipeDatabaseImporter.parseBackup(ZipInput(ImportFixtures.zip("newpipe.db" to "this is not a database".encodeToByteArray())), opener, NOW)
        assertEquals(ImportReason.DATABASE_UNREADABLE, result.reason)
        assertEquals(emptyList(), File(work, "cache").listFiles()?.toList().orEmpty())

        val refusing = TempFileSqliteOpener(File(work, "cache")) { error("cannot open") }
        assertEquals(ImportReason.DATABASE_UNREADABLE, NewPipeDatabaseImporter.parseBackup(ZipInput(backup()), refusing, NOW).reason)
        assertEquals(emptyList(), File(work, "cache").listFiles()?.toList().orEmpty(), "a failed open leaves no file behind")
    }

    @Test
    fun aBackupFromAnOlderVersionLosesOnlyThePartsItLacks() {
        val old = ImportFixtures.newPipeDatabase(work) {
            execute("DROP TABLE stream_state")
            execute("DROP TABLE remote_playlists")
            execute("INSERT INTO streams VALUES (1, 0, 'https://www.youtube.com/watch?v=$V0', 'Old video', 'VIDEO_STREAM', 60, 'Someone', NULL, NULL, NULL, NULL, NULL, NULL)")
            execute("INSERT INTO stream_history VALUES (1, 1700000000000, 1)")
        }
        val result = NewPipeDatabaseImporter.parseBackup(ZipInput(backup(database = old)), opener, NOW)
        val entry = result.history.single()
        assertEquals(V0, entry.item.id)
        assertEquals(0L, entry.positionMs)
        assertEquals(60_000L, entry.durationMs)
        assertNull(entry.item.channelId)
        assertEquals("https://i.ytimg.com/vi/$V0/mqdefault.jpg", entry.item.thumbnailUrl, "no thumbnail in the row: the id's picture")
        assertTrue(result.savedPlaylists.isEmpty())
        assertNull(result.reason)
    }

    @Test
    fun parseReadsOpenRowsWithoutClosingThem() {
        val file = File(work, "direct.db").also { it.writeBytes(ImportFixtures.newPipeDatabase(work)) }
        val rows = ImportFixtures.JdbcTestRows(file.path)
        val result = NewPipeDatabaseImporter.parse(rows, NOW)
        assertEquals(4, result.history.size)
        assertTrue(!rows.closed, "closing is the caller's job")
        rows.close()
    }

    @Test
    fun rowsThatThrowForEveryQueryAreNotADatabase() {
        val rows = object : SqliteRows {
            override fun query(sql: String): List<Map<String, Any?>> = error("no such table")
            override fun close() = Unit
        }
        assertEquals(ImportReason.DATABASE_UNREADABLE, NewPipeDatabaseImporter.parse(rows, NOW).reason)
    }

    @Test
    fun anEmptyDatabaseIsEmptyWithAReason() {
        val bytes = ImportFixtures.newPipeDatabase(work) { }
        val result = NewPipeDatabaseImporter.parseBackup(ZipInput(backup(database = bytes)), opener, NOW)
        assertTrue(result.isEmpty)
        assertEquals(ImportReason.NOTHING_FOUND, result.reason)
    }

    @Test
    fun theOpenerRefusesToHideAnErrorFromARowsThatCannotBeOpened() {
        assertFailsWith<IllegalStateException> { TempFileSqliteOpener(File(work, "cache2")) { error("boom") }.open(ByteArray(1)) }
        assertEquals(emptyList(), File(work, "cache2").listFiles()?.toList().orEmpty())
    }
}
