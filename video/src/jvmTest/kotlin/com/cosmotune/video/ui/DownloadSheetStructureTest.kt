package com.cosmotune.video.ui

import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.DownloadAsk
import com.cosmotune.video.ui.actions.DownloadReopen
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.actions.cancelStarted
import com.cosmotune.video.ui.actions.reopen
import com.cosmotune.video.ui.actions.startDownload
import com.cosmotune.video.ui.downloads.DIALOG_WIDTH
import com.cosmotune.video.ui.downloads.DownloadAskResolver
import com.cosmotune.video.ui.downloads.DownloadSheetState
import com.cosmotune.video.ui.downloads.ROW_HEIGHT
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The download sheet, its dialog and its host (spec 5.16, plan task Y-32 step 5). What needs no window is run (the host's queueing,
 * "Change", the sheet's state, "Remember my choice"); what does is pinned in the source: no blur, 54 dp rows, the button's text for one
 * and for a batch, the keyboard of the dialog, the desktop's dialog against the phone's sheet, and no download without the host.
 */
class DownloadSheetStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private fun code(path: String) = VideoSourceContract.code(read(path))

    private val downloadsUi = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/downloads"
    private val host = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/actions/DownloadOptionsHost.kt"
    private val handler = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/actions/VideoActionHandler.kt"
    private val mine = listOf("DownloadOptionsSheet", "DownloadOptionsDialog", "SubtitleLanguageList", "DownloadAskResolver", "LatestCountPicker", "DownloadToast")
        .map { "$downloadsUi/$it.kt" } + host

    private val video = FakeVideoSource.videos[0]
    private val playlist = DownloadBatchInfo("PLfakeplaylist0000000001", BatchKind.PLAYLIST, "A playlist")
    private val channel = DownloadBatchInfo("UCfakechannel0000000001", BatchKind.CHANNEL_LATEST, "A channel")
    private val request = DownloadRequest(DownloadQuality.Rung(720, 60), SubtitlePick(true, "en"))

    @AfterTest fun clearTheReopenedAsk() {
        DownloadReopen.pending = null
    }

    // ---- The look --------------------------------------------------------------------------------------------------------------

    @Test fun theSheetAndTheDialogUseNoBlur() {
        assertTrue("SurfaceSheetSwitch(" in code("$downloadsUi/DownloadOptionsSheet.kt"), "the phone's sheet is part two's sheet host")
        assertTrue("SurfaceSheetSwitch(" in code("$downloadsUi/SubtitleLanguageList.kt"), "the language list too")
        val surface = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/sheets/CosmoSheetSurface.kt")
        assertTrue(".cosmoGlass(hazeState = null, shape = SHEET_SHAPE)" in surface, "whose surface has no backdrop blur")
        assertTrue("CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth, hazeState = null)" in code("$downloadsUi/DownloadOptionsDialog.kt"))
        assertTrue(".cosmoGlass(hazeState = null, shape = TOAST_SHAPE)" in code("$downloadsUi/DownloadToast.kt"))
        for (path in mine) {
            val src = code(path)
            assertFalse(Regex("""HazeState|hazeSource|hazeEffect|hazeChild|\.blur\(""").containsMatchIn(src), "$path: no blur")
            for (m in Regex("""hazeState = (\w+)""").findAll(src)) assertEquals("null", m.groupValues[1], "$path: no backdrop")
        }
    }

    @Test fun theRowsAre54dpTall() {
        assertEquals(54f, ROW_HEIGHT.value)
        val sheet = code("$downloadsUi/DownloadOptionsSheet.kt")
        assertTrue("internal val ROW_HEIGHT = 54.dp" in sheet)
        assertTrue(".heightIn(min = ROW_HEIGHT)" in VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsSheet.kt"), "DownloadSheetRow"))
        // every choice is one of the sheet rows
        val quality = VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsSheet.kt"), "QualityRows")
        assertEquals(3, Regex("""DownloadRadioRow\(""").findAll(quality).count(), "Auto, each rung, audio only")
        assertTrue("selectableGroup()" in quality)
        assertTrue(".heightIn(max = QUALITY_MAX_HEIGHT).verticalScroll(scroll)" in quality && "QUALITY_MAX_HEIGHT = ROW_HEIGHT * 5.5f" in sheet, "a full ladder scrolls, the button stays on screen")
        assertTrue("DownloadRadioRow(" in code("$downloadsUi/SubtitleLanguageList.kt"))
    }

    @Test fun theSheetHoldsWhatSpec516Lists() {
        val sheet = code("$downloadsUi/DownloadOptionsSheet.kt")
        val body = VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsSheet.kt"), "DownloadOptionsBody")
        // the order: head, the latest picker, the options (or the blob while they load), the button
        val order = listOf("DownloadSheetHead(state)", "if (state.picker) LatestCountPicker(", "CosmoLoadingBlob()", "QualityRows(", "SubtitleRows(", "Res.string.video_dl_remember", "CosmoButton(")
        val at = order.map { body.indexOf(it) }
        assertTrue(at.all { it >= 0 } && at == at.sorted(), "the sheet's order: $at")
        for (key in listOf(
            "video_dl_header_batch", "video_dl_quality", "video_dl_auto", "video_dl_auto_means", "video_dl_audio_only", "video_dl_estimate",
            "video_dl_subtitles_q", "video_dl_subtitle_language", "video_dl_remember",
        )) assertTrue("Res.string.$key" in sheet, key)
        assertTrue("Modifier.size(HEAD_THUMB_W, HEAD_THUMB_H)" in sheet && "HEAD_THUMB_W = 96.dp" in sheet && "HEAD_THUMB_H = 54.dp" in sheet, "the 96 x 54 thumbnail")
        assertTrue("SIZE_SP = 13f" in sheet && "fontFeatureSettings = \"tnum\"" in sheet && "color = Color(palette.textTertiary)" in sheet, "the size: 13 sp tabular textTertiary")
        assertTrue("DETAIL_SP = 12.5f" in sheet, "the codec in 12.5 sp")
        val list = code("$downloadsUi/SubtitleLanguageList.kt")
        assertTrue("Res.string.video_dl_auto_generated" in list && "Res.string.video_dl_each_original" in list)
        val picker = code("$downloadsUi/LatestCountPicker.kt")
        assertTrue("for (count in LatestCount.CHOICES)" in picker && "CosmoChip(" in picker && "Res.string.video_dl_latest_count" in picker)
    }

    @Test fun theButtonSaysDownloadForOneAndDownloadNForABatch() {
        val body = VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsSheet.kt"), "DownloadOptionsBody")
        assertTrue("is DownloadAsk.One -> stringResource(Res.string.video_dl_button)" in body)
        assertTrue("is DownloadAsk.Batch -> stringResource(Res.string.video_dl_button_n, state.items.size)" in body)
        assertTrue("kind = CosmoButtonKind.PRIMARY" in body && "modifier = Modifier.fillMaxWidth()" in body, "the primary button, full width")
        val strings = read("video/src/commonMain/composeResources/values/strings.xml")
        assertTrue("<string name=\"video_dl_button\">Download</string>" in strings)
        assertTrue("<string name=\"video_dl_button_n\">Download %1\$d</string>" in strings)
        assertTrue("<string name=\"video_dl_header_batch\">Download %1\$d videos</string>" in strings)
    }

    // ---- The keyboard and the platforms ----------------------------------------------------------------------------------------

    @Test fun theDialogIs440dpAndKeyboardReachable() {
        assertEquals(440f, DIALOG_WIDTH.value)
        val dialog = code("$downloadsUi/DownloadOptionsDialog.kt")
        assertTrue("PopupProperties(focusable = true" in dialog, "a focusable window takes the keys")
        val keys = VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsDialog.kt"), "dialogKey")
        assertTrue("Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)" in keys && "Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)" in keys, "the arrows move")
        assertTrue("Key.Escape ->" in keys && "if (choosingLanguage) onBack() else onDismiss()" in keys, "Escape closes or goes back")
        assertTrue("Key.Enter, Key.NumPadEnter ->" in keys && "if (canDownload) onConfirm()" in keys, "Enter downloads")
        val row = VideoSourceContract.functionBody(read("$downloadsUi/DownloadOptionsSheet.kt"), "DownloadSheetRow")
        assertTrue("event.key != Key.Spacebar" in row && "onSpace()" in row, "Space chooses the focused row")
        assertTrue(".cosmoFocusRing(interaction, ROW_RADIUS, floating = true)" in row, "the focus ring")
        assertTrue("firstFocus.requestFocus()" in dialog, "the chosen row takes the focus")
    }

    @Test fun theDesktopGetsTheDialogAndThePhoneTheSheet() {
        val src = code(host)
        val ask = VideoSourceContract.functionBody(read(host), "AskForOptions")
        val desktop = ask.indexOf("if (navigator === DesktopVideoState.navigator)")
        assertTrue(desktop >= 0)
        assertTrue(ask.indexOf("DownloadOptionsDialog(state, onConfirm = confirm, onDismiss = close)") > desktop)
        assertTrue(ask.indexOf("DownloadOptionsSheet(state, onConfirm = confirm, onDismiss = close)") > ask.indexOf("} else {", desktop))
        val entry = code("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/desktop/VideoDesktopEntry.kt")
        assertTrue("DesktopVideoState.navigator" in entry && "LocalVideoNavigator provides navigator" in entry, "the desktop page provides its one navigator")
        assertTrue("@Composable\nfun DownloadOptionsHost(ask: DownloadAsk?, onDismiss: () -> Unit)" in src, "Y-16's signature is kept")
    }

    // ---- Nothing downloads without the host --------------------------------------------------------------------------------------

    @Test fun noDownloadStartsWithoutTheHost() {
        val root = File(VideoSourceContract.root, "video/src")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" && "${File.separator}jvmTest${File.separator}" !in it.path }
            .filterNot { "${File.separator}downloads${File.separator}android${File.separator}" in it.path || "${File.separator}downloads${File.separator}desktop${File.separator}" in it.path }
            .toList()
        assertTrue(sources.size > 50)
        val callers = sources.filter { Regex("""\.enqueue(Batch)?\(""").containsMatchIn(VideoSourceContract.code(it.readText())) }.map { it.name }
        assertEquals(listOf("DownloadOptionsHost.kt"), callers, "only the host queues a download (after its sheet or a remembered choice)")
        val shown = sources.filter { f ->
            val text = VideoSourceContract.code(f.readText())
            Regex("""(?<!fun )(DownloadOptionsSheet|DownloadOptionsDialog)\(""").containsMatchIn(text)
        }.map { it.name }.toSet()
        assertEquals(setOf("DownloadOptionsHost.kt"), shown, "only the host shows the sheet or the dialog")
        val download = VideoSourceContract.functionBody(read(handler), "download")
        assertTrue("state.downloadAsk = DownloadAsk.One(item)" in download, "a card's Download only asks")
        val body = VideoSourceContract.functionBody(read(handler), "VideoActionsHost")
        assertTrue("DownloadOptionsHost(ask = state.downloadAsk, onDismiss = { state.downloadAsk = null })" in body)
    }

    // ---- The host, run ------------------------------------------------------------------------------------------------------------

    @Test fun rememberMyChoiceWritesTheDownloadPrefs() {
        val settings = FakeVideoSettingsRepository()
        settings.update { DownloadAskResolver.confirm(it, request, remember = true) }
        assertEquals(DownloadPrefs(askBeforeEach = false, remembered = request), settings.settings.value.downloads)
        settings.update { DownloadAskResolver.confirm(it, DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(false, null)), remember = false) }
        assertEquals(DownloadPrefs(askBeforeEach = false, remembered = request), settings.settings.value.downloads, "unticked leaves it")
        val confirm = VideoSourceContract.functionBody(read(host), "AskForOptions")
        assertTrue("graph.settings.update { DownloadAskResolver.confirm(it, request, remember) }" in confirm)
        assertTrue("startDownload(graph.downloads, ask, request, state.count)" in confirm)
        assertTrue("showToast(ActionToastKind.DOWNLOAD_ADDED, onAction = { openDownloads(navigator) })" in confirm, "Added to Downloads · View")
    }

    @Test fun aVideoIsQueuedOnceAndChangeTakesItBack() {
        val downloads = FakeDownloadController()
        val toasts = VideoActionsState()
        val ask = DownloadAsk.One(video)
        val started = startDownload(downloads, ask, request, count = 10)
        assertEquals(1, started.size)
        val entry = downloads.downloads.value.single()
        assertEquals(video.id, entry.item.id)
        assertEquals(request, entry.request)
        assertEquals(emptyList(), startDownload(downloads, ask, request, count = 10), "queued already: nothing new")
        reopen(downloads, toasts, ask, started)
        assertTrue(downloads.downloads.value.isEmpty(), "Change takes back what the remembered choice queued")
        assertSame(ask, toasts.downloadAsk, "and asks again")
        assertSame(ask, DownloadReopen.pending, "with the sheet")
    }

    @Test fun changeLeavesAFinishedDownload() {
        val downloads = FakeDownloadController()
        val started = startDownload(downloads, DownloadAsk.One(video), request, count = 10)
        downloads.runUntilIdle()
        cancelStarted(downloads, started)
        assertTrue(downloads.downloads.value.single().state is DownloadState.Done)
    }

    @Test fun aBatchQueuesTheNewestNAndSkipsWhatIsDownloaded() {
        val downloads = FakeDownloadController()
        val items = FakeVideoSource.videos.take(8)
        downloads.enqueue(items[0], request)
        downloads.runUntilIdle()
        downloads.enqueue(items[1], request)
        downloads.fail(downloads.downloads.value.single { it.item.id == items[1].id }.id)
        val started = startDownload(downloads, DownloadAsk.Batch(channel, items, latestPicker = true), request, count = 5)
        assertEquals(4, started.size, "the newest five less the finished one; the failed one comes back")
        assertEquals(items.take(5).map { it.id }.toSet(), downloads.downloads.value.map { it.item.id }.toSet())
        assertEquals(channel, downloads.batches.value.single().info)
        val all = startDownload(FakeDownloadController(), DownloadAsk.Batch(playlist, items, latestPicker = false), request, count = 5)
        assertEquals(8, all.size, "no picker: the whole list")
    }

    @Test fun theToastsCarryTheirTextAndAction() {
        val state = VideoActionsState()
        var changed = 0
        state.showToast(ActionToastKind.DOWNLOAD_STARTED, detail = "720p", onAction = { changed += 1 })
        val toast = assertNotNull(state.toast)
        assertEquals("720p", toast.detail)
        toast.onAction?.invoke()
        assertEquals(1, changed)
        state.showToast(ActionToastKind.ALREADY_DOWNLOADED)
        assertNull(state.toast?.onAction)
        val strings = read("video/src/commonMain/composeResources/values/strings.xml")
        for ((key, text) in listOf(
            "video_dl_toast_started" to "Downloading in %1\$s", "video_dl_toast_change" to "Change", "video_dl_added" to "Added to Downloads",
            "video_dl_view" to "View", "video_dl_already" to "Already downloaded", "video_dl_in_downloads" to "Already in Downloads",
        )) assertTrue("<string name=\"$key\">$text</string>" in strings, key)
        val toastHost = VideoSourceContract.functionBody(read(handler), "ActionToastHost")
        assertTrue("DownloadToast(" in toastHost && "CosmoToast(message" in toastHost && "state.clearToast(toast)" in toastHost)
        val hostSrc = VideoSourceContract.functionBody(read(host), "DownloadOptionsHost")
        assertTrue("showToast(ActionToastKind.DOWNLOAD_STARTED, detail = label, onAction = { reopen(graph.downloads, toasts, ask, started) })" in hostSrc, "Downloading in 720p · Change")
        assertTrue("showToast(ActionToastKind.ALREADY_DOWNLOADED)" in hostSrc)
    }

    // ---- The sheet's state, run ---------------------------------------------------------------------------------------------------

    @Test fun theSheetStartsOnTheRememberedChoiceOnceItsOptionsCome() = runTest {
        val prefs = DownloadPrefs(askBeforeEach = false, remembered = DownloadRequest(DownloadQuality.Rung(1080, 60), SubtitlePick(true, "hi")))
        val state = DownloadSheetState(DownloadAsk.One(video), prefs, appLanguage = "en", entries = emptyList())
        assertFalse(state.canDownload, "nothing to press while the options load")
        assertTrue(state.remember, "a remembered choice keeps the box ticked")
        state.loaded(FakeDownloadController().options(video))
        assertEquals(DownloadQuality.Rung(1080, 60), state.quality)
        assertEquals("hi", state.language?.tag)
        assertTrue(state.canDownload)
        assertEquals(DownloadRequest(DownloadQuality.Rung(1080, 60), SubtitlePick(true, "hi")), state.request())
        state.subtitles = false
        assertEquals(SubtitlePick(false, null), state.request().subtitles)
    }

    @Test fun aFreshSheetAsksWithAutoAndTheBoxUnticked() = runTest {
        val state = DownloadSheetState(DownloadAsk.One(video), DownloadPrefs(), appLanguage = "en", entries = emptyList())
        assertFalse(state.remember)
        state.loaded(FakeDownloadController().options(video))
        assertEquals(DownloadQuality.Auto, state.quality)
        assertFalse(state.subtitles)
        assertEquals("en", state.language?.tag, "the app's language, ready when the switch goes on")
    }

    @Test fun theLatestPickerCountsWhatItWouldStillDownload() = runTest {
        val items = FakeVideoSource.videos.take(10)
        val engine = FakeDownloadController()
        engine.enqueue(items[0], request)
        engine.runUntilIdle()
        val state = DownloadSheetState(DownloadAsk.Batch(channel, items, latestPicker = true), DownloadPrefs(), "en", engine.downloads.value)
        assertTrue(state.picker)
        assertEquals(9, state.items.size, "ten newest, one of them downloaded")
        state.count = 5
        assertEquals(4, state.items.size)
        state.loaded(engine.batchOptions(state.items))
        state.quality = DownloadQuality.Rung(480, 30)
        state.count = 25
        state.loaded(engine.batchOptions(state.items))
        assertEquals(DownloadQuality.Rung(480, 30), state.quality, "a new count keeps the choices")
        assertEquals(9, state.items.size, "fewer loaded than asked: all of them")
    }

    @Test fun aFailureSaysWhyAndTryAgainAsksAgain() {
        val state = DownloadSheetState(DownloadAsk.One(video), DownloadPrefs(), "en", emptyList())
        state.failed(VideoSourceError.Network("offline", null))
        assertEquals(VideoUiError.NETWORK, state.error)
        val before = state.attempt
        state.retry()
        assertNull(state.error)
        assertEquals(before + 1, state.attempt)
    }

    // ---- The rules every new screen keeps ------------------------------------------------------------------------------------------

    @Test fun noColourByHandLiveColoursOnlyWhileDrawingNoPlayheadNoSourceName() {
        for (path in mine + handler) {
            val src = code(path)
            assertFalse(Regex("""Color\.(Black|White)|Color\(0x""").containsMatchIn(src), "$path: colours come from the palette")
            assertFalse("positionMs()" in src, "$path: no playhead")
            assertFalse(Regex("""(?i)youtube|\byt\b""").containsMatchIn(src), "$path: no source name")
            assertFalse("org.schabi" in src, "$path: no extractor")
            var from = 0
            while (true) {
                val at = src.indexOf(".live(", from)
                if (at < 0) break
                assertTrue(insideDrawing(src, at), "$path: live colours only while drawing (at ${src.substring(maxOf(0, at - 60), at)})")
                from = at + 1
            }
        }
    }

    /** Whether position [at] of [src] lies inside a drawing lambda (`drawBehind`, `drawWithCache`, `drawWithContent`, `graphicsLayer`, `Canvas`). */
    private fun insideDrawing(src: String, at: Int): Boolean {
        val open = ArrayDeque<Int>()
        for (i in 0 until at) {
            when (src[i]) {
                '{' -> open.addLast(i)
                '}' -> if (open.isNotEmpty()) open.removeLast()
            }
        }
        return open.any { brace ->
            val before = src.substring(maxOf(0, brace - 40), brace).trimEnd()
            Regex("""(drawBehind|drawWithCache|drawWithContent|graphicsLayer|Canvas\([^)]*\))$""").containsMatchIn(before)
        }
    }
}
