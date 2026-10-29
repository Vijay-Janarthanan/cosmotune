package com.cosmotune.video.ui

import com.cosmotune.video.importers.ImportFormat
import com.cosmotune.video.importers.JdbcSqliteRows
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LibraryExport
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.library.ChannelResolver
import com.cosmotune.video.ui.library.CreatedFile
import com.cosmotune.video.ui.library.ImportPhase
import com.cosmotune.video.ui.library.ImportPlatform
import com.cosmotune.video.ui.library.ImportReader
import com.cosmotune.video.ui.library.ImportState
import com.cosmotune.video.ui.library.LibraryPlan
import com.cosmotune.video.ui.library.PickedFile
import com.cosmotune.video.ui.library.RowSpan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The library pages (plan task Y-22, step 4): Library, History, Watch later and Liked, Saved playlists, Channels, Import and export,
 * and the file picker. What no render can show is read from the source (the nav host's branch lines, no `newpipe.settings` anywhere,
 * the confirmation before the history is cleared, keys on every lazy list, no text, colour or live read in the wrong place), and the
 * rules and the import flow are run: on files built in memory, the desktop's real SQLite reader and the fake source, never the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/library"
    private fun file(name: String) = VideoSourceContract.read("$dir/$name.kt")
    private fun code(name: String) = VideoSourceContract.code(file(name))
    private val androidPicker = "video/src/androidMain/kotlin/com/cosmotune/video/ui/library/FilePicker.android.kt"
    private val desktopPicker = "video/src/jvmMain/kotlin/com/cosmotune/video/ui/library/FilePicker.jvm.kt"
    private val navHost = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt"

    /** The seven files of the brief, and the three this task adds beside them (the shared pieces, the rules, the import flow). */
    private val pages = listOf("LibraryPage", "HistoryPage", "VideoListPage", "SavedPlaylistsPage", "ChannelsPage", "ImportPage")
    private val all = pages + listOf("FilePicker", "LibraryParts", "LibraryPlan", "ImportFlow")
    private val everySource get() = all.map { it to file(it) } + listOf(
        "FilePicker.android" to VideoSourceContract.read(androidPicker),
        "FilePicker.jvm" to VideoSourceContract.read(desktopPicker),
    )

    private val temp: File = Files.createTempDirectory("y22-library").toFile()

    @AfterTest fun cleanUp() {
        temp.deleteRecursively()
    }

    // ---- The nav host -------------------------------------------------------------------------------------------------------

    @Test fun theSevenBranchesCallThePagesByTheirFullNamesAndLocalPlaylistStaysWithY20() {
        val text = VideoSourceContract.read(navHost)
        val lines = text.lines()
        val expected = mapOf(
            "Library" to "LibraryPage", "History" to "HistoryPage", "WatchLater" to "WatchLaterPage", "Liked" to "LikedPage",
            "SavedPlaylists" to "SavedPlaylistsPage", "Channels" to "ChannelsPage", "Import" to "ImportPage",
        )
        for ((route, page) in expected) {
            val line = lines.single { it.trim().startsWith("is VideoRoute.$route ->") }.trim()
            assertEquals("is VideoRoute.$route -> com.cosmotune.video.ui.library.$page(contentPadding, wide)   // Y-22", line)
        }
        // Y-20 has taken its line (the channel and playlist pages): it is no longer a placeholder, and not Y-22's
        assertEquals("is VideoRoute.LocalPlaylist -> com.cosmotune.video.ui.playlist.PlaylistPage(null, r.id, contentPadding, wide)", lines.single { it.trim().startsWith("is VideoRoute.LocalPlaylist ->") }.trim())
        assertFalse(Regex("""(?m)^import com\.cosmotune\.video\.ui\.library""").containsMatchIn(text), "the branches need no import line")
        assertTrue("com.cosmotune.video.ui.actions.VideoActionsHost(contentPadding)" in text, "Y-16's action host stays")
    }

    @Test fun everyPageTakesThePaddingAndTheWidthOfTheNavHost() {
        for (page in listOf("LibraryPage", "HistoryPage", "SavedPlaylistsPage", "ChannelsPage", "ImportPage")) {
            assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), VideoSourceContract.parameters(file(page), page), page)
        }
        for (page in listOf("WatchLaterPage", "LikedPage")) {
            assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), VideoSourceContract.parameters(file("VideoListPage"), page), page)
        }
        assertEquals(
            listOf("kind: VideoListKind", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(file("VideoListPage"), "VideoListPage"),
        )
    }

    // ---- Library (spec 5.8) -------------------------------------------------------------------------------------------------

    @Test fun theLibraryHasItsHeadingTheTonalPlusTheShelfTheSevenRowsInOrderAndYourPlaylists() {
        val src = code("LibraryPage")
        assertTrue("LibraryHeader(" in src && "Res.string.video_library" in src, "Library in display(DISPLAY_M)")
        assertTrue("CosmoPageHeader(title = title, modifier = modifier, large = wide, actions = actions)" in code("LibraryParts"), "the heading is part two's page heading")
        assertTrue(Regex("""CosmoIconButton\(\s*CosmoIcons\.Plus,[\s\S]*?style = CosmoIconButtonStyle\.TONAL,\s*size = 40\.dp""").containsMatchIn(src), "the 40 dp tonal plus")
        assertTrue("NewPlaylistDialog(" in src && "createPlaylist(" in src, "the plus makes a playlist")
        assertTrue("LibraryPlan.fractions(" in src && "VideoRoute.History" in src, "the You tab's History shelf has the watched bars and opens the history")
        val rows = Regex("""LibraryLink\(\s*([\w.]+),\s*Res\.string\.(\w+)""").findAll(src).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            listOf(
                "VideoIcons.History" to "video_history", "CosmoIcons.Clock" to "video_watch_later", "CosmoIcons.HeartFill" to "video_liked",
                "CosmoIcons.Download" to "video_downloads", "VideoIcons.Subscriptions" to "video_channels",
                "VideoIcons.Bookmark" to "video_saved_playlists", "CosmoIcons.Video" to "video_local_title", "VideoIcons.Upload" to "video_import_export",
            ),
            rows,
        )
        for (route in listOf("History", "WatchLater", "Liked", "Downloads", "Channels", "SavedPlaylists", "LocalFiles", "Import")) {
            assertTrue("VideoRoute.$route" in src, "a row pushes $route")
        }
        assertTrue("Res.string.video_your_playlists" in src && "VideoRoute.LocalPlaylist(" in src && "LibraryPlan.coverOf(" in src)
        assertTrue("CosmoRowLeading.Icon(" in file("LibraryParts"), "the rows have the 40 dp tonal icon tile")
    }

    @Test fun continueWatchingTakesTenToNinetyPercentNewestFirstUpToTen() {
        val v = FakeVideoSource.videos
        fun entry(n: Int, position: Long, duration: Long) = HistoryEntry(v[n], FakeVideoSource.NOW_MS - n, position, duration, 1)
        val history = listOf(
            entry(0, 50_000, 1_000_000),     // 5 %: not yet
            entry(1, 500_000, 1_000_000),    // 50 %: in
            entry(2, 950_000, 1_000_000),    // 95 %: watched
            entry(3, 5_000, 20_000),         // 25 % but under 10 s: no bar
            entry(4, 120_000, 0),            // the item's own duration (482 s): 25 %: in
            entry(10, 600_000, 0),           // live: no duration
            entry(5, 100_000, 1_000_000),    // exactly 10 %: in
            entry(6, 899_000, 1_000_000),    // 89.9 %: in
        )
        assertEquals(listOf("fakevideo02", "fakevideo05", "fakevideo06", "fakevideo07"), LibraryPlan.continueWatching(history).map { it.item.id })
        val many = (0 until 15).map { HistoryEntry(v[0].copy(id = "v$it"), 0, 300_000, 1_000_000, 1) }
        assertEquals((0 until 10).map { "v$it" }, LibraryPlan.continueWatching(many).map { it.item.id })
        assertEquals(0.5f, LibraryPlan.fractions(history)["fakevideo02"])
        assertNull(LibraryPlan.fractions(history)["fakevideo04"], "no bar under 10 s")
    }

    // ---- History (spec 5.8) -------------------------------------------------------------------------------------------------

    @Test fun historyGroupsByTheDayWatchedAndSearchesByTitleOrChannel() {
        val src = code("HistoryPage")
        assertTrue("FeedMerge.groups(" in src && "it.lastWatchedMs" in src, "the day overlines group by when it was watched")
        assertTrue("FeedDay.startOfToday(" in src)
        for (key in listOf("video_today", "video_yesterday", "video_this_week", "video_this_month", "video_older")) assertTrue("Res.string.$key" in src)
        assertTrue("CosmoSearchField(" in src && "Res.string.video_search_history" in src && "LibraryPlan.filterHistory(" in src)
        assertTrue("VideoSheetContext.HISTORY" in src, "the sheet offers Remove from history")
        assertTrue("watchedFraction(" in src, "the rows draw the watched bar")

        val v = FakeVideoSource.videos
        val history = listOf(HistoryEntry(v[0], 3, 0, 0, 1), HistoryEntry(v[6], 2, 0, 0, 1), HistoryEntry(v[1], 1, 0, 0, 1))
        assertEquals(listOf("fakevideo01"), LibraryPlan.filterHistory(history, "  TINY synth ").map { it.item.id }, "by title, any case, trimmed")
        assertEquals(listOf("fakevideo07"), LibraryPlan.filterHistory(history, "lantern").map { it.item.id })
        assertEquals(listOf("fakevideo07"), LibraryPlan.filterHistory(history, "paper lantern").map { it.item.id }, "by channel")
        assertEquals(listOf("fakevideo01", "fakevideo02"), LibraryPlan.filterHistory(history, "northern").map { it.item.id }, "order kept")
        assertSame(history, LibraryPlan.filterHistory(history, "   "), "a blank query keeps the list as it is")
    }

    @Test fun clearingTheHistoryAsksFirstAndPausingIsASwitch() {
        val src = code("HistoryPage")
        assertEquals(1, Regex("""\.clearHistory\(\)""").findAll(src).count(), "one place clears the history")
        val dialog = src.indexOf("ClearHistoryDialog(")
        assertTrue(dialog > 0, "the confirmation dialog")
        val confirm = src.indexOf("onConfirm", dialog)
        val clear = src.indexOf(".clearHistory()")
        assertTrue(confirm in dialog until clear, "the history is cleared from the dialog's confirm, nowhere else")
        assertTrue("CosmoDialogCard(" in src && "Res.string.video_clear_history_confirm" in src)
        assertTrue("Res.string.video_clear_history" in src && "confirmClear = true" in src, "the menu's row only asks")
        assertTrue("CosmoSwitch(" in src && "setHistoryPaused(" in src && "Res.string.video_pause_history" in src)
        assertTrue("Role.Switch" in src, "the pause row is one switch for assistive technology")
        assertTrue("com.cosmotune.ui.bridge.DropdownMenu(" in src, "the page's menu (the shell's top bar takes no page actions)")
    }

    // ---- Watch later and Liked ----------------------------------------------------------------------------------------------

    @Test fun watchLaterReordersAndBothListsSwipeToRemoveAndDownloadAll() {
        val src = code("VideoListPage")
        assertTrue("moveInWatchLater(" in src && "removeFromWatchLater(" in src && "setLiked(" in src)
        assertTrue("SwipeToRemove(" in src && "SwipeToDismissBox(" in file("LibraryParts"))
        assertTrue("detectDragGestures(" in src && "LibraryPlan.reorderTarget(" in src, "drag to reorder")
        assertTrue("translationY = reorder.offset" in src, "the dragged row moves in the layer, not in composition")
        assertTrue("CustomAccessibilityAction(" in src && "Res.string.video_move_up" in src && "Res.string.video_move_down" in src, "reordering without a drag")
        assertTrue("DownloadBatchInfo(\"watch_later\", BatchKind.WATCH_LATER, " in src, "watch later's batch")
        assertTrue("DownloadBatchInfo(\"liked\", BatchKind.PLAYLIST, " in src, "liked videos' batch")
        assertTrue("latestPicker = false" in src && "actions.downloadBatch(" in src && "Res.string.video_dl_all" in src)
    }

    @Test fun aDraggedRowLandsOnTheRowUnderItsMiddle() {
        val rows = (0..5).map { RowSpan(it, top = it * 100f, size = 100f) }
        assertEquals(2, LibraryPlan.reorderTarget(2, 250f, rows), "still over itself")
        assertEquals(3, LibraryPlan.reorderTarget(2, 310f, rows))
        assertEquals(5, LibraryPlan.reorderTarget(2, 599f, rows), "a fast drag jumps rows")
        assertEquals(1, LibraryPlan.reorderTarget(2, 150f, rows))
        assertEquals(2, LibraryPlan.reorderTarget(2, 900f, rows), "past the last row on screen: nowhere new")
        assertEquals(listOf("b", "c", "a", "d"), LibraryPlan.moved(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), LibraryPlan.moved(listOf("a", "b", "c", "d"), 3, -4), "clamped")
    }

    // ---- Saved playlists and channels ---------------------------------------------------------------------------------------

    @Test fun savedPlaylistsAreRowsAndChannelsAreSortedByNameWithAnUnsubscribeButton() {
        val saved = code("SavedPlaylistsPage")
        assertTrue("PlaylistRow(" in saved && "VideoRoute.Playlist(" in saved)
        val channels = code("ChannelsPage")
        assertTrue("ChannelRow(" in channels && "LibraryPlan.channelsByName(" in channels && ".unsubscribe(" in channels && "VideoRoute.Channel(" in channels)
        val subs = listOf("beta", "Alpha", "Émile", "alpha two", "delta").mapIndexed { i, n -> Subscription("id$i", n, null, 0, null) }
        assertEquals(listOf("Alpha", "alpha two", "beta", "delta", "Émile"), LibraryPlan.channelsByName(subs).map { it.name })
    }

    // ---- Import and export (spec 8.2) ---------------------------------------------------------------------------------------

    @Test fun theImportPageHasTheFiveRowsTheSummaryCardAndTheExport() {
        val src = code("ImportPage")
        for (key in listOf("video_import_newpipe_subs", "video_import_newpipe_db", "video_import_takeout", "video_import_opml", "video_import_cosmotune")) {
            assertTrue("Res.string.$key" in src, key)
        }
        for (key in listOf("video_import_summary", "video_import_confirm", "video_import_done", "video_import_failed", "video_export_done")) {
            assertTrue("Res.string.$key" in src, key)
        }
        for (key in listOf("video_import_skipped", "video_import_lookups")) assertTrue("Res.plurals.$key" in src, key)
        assertTrue("picker.open(" in src && "picker.create(LibraryPlan.exportFileName(" in src, "each row opens the picker; export creates the file")
        assertTrue("state.confirm()" in src && "state.export(" in src)
        assertEquals("cosmotune-video-2026-10-16.json", LibraryPlan.exportFileName(LocalDate.of(2026, 10, 16)))
    }

    @Test fun noFileOfTheTaskNamesTheSettingsFileOfTheBackupAndTheDatabaseIsReadOnlyThroughTheImporter() {
        for ((name, source) in everySource) {
            assertFalse("newpipe.settings" in source, "$name names the backup's settings file")
            assertFalse("org.schabi" in source, "$name imports the extractor")
            assertFalse("ObjectInputStream" in source, "$name deserialises")
        }
        val flow = code("ImportFlow")
        assertTrue("NewPipeDatabaseImporter.parseBackup(zip, TempFileSqliteOpener(platform.cacheDir, platform.sqlite), nowMs)" in flow)
        assertTrue("read(ZipInput(temp))" in flow && "read(ZipInput(it))" in flow, "archives are read from a file")
        assertTrue("temp.delete()" in flow.substringAfter("finally"), "the copy is deleted")
    }

    @Test fun theLookupsRunFourAtATimeWithProgressAndCountFailuresAndRepeats() = runTest {
        val gates = (0 until 9).map { CompletableDeferred<ChannelItem?>() }
        var inFlight = 0
        var most = 0
        val progress = ArrayList<Int>()
        val addresses = (0 until 9).map { "https://example.invalid/@c$it" }
        val result = async {
            ChannelResolver.resolve(
                addresses = addresses,
                known = setOf("UCknown"),
                nowMs = 42,
                lookup = { address ->
                    val n = address.substringAfterLast("@c").toInt()
                    inFlight += 1
                    most = maxOf(most, inFlight)
                    try {
                        gates[n].await() ?: error("no such channel")
                    } finally {
                        inFlight -= 1
                    }
                },
                onProgress = { progress += it },
            )
        }
        runCurrent()
        assertEquals(4, inFlight, "four at a time")
        val channel = { id: String -> ChannelItem(id, "Name $id", null, null, null, null) }
        gates.forEachIndexed { n, gate ->
            gate.complete(
                when (n) {
                    3 -> null                        // the lookup fails
                    5 -> channel("UCknown")          // already in the file
                    7 -> channel("UCc0")             // found twice
                    else -> channel("UCc$n")
                },
            )
            runCurrent()
        }
        val resolved = result.await()
        assertEquals(4, most)
        assertEquals((1..9).toList(), progress)
        assertEquals(listOf("UCc0", "UCc1", "UCc2", "UCc4", "UCc6", "UCc8"), resolved.subscriptions.map { it.channelId })
        assertEquals(42L, resolved.subscriptions.first().subscribedAtMs)
        assertEquals(1, resolved.failed)
        assertEquals(2, resolved.repeats)
        assertTrue("PARALLEL = 4" in code("ImportFlow") && "Semaphore(" in code("ImportFlow"))
    }

    private val platform get() = ImportPlatform(File(temp, "cache"), ::JdbcSqliteRows)

    private fun picked(name: String, bytes: ByteArray, sized: Boolean = true) = PickedFile(name, if (sized) bytes.size.toLong() else null, null) { ByteArrayInputStream(bytes) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val realId = "UCabcdefghijklmnopqrstuv"
    private val host = "https://www.you" + "tube.com"

    private fun cacheIsEmpty() = (File(temp, "cache").listFiles() ?: emptyArray()).isEmpty()

    @Test fun eachTextFormatIsReadByItsImporter() {
        val newpipe = """{"app_version":"0.27","subscriptions":[{"service_id":0,"url":"$host/channel/$realId","name":"Real"},{"service_id":0,"url":"$host/@northernsignal","name":"Old"}]}"""
        val subs = assertNotNull(ImportReader.read(picked("newpipe_subscriptions_202610161200.json", newpipe.encodeToByteArray()), platform, 7))
        assertEquals(ImportFormat.NEWPIPE_SUBSCRIPTIONS, subs.format)
        assertEquals(1, subs.channels)
        assertEquals(listOf("$host/@northernsignal"), subs.lookups)

        val opml = """<opml version="1.1"><body><outline text="Subs"><outline text="Real" xmlUrl="$host/feeds/videos.xml?channel_id=$realId"/></outline></body></opml>"""
        assertEquals(ImportFormat.OPML, ImportReader.read(picked("subs.opml", opml.encodeToByteArray()), platform, 7)?.format)

        val csv = "Channel Id,Channel Url,Channel Title\r\n$realId,http://www.you" + "tube.com/channel/$realId,Real\r\n"
        val takeout = assertNotNull(ImportReader.read(picked("subscriptions.csv", csv.encodeToByteArray()), platform, 7))
        assertEquals(ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV, takeout.format)
        assertEquals(1, takeout.channels)

        assertNull(ImportReader.read(picked("notes.txt", "just some words".encodeToByteArray()), platform, 7), "not a format: failed")
        assertNull(ImportReader.read(picked("empty.json", """{"subscriptions":[]}""".encodeToByteArray()), platform, 7), "nothing in it: failed")
        assertNull(ImportReader.read(picked("nothing.json", ByteArray(0)), platform, 7))
    }

    @Test fun aTakeoutArchiveIsReadFromACopyInTheCacheOrFromItsStreamAndTheCopyIsDeleted() {
        val csv = "Channel Id,Channel Url,Channel Title\n$realId,http://example.invalid/channel/$realId,Real\n"
        val history = """[{"header":"Video","title":"Watched A film","titleUrl":"$host/watch?v=abcdefghijk","time":"2026-10-01T10:00:00Z"}]"""
        val archive = zip("Takeout/subscriptions/subscriptions.csv" to csv.encodeToByteArray(), "Takeout/history/watch-history.json" to history.encodeToByteArray())
        for (sized in listOf(true, false)) {
            val read = assertNotNull(ImportReader.read(picked("takeout-20261016.zip", archive, sized), platform, 7))
            assertEquals(ImportFormat.TAKEOUT_ZIP, read.format)
            assertEquals(1, read.channels)
            assertEquals(1, read.historyEntries)
            assertTrue(cacheIsEmpty(), "nothing is left in the cache folder")
        }
        val onDisk = File(temp, "takeout.zip").also { it.writeBytes(archive) }
        val fromFile = assertNotNull(ImportReader.read(PickedFile(onDisk.name, onDisk.length(), onDisk) { onDisk.inputStream() }, platform, 7))
        assertEquals(1, fromFile.channels)
        assertTrue(onDisk.isFile, "the picked file itself is never deleted")
    }

    @Test fun aNewPipeBackupIsReadThroughTheDesktopsSqliteReaderAndTheExtractedDatabaseIsDeleted() {
        val db = File(temp, "made.db")
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY, service_id INTEGER, url TEXT, name TEXT, avatar_url TEXT)")
                s.executeUpdate("INSERT INTO subscriptions (service_id, url, name, avatar_url) VALUES (0, '$host/channel/$realId', 'Real', NULL)")
                s.executeUpdate("INSERT INTO subscriptions (service_id, url, name, avatar_url) VALUES (0, '$host/user/someone', 'Old', NULL)")
            }
        }
        // the backup's other entries are there too and are not read (a settings file would be Java-serialised: junk here)
        val backup = zip("newpipe.db" to db.readBytes(), "newpipe" + ".settings" to byteArrayOf(-84, -19, 0, 5, 1, 2, 3), "preferences.json" to "{}".encodeToByteArray())
        val read = assertNotNull(ImportReader.read(picked("NewPipeData-20261016_120000.zip", backup), platform, 7))
        assertEquals(ImportFormat.NEWPIPE_DATABASE, read.format)
        assertEquals(listOf(realId), read.doc.subscriptions.map { it.channelId })
        assertEquals(listOf("$host/user/someone"), read.lookups)
        assertTrue(cacheIsEmpty(), "the copied archive and the extracted database are both gone")
    }

    @Test fun theImportStateLooksUpThenWaitsForImportAndMergesNothingBeforeIt() = runTest {
        val library = FakeVideoLibrary()
        val io = StandardTestDispatcher(testScheduler)
        val source = FakeVideoSource()
        val state = ImportState(this, library, source, platform, io = io, clock = { 99 })
        val newpipe = """{"subscriptions":[{"service_id":0,"url":"$host/channel/$realId","name":"Real"},{"service_id":0,"url":"$host/@northernsignal"},{"service_id":0,"url":"$host/@nobody"}]}"""
        state.read(picked("subs.json", newpipe.encodeToByteArray()))
        assertIs<ImportPhase.Reading>(state.phase)
        advanceUntilIdle()
        val ready = assertIs<ImportPhase.Ready>(state.phase)
        assertEquals(listOf(realId, FakeVideoSource.CHANNEL_1), ready.pending.doc.subscriptions.map { it.channelId }, "the handle was looked up")
        assertEquals(1, ready.pending.skipped, "the lookup that found nothing is skipped")
        assertTrue(library.subscriptions.value.isEmpty(), "nothing is written before Import")
        assertTrue(source.calls.any { it.startsWith("channel(") })

        state.confirm()
        advanceUntilIdle()
        val done = assertIs<ImportPhase.Done>(state.phase)
        assertEquals(2, done.report.subscriptions)
        assertEquals(setOf(realId, FakeVideoSource.CHANNEL_1), library.subscriptions.value.map { it.channelId }.toSet())

        state.dismiss()
        assertSame(ImportPhase.Idle, state.phase)
        state.read(picked("notes.txt", "words".encodeToByteArray()))
        advanceUntilIdle()
        assertIs<ImportPhase.Failed>(state.phase)
    }

    @Test fun theExportWritesAFileThatImportsBack() = runTest {
        val library = FakeVideoLibrary.withSamples()
        val io = StandardTestDispatcher(testScheduler)
        val state = ImportState(this, library, FakeVideoSource(), platform, io = io)
        val written = ByteArrayOutputStream()
        var ok: Boolean? = null
        state.export(CreatedFile("cosmotune-video-2026-10-16.json") { written }) { ok = it }
        advanceUntilIdle()
        assertEquals(true, ok)
        val doc = LibraryExport.parse(written.toByteArray().decodeToString())
        assertEquals(library.subscriptions.value.size, doc.subscriptions.size)

        val fresh = FakeVideoLibrary()
        val back = ImportState(this, fresh, FakeVideoSource(), platform, io = io)
        back.read(picked("cosmotune-video-2026-10-16.json", written.toByteArray()))
        advanceUntilIdle()
        val ready = assertIs<ImportPhase.Ready>(back.phase)
        assertEquals(ImportFormat.COSMOTUNE_EXPORT, ready.pending.format)
        back.confirm()
        advanceUntilIdle()
        assertIs<ImportPhase.Done>(back.phase)
        assertEquals(library.subscriptions.value.map { it.channelId }.toSet(), fresh.subscriptions.value.map { it.channelId }.toSet())
        assertEquals(library.history.value.map { it.item.id }.toSet(), fresh.history.value.map { it.item.id }.toSet())
        assertEquals(library.watchLater.value.map { it.id }, fresh.watchLater.value.map { it.id })
        assertEquals(library.playlists.value.map { it.name }, fresh.playlists.value.map { it.name })
    }

    // ---- The file picker ----------------------------------------------------------------------------------------------------

    @Test fun thePickerIsAnExpectClassWithTheDocumentPickerOnAndroidAndTheFileDialogOnTheDesktop() {
        val expect = code("FilePicker")
        assertTrue("expect class FilePickerLauncher" in expect && "fun open(mimeTypes: List<String>)" in expect && "fun create(suggestedName: String, mime: String)" in expect)
        assertTrue(Regex("""@Composable\s+expect fun rememberFilePicker\(onOpened: \(PickedFile\) -> Unit, onCreated: \(CreatedFile\) -> Unit\): FilePickerLauncher""").containsMatchIn(expect))
        val android = VideoSourceContract.code(VideoSourceContract.read(androidPicker))
        assertTrue("actual class FilePickerLauncher" in android && "actual fun rememberFilePicker(onOpened: (PickedFile) -> Unit, onCreated: (CreatedFile) -> Unit): FilePickerLauncher" in android)
        assertTrue("rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())" in android)
        assertTrue("Intent.ACTION_CREATE_DOCUMENT" in android && "Intent.CATEGORY_OPENABLE" in android)
        assertTrue("::AndroidSqliteRows" in android && "cacheDir" in android)
        val desktop = VideoSourceContract.code(VideoSourceContract.read(desktopPicker))
        assertTrue("actual class FilePickerLauncher" in desktop && "FileDialog.LOAD" in desktop && "FileDialog.SAVE" in desktop && "java.awt.FileDialog" in VideoSourceContract.read(desktopPicker))
        assertTrue("::JdbcSqliteRows" in desktop)
    }

    // ---- Rules of every page ------------------------------------------------------------------------------------------------

    @Test fun everyLazyListHasKeysAndEveryTextIsAString() {
        for (name in pages + "LibraryParts") {
            val src = code(name)
            for (call in Regex("""\b(items|itemsIndexed)\(""").findAll(src)) {
                val head = src.substring(call.range.first, minOf(src.length, call.range.first + 160))
                assertTrue("key =" in head, "$name: a list without keys: $head")
            }
            for (call in Regex("""\bitem\(""").findAll(src)) {
                val head = src.substring(call.range.first, minOf(src.length, call.range.first + 60))
                assertTrue("key =" in head, "$name: an item without a key: $head")
            }
            assertFalse(Regex("""Text\(\s*(text = )?"""").containsMatchIn(src), "$name: a literal text")
            assertFalse(Regex("""(contentDescription|label|message|title|placeholder) = "[^"]""").containsMatchIn(src), "$name: a literal label")
        }
    }

    /** The text before the `{` of every block that encloses [index], innermost first (as the shell's test reads it). */
    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$""")

    @Test fun theLivePaletteIsReadOnlyWhileDrawingAndNoColourIsPickedByHand() {
        for ((name, source) in everySource) {
            val src = VideoSourceContract.code(source)
            var from = 0
            while (true) {
                val at = src.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (src[at - 1].isLetterOrDigit() || src[at - 1] == '_')) continue
                assertTrue(enclosingHeads(src, at).any { drawHead.containsMatchIn(it) }, "$name: live( outside a draw lambda at ${src.take(at).count { it == '\n' } + 1}")
            }
            assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(src), "$name uses a named colour")
            assertFalse("positionMs()" in src, "$name reads the playhead")
            assertFalse(Regex("""(?i)youtube|\bYT\b|cookie|OkHttpClient""").containsMatchIn(source), "$name names the source, touches cookies or builds a client")
        }
    }

    @Test fun cardsShowTheDeArrowTitleAndPictureAndTheDownloadedBadge() {
        val parts = code("LibraryParts")
        assertTrue("rememberVideoTitle(" in parts && "rememberVideoThumbnail(" in parts)
        for (name in listOf("HistoryPage", "VideoListPage", "LibraryPage")) {
            assertTrue("rememberDownloadedIds()" in code(name), "$name marks downloaded videos")
            assertTrue("actions.play(" in code(name), "$name: a tap plays through the shared handler")
            assertTrue("VideoActionsSheet(" in code(name), "$name: more opens the video sheet")
        }
    }
}
