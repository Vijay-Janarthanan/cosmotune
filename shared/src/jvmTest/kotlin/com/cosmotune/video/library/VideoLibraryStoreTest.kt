package com.cosmotune.video.library

import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A folder in memory: the files by name, and every write in order. A write can be made to fail. */
internal class MemoryLibraryFiles : LibraryFiles {
    val files = LinkedHashMap<String, String>()
    val writes = ArrayList<String>()
    var failWrites = false

    @Synchronized
    override fun read(name: String): String? = files[name]

    @Synchronized
    override fun write(name: String, text: String) {
        if (failWrites) throw IOException("disk full")
        files[name] = text
        writes += name
    }

    @Synchronized
    override fun rename(name: String, newName: String) {
        files.remove(name)?.let { files[newName] = it }
    }
}

/**
 * The library store (plan task Y-7, step 2) over a folder in memory, with virtual time: nothing is written at once, a file is
 * written 500 ms after the last change to it, and only that file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoLibraryStoreTest {

    private val now = FakeVideoSource.NOW_MS
    private val video = FakeVideoSource.videos
    private val channels = FakeVideoSource.channels

    private class Rig(val files: MemoryLibraryFiles, val logs: MutableList<String>, val store: VideoLibraryStore)

    /** A store whose timers and writes run on the test's virtual clock; [clock] is the library's own time. */
    private fun TestScope.rig(
        files: MemoryLibraryFiles = MemoryLibraryFiles(),
        clock: () -> Long = { now },
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Rig {
        val logs = ArrayList<String>()
        return Rig(files, logs, VideoLibraryStore(files, backgroundScope, clock, ioDispatcher = io, log = { logs += it }))
    }

    /** Lets [ms] of virtual time pass and runs what is due at its end. */
    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ------------------------------------------------------------------ loading

    @Test
    fun missingFilesAreEmptyListsAndNothingIsWrittenAtConstruction() = runTest {
        val r = rig()
        assertEquals(emptyList(), r.store.subscriptions.value)
        assertEquals(emptyList(), r.store.history.value)
        assertEquals(emptyList(), r.store.watchLater.value)
        assertEquals(emptyList(), r.store.liked.value)
        assertEquals(emptyList(), r.store.playlists.value)
        assertEquals(emptyList(), r.store.savedPlaylists.value)
        assertEquals(emptyList(), r.store.searches.value)
        assertFalse(r.store.historyPaused.value)
        pass(10_000)
        assertEquals(emptyList(), r.files.writes)
        assertTrue(r.logs.isEmpty())
    }

    @Test
    fun everyFileIsLoadedAtConstruction() = runTest {
        val files = MemoryLibraryFiles()
        files.files["subscriptions.json"] = LibraryCodec.encodeSubscriptions(listOf(Subscription("UC1", "A", null, 1, null)))
        files.files["history.json"] = LibraryCodec.encodeHistory(listOf(HistoryEntry(video[0], 5, 6, 7, 2)), paused = true)
        files.files["watch-later.json"] = LibraryCodec.encodeItems(listOf(video[1], video[2]))
        files.files["liked.json"] = LibraryCodec.encodeItems(listOf(video[3]))
        files.files["playlists.json"] = LibraryCodec.encodePlaylists(listOf(LocalPlaylist("p1", "Mine", listOf(video[4]), 1, 2)))
        files.files["saved-playlists.json"] = LibraryCodec.encodeSavedPlaylists(listOf(FakeVideoSource.playlist))
        files.files["searches.json"] = LibraryCodec.encodeSearches(listOf("rain", "tape"))
        val store = rig(files).store
        assertEquals(listOf("UC1"), store.subscriptions.value.map { it.channelId })
        assertEquals(listOf(HistoryEntry(video[0], 5, 6, 7, 2)), store.history.value)
        assertTrue(store.historyPaused.value)
        assertEquals(listOf(video[1], video[2]), store.watchLater.value)
        assertEquals(listOf(video[3]), store.liked.value)
        assertEquals(listOf("p1"), store.playlists.value.map { it.id })
        assertEquals(listOf(FakeVideoSource.playlist), store.savedPlaylists.value)
        assertEquals(listOf("rain", "tape"), store.searches.value)
        assertEquals(6L, store.positionOf(video[0].id))
        assertNull(store.positionOf("unknown"))
    }

    @Test
    fun aFileThatCannotBeParsedIsSetAsideAndTreatedAsEmptyWithALogLine() = runTest {
        val files = MemoryLibraryFiles()
        files.files["history.json"] = "{ this is not json"
        files.files["liked.json"] = LibraryCodec.encodeItems(listOf(video[3]))
        val r = rig(files)
        assertEquals(emptyList(), r.store.history.value)
        assertEquals(listOf(video[3]), r.store.liked.value, "the other files are read as usual")
        assertEquals("{ this is not json", files.files["history.json.broken-$now"])
        assertNull(files.files["history.json"], "the broken file is renamed, not copied")
        assertEquals(1, r.logs.size)
        assertTrue(r.logs[0].contains("history.json"), r.logs[0])
        assertTrue(r.logs[0].contains("history.json.broken-$now"), r.logs[0])
        assertEquals(emptyList(), files.writes, "setting a file aside is not a write")
    }

    @Test
    fun aFileOfANewerVersionIsKeptAsideToo() = runTest {
        val files = MemoryLibraryFiles()
        val future = """{"version":9,"searches":["from the future"]}"""
        files.files["searches.json"] = future
        val r = rig(files)
        assertEquals(emptyList(), r.store.searches.value)
        assertEquals(future, files.files["searches.json.broken-$now"])
        r.store.addSearch("rain")
        pass(500)
        assertEquals(listOf("rain"), LibraryCodec.decodeSearches(files.files.getValue("searches.json")))
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun aChangeIsWrittenFiveHundredMillisecondsLater() = runTest {
        val r = rig()
        r.store.addSearch("rain")
        assertEquals(listOf("rain"), r.store.searches.value, "the state changes at once")
        pass(499)
        assertEquals(emptyList(), r.files.writes)
        pass(1)
        assertEquals(listOf("searches.json"), r.files.writes)
        assertEquals(listOf("rain"), LibraryCodec.decodeSearches(r.files.files.getValue("searches.json")))
    }

    @Test
    fun theTimerRestartsWithEveryChangeToTheSameFile() = runTest {
        val r = rig()
        r.store.addSearch("one")
        pass(300)
        r.store.addSearch("two")
        pass(300)
        assertEquals(emptyList(), r.files.writes, "600 ms after the first change, 300 after the last")
        pass(200)
        assertEquals(listOf("searches.json"), r.files.writes)
        assertEquals(listOf("two", "one"), LibraryCodec.decodeSearches(r.files.files.getValue("searches.json")))
    }

    @Test
    fun eachFileHasItsOwnTimerAndNameAndOnlyTheChangedOneIsWritten() = runTest {
        val r = rig()
        r.store.subscribe(channels[0])
        pass(100)
        r.store.addToWatchLater(video[0])
        r.store.setLiked(video[1], true)
        r.store.savePlaylist(FakeVideoSource.playlist)
        pass(100)
        r.store.createPlaylist("Mine")
        r.store.addSearch("rain")
        r.store.setHistoryPaused(true)
        pass(1_000)
        assertEquals(
            listOf(
                "subscriptions.json", "watch-later.json", "liked.json", "saved-playlists.json", "playlists.json",
                "searches.json", "history.json",
            ).sorted(),
            r.files.writes.sorted(),
        )
        assertEquals(7, r.files.writes.size, "one write each")
    }

    @Test
    fun aPositionUpdateEveryTenSecondsOnlyRewritesTheHistory() = runTest {
        val r = rig()
        r.store.subscribe(channels[0])
        r.store.addToWatchLater(video[0])
        pass(1_000)
        r.files.writes.clear()
        for (second in 10..60 step 10) {
            r.store.recordWatch(video[0], positionMs = second * 1_000L, durationMs = 754_000)
            pass(10_000)
        }
        assertEquals(List(6) { "history.json" }, r.files.writes)
        assertEquals(60_000L, r.store.positionOf(video[0].id))
        val written = LibraryCodec.decodeHistory(r.files.files.getValue("history.json"))
        assertEquals(60_000L, written.entries.single().positionMs)
    }

    @Test
    fun aCallThatChangesNothingWritesNothing() = runTest {
        val r = rig()
        r.store.addToWatchLater(video[0])
        pass(1_000)
        r.files.writes.clear()
        r.store.addToWatchLater(video[0])
        r.store.removeFromWatchLater("not there")
        r.store.unsubscribe("not followed")
        r.store.removeSearch("never searched")
        r.store.removeFromHistory("never watched")
        r.store.clearHistory()
        r.store.clearSearches()
        r.store.setHistoryPaused(false)
        r.store.moveInWatchLater(0, 0)
        pass(5_000)
        assertEquals(emptyList(), r.files.writes)
    }

    @Test
    fun writesGoThroughTheIoDispatcher() = runTest {
        val dispatched = ArrayList<String>()
        val test = StandardTestDispatcher(testScheduler)
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched += "io"
                test.dispatch(context, block)
            }
        }
        val r = rig(io = io)
        r.store.addSearch("rain")
        pass(500)
        assertEquals(listOf("searches.json"), r.files.writes)
        assertTrue(dispatched.isNotEmpty(), "the write ran on the IO dispatcher, not on the timer's")
    }

    @Test
    fun aRestartReadsBackTheSameState() = runTest {
        val files = MemoryLibraryFiles()
        val first = rig(files).store
        first.subscribe(channels[0])
        first.subscribe(channels[1])
        first.recordWatch(video[0], 100_000, 754_000)
        first.recordWatch(video[2], 200_000, 1_325_000)
        first.addToWatchLater(video[3])
        first.addToWatchLater(video[4])
        first.moveInWatchLater(0, 1)
        first.setLiked(video[5], true)
        val id = first.createPlaylist("For the drive")
        first.addToPlaylist(id, video[6])
        first.addToPlaylist(id, video[7])
        first.savePlaylist(FakeVideoSource.playlist)
        first.addSearch("tape echo")
        first.setHistoryPaused(true)
        pass(1_000)

        val second = rig(files).store
        assertEquals(first.subscriptions.value, second.subscriptions.value)
        assertEquals(first.history.value, second.history.value)
        assertEquals(first.historyPaused.value, second.historyPaused.value)
        assertEquals(first.watchLater.value, second.watchLater.value)
        assertEquals(first.liked.value, second.liked.value)
        assertEquals(first.playlists.value, second.playlists.value)
        assertEquals(first.savedPlaylists.value, second.savedPlaylists.value)
        assertEquals(first.searches.value, second.searches.value)
        assertEquals(listOf(video[4].id, video[3].id), second.watchLater.value.map { it.id })
    }

    @Test
    fun flushWritesWhatIsWaitingAtOnceAndTheTimerDoesNotWriteItAgain() = runTest {
        val r = rig()
        r.store.addSearch("rain")
        r.store.subscribe(channels[0])
        assertEquals(emptyList(), r.files.writes)
        r.store.flush()
        assertEquals(setOf("searches.json", "subscriptions.json"), r.files.writes.toSet())
        assertEquals(2, r.files.writes.size)
        pass(5_000)
        assertEquals(2, r.files.writes.size)
        r.store.flush()
        assertEquals(2, r.files.writes.size, "nothing waiting, nothing written")
    }

    @Test
    fun aFailedWriteIsLoggedAndTriedAgainByTheNextFlush() = runTest {
        val r = rig()
        r.files.failWrites = true
        r.store.addSearch("rain")
        pass(500)
        assertEquals(emptyList(), r.files.writes)
        assertEquals(1, r.logs.size)
        assertTrue(r.logs[0].contains("searches.json"), r.logs[0])
        assertEquals(listOf("rain"), r.store.searches.value, "the state is kept")
        r.files.failWrites = false
        r.store.flush()
        assertEquals(listOf("searches.json"), r.files.writes)
    }

    // ------------------------------------------------------------------ the rules, through the store

    @Test
    fun subscribeAndUnsubscribe() = runTest {
        val s = rig().store
        s.subscribe(channels[0])
        s.subscribe(channels[0])
        s.subscribe(channels[1])
        assertEquals(channels.map { it.id }, s.subscriptions.value.map { it.channelId })
        assertEquals(now, s.subscriptions.value[0].subscribedAtMs)
        s.unsubscribe(channels[0].id)
        assertEquals(listOf(channels[1].id), s.subscriptions.value.map { it.channelId })
    }

    @Test
    fun historyUsesTheClockAndTheThirtyMinuteRule() = runTest {
        var time = now
        val s = rig(clock = { time }).store
        s.recordWatch(video[0], 1_000, 754_000)
        time += 10_000
        s.recordWatch(video[0], 11_000, 754_000)
        assertEquals(1, s.history.value.single().watchCount)
        assertEquals(now + 10_000, s.history.value.single().lastWatchedMs)
        time += 31 * 60_000L
        s.recordWatch(video[0], 5_000, 754_000)
        assertEquals(2, s.history.value.single().watchCount)
        s.recordWatch(video[1], 0, 600_000)
        assertEquals(listOf(video[1].id, video[0].id), s.history.value.map { it.item.id })
        s.removeFromHistory(video[1].id)
        assertEquals(listOf(video[0].id), s.history.value.map { it.item.id })
        s.clearHistory()
        assertEquals(emptyList(), s.history.value)
        assertNull(s.positionOf(video[0].id))
    }

    @Test
    fun nothingIsRecordedWhileHistoryIsPausedAndThePauseIsKept() = runTest {
        val files = MemoryLibraryFiles()
        val s = rig(files).store
        s.setHistoryPaused(true)
        s.recordWatch(video[0], 1_000, 754_000)
        assertEquals(emptyList(), s.history.value)
        assertTrue(s.historyPaused.value)
        pass(500)
        assertTrue(LibraryCodec.decodeHistory(files.files.getValue("history.json")).paused)
        s.setHistoryPaused(false)
        s.recordWatch(video[0], 1_000, 754_000)
        assertEquals(1, s.history.value.size)
    }

    @Test
    fun watchLaterAndLiked() = runTest {
        val s = rig().store
        s.addToWatchLater(video[0])
        s.addToWatchLater(video[1])
        s.addToWatchLater(video[2])
        s.moveInWatchLater(2, 0)
        assertEquals(listOf(video[2], video[0], video[1]), s.watchLater.value)
        s.removeFromWatchLater(video[0].id)
        assertEquals(listOf(video[2], video[1]), s.watchLater.value)
        s.setLiked(video[3], true)
        s.setLiked(video[4], true)
        assertEquals(listOf(video[4], video[3]), s.liked.value)
        s.setLiked(video[3], true)
        assertEquals(listOf(video[3], video[4]), s.liked.value)
        s.setLiked(video[3], false)
        assertEquals(listOf(video[4]), s.liked.value)
    }

    @Test
    fun playlistsCreateRenameAddMoveRemoveDelete() = runTest {
        var time = now
        val s = rig(clock = { time }).store
        val first = s.createPlaylist(" For the drive ")
        val second = s.createPlaylist("Quiet")
        assertTrue(first.isNotBlank() && second.isNotBlank() && first != second)
        assertEquals(listOf("For the drive", "Quiet"), s.playlists.value.map { it.name })
        assertEquals(listOf(first, second), s.playlists.value.map { it.id })
        time += 1_000
        s.addToPlaylist(first, video[0])
        s.addToPlaylist(first, video[1])
        s.addToPlaylist(first, video[0])
        s.addToPlaylist(first, video[2])
        s.moveInPlaylist(first, 2, 0)
        assertEquals(listOf(video[2], video[0], video[1]), s.playlists.value[0].items)
        assertEquals(now + 1_000, s.playlists.value[0].updatedMs)
        assertEquals(now, s.playlists.value[0].createdMs)
        s.removeFromPlaylist(first, video[0].id)
        assertEquals(listOf(video[2], video[1]), s.playlists.value[0].items)
        s.renamePlaylist(second, "Late")
        assertEquals("Late", s.playlists.value[1].name)
        s.deletePlaylist(first)
        assertEquals(listOf(second), s.playlists.value.map { it.id })
    }

    @Test
    fun aPlaylistIdIsRandomAndNeverShared() = runTest {
        val s = rig().store
        val ids = (1..200).map { s.createPlaylist("List $it") }
        assertEquals(200, ids.toSet().size)
        assertEquals(ids, s.playlists.value.map { it.id })
    }

    @Test
    fun atFiveHundredPlaylistsACreateReturnsAnIdThatBelongsToNoPlaylist() = runTest {
        val s = rig().store
        repeat(500) { s.createPlaylist("List $it") }
        val id = s.createPlaylist("One too many")
        assertEquals(500, s.playlists.value.size)
        assertTrue(s.playlists.value.none { it.id == id })
        s.addToPlaylist(id, video[0])
        assertTrue(s.playlists.value.all { it.items.isEmpty() })
    }

    @Test
    fun savedPlaylistsAndSearches() = runTest {
        val s = rig().store
        s.savePlaylist(FakeVideoSource.playlist)
        s.savePlaylist(FakeVideoSource.playlist)
        assertEquals(1, s.savedPlaylists.value.size)
        s.unsavePlaylist(FakeVideoSource.playlist.id)
        assertEquals(emptyList(), s.savedPlaylists.value)
        s.addSearch("rain")
        s.addSearch("Tape")
        s.addSearch("RAIN")
        assertEquals(listOf("RAIN", "Tape"), s.searches.value)
        s.removeSearch("Tape")
        assertEquals(listOf("RAIN"), s.searches.value)
        s.clearSearches()
        assertEquals(emptyList(), s.searches.value)
    }

    @Test
    fun theStateFlowsEmitTheNewListAtOnce() = runTest {
        val s = rig().store
        val seen = ArrayList<List<String>>()
        seen += s.searches.value
        s.addSearch("one")
        seen += s.searches.value
        s.addSearch("two")
        seen += s.searches.value
        assertEquals(listOf(emptyList(), listOf("one"), listOf("two", "one")), seen)
    }

    @Test
    fun changesFromSeveralThreadsAreNotLost() = runTest {
        val s = rig().store
        val threads = (0 until 4).map { t ->
            Thread {
                for (n in 0 until 400) s.addToWatchLater(FakeVideoSource.sampleItem("fakevideo01")!!.copy(id = "t$t-$n"))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1_600, s.watchLater.value.size)
        assertEquals(1_600, s.watchLater.value.map { it.id }.toSet().size)
    }

    @Test
    fun theExportIsTheLibraryAndImportMergesIntoIt() = runTest {
        val s = rig().store
        s.subscribe(channels[0])
        s.addToWatchLater(video[0])
        val json = s.exportJson()
        assertNotNull(LibraryExport.parseOrNull(json))
        val other = rig().store
        other.subscribe(channels[1])
        val report = other.importDocument(LibraryExport.parse(json))
        assertEquals(ImportReport(subscriptions = 1, history = 0, playlists = 0, skipped = 0), report)
        assertEquals(setOf(channels[0].id, channels[1].id), other.subscriptions.value.map { it.channelId }.toSet())
        assertEquals(listOf(video[0]), other.watchLater.value)
    }
}
