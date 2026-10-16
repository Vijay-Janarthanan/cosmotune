package com.cosmotune.video.ui

import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.DescriptionLink
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.PictureInPictureState
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.watch.EscHint
import com.cosmotune.video.ui.watch.PipUpdater
import com.cosmotune.video.ui.watch.WatchChip
import com.cosmotune.video.ui.watch.WatchEntry
import com.cosmotune.video.ui.watch.WatchOrientation
import com.cosmotune.video.ui.watch.WatchRules
import com.cosmotune.video.ui.watch.WideSegment
import com.cosmotune.video.ui.watch.descriptionLinks
import com.cosmotune.video.ui.watch.onFullWindowKey
import com.cosmotune.video.ui.watch.onWatchPageKey
import com.cosmotune.video.ui.watch.watchChips
import com.cosmotune.video.ui.watch.watchedFractions
import com.cosmotune.video.ui.watch.wideSegments
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watch page (plan task Y-19, step 7): what no render can show, read from the source (the branch line, the shell's
 * picture-in-picture line, the full window's Escape, the page's keys, the playhead never read, the look's rules), and the page's
 * decisions, run (auto-enter on and off, the chips of a live stream, the start of a page, full screen and the turned phone, the keys,
 * the description's links, the offline stand-in, the side column's segments).
 */
class WatchStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/watch"
    private val names = listOf("WatchPage", "WatchPageWide", "WatchMeta", "DescriptionTile", "DescriptionSheet", "UpNext", "FullscreenController", "FullWindowLayer")
    private fun file(name: String) = read("$dir/$name.kt")
    private val android get() = read("video/src/androidMain/kotlin/com/cosmotune/video/ui/watch/FullscreenController.android.kt")
    private val desktop get() = read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/watch/FullscreenController.jvm.kt")
    private val files: List<Pair<String, String>> get() = names.map { it to file(it) } + listOf("FullscreenController.android" to android, "FullscreenController.jvm" to desktop)
    private val app get() = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/CosmoVideoApp.kt")
    private fun flat(text: String) = VideoSourceContract.code(text).replace(Regex("\\s+"), " ")

    private val video = FakeVideoSource.videos[0]
    private val live = video.copy(id = "fakelive01", isLive = true, durationSec = null)
    private val upcoming = video.copy(id = "fakeupcoming01", isUpcoming = true)

    // ---- The seams -------------------------------------------------------------------------------------------------------

    @Test fun theBranchLineCallsTheWatchPage() {
        val host = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt")
        val lines = host.lines().filter { "is VideoRoute.Watch ->" in it }
        assertEquals(listOf("                is VideoRoute.Watch -> com.cosmotune.video.ui.watch.WatchPage(r, contentPadding, wide)"), lines)
        assertFalse("import com.cosmotune.video.ui.watch" in host, "a fully qualified name in the line, no import line")
    }

    @Test fun theWatchPageHasTheSignatureOfTheBrief() {
        assertEquals(
            listOf("route: VideoRoute.Watch", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(file("WatchPage"), "WatchPage"),
        )
        assertTrue("@Composable fun WatchPage(" in flat(file("WatchPage")), "a public composable")
    }

    @Test fun theShellsPictureInPictureLineReadsActiveAndDrawsThePlayerAlone() {
        val lines = app.lines()
        val pip = lines.filter { it.startsWith("    val pip = ") }
        assertEquals(listOf("    val pip = com.cosmotune.video.player.rememberPictureInPicture().active"), pip)
        val at = lines.indexOf(pip.single())
        assertEquals("    if (pip) {", lines[at + 1])
        assertEquals("        VideoPipLayer(graph)", lines[at + 2])
        val layer = flat(VideoSourceContract.functionBody(app, "VideoPipLayer"))
        assertTrue("com.cosmotune.video.ui.watch.WatchPictureInPicture(graph.playback, graph.surface, Modifier.fillMaxSize())" in layer)
        val pipContent = flat(VideoSourceContract.functionBody(file("WatchPage"), "WatchPictureInPicture"))
        assertTrue("VideoPlayerBox(playback, surface, PlayerLayout.FULLSCREEN, modifier, onToggleFullscreen = {}, chrome = false)" in pipContent)
        assertEquals(1, Regex("VideoPlayerBox\\(").findAll(pipContent).count(), "the player alone")
    }

    // ---- Picture-in-picture ----------------------------------------------------------------------------------------------

    private class RecordingPip : PictureInPictureState {
        val sent = mutableListOf<String>()
        override val supported: Boolean get() = true
        override val active: Boolean get() = false
        override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {
            sent += "$enabled $aspectWidth:$aspectHeight ${sourceRect?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" }}"
        }
    }

    @Test fun autoEnterIsSentOnlyWhenSomethingChangesAndGoesOffOnLeavingAndWhilePaused() {
        val pip = RecordingPip()
        val updater = PipUpdater(pip)
        updater.update(enabled = true, aspect = 16f / 9f)
        updater.update(enabled = true, aspect = 16f / 9f)
        assertEquals(listOf("true 1778:1000 null"), pip.sent, "once for one wish")
        updater.bounds(Rect(0f, 63f, 1080f, 670f))
        updater.bounds(Rect(0f, 63f, 1080f, 670f))
        assertEquals("true 1778:1000 0,63,1080,670", pip.sent.last(), "the player's bounds are the source rectangle, sent once")
        assertEquals(2, pip.sent.size)
        updater.update(enabled = false, aspect = 16f / 9f)
        assertEquals("false 1778:1000 0,63,1080,670", pip.sent.last(), "paused: off, with the shape kept for the button")
        updater.update(enabled = true, aspect = 9f / 16f)
        assertEquals("true 563:1000 0,63,1080,670", pip.sent.last(), "a vertical video's shape")
        updater.leave()
        assertEquals("false 563:1000 0,63,1080,670", pip.sent.last(), "leaving the page turns auto-enter off")
    }

    @Test fun aPageFadingIntoTheNextVideosPageDoesNotTurnOffWhatTheNewOneTurnedOn() {
        val pip = RecordingPip()
        val old = PipUpdater(pip)
        val next = PipUpdater(pip)
        old.update(enabled = true, aspect = 16f / 9f)
        next.update(enabled = true, aspect = 16f / 9f)
        val before = pip.sent.size
        old.leave()
        assertEquals(before, pip.sent.size, "the old page leaves: the new one spoke last")
        next.leave()
        assertEquals("false 1778:1000 null", pip.sent.last())
    }

    @Test fun autoEnterFollowsTheSettingThePlayingVideoAndTheTopRoute() {
        val playing = PlaybackUiState.Idle.copy(item = video, status = PlayerStatus.READY, playing = true)
        assertTrue(WatchRules.pipEnabled(autoPip = true, state = playing, onTop = true))
        assertTrue(WatchRules.pipEnabled(true, playing.copy(status = PlayerStatus.BUFFERING), true))
        assertFalse(WatchRules.pipEnabled(true, playing.copy(playing = false), true), "not while paused")
        assertFalse(WatchRules.pipEnabled(false, playing, true), "not with the setting off")
        assertFalse(WatchRules.pipEnabled(true, playing, onTop = false), "not when another page is on top")
        assertFalse(WatchRules.pipEnabled(true, playing.copy(status = PlayerStatus.ENDED), true), "not once ended")
        assertFalse(WatchRules.pipEnabled(true, PlaybackUiState.Idle, true), "not with nothing loaded")
        val page = flat(file("WatchPage"))
        assertTrue("WatchRules.pipEnabled(settings.autoPip, state, onTop = navigator.top == route)" in page)
        assertTrue("DisposableEffect(pipUpdater) { onDispose { pipUpdater.leave() } }" in page, "enabled = false on leaving the route")
        assertTrue("SideEffect { pipUpdater.update(enabled = pipEnabled, aspect = aspect) }" in page)
    }

    @Test fun theBackgroundFollowsTheActivityWhileNotInPictureInPicture() {
        val lifecycle = flat(VideoSourceContract.functionBody(file("WatchPage"), "WatchLifecycle"))
        assertTrue("Lifecycle.Event.ON_STOP -> if (!pip.active) playback.onUiVisible(false)" in lifecycle)
        assertTrue("Lifecycle.Event.ON_START -> playback.onUiVisible(true)" in lifecycle)
        assertTrue("lifecycle.removeObserver(observer)" in lifecycle)
        assertTrue(WatchRules.pipWindowClosed(Lifecycle.State.CREATED), "picture-in-picture left with the activity stopped: closed")
        assertFalse(WatchRules.pipWindowClosed(Lifecycle.State.STARTED), "left to the full app: not closed")
        assertFalse(WatchRules.pipWindowClosed(Lifecycle.State.RESUMED))
    }

    // ---- Full screen, the full window and the keys ---------------------------------------------------------------------------

    @Test fun theFullWindowLayerHandlesEscapeAndF() {
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = video, status = PlayerStatus.READY, playing = true, durationMs = 600_000))
        var exits = 0
        val settings = VideoSettings()
        assertTrue(onFullWindowKey("Escape", false, false, playback, settings, null) { exits++ })
        assertEquals(1, exits, "Escape closes the full window")
        assertTrue(onFullWindowKey("F", false, false, playback, settings, null) { exits++ })
        assertEquals(2, exits, "F closes it too")
        assertFalse(onFullWindowKey("T", false, false, playback, settings, null) { exits++ }, "no theatre in the full window")
        assertFalse(onFullWindowKey("F", false, true, playback, settings, null) { exits++ }, "Ctrl F is the app's")
        assertTrue(onFullWindowKey("Space", false, false, playback, settings, null) { exits++ })
        assertEquals(2, exits)
        assertTrue("togglePlayPause()" in playback.calls)
        val layer = flat(file("FullWindowLayer"))
        assertTrue("VideoFullWindow(onExit = onExit) { FullWindowContent(playback, surface, title, onExit, onBoundsChanged) }" in layer)
        assertTrue(".onPreviewKeyEvent {" in layer && "onFullWindowKey(name," in layer)
        assertTrue("layout = PlayerLayout.WINDOW," in layer && "desktopFullWindow = true," in layer)
        assertTrue("Res.string.video_exit_full_window" in layer, "\"Esc to exit full window\"")
        assertTrue("HINT_TOP = 20.dp" in layer && "HINT_END = 24.dp" in layer && "HINT_SP = 12.5f" in layer, "12.5 sp at the top right")
    }

    @Test fun theEscapeHintHidesWithTheControlsAndComesBackWhenThePointerMoves() {
        var now = 1_000L
        val hint = EscHint { now }
        assertTrue(hint.shown)
        assertEquals(3_000L, hint.remaining())
        now += 2_000
        hint.poke()
        assertEquals(3_000L, hint.remaining(), "a move while shown moves the deadline")
        now += 3_000
        assertTrue(hint.remaining() <= 0)
        hint.hide()
        assertFalse(hint.shown)
        hint.poke()
        assertTrue(hint.shown, "a move shows it again")
    }

    @Test fun theWidePageTakesTheKeysOfTheMapFirst() {
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = video, status = PlayerStatus.READY, playing = true, durationMs = 600_000))
        var theatre = 0
        var fullWindow = 0
        fun key(name: String, shift: Boolean = false, ctrl: Boolean = false) =
            onWatchPageKey(name, shift, ctrl, playback, VideoSettings(), null, onFullWindow = { fullWindow++ }, onTheatre = { theatre++ })
        assertTrue(key("T")); assertEquals(1, theatre, "T: theatre")
        assertTrue(key("F")); assertEquals(1, fullWindow, "F: the full window")
        assertFalse(key("Escape"), "Escape is not the page's: no full window here")
        assertTrue(key("K")); assertTrue(key("Right")); assertTrue(key("5"))
        assertTrue(listOf("togglePlayPause()", "seekBy(5000)", "seekTo(300000)").all { it in playback.calls }, "${playback.calls}")
        assertFalse(key("K", ctrl = true), "Ctrl K focuses search")
        assertTrue(key("N", shift = true)); assertTrue("playNext()" in playback.calls)
        val wide = flat(file("WatchPageWide"))
        assertTrue(".focusRequester(focus) .focusable() .onPreviewKeyEvent { event ->" in wide, "the page handles the keys while it has the focus")
        assertTrue("onWatchPageKey(" in wide && "playerKeyName(event)" in wide)
    }

    @Test fun fullScreenDrawsOnlyThePlayerInItsOwnWindow() {
        val page = file("WatchPage")
        val content = flat(VideoSourceContract.functionBody(page, "FullscreenContent"))
        assertEquals(1, Regex("VideoPlayerBox\\(").findAll(content).count())
        assertTrue("layout = PlayerLayout.FULLSCREEN," in content && "onToggleFullscreen = onExit," in content)
        val window = flat(VideoSourceContract.functionBody(page, "WatchFullscreen"))
        assertTrue("VideoFullWindow(onExit = onExit) {" in window)
        assertTrue("fullscreen -> WatchFullscreen(" in flat(page), "full screen takes the page's place")
    }

    @Test fun theFullscreenControllerIsAnExpectWithBothActuals() {
        val common = flat(file("FullscreenController"))
        assertTrue("@Composable internal expect fun FullscreenController(orientation: WatchOrientation, lightStatusBar: Boolean)" in common)
        assertTrue("@Composable internal expect fun VideoFullWindow(onExit: () -> Unit, content: @Composable () -> Unit)" in common)
        assertTrue("@Composable internal expect fun rememberEnterPictureInPicture(): (() -> Unit)?" in common)
        val a = flat(android)
        assertTrue("WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE" in a && "controller.hide(WindowInsetsCompat.Type.systemBars())" in a, "the system bars hidden")
        assertTrue("ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE" in a && "activity.requestedOrientation = wanted" in a, "landscape through requestedOrientation")
        assertTrue("activity.requestedOrientation = saved" in a, "back to the user's setting on exit")
        assertTrue("internal actual val rotationEntersFullScreen: Boolean = true" in a)
        val d = flat(desktop)
        assertFalse("import android." in d || "android.app" in d, "the desktop actual touches no Android class")
        assertTrue("internal actual val rotationEntersFullScreen: Boolean = false" in d)
        assertTrue("internal actual fun rememberEnterPictureInPicture(): (() -> Unit)? = null" in d)
        assertTrue(Regex("""actual fun FullscreenController\(orientation: WatchOrientation, lightStatusBar: Boolean\) \{\s*}""").containsMatchIn(d), "a no-op")
    }

    @Test fun aTurnedPhoneEntersFullScreenAndBackLeavesItUpright() {
        assertTrue(WatchRules.phoneLandscape(740f, 360f, rotates = true), "a phone turned")
        assertFalse(WatchRules.phoneLandscape(360f, 740f, rotates = true), "upright")
        assertFalse(WatchRules.phoneLandscape(1280f, 800f, rotates = true), "a tablet turned keeps its wide page")
        assertFalse(WatchRules.phoneLandscape(900f, 400f, rotates = false), "a short desktop window is still the desktop's page")
        assertTrue(WatchRules.fullscreenShown(requested = false, phoneLandscape = true, portraitLock = false))
        assertFalse(WatchRules.fullscreenShown(requested = false, phoneLandscape = true, portraitLock = true), "left while turned")
        assertTrue(WatchRules.fullscreenShown(requested = true, phoneLandscape = false, portraitLock = false))
        assertEquals(WatchOrientation.LANDSCAPE, WatchRules.orientation(requested = true, landscapeVideo = true, portraitLock = false))
        assertEquals(WatchOrientation.FREE, WatchRules.orientation(requested = true, landscapeVideo = false, portraitLock = false), "a vertical video stays as the phone is")
        assertEquals(WatchOrientation.PORTRAIT, WatchRules.orientation(requested = false, landscapeVideo = true, portraitLock = true))
        assertEquals(WatchOrientation.FREE, WatchRules.orientation(requested = false, landscapeVideo = true, portraitLock = false))
    }

    // ---- The start of a page ---------------------------------------------------------------------------------------------

    @Test fun aPagePlaysItsVideoUnlessItIsOnAndFollowsThePlaybackWhenItMovedOn() {
        val a = VideoRoute.Watch("fakevideo01")
        val aAt = VideoRoute.Watch("fakevideo01", 42_000)
        assertEquals(WatchEntry.Play, WatchRules.entry(a, currentId = null, currentShort = false, lastRoute = null))
        assertEquals(WatchEntry.Play, WatchRules.entry(a, "fakevideo02", false, lastRoute = VideoRoute.Watch("fakevideo02")), "another video on: play this one")
        assertEquals(WatchEntry.Keep, WatchRules.entry(a, "fakevideo01", false, null), "already the one on")
        assertEquals(WatchEntry.Seek(42_000), WatchRules.entry(aAt, "fakevideo01", false, null), "on, with a start: seek once")
        assertEquals(WatchEntry.Keep, WatchRules.entry(aAt, "fakevideo01", false, aAt), "the same route found again: no second seek")
        assertEquals(WatchEntry.Follow("fakevideo02"), WatchRules.entry(a, "fakevideo02", false, lastRoute = a), "found again after autoplay moved on")
        assertEquals(WatchEntry.Play, WatchRules.entry(a, "fakeshort01", currentShort = true, lastRoute = a), "a Short in the pager is not followed")
        assertEquals("fakevideo02", WatchRules.follow(a, "fakevideo02", false, seenOwn = true, onTop = true))
        assertNull(WatchRules.follow(a, "fakevideo02", false, seenOwn = false, onTop = true), "not before its own video was on")
        assertNull(WatchRules.follow(a, "fakevideo02", false, seenOwn = true, onTop = false), "not when another page is on top")
        assertNull(WatchRules.follow(a, null, false, seenOwn = true, onTop = true), "not when stopped")
        assertNull(WatchRules.follow(a, "fakevideo01", false, seenOwn = true, onTop = true))
        val start = flat(VideoSourceContract.functionBody(file("WatchPage"), "StartWatching"))
        assertTrue("WatchEntry.Play -> playback.play(route.videoId, route.startMs)" in start)
        assertTrue("navigator.replaceTop(" in start)
    }

    @Test fun theDownloadPlaysWhenTheSourceCannotBeReached() {
        assertTrue(WatchRules.failedForNetwork(PlaybackUiState.Idle.copy(error = VideoUiError.NETWORK), "fakevideo01"), "a failure before any detail")
        assertTrue(WatchRules.failedForNetwork(PlaybackUiState.Idle.copy(item = video, error = VideoUiError.NETWORK), video.id))
        assertFalse(WatchRules.failedForNetwork(PlaybackUiState.Idle.copy(item = video, error = VideoUiError.NETWORK), "fakevideo02"), "another video's")
        assertFalse(WatchRules.failedForNetwork(PlaybackUiState.Idle.copy(item = video, error = VideoUiError.GEO), video.id))
        val offline = flat(VideoSourceContract.functionBody(file("WatchPage"), "PlayDownloadWhenOffline"))
        assertTrue("entry.readLocalBundle()" in offline && "playback.playLocal(entry.item, bundle, route.startMs)" in offline)
        assertTrue("withContext(Dispatchers.IO)" in offline, "the sidecar is read off the main thread")
    }

    @Test fun theOfflineStandInIsTheDetailsWithNoStream() = runBlocking {
        val details = FakeVideoSource().details(video.id)
        assertFalse(WatchRules.isOfflineStandIn(details))
        val standIn = details.copy(streams = StreamSet(emptyList(), emptyList(), emptyList(), null, null, null, emptyMap(), null), related = emptyList())
        assertTrue(WatchRules.isOfflineStandIn(standIn))
        val short = FakeVideoSource.shorts[0]
        val withShort = details.copy(related = listOf(ListItem.Video(short), ListItem.Video(video), ListItem.Video(video)))
        assertEquals(listOf(short, video), WatchRules.relatedVideos(withShort, hideShorts = false), "videos only, each once")
        assertEquals(listOf(video), WatchRules.relatedVideos(withShort, hideShorts = true), "Hide Shorts leaves them out")
        assertEquals(emptyList(), WatchRules.relatedVideos(null, hideShorts = false))
    }

    // ---- The blocks ------------------------------------------------------------------------------------------------------

    @Test fun aLiveStreamOrAPremiereHasNoDownloadAndNoPlayAsASongChip() {
        assertEquals(
            listOf(WatchChip.LIKES, WatchChip.DISLIKES, WatchChip.SAVE, WatchChip.WATCH_LATER, WatchChip.DOWNLOAD, WatchChip.SHARE, WatchChip.PLAY_AS_SONG),
            watchChips(video, likes = 1200, dislikes = 30, wide = false),
            "the order of spec 5.4",
        )
        for (item in listOf(live, upcoming)) {
            val chips = watchChips(item, likes = 10, dislikes = null, wide = false)
            assertFalse(WatchChip.DOWNLOAD in chips, "${item.id}: no Download")
            assertFalse(WatchChip.PLAY_AS_SONG in chips, "${item.id}: no Play as a song")
            assertFalse(WatchChip.DISLIKES in chips)
        }
        assertFalse(WatchChip.LIKES in watchChips(video, likes = null, dislikes = null, wide = false), "no count, no chip")
        assertEquals(WatchChip.THEATRE, watchChips(video, null, null, wide = true).last(), "the wide page's theatre chip")
    }

    @Test fun theChipsGoThroughTheSharedHandlerAndTheDownloadSheet() {
        val page = flat(file("WatchPage"))
        assertTrue("WatchChip.SAVE -> act(VideoAction.SaveToPlaylist)" in page)
        assertTrue("WatchChip.WATCH_LATER -> act(VideoAction.WatchLater)" in page)
        assertTrue("WatchChip.SHARE -> act(VideoAction.Share)" in page)
        assertTrue("WatchChip.PLAY_AS_SONG -> act(VideoAction.PlayAsSong)" in page)
        assertTrue("else -> actions.download(info.item)" in page, "Download asks the download sheet (Y-32)")
        assertTrue("info.downloadedLabel != null -> navigator.push(VideoRoute.Downloads)" in page)
        assertTrue("onPlay = { item -> actions.play(item, view.related) }" in page, "a related Short opens the pager with the list")
        val meta = flat(file("WatchMeta"))
        assertTrue("Res.string.video_likes_cd" in meta && "Res.string.video_dislikes_cd" in meta, "the counts are read by their descriptions")
        assertTrue("stringResource(Res.string.video_dl_done, stringResource(Res.string.video_dl_done_cd), done)" in meta, "Downloaded and its quality")
        assertTrue("kind = if (info.subscribed) CosmoButtonKind.TONAL else CosmoButtonKind.PRIMARY," in meta, "Subscribe PRIMARY, Subscribed TONAL")
    }

    @Test fun theDescriptionKeepsOnlyLinksItCanDraw() {
        val text = "Intro at 0:42, more at https://example.org and a bad one."
        val links = listOf(
            DescriptionLink(37, 56, "https://example.org/far", null),       // past the end
            DescriptionLink(9, 13, null, 42_000),
            DescriptionLink(23, 42, "https://example.org", null),
            DescriptionLink(25, 30, "https://example.org/overlap", null),   // overlaps the one before
            DescriptionLink(47, 50, "javascript:alert(1)", null),           // not on the web
            DescriptionLink(5, 5, null, 1_000),                             // empty
        )
        val kept = descriptionLinks(Description(text, links))
        assertEquals(listOf(9 to 13, 23 to 42), kept.map { it.start to it.end })
        assertEquals(42_000L, kept[0].timestampMs)
    }

    @Test fun theSideColumnOffersChaptersAndCommentsOnlyWhenThereAreSome() {
        assertEquals(listOf(WideSegment.UP_NEXT, WideSegment.CHAPTERS, WideSegment.COMMENTS), wideSegments(hasChapters = true, comments = true))
        assertEquals(listOf(WideSegment.UP_NEXT, WideSegment.COMMENTS), wideSegments(hasChapters = false, comments = true))
        assertEquals(listOf(WideSegment.UP_NEXT), wideSegments(hasChapters = false, comments = false), "offline")
        val wide = flat(file("WatchPageWide"))
        assertTrue("CommentsPanel( videoId = view.videoId, sheet = false," in wide)
        assertTrue("CosmoSegmented(options = labels, selectedIndex = selected" in wide)
        val page = flat(file("WatchPage"))
        assertTrue("CommentsPanel( videoId = view.videoId, sheet = true, onDismiss = { commentsOpen = false }, modifier = Modifier.align(Alignment.BottomCenter).padding(top = top," in page, "the phone's sheet under the player, removed on dismiss")
        val phone = VideoSourceContract.code(VideoSourceContract.functionBody(file("WatchPage"), "WatchPagePhone"))
        assertTrue(phone.indexOf("VideoBackHandler(") in 0 until phone.indexOf("CommentsPanel("), "the page's Back is registered before the panel's, so the replies level goes first")
    }

    @Test fun aDownloadPlayingFromItsBundleIsNotReloadedFromTheSource() {
        val page = VideoSourceContract.code(file("WatchPage"))
        assertFalse("graph.source" in page || ".details(" in page, "the page asks the source nothing: the controller loads, the page only reads its state")
        // the playLocal-then-push of the Downloads page finds the video on: Keep, no play
        assertEquals(WatchEntry.Keep, WatchRules.entry(VideoRoute.Watch(video.id), currentId = video.id, currentShort = false, lastRoute = null))
        // Music's link with a start time: played from it, or a seek when the video is on
        assertEquals(WatchEntry.Seek(61_000), WatchRules.entry(VideoRoute.Watch(video.id, 61_000), video.id, false, null))
        assertTrue("WatchEntry.Play -> playback.play(route.videoId, route.startMs)" in flat(file("WatchPage")))
    }

    @Test fun theNumbersAreTheSpecs() {
        val page = flat(file("WatchPage"))
        assertTrue("VideoMetrics.playerHeightDp(width.value, height.value, aspect).dp" in page, "the phone's player: 16:9, vertical capped at 0.56")
        val wide = flat(file("WatchPageWide"))
        for (n in listOf("VideoMetrics.DESKTOP_MARGIN_DP", "VideoMetrics.COLUMN_GAP_DP", "VideoMetrics.SIDE_COLUMN_DP", "VideoMetrics.PLAYER_RADIUS_DP")) assertTrue(n in wide, n)
        assertEquals(528f, WatchRules.theatreHeightDp(1004f, 704f, 16f / 9f), 0.01f, "theatre: at most 75 % of the page's height")
        assertEquals(1004f / (16f / 9f), WatchRules.theatreHeightDp(1004f, 2000f, 16f / 9f), 0.01f)
        val meta = flat(file("WatchMeta"))
        for (n in listOf("TITLE_TOP = 12.dp", "META_TOP = 6.dp", "META_SP = 13f", "CHIPS_TOP = 12.dp", "CHIPS_BOTTOM = 4.dp", "CHIPS_GAP = 8.dp", "CHANNEL_PAD_V = 10.dp", "CHANNEL_AVATAR = 40.dp", "CHANNEL_GAP = 12.dp", "CHANNEL_NAME_SP = 15.5f", "CHANNEL_COUNT_SP = 13f")) {
            assertTrue(n in meta, n)
        }
        assertTrue("type.title(TypeScale.TITLE_S)" in meta && "maxLines = 2" in meta)
        val tile = flat(file("DescriptionTile"))
        for (n in listOf("SuperellipseShape(24.dp)", "TILE_MARGIN_V = 8.dp", "TILE_PAD_H = 16.dp", "TILE_PAD_V = 14.dp", "TEXT_SP = 14f", "TEXT_LINE = 1.55f", "TILE_LINES = 3")) assertTrue(n in tile, n)
        val sheet = flat(file("DescriptionSheet"))
        assertTrue("CHAPTER_FRAME_W = 96.dp" in sheet && "CHAPTER_FRAME_H = 54.dp" in sheet, "the chapters' 96 x 54 frame")
    }

    @Test fun theWatchedBarsComeFromTheHistory() {
        val other = FakeVideoSource.videos[1]
        val history = listOf(
            HistoryEntry(video, lastWatchedMs = 1, positionMs = 300_000, durationMs = 600_000, watchCount = 1),
            HistoryEntry(other, lastWatchedMs = 1, positionMs = 5_000, durationMs = 600_000, watchCount = 1),
        )
        assertEquals(mapOf(video.id to 0.5f), watchedFractions(listOf(video, other), history), "under 10 s: no bar")
        assertEquals(emptyMap(), watchedFractions(emptyList(), history))
    }

    // ---- Rules of the whole page ---------------------------------------------------------------------------------------------

    @Test fun thePlayheadIsNeverReadByTheWatchPage() {
        for ((name, source) in files) assertFalse("positionMs(" in VideoSourceContract.code(source), "$name reads the playhead")
    }

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

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        var seen = 0
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                seen++
                assertTrue(enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }, "$name: live( at line ${code.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
        assertTrue(seen > 0, "the check found the chip's live reads")
    }

    @Test fun noColourIsPickedByHandAndTheBlacksAndWhitesAreTheDocumentedOnes() {
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b""").containsMatchIn(code), "$name uses a named colour")
        }
        // White text over the video, the black of the player and the full window, a black text shadow over the video.
        assertEquals(listOf("FullWindowLayer"), files.filter { "Color.White" in VideoSourceContract.code(it.second) }.map { it.first })
        assertEquals(
            setOf("WatchPage", "WatchPageWide", "FullWindowLayer"),
            files.filter { "Color.Black" in VideoSourceContract.code(it.second) }.map { it.first }.toSet(),
        )
        assertTrue("android.graphics.Color.BLACK" in android, "the full screen window's own black")
        for ((name, source) in files) assertFalse(Regex("""cosmoGlass\(hazeState = (?!null)""").containsMatchIn(VideoSourceContract.code(source)), "$name blurs")
    }

    @Test fun nothingNamesTheSourceOrTheExtractorAndNothingTouchesAnAccount() {
        for ((name, source) in files) {
            assertFalse("org.schabi" in VideoSourceContract.code(source), "$name imports the extractor")
            assertFalse(Regex("""(?i)youtube|\bYT\b|cookie|CookieJar""").containsMatchIn(source), "$name names the source or touches cookies")
        }
    }

    @Test fun everyControlHasALabel() {
        val meta = flat(file("WatchMeta"))
        assertTrue("role = Role.Button" in meta && "cosmoFocusRing(interaction" in meta && "minimumInteractiveComponentSize()" in meta, "chips: buttons, 48 dp, the focus ring")
        val sheet = flat(file("DescriptionSheet"))
        assertTrue("contentDescription = stringResource(SharedRes.string.cosmo_close)" in sheet)
        val upNext = flat(file("UpNext"))
        assertTrue("CosmoSwitch(checked = autoplay, onCheckedChange = onAutoplay, modifier = Modifier.semantics { contentDescription = label })" in upNext)
    }
}
