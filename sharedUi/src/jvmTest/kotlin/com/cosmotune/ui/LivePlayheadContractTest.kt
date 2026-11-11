package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every ring and moon drawn from the playing song's playhead reads the interpolated clock (LivePlayhead), never the
 * half-second reading in PlaybackPosition itself, which steps.
 */
class LivePlayheadContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    private fun ringLines(path: String): List<String> =
        read(path).lines().filter { Regex("progress\\s*=|fun progress\\(").containsMatchIn(it) && it.contains("ositionMs") }

    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"
    private val tv = "tv/src/main/java/com/cosmotune/music/ui/tv"

    private val owners = listOf(
        "$ui/shell/CosmoBottomChrome.kt",
        "$ui/shell/CosmoPlayerBar.kt",
        "$ui/nowplaying/CosmoNowPlaying.kt",
        "$tv/player/TvNowPlayingParts.kt",
        "$tv/screens/TvHomeScreen.kt",
        "$tv/screens/TvPodioScreen.kt",
        "$tv/TvApp.kt",
        "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt",
    )

    @Test fun noRingReadsTheSteppedReadingDirectly() {
        for (site in owners + "$ui/nowplaying/NowPlayingCard.kt") {
            val stepped = ringLines(site).filter { !it.contains("live?.positionMs()") && !it.contains("playhead.positionMs()") }
            assertTrue(stepped.isEmpty(), "$site draws a ring from the stepped reading: $stepped")
        }
    }

    @Test fun theRingOwnersRunTheFrameClock() {
        for (site in owners) assertTrue(read(site).contains("rememberLivePlayhead("), "$site makes a live playhead")
        assertTrue(read("$ui/nowplaying/NowPlayingCard.kt").contains("live?.positionMs()"), "the card's progress reads it")
    }

    @Test fun theTrayAndMiniPlayerReadItToo() {
        val desktop = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop"
        assertTrue(read("$desktop/DesktopTrayFlyout.kt").contains("rememberLivePlayhead("), "the tray card")
        assertTrue(read("$desktop/DesktopMiniPlayer.kt").contains("rememberLivePlayhead("), "the mini player")
    }

    @Test fun theFrameLoopIsPausedAndReducedMotionAware() {
        val src = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/playback/LivePlayhead.kt")
        assertTrue(src.contains("withInfiniteAnimationFrameNanos"), "advanced on the frame clock, as an infinite animation a UI test does not wait for")
        assertTrue(src.contains("if (!run) return@LaunchedEffect"), "no loop while paused")
        assertTrue(src.contains("LocalReducedMotion.current") && src.contains("REDUCED_STEP_MS"), "slower under reduced motion")
        assertFalse(src.contains("delay(1000") || src.contains("delay(1_000"), "no one-second loop")
    }

    @Test fun theWatchRunsItsOrbitOnTheFrameClock() {
        val src = read("wearos/src/main/java/com/cosmotune/music/wear/ui/NowPlayingScreen.kt")
        assertTrue(src.contains("withInfiniteAnimationFrameNanos"), "the Wear orbit is advanced every frame")
        assertFalse(src.contains("PROGRESS_TICK_MS"), "not on a 500 ms tick")
    }
}
