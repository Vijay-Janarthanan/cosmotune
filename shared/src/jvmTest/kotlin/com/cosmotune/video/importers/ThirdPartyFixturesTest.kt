package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.NOW
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The importers on the files the upstream projects use in their own tests (NewPipe's `import_export_test.json`,
 * `db_ser_json.zip` and `nodb_noser_json.zip`; NewPipeExtractor's `youtube_takeout_import_test*`), pinned with the counts found
 * by opening them once. Those files are GPLv3 and are not part of this task's commit (the permission to copy them in was refused
 * while the task ran). The test finds them in `src/jvmTest/resources/video/importers/` (copied there by hand) or in the folders
 * listed in the environment variable `VIDEO_IMPORT_FIXTURES` (separated like a class path), for example the folders of a NewPipe
 * and a NewPipeExtractor checkout; until it finds a file, each test that needs it is skipped (not passed).
 */
class ThirdPartyFixturesTest {
    private val work: File = Files.createTempDirectory("third-party-import-test").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray? {
        ImportFixtures::class.java.getResourceAsStream("/video/importers/$name")?.use { return it.readBytes() }
        val folders = System.getenv("VIDEO_IMPORT_FIXTURES").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        return folders.map { File(it, name) }.firstOrNull { it.isFile }?.readBytes()
    }

    private fun require(name: String): ByteArray {
        val bytes = fixture(name)
        assumeTrue("$name has not been copied into the importer fixtures", bytes != null)
        return bytes!!
    }

    @Test
    fun newPipesOwnSubscriptionsExport() {
        val preview = NewPipeSubscriptionsImporter.parse(require("import_export_test.json").decodeToString(), NOW)
        assertEquals(
            listOf("UCsXVk37bltHxD1rDPwtNM8Q", "UCEOXxzW2vU0P-0THehuIIeg", "UCAuUUnT6oDeKwE6v1NGQxug", "UCfIXdjDQH9Fau7y99_Orpjw", "UCJ0-OtVpF0wOKEqT2Z1HEtA"),
            preview.subscriptions.map { it.channelId },
        )
        assertEquals(3, preview.skipped, "the same channel four times, under four names")
    }

    @Test
    fun newPipesOwnBackupWithADatabase() {
        val zip = ImportFixtures.RecordingZip(ZipInput(require("db_ser_json.zip")))
        val opener = TempFileSqliteOpener(File(work, "cache")) { ImportFixtures.JdbcTestRows(it) }
        val preview = NewPipeDatabaseImporter.parseBackup(zip, opener, NOW)
        assertEquals(listOf("newpipe.db"), zip.reads, "newpipe.settings is in this archive, and is not touched")
        assertEquals(0, preview.subscriptions.size)
        assertEquals(listOf("Ujb0PdUuxxM", "TyUA1OmXMXA", "z1h5gWuxX_Q", "UXqq0ZvbOnk", "mKdjycj-7eE"), preview.history.map { it.item.id })
        val charge = preview.history.single { it.item.id == "UXqq0ZvbOnk" }
        assertEquals(139_192L, charge.positionMs)
        assertEquals(263_000L, charge.durationMs)
        assertEquals("CHARGE - Blender Open Movie", charge.item.title)
        assertEquals(listOf("the friday checkouty", "tech altar", "techaltar", "mrbeast lamborghini"), preview.searches)
        assertEquals(0, preview.playlists.size + preview.savedPlaylists.size)
        assertEquals(2, preview.skipped, "the two PeerTube videos of the history")
    }

    @Test
    fun newPipesOwnBackupWithoutADatabase() {
        val zip = ImportFixtures.RecordingZip(ZipInput(require("nodb_noser_json.zip")))
        val preview = NewPipeDatabaseImporter.parseBackup(zip, TempFileSqliteOpener(File(work, "cache")) { ImportFixtures.JdbcTestRows(it) }, NOW)
        assertEquals(ImportReason.NO_DATABASE, preview.reason)
        assertEquals(emptyList(), zip.reads)
    }

    @Test
    fun theExtractorsTakeoutSubscriptionFiles() {
        val csv = TakeoutSubscriptionsImporter.parseCsv(require("youtube_takeout_import_test_1.csv").decodeToString(), NOW)
        assertEquals(listOf("Raycevick", "Karl Jobst", "Digital Foundry", "EventStatus", "videogamedunkey"), csv.subscriptions.map { it.name })

        val english = TakeoutSubscriptionsImporter.parseZip(ZipInput(require("youtube_takeout_import_test_1.zip")), NOW)
        assertEquals(csv.subscriptions.map { it.channelId }, english.subscriptions.map { it.channelId })

        val spanish = TakeoutSubscriptionsImporter.parseZip(ZipInput(require("youtube_takeout_import_test_2.zip")), NOW)
        assertEquals(172, spanish.subscriptions.size, "a Takeout in Spanish: the folder and the header are in Spanish")
        assertEquals("UC-UcKEWOSpz0AfY33TrYw3g", spanish.subscriptions.first().channelId)

        val json = TakeoutSubscriptionsImporter.parseJson(require("youtube_takeout_import_test.json").decodeToString(), NOW)
        assertEquals(4, json.subscriptions.size, "Gorillaz is in the file four times")
        assertEquals(3, json.skipped)
        assertEquals("UCfIXdjDQH9Fau7y99_Orpjw", json.subscriptions.first().channelId, "snippet.resourceId.channelId, not the account's own channel")
    }

    @Test
    fun theDetectorKnowsThemAll() {
        fun detect(name: String, file: String = name) = ImportDetector.detect(file, require(name).let { it.copyOf(minOf(it.size, ImportDetector.HEAD_BYTES)) })
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, detect("import_export_test.json"))
        assertEquals(ImportFormat.NEWPIPE_DATABASE, detect("db_ser_json.zip"))
        assertEquals(ImportFormat.NEWPIPE_DATABASE, detect("nodb_noser_json.zip"))
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV, detect("youtube_takeout_import_test_1.csv"))
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("youtube_takeout_import_test_1.zip"))
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("youtube_takeout_import_test_2.zip"))
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_JSON, detect("youtube_takeout_import_test.json"))
    }
}
