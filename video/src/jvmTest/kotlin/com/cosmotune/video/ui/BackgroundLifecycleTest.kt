package com.cosmotune.video.ui

import androidx.lifecycle.Lifecycle
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.ui.shell.VideoBackgroundObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Leaving the app from any page while the mini player plays (Y-34, after Y-19's concern 3): the shell tells the playback the UI went
 * away and came back, as the watch page does for itself, so "Keep playing in the background = off" pauses there too (the controller's
 * own rule, tested by Y-10) and the video track stops decoding when it is on.
 */
class BackgroundLifecycleTest {

    @Test fun leavingFromAnotherPageTellsThePlaybackAndComingBackToo() {
        val playback = FakeVideoPlayback()
        val observer = VideoBackgroundObserver(playback, onWatch = { false })
        observer.on(Lifecycle.Event.ON_CREATE)
        observer.on(Lifecycle.Event.ON_START)
        observer.on(Lifecycle.Event.ON_RESUME)
        assertTrue(playback.calls.isEmpty(), "opening the mode says nothing")
        observer.on(Lifecycle.Event.ON_PAUSE)
        assertTrue(playback.calls.isEmpty(), "a pause is not leaving (a dialog, the notification shade)")
        observer.on(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("onUiVisible(false)"), playback.calls)
        observer.on(Lifecycle.Event.ON_START)
        assertEquals(listOf("onUiVisible(false)", "onUiVisible(true)"), playback.calls)
    }

    @Test fun onTheWatchPageThePageSpeaksForItself() {
        val playback = FakeVideoPlayback()
        var watch = true
        val observer = VideoBackgroundObserver(playback, onWatch = { watch })
        observer.on(Lifecycle.Event.ON_STOP)
        observer.on(Lifecycle.Event.ON_START)
        assertTrue(playback.calls.isEmpty(), "the watch page's own observer reports, not the shell's")
        // Left from a page, back on the watch page (opened meanwhile): the page reports the start itself.
        watch = false
        observer.on(Lifecycle.Event.ON_STOP)
        watch = true
        observer.on(Lifecycle.Event.ON_START)
        assertEquals(listOf("onUiVisible(false)"), playback.calls)
    }

    @Test fun theShellComposesItOutsidePictureInPictureAndKeepsThePipLine() {
        val app = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/CosmoVideoApp.kt")
        val lines = app.lines()
        val at = lines.indexOf("    val pip = com.cosmotune.video.player.rememberPictureInPicture().active")
        assertEquals(listOf("    if (pip) {", "        VideoPipLayer(graph)", "        return", "    }"), lines.subList(at + 1, at + 5))
        assertTrue(lines[at + 5].trim().startsWith("com.cosmotune.video.ui.shell.VideoBackgroundLifecycle(graph.playback, onWatch = route is VideoRoute.Watch)"))
        assertEquals(1, Regex("VideoBackgroundLifecycle\\(").findAll(app).count())
    }
}
