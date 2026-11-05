package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.C2
import com.cosmotune.video.importers.ImportFixtures.C3
import com.cosmotune.video.importers.ImportFixtures.NOW
import com.cosmotune.video.importers.ImportFixtures.V0
import com.cosmotune.video.importers.ImportFixtures.V1
import com.cosmotune.video.importers.ImportFixtures.V2
import com.cosmotune.video.importers.ImportFixtures.V3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoutArchiveImporterTest {
    private val folder = "Takeout/YouTube and YouTube Music"

    private val archive = ImportFixtures.zip(
        "Takeout/archive_browser.html" to "<html></html>".encodeToByteArray(),
        "$folder/subscriptions/subscriptions.csv" to ImportFixtures.bytes("takeout-subscriptions.csv"),
        "$folder/history/watch-history.json" to ImportFixtures.bytes("watch-history.json"),
        "$folder/history/search-history.json" to """[{"header":"YouTube","title":"Searched for wood","titleUrl":"https://www.youtube.com/results?search_query=wood","time":"2026-10-07T08:00:00Z"}]""".encodeToByteArray(),
        "$folder/playlists/playlists.csv" to ImportFixtures.bytes("playlists-index.csv"),
        "$folder/playlists/Road trip-videos.csv" to ImportFixtures.bytes("playlist-current.csv"),
        "$folder/playlists/Old list-videos.csv" to ImportFixtures.bytes("playlist-old.csv"),
        "$folder/music/library-songs.csv" to "Song ID,Title\r\nabc,Hum\r\n".encodeToByteArray(),
        "__MACOSX/$folder/subscriptions/._subscriptions.csv" to ByteArray(4),
    )

    @Test
    fun readsTheChannelsTheHistoryAndThePlaylistsInOneStep() {
        val preview = TakeoutArchiveImporter.parse(ZipInput(archive), NOW)
        assertEquals(listOf(C0, C1, C2, C3), preview.subscriptions.map { it.channelId })
        assertEquals(listOf(V0, V1, V2, V3, ImportFixtures.V4, ImportFixtures.V7), preview.history.map { it.item.id })
        assertEquals(listOf("Road trip", "Fixture, the old way"), preview.playlists.map { it.name })
        // the 4 of the history file, the search file (1) and the repeated video of the playlist (1)
        assertEquals(4 + 1 + 1, preview.skipped)
        assertNull(preview.reason)
    }

    @Test
    fun theIndexInsideTheArchiveGivesThePlaylistItsId() {
        val preview = TakeoutArchiveImporter.parse(ZipInput(archive), NOW)
        assertEquals("takeout-PLfixturecurrentplaylist0000000000", preview.playlists.first().id)
    }

    @Test
    fun anArchiveWithOnlyHistoryStillImportsIt() {
        val zip = ImportFixtures.zip("$folder/history/watch-history.json" to ImportFixtures.bytes("watch-history.json"))
        val preview = TakeoutArchiveImporter.parse(ZipInput(zip), NOW)
        assertTrue(preview.subscriptions.isEmpty() && preview.playlists.isEmpty())
        assertEquals(6, preview.history.size)
        assertNull(preview.reason)
    }

    @Test
    fun anArchiveWithNothingToImportSaysSo() {
        val zip = ImportFixtures.zip("Takeout/archive_browser.html" to ByteArray(0), "$folder/music/library-songs.csv" to "Song ID,Title\r\nabc,Hum\r\n".encodeToByteArray())
        val preview = TakeoutArchiveImporter.parse(ZipInput(zip), NOW)
        assertTrue(preview.isEmpty)
        assertEquals(ImportReason.NOTHING_FOUND, preview.reason)
        assertEquals(ImportReason.NOTHING_FOUND, TakeoutArchiveImporter.parse(ZipInput(ByteArray(0)), NOW).reason)
    }

    @Test
    fun nothingThatIsNotYouTubeDataIsReadAsAPlaylist() {
        // the subscriptions file and a CSV of another product are not playlists
        val preview = TakeoutArchiveImporter.parse(ZipInput(archive), NOW)
        assertEquals(2, preview.playlists.size)
    }
}
