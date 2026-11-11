package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The video views of Now Playing (the Orbit board, section 4), pinned by reading the source: Whole is the video's own shape in a
 * box of that shape (so nothing is cut), with a glow round it; nothing is drawn over the video; Cinema hides its chrome three
 * seconds after the pointer last moved; the kept view comes from the look settings.
 */
class NowPlayingVideoContractTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying"
    private val video get() = SourceContract.code(SourceContract.read("$dir/NowPlayingVideo.kt"))
    private val cinema get() = SourceContract.code(SourceContract.read("$dir/NowPlayingCinema.kt"))
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

    @Test fun cinemaHidesItsChromeThreeSecondsAfterThePointerLastMovedAndLeavesOnBackOrDoubleTap() {
        assertTrue(cinema.contains("CINEMA_CHROME_MS = 3_000L"))
        assertTrue(cinema.contains("PointerEventType.Move"), "a movement of the pointer brings the chrome back")
        assertTrue(cinema.contains("onDoubleTap = onLeave"))
        assertTrue(screen.contains("PlayerBackHandler(enabled = cinemaShown) { leaveCinema() }"), "Esc and back leave Cinema")
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
