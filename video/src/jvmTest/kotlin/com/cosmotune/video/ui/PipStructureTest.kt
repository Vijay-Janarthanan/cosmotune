package com.cosmotune.video.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The picture-in-picture window and the page that stands in for the player while it holds the picture: the window is clean (the
 * picture, the subtitles and its small controls), the page carries the player's options, and the hand-over is safe.
 */
class PipStructureTest {
    private fun code(path: String) = VideoSourceContract.code(VideoSourceContract.read(path)).replace(Regex("\\s+"), " ")

    private val window = "video/src/jvmMain/kotlin/com/cosmotune/video/player/DesktopPip.kt"
    private val entry = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/watch/PipEntry.kt"

    @Test
    fun theWindowIsDisposedAfterTheEventThatClosedItAndThePageTakesTheVideoBackOnlyThen() {
        val text = code(window)
        val close = VideoSourceContract.functionBody(VideoSourceContract.read(window), "close").replace(Regex("\\s+"), " ")
        assertTrue("window.isVisible = false" in close, "hidden at once")
        assertTrue("SwingUtilities.invokeLater {" in close, "taken apart after the click or effect that closed it has finished")
        assertTrue(close.indexOf("window.dispose()") < close.indexOf("isOpen = false"), "the page gets the picture back after the window's surface is gone")
        assertTrue("if (isOpen || frame != null" in text, "no second window while one is going")
    }

    @Test
    fun theWindowStaysCleanAndItsControlsFollowThePointer() {
        val text = code(window)
        for (leftOut in listOf("PlayerStats", "PlayerSettingsMenu", "SmoothnessPanel", "readout")) {
            assertFalse(leftOut in text, "$leftOut belongs to the page, not to the window")
        }
        assertTrue("PipControlsTimer(" in text && "pointerOverWindow(" in text, "the pure rules decide, the screen's pointer feeds them")
        assertTrue("addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)" in text)
        assertTrue("removeAWTEventListener(listener)" in text, "the listener goes with the window")
        assertTrue("MouseInfo.getPointerInfo()" in text, "the pointer is read from the screen: the authority when an unfocused window gets no exit event")
        assertTrue("LocalVideoHostWindow provides window" in text, "smoothing reads the monitor this window is on")
        // a drag or a resize keeps the controls up
        assertTrue(Regex("onDragStart = \\{ lock\\(\\)").containsMatchIn(text) && "unlock()" in text)
        assertFalse("CONTROLS_MS" in text, "no fixed fade after the last movement any more")
    }

    @Test
    fun thePageThatStandsInForThePlayerCarriesItsOptions() {
        val text = code(entry)
        for (piece in listOf(
            "CaptionsButton(", "PlayerSettingsMenu(", "PlayerStats(playback, state, playhead, metrics", "CentreControls(", "PlayerScrubber(",
            "TimeText(", "LiveStatus(", "ThumbnailFrame(rememberVideoThumbnail(item)", "rememberVideoTitle(item)", "Res.string.video_pip_return",
        )) {
            assertTrue(piece in text, "the stand-in page has $piece")
        }
        assertTrue("var statsShown by PlayerStatsSwitch::shown" in text, "the stats switch is the player's own")
        assertTrue("fun PipPlaceholder(playback: VideoPlayback, onReturn: () -> Unit" in text)
        val box = code("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/player/VideoPlayerBox.kt")
        assertTrue("var statsShown by PlayerStatsSwitch::shown" in box, "the player reads the same switch")
        for (page in listOf(
            "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/local/LocalPlayerPage.kt",
            "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/watch/WatchPageWide.kt",
        )) {
            assertTrue("PipPlaceholder(playback, onReturn = onReturnFromPip)" in code(page), "$page gives the stand-in the playback")
        }
    }

    @Test
    fun everySurfaceTellsSmoothingWhichWindowItIsIn() {
        val canvas = code("video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/MpvRenderer.kt")
        assertTrue("exchange.display.attach(painter, host)" in canvas && "exchange.display.detach(painter)" in canvas)
        val engine = code("video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/DesktopVideoEngine.kt")
        assertTrue("displayHz = exchange.display::hz" in engine, "the pipeline reads the rate through the source, not the primary monitor")
        val main = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/Main.kt")
        assertTrue("LocalVideoHostWindow provides composeWindow" in main, "the main window is a host too")
    }

    @Test
    fun theSmoothingRendererIsClosedUnderTheLockTheScreenDrawsUnder() {
        val src = VideoSourceContract.read("video/src/jvmMain/kotlin/com/cosmotune/video/player/smooth/SmoothingPipeline.kt")
        val close = VideoSourceContract.functionBody(src, "close").replace(Regex("\\s+"), " ")
        assertTrue("synchronized(poolLock) { releasePool() renderer?.close() renderer = null }" in close)
    }
}
