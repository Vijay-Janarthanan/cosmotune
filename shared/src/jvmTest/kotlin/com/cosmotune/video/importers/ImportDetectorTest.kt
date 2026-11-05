package com.cosmotune.video.importers

import com.cosmotune.video.library.FakeVideoLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportDetectorTest {
    private fun detect(fileName: String, bytes: ByteArray): ImportFormat =
        ImportDetector.detect(fileName, bytes.copyOf(minOf(bytes.size, ImportDetector.HEAD_BYTES)))

    private fun detectFixture(name: String, fileName: String = name): ImportFormat = detect(fileName, ImportFixtures.bytes(name))

    @Test
    fun recognisesNewPipeSubscriptions() {
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, detectFixture("newpipe-subscriptions.json"))
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, detectFixture("newpipe-subscriptions-user.json"))
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, detect("subs.json", """{"subscriptions": []}""".encodeToByteArray()))
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, detect("subs.json", "\uFEFF{\"app_version\":\"1\"}".encodeToByteArray()))
    }

    @Test
    fun recognisesTheNewPipeBackupByItsFirstEntry() {
        val withDatabase = ImportFixtures.zip("newpipe.db" to ByteArray(2048) { 1 }, "newpipe.settings" to ByteArray(10), "preferences.json" to "{}".encodeToByteArray())
        assertEquals(ImportFormat.NEWPIPE_DATABASE, detect("NewPipeData-20261016_120000.zip", withDatabase))
        val inFolder = ImportFixtures.zip("NewPipeData/newpipe.db" to ByteArray(512))
        assertEquals(ImportFormat.NEWPIPE_DATABASE, detect("NewPipeData.zip", inFolder), "zipped again with its folder")
        val withoutDatabase = ImportFixtures.zip("preferences.json" to "{}".encodeToByteArray())
        assertEquals(ImportFormat.NEWPIPE_DATABASE, detect("backup.zip", withoutDatabase), "a backup without the database is still a backup: the importer says why it is empty")
    }

    @Test
    fun recognisesAGoogleTakeoutArchive() {
        val english = ImportFixtures.zip("Takeout/archive_browser.html" to ByteArray(0), "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to ByteArray(3))
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("takeout-20261016T120000Z-001.zip", english))
        val spanish = ImportFixtures.zip("Takeout/YouTube y YouTube Music/suscripciones/suscripciones.csv" to ImportFixtures.bytes("takeout-subscriptions-es.csv"))
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("archive.zip", spanish))
        val rezipped = ImportFixtures.zip("YouTube and YouTube Music/subscriptions/subscriptions.csv" to ByteArray(3))
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("mine.zip", rezipped), "a folder zipped again keeps its YouTube folder")
        assertEquals(ImportFormat.TAKEOUT_ZIP, detect("takeout.zip", ImportFixtures.zip("data/x.csv" to ByteArray(3))), "the file name says Takeout")
    }

    @Test
    fun anyOtherZipIsUnknown() {
        assertEquals(ImportFormat.UNKNOWN, detect("photos.zip", ImportFixtures.zip("readme.txt" to ByteArray(3))))
        assertEquals(ImportFormat.UNKNOWN, detect("short.zip", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
    }

    @Test
    fun recognisesTheTakeoutJsonFiles() {
        assertEquals(ImportFormat.TAKEOUT_HISTORY_JSON, detectFixture("watch-history.json"))
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_JSON, detectFixture("takeout-subscriptions-old.json"))
        assertEquals(ImportFormat.UNKNOWN, detect("x.json", "[]".encodeToByteArray()))
    }

    @Test
    fun recognisesTheTakeoutCsvFiles() {
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV, detectFixture("takeout-subscriptions.csv"))
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV, detectFixture("takeout-subscriptions-es.csv"), "a header in another language, a byte order mark")
        assertEquals(ImportFormat.TAKEOUT_PLAYLIST_CSV, detectFixture("playlist-current.csv", "Road trip-videos.csv"))
        assertEquals(ImportFormat.TAKEOUT_PLAYLIST_CSV, detectFixture("playlist-old.csv"))
    }

    @Test
    fun recognisesOpml() {
        assertEquals(ImportFormat.OPML, detectFixture("freetube-subscriptions.opml"))
        assertEquals(ImportFormat.OPML, detect("subs.xml", "  <OPML version=\"2.0\"><body/></OPML>".encodeToByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detect("page.html", "<html><body>hello</body></html>".encodeToByteArray()))
    }

    @Test
    fun recognisesTheCosmotuneExportOfTheLibrary() {
        assertEquals(ImportFormat.COSMOTUNE_EXPORT, detect("cosmotune-video-2026-10-16.json", FakeVideoLibrary.withSamples().exportJson().encodeToByteArray()))
    }

    @Test
    fun whatItDoesNotKnowIsUnknown() {
        assertEquals(ImportFormat.UNKNOWN, detect("empty.csv", ByteArray(0)))
        assertEquals(ImportFormat.UNKNOWN, detect("note.txt", "just some words\r\non two lines\r\n".encodeToByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detect("picture.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2, 3)))
        assertEquals(ImportFormat.UNKNOWN, detect("x.json", """{"watchHistory": [], "subscriptions": [{"channelId": "UC"}]}""".encodeToByteArray()), "another app's backup")
        assertEquals(ImportFormat.UNKNOWN, detectFixture("playlists-index.csv"), "the index alone holds no videos")
        assertEquals(ImportFormat.UNKNOWN, detect("raw.db", "SQLite format 3\u0000 and more".encodeToByteArray()), "only the backup ZIP is read")
    }

    @Test
    fun aHeadCutInTheMiddleOfAFileStillDecides() {
        val bytes = ImportFixtures.bytes("watch-history.json")
        assertEquals(ImportFormat.TAKEOUT_HISTORY_JSON, ImportDetector.detect("watch-history.json", bytes.copyOf(300)))
    }
}
