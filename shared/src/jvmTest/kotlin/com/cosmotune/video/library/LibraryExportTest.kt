package com.cosmotune.video.library

import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Export and import (plan task Y-7, step 3; spec 8.1, 8.2): the file's keys, a round trip, a merge, and nothing deleted. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryExportTest {

    private val now = FakeVideoSource.NOW_MS
    private val video = FakeVideoSource.videos
    private val channels = FakeVideoSource.channels

    private fun TestScope.store(files: MemoryLibraryFiles = MemoryLibraryFiles(), clock: () -> Long = { now }) =
        VideoLibraryStore(files, backgroundScope, clock, ioDispatcher = StandardTestDispatcher(testScheduler), log = {})

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A library with something in every list. */
    private fun fill(s: VideoLibraryStore) {
        channels.forEach { s.subscribe(it) }
        s.recordWatch(video[0], 120_000, 754_000)
        s.recordWatch(video[2], 300_000, 1_325_000)
        s.recordWatch(FakeVideoSource.shorts[0], 9_000, 17_000)
        s.addToWatchLater(video[3])
        s.addToWatchLater(video[5])
        s.setLiked(video[1], true)
        s.setLiked(video[6], true)
        val mine = s.createPlaylist("For the drive")
        s.addToPlaylist(mine, video[3])
        s.addToPlaylist(mine, video[0])
        s.createPlaylist("Quiet")
        s.savePlaylist(FakeVideoSource.playlist)
        s.addSearch("tape echo")
        s.addSearch("rain sounds")
    }

    private fun assertSameLibrary(expected: VideoLibraryStore, actual: VideoLibraryStore) {
        assertEquals(expected.subscriptions.value, actual.subscriptions.value)
        assertEquals(expected.history.value, actual.history.value)
        assertEquals(expected.watchLater.value, actual.watchLater.value)
        assertEquals(expected.liked.value, actual.liked.value)
        assertEquals(expected.playlists.value, actual.playlists.value)
        assertEquals(expected.savedPlaylists.value, actual.savedPlaylists.value)
        assertEquals(expected.searches.value, actual.searches.value)
    }

    @Test
    fun theExportHasTheKeysOfTheSpecAndNothingElse() = runTest {
        val s = store()
        fill(s)
        val root = Json.parseToJsonElement(s.exportJson()).jsonObject
        assertEquals(
            listOf(
                "format", "version", "exportedAt", "subscriptions", "history", "watchLater", "liked", "playlists",
                "savedPlaylists", "searches",
            ),
            root.keys.toList(),
        )
        assertEquals("cosmotune-video", root.getValue("format").jsonPrimitive.content)
        assertEquals(1L, root.getValue("version").jsonPrimitive.long)
        assertEquals(now, root.getValue("exportedAt").jsonPrimitive.long)
        assertEquals(2, root.getValue("subscriptions").jsonArray.size)
        assertEquals(3, root.getValue("history").jsonArray.size)
    }

    @Test
    fun theExportLeavesOutSettingsAndTheCaches() = runTest {
        val s = store()
        fill(s)
        s.setHistoryPaused(true)
        val text = s.exportJson()
        for (word in listOf("settings", "paused", "feed", "downloads")) assertTrue(!text.contains(word), "the export mentions $word")
    }

    @Test
    fun exportThenImportIntoAnEmptyStoreGivesTheSameState() = runTest {
        val a = store()
        fill(a)
        val b = store()
        val report = b.importDocument(LibraryExport.parse(a.exportJson()))
        assertSameLibrary(a, b)
        assertEquals(ImportReport(subscriptions = 2, history = 3, playlists = 2, skipped = 0), report)
    }

    @Test
    fun theStateSurvivesFilesAndARestartAfterAnImport() = runTest {
        val a = store()
        fill(a)
        val files = MemoryLibraryFiles()
        val b = store(files)
        b.importDocument(LibraryExport.parse(a.exportJson()))
        pass(500)
        assertSameLibrary(a, store(files))
    }

    @Test
    fun importingIntoANonEmptyStoreMerges() = runTest {
        val a = store()
        fill(a)

        var time = now
        val b = store(clock = { time })
        b.subscribe(channels[1])                                        // also in the export
        b.recordWatch(video[0], 700_000, 754_000)                       // in both, watched at the same time: mine stays
        time += 60_000
        b.recordWatch(video[7], 5_000, 61_000)                          // only mine
        b.addToWatchLater(video[5])                                     // also in the export
        b.addToWatchLater(video[8])                                     // only mine
        val drive = b.createPlaylist("for the drive")                   // same name as the export's, other case
        b.addToPlaylist(drive, video[9])                                // only mine
        b.addToPlaylist(drive, video[3])                                // also in the export
        b.addSearch("my own search")
        b.setLiked(video[10], true)

        val report = b.importDocument(LibraryExport.parse(a.exportJson()))

        assertEquals(setOf(channels[0].id, channels[1].id), b.subscriptions.value.map { it.channelId }.toSet())
        assertEquals(
            setOf(video[0].id, video[2].id, FakeVideoSource.shorts[0].id, video[7].id),
            b.history.value.map { it.item.id }.toSet(),
        )
        assertEquals(700_000L, b.history.value.first { it.item.id == video[0].id }.positionMs, "my watch is the newer one")
        assertEquals(setOf(video[3].id, video[5].id, video[8].id), b.watchLater.value.map { it.id }.toSet())
        assertEquals(listOf(video[5].id, video[8].id, video[3].id), b.watchLater.value.map { it.id }, "mine first, the new ones after")
        assertEquals(listOf("for the drive", "Quiet"), b.playlists.value.map { it.name })
        assertEquals(listOf(video[9].id, video[3].id, video[0].id), b.playlists.value[0].items.map { it.id })
        assertEquals(listOf(drive, b.playlists.value[1].id), b.playlists.value.map { it.id })
        assertEquals(listOf("my own search", "tape echo", "rain sounds").sorted(), b.searches.value.sorted())
        assertEquals(setOf(video[10].id, video[1].id, video[6].id), b.liked.value.map { it.id }.toSet())
        assertEquals(listOf(FakeVideoSource.playlist), b.savedPlaylists.value)
        // 1 new channel, 2 new histories (video[2] and the Short), the "drive" playlist grew and "Quiet" is new
        assertEquals(ImportReport(subscriptions = 1, history = 2, playlists = 2, skipped = 0), report)
    }

    @Test
    fun theNewerPositionWinsInBothDirections() = runTest {
        var time = now
        val a = store(clock = { time })
        a.recordWatch(video[0], 100_000, 754_000)
        a.recordWatch(video[1], 50_000, 600_000)
        val older = a.exportJson()
        time += 3_600_000
        val b = store(clock = { time })
        b.recordWatch(video[0], 600_000, 754_000)                       // watched later and further
        time += 3_600_000
        val c = store(clock = { time })
        c.recordWatch(video[1], 10_000, 600_000)                        // watched later but not as far: still the newer
        b.importDocument(LibraryExport.parse(older))
        assertEquals(600_000L, b.positionOf(video[0].id))
        assertEquals(50_000L, b.positionOf(video[1].id), "a video only the export has comes in")
        c.importDocument(LibraryExport.parse(older))
        assertEquals(10_000L, c.positionOf(video[1].id))
        assertEquals(100_000L, c.positionOf(video[0].id))
    }

    @Test
    fun importingTheSameFileAgainChangesNothingAndWritesNothing() = runTest {
        val files = MemoryLibraryFiles()
        val a = store(files)
        fill(a)
        val json = a.exportJson()
        pass(1_000)
        files.writes.clear()
        val report = a.importDocument(LibraryExport.parse(json))
        pass(5_000)
        assertEquals(ImportReport(0, 0, 0, 0), report)
        assertEquals(emptyList(), files.writes)
    }

    @Test
    fun anImportWritesOnlyTheFilesItChanged() = runTest {
        val files = MemoryLibraryFiles()
        val s = store(files)
        val doc = LibraryImport(
            subscriptions = listOf(Subscription("UC1", "A", null, 1, null)), history = emptyList(), playlists = emptyList(),
            savedPlaylists = emptyList(), watchLater = emptyList(), liked = emptyList(), searches = emptyList(),
        )
        s.importDocument(doc)
        pass(500)
        assertEquals(listOf("subscriptions.json"), files.writes)
    }

    @Test
    fun anImportNeverDeletesAndSkipsWhatHasNoId() = runTest {
        val s = store()
        fill(s)
        val before = s.subscriptions.value
        val report = s.importDocument(
            LibraryImport(
                subscriptions = listOf(Subscription("", "Nameless", null, 1, null)), history = emptyList(), playlists = emptyList(),
                savedPlaylists = emptyList(), watchLater = emptyList(), liked = emptyList(), searches = emptyList(),
            ),
        )
        assertEquals(before, s.subscriptions.value)
        assertEquals(ImportReport(0, 0, 0, 1), report)
        assertEquals(3, s.history.value.size)
        assertEquals(2, s.watchLater.value.size)
    }

    @Test
    fun theFakeLibrarysExportIsReadToo() = runTest {
        val fake = FakeVideoLibrary.withSamples()
        val s = store()
        s.importDocument(LibraryExport.parse(fake.exportJson()))
        assertEquals(fake.subscriptions.value, s.subscriptions.value)
        assertEquals(fake.history.value, s.history.value)
        assertEquals(fake.watchLater.value, s.watchLater.value)
        assertEquals(fake.liked.value, s.liked.value)
        assertEquals(fake.savedPlaylists.value, s.savedPlaylists.value)
        assertEquals(fake.playlists.value.map { it.name to it.items }, s.playlists.value.map { it.name to it.items })
        assertEquals(fake.searches.value.toSet(), s.searches.value.toSet())
    }

    // ------------------------------------------------------------------ reading a file

    @Test
    fun aMinimalExportIsAnEmptyImport() {
        val doc = LibraryExport.parse("""{"format":"cosmotune-video","version":1}""")
        assertEquals(LibraryImport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), doc)
    }

    @Test
    fun unknownKeysInAnExportAreIgnored() {
        val doc = LibraryExport.parse("""{"format":"cosmotune-video","version":1,"theme":"dark","searches":["rain"]}""")
        assertEquals(listOf("rain"), doc.searches)
    }

    @Test
    fun aFileThatIsNotACosmotuneVideoExportIsRefused() {
        assertFailsWith<LibraryFormatException> { LibraryExport.parse("""{"version":1,"subscriptions":[]}""") }
        assertFailsWith<LibraryFormatException> { LibraryExport.parse("""{"format":"newpipe","version":1}""") }
        assertFailsWith<LibraryFormatException> { LibraryExport.parse("not json") }
        assertFailsWith<LibraryFormatException> { LibraryExport.parse("[]") }
        assertNull(LibraryExport.parseOrNull("""{"format":"newpipe","version":1}"""))
    }

    @Test
    fun aNewerExportVersionIsRefusedWithTheVersionInTheMessage() {
        val ex = assertFailsWith<LibraryFormatException> { LibraryExport.parse("""{"format":"cosmotune-video","version":2}""") }
        assertTrue(ex.message!!.contains("version 2"), ex.message)
        assertNull(LibraryExport.parseOrNull("""{"format":"cosmotune-video","version":2}"""))
        assertNotNull(LibraryExport.parseOrNull("""{"format":"cosmotune-video","version":1}"""))
    }

    @Test
    fun anExportIsTheSameTextForTheSameLibrary() = runTest {
        val a = store()
        fill(a)
        assertEquals(a.exportJson(), a.exportJson())
        val parsed: JsonObject = Json.parseToJsonElement(a.exportJson()).jsonObject
        assertEquals(LibraryExport.FORMAT, parsed.getValue("format").jsonPrimitive.content)
    }
}
