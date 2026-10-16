package com.cosmotune.video.ui

import androidx.compose.runtime.saveable.SaverScope
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.downloads.DownloadItemAction
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.DownloadsFilter
import com.cosmotune.video.downloads.DownloadsSort
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.downloads.DownloadsMetrics
import com.cosmotune.video.ui.downloads.DownloadsPageState
import com.cosmotune.video.ui.downloads.cancelBatch
import com.cosmotune.video.ui.downloads.label
import com.cosmotune.video.ui.downloads.playDownload
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Downloads page (plan task Y-33, step 5; spec 5.10). What no render can show is read from the source: the nav host's branch,
 * the page's signature, that every state's words are `video_dl_*` keys, the item sheet's rows, no blur, the live palette only while
 * drawing, keys on the lazy list, reduced motion, the 840 dp column, the swipe only on the phone and the downloaded badge. The page's
 * own pieces of logic are run: a finished download's tap plays it offline and opens the watch page, a batch's Cancel keeps what is
 * downloaded, and the page's saved state comes back.
 */
class DownloadsPageStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/downloads"
    private val files = listOf("DownloadsPage", "DownloadBatchHeader", "DownloadItemRow", "DownloadItemSheet", "DownloadsPlan")
    private fun file(name: String) = VideoSourceContract.read("$dir/$name.kt")
    private fun code(name: String) = VideoSourceContract.code(file(name))
    private val strings by lazy { VideoSourceContract.read("video/src/commonMain/composeResources/values/strings.xml") }

    private val video = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))

    // ---- Where the page stands -----------------------------------------------------------------------------------------

    @Test fun theDirectoryHoldsTheBriefsFilesAndThePlatformsCopyToMovies() {
        val names = File(VideoSourceContract.root, dir).listFiles { f -> f.extension == "kt" }!!.map { it.nameWithoutExtension }.toSet()
        assertTrue(names.containsAll(files), "missing: ${files.toSet() - names}")
        val android = VideoSourceContract.read("video/src/androidMain/kotlin/com/cosmotune/video/ui/downloads/DownloadItemSheet.android.kt")
        val desktop = VideoSourceContract.read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/downloads/DownloadItemSheet.jvm.kt")
        assertTrue("internal actual fun rememberSaveCopy(): SaveCopy?" in android && "SaveCopyToMovies(context)" in android, "Android copies through Y-24's SaveCopyToMovies")
        assertTrue("LocalContext.current.applicationContext" in android, "from the application context, never an activity")
        assertTrue("internal actual fun rememberSaveCopy(): SaveCopy? = null" in desktop, "the desktop has no Movies copy")
        assertTrue("internal expect fun rememberSaveCopy(): SaveCopy?" in code("DownloadItemSheet"))
    }

    @Test fun theNavHostsDownloadsBranchIsThePageOnItsOwnLine() {
        val lines = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt").lines()
        val branch = lines.filter { it.trim().startsWith("is VideoRoute.Downloads ->") }
        assertEquals(1, branch.size, "one branch for Downloads")
        assertEquals(
            "                is VideoRoute.Downloads -> com.cosmotune.video.ui.downloads.DownloadsPage(contentPadding, wide)   // Y-33: the Downloads page",
            branch.single(),
        )
        val at = lines.indexOf(branch.single())
        assertTrue(lines[at - 1].isBlank() && lines[at + 1].isBlank(), "the blank lines around the branch stay")
        assertTrue(lines.none { it.startsWith("import com.cosmotune.video.ui.downloads") }, "a fully qualified name, no import line")
    }

    @Test fun thePageHasTheBriefsSignature() {
        assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), VideoSourceContract.parameters(file("DownloadsPage"), "DownloadsPage"))
        assertTrue("@Composable\nfun DownloadsPage(" in file("DownloadsPage"), "public and composable")
    }

    @Test fun thePageReadsTheEngineAndHandsItsActionsToIt() {
        val src = code("DownloadsPage")
        assertTrue("downloads.downloads.collectAsState()" in src && "downloads.batches.collectAsState()" in src)
        for (call in listOf("retry(", "pause(", "resume(", "cancel(", "remove(", "pauseBatch(", "resumeBatch(", "cancelBatch(")) {
            assertTrue("downloads.$call" in src, "the page calls downloads.$call")
        }
        assertTrue("withContext(Dispatchers.IO) { downloads.storage() }" in src, "the storage line is measured off the main thread")
        assertTrue("stringResource(Res.string.video_dl_storage, sizes.first, sizes.second)" in src)
        assertTrue("DownloadsPlan.build(entries, batches, page.filter, page.sort, page.collapsed)" in src, "the rows are the plan's")
        assertTrue("DownloadsPlan.actionsFor(sheetEntry, canSaveCopy = saveCopy != null)" in src, "the sheet offers the plan's rows, Movies only where the platform has it")
    }

    // ---- The words -----------------------------------------------------------------------------------------------------

    @Test fun everyStateSaysItsWordsThroughAVideoDlKey() {
        val body = VideoSourceContract.functionBody(file("DownloadItemRow"), "downloadStateText")
        val expected = mapOf(
            "DownloadState.Waiting ->" to "video_dl_waiting)",
            "DownloadState.WaitingForWifi ->" to "video_dl_waiting_wifi)",
            "is DownloadState.Paused ->" to "video_dl_paused_battery else Res.string.video_dl_paused)",
            "is DownloadState.Running ->" to "video_dl_running, DownloadsPlan.percent(s.fraction))",
            "DownloadState.Joining ->" to "video_dl_joining)",
            "is DownloadState.Done ->" to "doneText(entry)",
            "is DownloadState.Failed ->" to "video_dl_failed), stringResource(Res.string.video_retry))",
        )
        for ((branch, words) in expected) {
            val line = body.lines().singleOrNull { branch in it } ?: error("no branch $branch")
            assertTrue(words in line, "$branch says $words: $line")
        }
        val done = VideoSourceContract.functionBody(file("DownloadItemRow"), "doneText")
        for (key in listOf("video_dl_audio_only", "video_dl_done", "video_dl_subs_got", "video_dl_subs_none", "video_meta_join")) assertTrue("Res.string.$key" in done, "done uses $key")
    }

    @Test fun everyKeyThePageUsesIsDeclaredAndNoTextIsALiteral() {
        val declared = Regex("""name="(video_\w+)"""").findAll(strings).map { it.groupValues[1] }.toSet()
        for (name in files) {
            val src = code(name)
            for (used in Regex("""Res\.(?:string|plurals)\.(video_\w+)""").findAll(src).map { it.groupValues[1] }) {
                assertTrue(used in declared, "$name uses $used, which is declared")
            }
            assertFalse(Regex("""Text\(\s*"""").containsMatchIn(src), "$name: no literal text")
            assertFalse(Regex("""(?i)\b(youtube|yt)\b""").containsMatchIn(file(name)), "$name: no source name")
        }
        for (key in listOf("video_dl_sort", "video_dl_show_items", "video_dl_hide_items", "video_dl_delete_confirm", "video_dl_save_copy_failed")) {
            assertTrue(key in declared, "this task's key $key")
        }
    }

    @Test fun theSheetHasEveryActionOfTheBriefWithItsOwnWords() {
        val src = code("DownloadItemSheet")
        val labels = DownloadItemAction.entries.associateWith { it.label().key }
        assertEquals("video_play", labels[DownloadItemAction.PLAY])
        assertEquals("video_dl_pause", labels[DownloadItemAction.PAUSE])
        assertEquals("video_dl_resume", labels[DownloadItemAction.RESUME])
        assertEquals("video_retry", labels[DownloadItemAction.RETRY])
        assertEquals("video_dl_cancel", labels[DownloadItemAction.CANCEL])
        assertEquals("video_dl_delete", labels[DownloadItemAction.DELETE])
        assertEquals("video_dl_save_copy", labels[DownloadItemAction.SAVE_COPY])
        assertTrue("SurfaceSheetSwitch(" in src && "CosmoActionRow(" in src && "skipPartiallyExpanded = true" in src, "part two's sheet with part two's rows")
        assertTrue(Regex("""onAction\(action\)\s+onDismiss\(\)""").containsMatchIn(src), "a row says what was tapped, then the sheet closes")
        assertTrue("if (action == DownloadItemAction.DELETE) CosmoSheetGap(menu = false)" in src, "the delete stands apart")
        assertTrue("CosmoDialogCard(" in src && "video_dl_delete_confirm" in src, "the delete asks first")
        val page = code("DownloadsPage")
        assertTrue("DownloadItemAction.DELETE -> deleting = entry.id" in page, "the sheet's Delete opens the question, it does not delete")
        assertTrue("onSwipeDelete = if (wide) null else ({ deleting = entry.id })" in page, "a swipe asks too, and only on the phone")
        assertTrue("showToast(if (saved) Res.string.video_dl_saved_copy else Res.string.video_dl_save_copy_failed)" in page, "a toast after the copy")
    }

    // ---- The look ------------------------------------------------------------------------------------------------------

    @Test fun nothingIsBlurred() {
        for (name in files) {
            val src = code(name)
            assertFalse(Regex("""hazeSource|hazeEffect|HazeState|\.blur\(|cosmoGlass\(""").containsMatchIn(src), "$name: no blur and no glass of its own (the sheet and the card are part two's, unblurred)")
        }
    }

    @Test fun theLivePaletteIsReadOnlyWhileDrawingAndNoColourIsPickedByHand() {
        var seen = 0
        for (name in files) {
            val src = code(name)
            var from = 0
            while (true) {
                val at = src.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (src[at - 1].isLetterOrDigit() || src[at - 1] == '_')) continue
                seen++
                assertTrue(enclosingHeads(src, at).any { drawHead.containsMatchIn(it) }, "$name: live( at line ${src.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
            assertFalse(Regex("""Color\.(Black|White|Red|Green|Blue|Gray)|Color\(0x""").containsMatchIn(src), "$name: colours come from the palette")
            assertFalse("positionMs" in src, "$name: nothing here reads the playhead")
            assertFalse(Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""").containsMatchIn(src), "$name remembers a brush or a live colour")
        }
        assertTrue(seen >= 4, "the ring, the line, the swipe's reveal and its block read the live palette (found $seen)")
    }

    @Test fun everyClickableHasARoleAndTheListHasKeys() {
        for (name in files) {
            val src = code(name)
            for (match in Regex("""\b(clickable|combinedClickable|selectable|toggleable)\(""").findAll(src)) {
                assertTrue("role =" in argumentsOf(src, match.range.last), "$name: ${match.value} has no role")
            }
        }
        val page = code("DownloadsPage")
        assertTrue("items(rows, key = { it.key }" in page, "the rows have stable keys")
        for (key in listOf("\"heading\"", "\"storage\"", "\"filters\"", "\"empty\"")) assertTrue("item(key = $key" in page, "the page's own blocks have keys: $key")
    }

    @Test fun reducedMotionTurnsTheAnimationsOff() {
        val page = code("DownloadsPage")
        assertTrue("val motion = if (reduced) Modifier else Modifier.animateItem()" in page, "no item animations under reduced motion")
        val header = code("DownloadBatchHeader")
        assertTrue("CosmoMotion.move(reduced)" in header && "LocalReducedMotion.current" in header, "the chevron's turn follows reduced motion")
        assertTrue("graphicsLayer { rotationZ = turn.value }" in header, "the turn is read in the layer")
    }

    @Test fun wideIsTheSameListAtMost840Wide() {
        assertEquals(840f, DownloadsMetrics.LIST_MAX_WIDTH_DP)
        assertEquals(12f, DownloadsMetrics.WIDE_SIDE_DP, "the rows' 16 and these 12 make the desktop's 28")
        val page = code("DownloadsPage")
        assertTrue(Regex("""downloadsColumn\(wide\)""").findAll(page).count() >= 6, "every block of the page stands in the column")
        assertTrue("desktop = wide" in page, "the desktop's rows have the desktop's thumbnail")
    }

    @Test fun theNumbersOfTheSpec() {
        assertEquals(24f, DownloadsMetrics.RING_DP)
        assertEquals(24f, DownloadsMetrics.BLOB_DP)
        assertEquals(3f, DownloadsMetrics.BATCH_LINE_DP)
        assertEquals(40f, DownloadsMetrics.BATCH_BUTTON_DP)
        assertEquals(13f, DownloadsMetrics.STORAGE_SP)
        assertEquals(12.5f, DownloadsMetrics.STATE_SP)
        val header = code("DownloadBatchHeader")
        assertTrue(Regex("""style = CosmoIconButtonStyle\.TONAL,\s+size = DownloadsMetrics\.BATCH_BUTTON_DP\.dp""").findAll(header).count() == 2, "Pause or Resume and Cancel: 40 dp TONAL")
        assertTrue("if (batch.unfinished > 0)" in header, "nothing to pause or cancel once all of it is done or failed")
        val page = code("DownloadsPage")
        assertTrue("type.display(TypeScale.DISPLAY_M)" in page, "\"Downloads\" in display(DISPLAY_M)")
        assertTrue("CosmoIcons.Sort" in page && "DropdownMenu(" in page && "DownloadsSort.entries" in page, "the sort menu: Newest, Oldest, Title, Size")
        assertTrue("CosmoEmptyState(" in page && "Res.string.video_dl_empty" in page, "\"Nothing downloaded yet\"")
        val row = code("DownloadItemRow")
        assertTrue("CosmoLoadingBlob(Modifier.size(DownloadsMetrics.BLOB_DP.dp))" in row, "joining: the 24 dp blob")
        assertTrue("is DownloadState.Running -> EndBox { DownloadRing(state.fraction) }" in row, "running: the ring")
        assertTrue("Color(colors.palette.track)" in row && "colors.live(CoverField.ACCENT)" in row, "accent on track")
    }

    @Test fun aFinishedRowShowsTheDownloadedBadge() {
        val row = code("DownloadItemRow")
        assertTrue("val finished = entry.state is DownloadState.Done" in row && "downloaded = finished" in row, "the badge on a finished download's thumbnail")
        assertTrue("VideoRowFrame(" in row && "Modifier.rowThumbnailSize(desktop)" in row, "a video row (Y-13's frame and thumbnail)")
        assertTrue("rememberVideoTitle(item)" in row && "rememberVideoThumbnail(item)" in row, "the title and picture every card shows")
    }

    // ---- Run -----------------------------------------------------------------------------------------------------------

    @Test fun aFinishedDownloadsTapPlaysItOfflineThenOpensTheWatchPage() = runBlocking {
        val downloads = FakeDownloadController()
        downloads.enqueue(FakeVideoSource.videos[0], video)
        val waiting = downloads.downloads.value.single()
        val playback = FakeVideoPlayback()
        val navigator = VideoNavigator()
        assertFalse(playDownload(playback, navigator, waiting), "nothing to play until it is done")
        assertTrue(playback.calls.isEmpty())
        assertEquals(VideoRoute.Home, navigator.top)
        downloads.runUntilIdle()
        val done = downloads.downloads.value.single()
        assertTrue(done.state is DownloadState.Done)
        assertTrue(playDownload(playback, navigator, done))
        assertEquals(listOf("playLocal(fakevideo01)"), playback.calls, "the files play, no source call")
        assertEquals(VideoRoute.Watch("fakevideo01"), navigator.top, "then the watch page opens on it")
        val page = code("DownloadsPage")
        assertTrue("DownloadItemAction.PLAY -> playOffline(entry)" in page && "playDownload(graph.playback, navigator, entry)" in page, "a finished tap goes there")
        val body = VideoSourceContract.functionBody(file("DownloadsPage"), "playDownload")
        assertTrue(body.indexOf("playLocal(") < body.indexOf("navigator.push(VideoRoute.Watch("), "playLocal first, then the watch page")
        assertTrue("withContext(Dispatchers.IO) { entry.readLocalBundle() }" in body, "the sidecar is read off the main thread")
    }

    @Test fun aBatchsCancelKeepsWhatIsAlreadyDownloaded() {
        val info = DownloadBatchInfo("pl-1", BatchKind.PLAYLIST, "Road trip")
        val items = FakeVideoSource.videos.take(3)
        val fresh = FakeDownloadController()
        fresh.enqueueBatch(info, items, video)
        cancelBatch(fresh, fresh.downloads.value, "pl-1")
        assertTrue(fresh.downloads.value.isEmpty() && fresh.batches.value.isEmpty(), "nothing finished: the whole batch goes")

        val started = FakeDownloadController()
        started.enqueueBatch(info, items, video)
        repeat(7) { started.advance() } // the first is done
        cancelBatch(started, started.downloads.value, "pl-1")
        assertEquals(listOf(items[0].id), started.downloads.value.map { it.item.id }, "the finished video stays")
        assertTrue(started.downloads.value.single().state is DownloadState.Done)
    }

    @Test fun thePagesStateComesBackAfterItWasSaved() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val state = DownloadsPageState(DownloadsFilter.SHORTS, DownloadsSort.SIZE, setOf("pl-1"))
        state.toggle("feed-1")
        val saved = with(DownloadsPageState.Saver) { scope.save(state) }!!
        val back = DownloadsPageState.Saver.restore(saved)!!
        assertEquals(DownloadsFilter.SHORTS, back.filter)
        assertEquals(DownloadsSort.SIZE, back.sort)
        assertEquals(setOf("pl-1", "feed-1"), back.collapsed)
        back.toggle("pl-1")
        assertEquals(setOf("feed-1"), back.collapsed)
        val unknown = DownloadsPageState.Saver.restore(listOf("NOPE", "NEVER"))!!
        assertEquals(DownloadsFilter.ALL, unknown.filter)
        assertEquals(DownloadsSort.NEWEST, unknown.sort)
        assertTrue(unknown.collapsed.isEmpty())
    }

    @Test fun theSortMenuNamesEachSortWithItsOwnKey() {
        assertEquals(
            listOf("video_dl_sort_newest", "video_dl_sort_oldest", "video_dl_sort_title", "video_dl_sort_size"),
            DownloadsSort.entries.map { it.label().key },
        )
        assertNull(DownloadsSort.entries.firstOrNull { it.label().key !in strings })
    }

    // ---- Helpers (as VideoBlocksStructureTest's) -----------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
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

    /** The text inside the parentheses that open at [open] in [code]. */
    private fun argumentsOf(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        error("the parenthesis at $open is never closed")
    }
}
