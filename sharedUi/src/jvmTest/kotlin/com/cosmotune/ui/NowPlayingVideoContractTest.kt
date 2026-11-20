package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The video views of Now Playing (the Orbit board, section 4), pinned by reading the source: Whole is the video's own shape in a
 * box of that shape (so nothing is cut), with a glow round it; nothing is drawn over the video; Cinema is the one plain
 * full-screen player, shared with Video and Watch; the kept view comes from the look settings.
 */
class NowPlayingVideoContractTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying"
    private val video get() = SourceContract.code(SourceContract.read("$dir/NowPlayingVideo.kt"))
    private val cinema get() = SourceContract.code(SourceContract.read("$dir/NowPlayingCinema.kt"))
    private val player get() = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/fullscreen/FullScreenPlayer.kt"))
    private val screen get() = SourceContract.code(SourceContract.read("$dir/CosmoNowPlaying.kt"))

    @Test fun wholeIsAFrameOfTheClipsOwnShapeSoCroppingItCutsNothing() {
        val body = SourceContract.functionBody(video, "NowPlayingVideo")
        assertTrue(body.contains("VideoView.whole(maxWidth.value, maxHeight.value,"), "Whole fits a frame of the clip's shape in the box")
        assertTrue(body.contains("contentMode = CanvasContentMode.CROP"), "in a box of its own shape the crop is the whole picture")
        assertTrue(body.contains("onAspectRatioChanged = {"), "the clip tells its own shape")
        assertTrue(body.contains("presentationAlpha = { reveal.value.coerceIn(0f, 1f) }"), "the clip's own alpha, not a layer: on the phone it is a TextureView")
        assertTrue(body.contains("VideoGlow("), "the space round a whole video glows")
        assertFalse(body.contains("FIT_PORTRAIT"), "no fixed portrait framing")
    }

    @Test fun nothingIsDrawnOnTheVideo() {
        val body = SourceContract.functionBody(video, "NowPlayingVideo")
        // the clip is the last thing in its box: the glow is behind it, nothing after it
        val clip = body.indexOf("CanvasArtworkPlayer(")
        assertTrue(body.indexOf("VideoGlow(") in 0 until clip, "the glow is under the video")
        assertFalse(Regex("""Icon\(|OrbitRoundButton\(|Text\(""").containsMatchIn(body), "no buttons or text on the video")
    }

    @Test fun cinemaIsTheSharedFullScreenPlayerInTheHostsFullWindow() {
        val body = SourceContract.functionBody(cinema, "NowPlayingCinema")
        assertTrue(body.contains("FullScreenWindow(onExit = onLeave)"), "the host's window: system bars hidden, the picture into the cutout")
        assertTrue(body.contains("FullScreenPlayer("), "the one plain player Video and Watch use too")
        assertTrue(body.contains("transport = FullScreenTransport.Tracks(t.onPrevious, t.onNext, hasPrevious, hasNext)"), "back / play-pause / next")
        assertTrue(body.contains("view = if (fill) VideoView.FILL else VideoView.WHOLE"), "its own shape until a pinch fills the screen")
        assertTrue(screen.contains("PlayerBackHandler(enabled = cinemaShown) { leaveCinema() }"), "Esc and back leave Cinema")
        assertTrue(screen.contains("(cinema || (landscape && !leftTurnedCinema)) && wantVideo"), "turning the phone sideways brings it")
    }

    @Test fun theFullScreenPlayerIsPlainAndHidesAfterThreeSeconds() {
        assertTrue(player.contains("const val FULL_SCREEN_HIDE_MS = 3_000L"))
        val body = SourceContract.functionBody(player, "FullScreenPlayer")
        assertTrue(body.contains("if (!shown || scrubbing || menuOpen || holdControls) return@LaunchedEffect"), "not while the bar is dragged or a menu is open")
        assertTrue(body.contains("PointerEventType.Move"), "a mouse that moves brings the controls back")
        assertTrue(body.contains("onTap = { shown = !shown; stamp++ }"), "a tap toggles them")
        assertTrue(body.contains("TapThird.LEFT -> if (canSkip) { skip(-FULL_SCREEN_SKIP_MS)") && body.contains("TapThird.RIGHT -> if (canSkip) { skip(FULL_SCREEN_SKIP_MS)"),
            "a double tap on the left or right third skips 10 s")
        assertTrue(body.contains(".pinchToFill { wanted -> setFill(wanted)"), "a pinch fills the screen or gives back the picture's shape")
        assertTrue(body.contains("picture(fill, Modifier.fillMaxSize())"), "the picture takes the whole window, the cutout too")
        assertTrue(body.contains("windowInsetsPadding(WindowInsets.safeDrawing)"), "the controls keep clear of it")
        assertTrue(player.contains("settings.fullScreenFill.collectAsState()"), "the shape chosen is remembered")
        assertTrue(player.contains("\"Leave full screen\""), "the ⋯ menu holds leaving")
    }

    @Test fun aDoubleTapSkipsOnlyFromTheOuterThirds() {
        assertTrue(com.cosmotune.ui.fullscreen.tapThird(0.1f) == com.cosmotune.ui.fullscreen.TapThird.LEFT)
        assertTrue(com.cosmotune.ui.fullscreen.tapThird(0.5f) == com.cosmotune.ui.fullscreen.TapThird.MIDDLE)
        assertTrue(com.cosmotune.ui.fullscreen.tapThird(0.9f) == com.cosmotune.ui.fullscreen.TapThird.RIGHT)
    }

    @Test fun theKeptViewIsTheLookSettingsAndTheMenuOffersEveryView() {
        assertTrue(screen.contains("shell?.settings?.videoView"))
        assertTrue(screen.contains("shell?.settings?.setVideoView(it)"))
        val items = video.substring(video.indexOf("fun videoViewItems("))
        for (name in listOf("\"Whole\"", "\"Fill\"", "\"Cinema\"", "\"Picture in picture\"", "\"Off\"")) assertTrue(items.contains(name), "$name is in the menu")
        assertFalse(items.contains("coming soon"), "the board's Mini is built now: picture-in-picture")
        // offered where the device has it, and shown (not offered) where it has none
        assertTrue(items.contains("if (pip != null) OrbitMenuItem(\"Picture in picture\"") && items.contains("\"Not on this device\", enabled = false"))
        assertTrue(screen.contains("pip = enterPip, inPip = pip.active"), "Now Playing hands the menu its picture-in-picture")
    }
}
