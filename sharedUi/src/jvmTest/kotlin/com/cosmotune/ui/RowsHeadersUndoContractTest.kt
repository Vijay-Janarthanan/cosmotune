package com.cosmotune.ui

import com.cosmotune.music.data.model.Song
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.pages.KeepDownloaded
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The redesign's shared header, flat row and "Removed · Undo" on the screens the mode boards left waiting (Podio, Read, Manga, Video,
 * Watch and the phone's Up next), and "Keep downloaded" running away from its page. Read from the source where a window cannot show
 * it, and run where it is plain logic.
 */
class RowsHeadersUndoContractTest {
    private val podio = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/podio/ui"
    private val manga = "manga/src/jvmSharedMain/kotlin/com/cosmotune/manga/ui"
    private val reading = "manga/src/jvmSharedMain/kotlin/com/cosmotune/reading"
    private val watch = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch"
    private val video = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui"
    private fun code(path: String) = SourceContract.code(SourceContract.read(path))
    private fun body(path: String, name: String) = SourceContract.code(SourceContract.functionBody(SourceContract.read(path), name))

    // ---- Podio --------------------------------------------------------------------------------------------------------------

    @Test fun podiosHeadsAreTheSharedOnes() {
        val head = body("$podio/PodioChrome.kt", "PodioHead")
        val row = head.substring(head.indexOf("KitModeSwitcher()"), head.indexOf("LiveNowStrip"))
        assertTrue("actions()" in row && "LocalModeProfileAction.current" in row, "on the phone the round buttons share the mode pill's row")
        assertTrue("OrbitPageHeading(title, label, fonts, look" in head, "then the 30 sp heading")
        assertTrue("OrbitBackHeader(kind, onBack" in body("$podio/PodioChrome.kt", "PodioBackHead"), "a page over a section has the shared back head")
        assertTrue("OrbitRowSkeleton(" in body("$podio/PodioChrome.kt", "PodioSkeletonRows"), "placeholders in the flat row's shape")
        val queue = body("$podio/PodioQueuePage.kt", "PodioQueuePage")
        assertTrue("PodioBackHead(\"Podio\", onBack" in queue && "OrbitPageHeading(\"Up next\", queueLabel(state)" in queue, "the queue's head")
        val pickUp = body("$podio/PodioPages.kt", "ContinueCard")
        assertTrue("OrbitPageHeader(" in pickUp && "OrbitHeaderSize.PICK_UP" in pickUp && "playing = now && env.playback.playing" in pickUp,
            "the episode you were in the middle of is the pick-up header, pause while it plays")
    }

    @Test fun podiosRowsAreFlat() {
        val episode = body("$podio/PodioKit.kt", "EpisodeRow")
        assertFalse("height = 60.dp" in episode || "endPadding" in episode, "the list's row height, no card gutter")
        assertTrue("OrbitHeardDisc(art, disc, heard, look)" in episode && "val disc = orbitRowDisc()" in episode, "the row's own disc, 40 dp when Compact")
        assertFalse("endPadding" in body("$podio/PodioKit.kt", "StationRow"), "a station row is flat")
        for (file in listOf("PodioPages.kt", "PodioQueuePage.kt", "PodioRadio.kt")) {
            val src = code("$podio/$file")
            assertFalse("height = 64.dp" in src || "height = 60.dp" in src, "$file sets no row height of its own")
            assertFalse(".padding(horizontal = side).padding(bottom = 6.dp)" in src, "$file keeps no gap between flat rows")
        }
    }

    @Test fun podiosUndoIsTheWindowsSnackbarAndADownloadIsRemovedWithUndo() {
        val app = code("$podio/PodioApp.kt")
        assertTrue("m.undoBar = com.cosmotune.ui.orbit.LocalOrbitUndo.current" in app, "removals reach the window's snackbar")
        assertFalse("PodioBanner(undo.message" in app || "m.undo =" in app || "m.undo)" in app, "no Undo on Podio's own banner")
        assertFalse("Remove download?" in app || "downloadToRemove" in app, "no dialog before a download goes")
        assertTrue("downloadedEpisodes.filter { it.id !in m.removingDownloads }" in app, "a download being removed leaves the lists at once")
        val model = code("$podio/PodioModel.kt")
        assertTrue("fun offerUndo(offer: PodioUndo) { undoBar?.show(offer.message, offer.undo) }" in model)
        val remove = body("$podio/PodioModel.kt", "removeDownload")
        assertTrue("undoBar.removeAfterUndo(" in remove && "remove(item)" in remove, "the file goes only once Undo has run out")
        val undo = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitUndo.kt")
        assertTrue("delay(ORBIT_UNDO_MS)" in undo && "job.cancel()" in undo, "a deferred removal waits the snackbar's 10 s and Undo cancels it")
    }

    @Test fun theShowPageIsTheCompactHeaderThatCollapses() {
        val show = body("$podio/PodioPages.kt", "ShowPage")
        assertTrue("OrbitPageHeader(" in show && "cover = art" in show, "the show's square art as the header's disc, not a tall picture")
        assertTrue("OrbitHeaderButton(if (following) CosmoIcons.Check else CosmoIcons.Plus" in show, "Follow is a round action")
        assertTrue("OrbitCollapsingHeaderBar(list, OrbitCollapsedHeader(art, title" in show, "the heading collapses once scrolled")
        val header = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitHeader.kt")
        assertTrue("fun BoxScope.OrbitCollapsingHeaderBar(" in header && "list.firstVisibleItemIndex >= after" in header)
    }

    // ---- Read and Manga ------------------------------------------------------------------------------------------------------

    @Test fun readAndMangaHeadsHoldTheirActions() {
        val head = body("$manga/ReadKit.kt", "ReadPageHead")
        assertTrue("OrbitPageHeading(" in head && "trailing = actions" in head, "+ and search stand in the heading's row")
        assertFalse("maxWidth >= 600.dp" in head || "Column {" in head, "never on a row of their own")
        assertTrue("OrbitRowSkeleton(" in body("$manga/ReadKit.kt", "ReadSkeletonRows"), "placeholders in the flat row's shape")
        for (name in listOf("ReadChapterRow", "ReadCoverRow")) {
            val row = body("$manga/ReadKit.kt", name)
            assertFalse("height = " in row || "endPadding" in row, "$name is flat at the list's row height")
        }
        val bookmark = body("$reading/ui/BooksApp.kt", "BookmarkRow")
        assertFalse("height = 62.dp" in bookmark || "endPadding" in bookmark, "a bookmark is a flat row")
    }

    @Test fun readAndMangaRemoveWithUndo() {
        val book = body("$reading/ui/BooksApp.kt", "BookLocalDetails")
        assertFalse("Remove this book?" in book || "CosmoAlertDialog(" in book, "no dialog before a book goes")
        assertTrue("takeOutBook(book.id)" in book && "putBackBook(taken)" in book && "deleteTakenBook(taken)" in book && "removeAfterUndo(" in book,
            "the book leaves at once, Undo puts it back, its files go when Undo runs out")
        val repository = code("$reading/data/ReadingRepository.kt")
        assertTrue("suspend fun takeOutBook(" in repository && "suspend fun putBackBook(" in repository && "suspend fun restoreBookmark(" in repository)
        assertTrue("undo.removedThen(mark.name)" in body("$reading/ui/BooksApp.kt", "BookmarkRow"), "a bookmark comes back with Undo")
        val history = body("$manga/MangaUpdates.kt", "MangaHistoryPage")
        assertTrue("undo.removedThen(entry.book.title)" in history, "History's cross offers Undo")
        val details = code("$manga/MangaDetails.kt")
        assertTrue("markWithUndo(scope, undo, book, chosen, !allRead, state, markWords)" in details, "marking the chosen chapters offers Undo")
        assertTrue("mangaRemoveDownloads(scope, undo, downloaded" in details, "removing their downloads too")
        assertTrue("takeOutDownloads(" in body("$manga/MangaApp.kt", "mangaRemoveDownloads") && "putBackDownloads(taken)" in body("$manga/MangaApp.kt", "mangaRemoveDownloads"))
    }

    // ---- Video and Watch -----------------------------------------------------------------------------------------------------

    @Test fun videosPageCardsAreTheSharedHeader() {
        assertTrue("OrbitPageHeader(" in body("$video/channel/ChannelHeader.kt", "ChannelCard"), "the channel")
        assertTrue("OrbitPageHeader(" in body("$video/playlist/PlaylistHeader.kt", "PlaylistCard"), "the playlist")
        assertTrue("OrbitHeaderSkeleton(" in body("$video/channel/ChannelHeader.kt", "ChannelHeaderSkeleton"))
        assertTrue("OrbitHeaderSkeleton(" in body("$video/playlist/PlaylistHeader.kt", "PlaylistHeaderSkeleton"))
        val frame = body("$video/blocks/VideoRow.kt", "VideoRowFrame")
        assertFalse("background(look.row)" in frame, "Up next, Library and the uploads are flat rows")
        assertFalse("height = 62.dp" in body("$video/library/LibraryOrbit.kt", "OrbitInfoRow"))
    }

    @Test fun watchsRowsAreFlatItsBackHeadSharedAndMyListUndoTheSnackbar() {
        val frame = body("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitWatch.kt", "WatchRowFrame")
        assertTrue("card: Boolean = false" in SourceContract.parameters(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitWatch.kt"), "WatchRowFrame").joinToString(),
            "flat unless asked for the card")
        assertTrue("if (!card) Modifier" in frame, "no fill on a flat row")
        assertTrue("card = true" in code("$watch/WatchPlayerPage.kt"), "the next episode over the picture keeps its card")
        val title = code("$watch/WatchTitlePage.kt")
        assertTrue("color = if (best) look.accent else look.ink" in title && "color = if (next) look.accent else look.ink" in title, "the best copy and the next episode in peach")
        assertTrue("OrbitCollapsingHeaderBar(list, OrbitCollapsedHeader(" in title, "the title collapses once scrolled")
        assertTrue("com.cosmotune.ui.orbit.OrbitBackHeader(kind" in body("$watch/WatchApp.kt", "WatchBackHeader"))
        val list = body("$watch/WatchListPages.kt", "WatchMyListPage")
        assertTrue("undo?.removed(gone.single().name, putBack)" in list && "CosmoToast(" !in list)
    }

    // ---- Keep downloaded -----------------------------------------------------------------------------------------------------

    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private fun song(id: String) = Song(id, "Title $id", "Artist", null)

    @Test fun aPassHandsOverTheNewSongsOnceAndSkipsWhatIsOnTheDevice() = runBlocking {
        val look = CosmoLookSettings(MapStore())
        look.setKeepDownloaded("VLmine", true)
        val handed = mutableListOf<String>()
        val songs = listOf(song("a"), song("b"), song("b"), song("c"))
        val first = KeepDownloaded.run(look, { songs }, onDevice = { it == "c" }, download = { _, list -> handed += list.map { it.videoId } }, nowMs = 1_000)
        assertEquals(2, first)
        assertEquals(listOf("a", "b"), handed, "each new song once; the one on the device is left")
        assertEquals(setOf("a", "b"), look.keptSongs("VLmine"))
        handed.clear()
        // The next pass: what was kept before is not fetched again (it may have been removed from the device on purpose).
        assertEquals(1, KeepDownloaded.run(look, { songs }, onDevice = { false }, download = { _, list -> handed += list.map { it.videoId } }, nowMs = 2_000))
        assertEquals(listOf("c"), handed)
        assertEquals(2_000, look.keepDownloadedCheckedMs(), "the pass's time is kept for the daily gate")
    }

    @Test fun aFailedHandOverIsTriedAgainAndAPlaylistTurnedOffIsPassedOver() = runBlocking {
        val look = CosmoLookSettings(MapStore())
        look.setKeepDownloaded("VLmine", true)
        runCatching { KeepDownloaded.run(look, { listOf(song("a")) }, { false }, { _, _ -> error("no room") }) }
        assertEquals(emptySet(), look.keptSongs("VLmine"), "nothing is marked kept until it was handed over")
        var asked = 0
        KeepDownloaded.run(look, { asked++; listOf(song("a")) }, { false }, { _, _ -> }, playlists = listOf("VLother"))
        assertEquals(0, asked, "a playlist whose switch is off is not read")
    }

    @Test fun theDailyGate() {
        assertTrue(KeepDownloaded.due(0, 5))
        assertFalse(KeepDownloaded.due(10_000, 10_000 + KeepDownloaded.DAY_MS - 1))
        assertTrue(KeepDownloaded.due(10_000, 10_000 + KeepDownloaded.DAY_MS))
        assertTrue(KeepDownloaded.due(10_000, 9_000), "a clock set back does not hold the pass off")
    }

    @Test fun keepDownloadedRunsInTheBackgroundOnBothHosts() {
        val worker = code("app/src/main/java/com/cosmotune/music/download/KeepDownloadedWorker.kt")
        assertTrue("PeriodicWorkRequestBuilder<KeepDownloadedWorker>(1, TimeUnit.DAYS)" in worker && "NetworkType.UNMETERED" in worker, "daily, on Wi-Fi")
        assertTrue("val added = before?.let { kept - it }.orEmpty()" in worker && "OneTimeWorkRequestBuilder<KeepDownloadedWorker>()" in worker, "and when a switch is turned on")
        assertTrue("cancelUniqueWork(DAILY)" in worker, "nothing scheduled while nothing is kept")
        assertTrue("KeepDownloaded.due(look.keepDownloadedCheckedMs()" in worker && "KeepDownloaded.run(" in worker && "Downloads.enqueue(" in worker,
            "at most daily, through the app's own downloads")
        assertTrue("KeepDownloadedScheduler.init(this)" in code("app/src/main/java/com/cosmotune/music/CosmotuneApplication.kt"))
        val desktop = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue("suspend fun keepDownloadedPass(" in desktop && "delay(com.cosmotune.ui.pages.KeepDownloaded.DAY_MS)" in desktop && "download = { _, songs -> downloadAll(songs) }" in desktop,
            "the desktop: on start, once a day while it runs, with Download all's queue")
        assertTrue("KeepDownloaded.fresh(songs, { it in downloadedIds }, kept)" in code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoReleasePage.kt"),
            "the page itself uses the same rule")
    }
}
