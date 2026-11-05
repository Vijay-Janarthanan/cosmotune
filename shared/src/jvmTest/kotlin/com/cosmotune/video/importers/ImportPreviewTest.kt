package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.C2
import com.cosmotune.video.importers.ImportFixtures.C3
import com.cosmotune.video.importers.ImportFixtures.NOW
import com.cosmotune.video.importers.ImportFixtures.V0
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.ImportReport
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportPreviewTest {
    @Test
    fun toLibraryImportCarriesEverythingButWatchLaterAndLiked() {
        val subscriptions = NewPipeSubscriptionsImporter.parse(ImportFixtures.text("newpipe-subscriptions.json"), NOW)
        val history = TakeoutHistoryImporter.parse(ImportFixtures.text("watch-history.json"))
        val playlist = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW)
        val all = subscriptions + history + playlist

        val document = all.toLibraryImport()
        assertEquals(subscriptions.subscriptions, document.subscriptions)
        assertEquals(history.history, document.history)
        assertEquals(playlist.playlists, document.playlists)
        assertTrue(document.watchLater.isEmpty() && document.liked.isEmpty() && document.savedPlaylists.isEmpty() && document.searches.isEmpty())
    }

    @Test
    fun plusAddsTheListsAndTheSkippedCounts() {
        val a = ImportPreview(skipped = 2, searches = listOf("a"), reason = ImportReason.NOTHING_FOUND)
        val b = ImportPreview(skipped = 3, searches = listOf("b"), unresolvedChannels = listOf("https://x/@y"))
        val sum = a + b
        assertEquals(5, sum.skipped)
        assertEquals(listOf("a", "b"), sum.searches)
        assertEquals(listOf("https://x/@y"), sum.unresolvedChannels)
        assertEquals(ImportReason.NOTHING_FOUND, sum.reason)
        assertNull((b + b).reason)
    }

    @Test
    fun isEmptyCountsChannelsStillToBeLookedUp() {
        assertTrue(ImportPreview().isEmpty)
        assertTrue(!ImportPreview(unresolvedChannels = listOf("https://x/@y")).isEmpty)
        assertTrue(!ImportPreview(searches = listOf("a")).isEmpty)
    }

    @Test
    fun theLibraryTakesWhatAnImportedPreviewCarriesAndKeepsWhatItHas() {
        val library = FakeVideoLibrary { NOW }
        library.recordWatch(
            VideoItem(V0, "Already watched here", null, null, null, null, 600, null, null, null),
            positionMs = 300_000,
            durationMs = 600_000,
        )
        val before = library.history.value.single()

        val preview = NewPipeSubscriptionsImporter.parse(ImportFixtures.text("newpipe-subscriptions.json"), NOW) +
            TakeoutHistoryImporter.parse(ImportFixtures.text("watch-history.json")) +
            TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW)
        val report = library.importDocument(preview.toLibraryImport())

        assertEquals(ImportReport(subscriptions = 4, history = 5, playlists = 1, skipped = 0), report, "five histories are new or newer; the one the library already had more recently stays")
        assertEquals(setOf(C0, C1, C2, C3), library.subscriptions.value.map { it.channelId }.toSet())
        assertEquals("Road trip", library.playlists.value.single().name)
        assertEquals(4, library.playlists.value.single().items.size)
        assertEquals(6, library.history.value.size, "the video the library already had is one of the six")
        val merged = library.history.value.first { it.item.id == V0 }
        assertTrue(merged.lastWatchedMs >= before.lastWatchedMs)
    }

    @Test
    fun importingTheSameFileTwiceChangesNothingTheSecondTime() {
        val library = FakeVideoLibrary { NOW }
        val preview = NewPipeSubscriptionsImporter.parse(ImportFixtures.text("newpipe-subscriptions.json"), NOW) +
            TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW)
        library.importDocument(preview.toLibraryImport())
        val subscriptions = library.subscriptions.value
        val playlist: LocalPlaylist = library.playlists.value.single()
        library.importDocument(preview.toLibraryImport())
        assertEquals(subscriptions, library.subscriptions.value)
        assertEquals(playlist.items, library.playlists.value.single().items)
        assertEquals(1, library.playlists.value.size)
    }

    @Test
    fun theImportedEntriesAreTheLibrarysOwnModels() {
        val entry: HistoryEntry = TakeoutHistoryImporter.parse(ImportFixtures.text("watch-history.json")).history.first()
        assertEquals(V0, entry.item.id)
        assertEquals(2, entry.watchCount)
    }
}
