package com.cosmotune.video.ui

import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.LiveWindow
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyAction
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.player.goToLive
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.showsGoLive
import com.cosmotune.video.ui.player.startTimeText
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live streams and premieres (plan task Y-28, step 3): read from the source, the controller's live rules (no history, no
 * segments, no resume, HLS as it is, seeks in the window, one more load after an end, premieres never loaded), the engines'
 * window, the chrome (the Live badge and "Go to live" past 10 s instead of the time, the bar up to the live edge, the Upcoming
 * badge with the start time), and the small pieces of logic, run.
 */
class LiveStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private fun code(path: String) = VideoSourceContract.code(read(path))
    private fun flat(path: String) = code(path).replace(Regex("\\s+"), " ")
    private fun body(path: String, name: String) = VideoSourceContract.functionBody(read(path), name).replace(Regex("\\s+"), " ")

    private val controller = "shared/src/commonMain/kotlin/com/cosmotune/video/player/controller/VideoPlayerController.kt"
    private val player = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/player"
    private val pill = "$player/GoLivePill.kt"
    private val controls = "$player/PlayerControls.kt"
    private val scrubber = "$player/PlayerScrubber.kt"
    private val android = "video/src/androidMain/kotlin/com/cosmotune/video/player/android/AndroidVideoEngine.kt"
    private val mpv = "video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/MpvPlayer.kt"
    private val hour = 3_600_000L

    // ---- The controller ------------------------------------------------------------------------------------------------------

    @Test fun theControllerWritesNoHistoryAndAsksNoSegmentsForLive() {
        assertTrue("if (l.live || !engineLoaded || library.historyPaused.value) return" in body(controller, "recordNow"), "no history for live")
        assertTrue("if (!s.sponsorBlock || l.live) return" in body(controller, "startSegments"), "no SponsorBlock lookup for live")
        assertTrue(body(controller, "startPosition").trimStart('{', ' ').startsWith("if (live) return 0"), "resume and an explicit start never apply to live")
    }

    @Test fun theControllerPlaysTheHlsAddressAsItIsAndKeepsSeeksInTheWindow() {
        val select = body(controller, "select")
        assertTrue("if (l.live && hls != null) return Selection(option = null, input = MediaInput.Hls(hls), video = null, audio = null, ladder = emptyList())" in select)
        assertTrue("if (l.local != null || l.live) return" in body(controller, "selectQuality"), "Auto only for live")
        assertTrue("if (l.local != null || l.live) return" in body(controller, "selectAudioTrack"), "no audio track choice for live")
        val seek = body(controller, "seekTo")
        assertTrue("if (LiveWindow.liveEdgeMs(duration) <= LiveWindow.WINDOW_START_MS) return" in seek, "no window, no seek")
        assertTrue("LiveWindow.clampSeek(ms, LiveWindow.WINDOW_START_MS, duration)" in seek)
        assertTrue("if (!l.live) seekTo(0)" in body(controller, "playPrevious"), "Previous never seeks a live stream to its window's start")
    }

    @Test fun anEndedLiveStreamIsLoadedAgainOnce() {
        val ended = body(controller, "onEnded")
        assertTrue("if (l.live && reloadLive(l)) return" in ended)
        assertTrue("if (l.loop && !l.live)" in ended, "a live stream never loops")
        val reload = body(controller, "reloadLive")
        assertTrue("if (last != null && now - last < LIVE_RELOAD_WINDOW_MS) return false" in reload)
        assertTrue("source.details(l.videoId)" in reload, "fresh details, never the cache")
        assertTrue("if (fresh.kind != StreamKind.LIVE || fresh.streams.hlsUrl == null)" in body(controller, "applyLiveReload"), "a broadcast that is over is not loaded again")
        assertEquals(60_000L, VideoPlayerController.LIVE_RELOAD_WINDOW_MS)
    }

    @Test fun anUpcomingVideoNeverReachesTheEngine() {
        val upcoming = body(controller, "showUpcoming")
        assertTrue("l.error = VideoUiError.UNAVAILABLE" in upcoming && "engineLoaded = false" in upcoming)
        assertFalse("engine." in upcoming, "nothing is asked of the engine")
        assertTrue("if (item.isUpcoming) { showUpcoming(l, item, details) return }" in body(controller, "applyRemote"), "before any stream is chosen")
        assertTrue("premiere != null -> showUpcoming(l, premiere, details = null)" in body(controller, "begin"), "a premiere known to be ahead is not asked for")
        assertTrue("if (error == VideoUiError.UNAVAILABLE && premiere != null)" in body(controller, "failLoad"), "the source refusing a premiere")
        assertTrue("if (details.item.isUpcoming) return" in body(controller, "finishFetch"), "a premiere's details are not cached")
    }

    // ---- The engines ---------------------------------------------------------------------------------------------------------

    @Test fun theEnginesReportTheLiveWindowAndStartLiveAtTheEdge() {
        val load = flat(android)
        assertTrue("val start = if (request.live) C.TIME_UNSET else from" in load, "Android: the default position of a live item")
        assertTrue("c.setMediaItem(item, start)" in load)
        assertTrue("val durationMs = if (c == null || c.duration == C.TIME_UNSET) 0L else c.duration.coerceAtLeast(0)" in load, "Android: the live window's duration")
        assertFalse("|| live ||" in load)
        val desktop = flat(mpv)
        assertTrue("val window = if (live) bufferedMs else durationMs" in desktop, "desktop: the end of the cache")
        assertTrue("durationMs = window, bufferedMs = bufferedMs" in desktop)
        assertTrue("if (!live && duration > 0 && ms > duration) ms = duration" in desktop, "desktop: a live playhead is never held at a duration")
        assertTrue("@Volatile private var live = false" in desktop)
        assertTrue("this.live = live" in desktop && "set(\"start\", if (live) \"none\" else seconds(startMs))" in desktop)
    }

    // ---- The chrome --------------------------------------------------------------------------------------------------------------

    @Test fun theBottomRowShowsTheLiveBadgeAndGoToLiveInsteadOfTheTime() {
        val text = flat(controls)
        assertTrue("if (state.live) LiveStatus(playback, state, playhead, onInput) else TimeText(" in text, "the page, full screen and the desktop page")
        assertTrue("if (state.live) LiveStatus(playback, state, playhead, onInput, Modifier.align(Alignment.CenterStart))" in text, "the full window's bar")
        val status = body(pill, "LiveStatus")
        assertTrue("LiveBadge()" in status && "if (behind) {" in status && "GoLivePill(onClick = {" in status)
        assertTrue("showsGoLive(playhead.second.longValue * 1000, duration)" in status, "read from the playhead's whole second, never per frame")
        assertTrue("derivedStateOf" in status, "the row recomposes only when the pill comes or goes")
    }

    @Test fun thePillIsACSecondaryPillAndItsThresholdIsTenSeconds() {
        val text = flat(pill)
        assertTrue("text = stringResource(Res.string.video_go_live)" in text)
        assertTrue("kind = CosmoButtonKind.QUIET" in text && "floating = true" in text, "a C secondary pill: the floating QUIET button")
        assertTrue("size = CosmoButtonSize.SMALL" in text && "icon = VideoIcons.Live" in text)
        assertTrue("LiveWindow.showGoLive(LiveWindow.behindLiveEdgeMs(positionMs, durationMs))" in text)
        assertEquals(10_000L, LiveWindow.GO_LIVE_AFTER_MS)
        // 10 s behind where live plays is not yet "more than 10 s"; a millisecond more is.
        assertFalse(showsGoLive(positionMs = hour - 20_000, durationMs = hour))
        assertTrue(showsGoLive(positionMs = hour - 20_001, durationMs = hour))
        assertFalse(showsGoLive(positionMs = hour - 12_000, durationMs = hour), "at live, the engine's jitter")
        assertFalse(showsGoLive(positionMs = 0, durationMs = 0), "no window")
    }

    @Test fun goToLiveSeeksToTheLiveEdge() {
        val live = FakeVideoSource.sampleItem("fakevideo11")!!
        val state = PlaybackUiState.Idle.copy(item = live, status = PlayerStatus.READY, playing = true, live = true, durationMs = hour)
        val playback = FakeVideoPlayback(initial = state).also { it.position = hour / 2 }
        goToLive(playback, state)
        assertEquals(listOf("seekTo(${hour - 10_000})"), playback.calls)
        // The number keys jump to tenths of the window up to the edge.
        assertTrue(performPlayerKey(PlayerKeyAction.SeekToFraction(0.5f), playback, state))
        assertEquals("seekTo(${(hour - 10_000) / 2})", playback.calls.last())
        assertFalse(performPlayerKey(PlayerKeyAction.SeekToFraction(0.5f), playback, state.copy(durationMs = 6_000)), "no room to seek")
    }

    @Test fun theScrubberIsTheWindowUpToTheLiveEdge() {
        val text = flat(scrubber)
        assertTrue("val duration = if (live) LiveWindow.liveEdgeMs(state.durationMs) else state.durationMs" in text)
        assertTrue("if (duration <= 0) { Box(modifier) return }" in text, "a window too short to seek in draws no bar")
        assertTrue("settings.sponsorBlock && settings.segmentsOnBar && !live" in text, "no segments on a live bar")
        assertTrue("val chapters = if (live) emptyList() else state.details?.chapters.orEmpty()" in text)
        assertTrue("storyboard = if (live) null else state.details?.storyboard" in text)
        assertTrue("behindSecond <= 0 -> liveLabel" in text && "else -> \"-\" + ScrubberGeometry.timeLabel(behindSecond * 1000)" in text, "the bubble says how far behind live")
        // The live edge moves every second or so: the gestures must not restart with it (a drag would be lost).
        assertTrue("val span by rememberUpdatedState(duration)" in text)
        assertEquals(2, Regex("""\.pointerInput\(playback\)""").findAll(text).count())
        assertFalse(".pointerInput(duration)" in text)
    }

    @Test fun anUpcomingVideoShowsTheBadgeAndItsStartTime() {
        val error = body(controls, "PlayerErrorState")
        assertTrue("val upcoming = LiveWindow.isUpcoming(state)" in error && "if (upcoming) UpcomingBadge()" in error)
        val line = body(controls, "upcomingText")
        assertTrue("stringResource(Res.string.video_live_starts, time)" in line && "remember(start) { startTimeText(start) }" in line)
        assertTrue("state.item?.uploadedText.orEmpty()" in line, "the source's own words without a time")
        assertTrue("palette.accentContainer" in flat(controls) && "palette.onAccentContainer" in flat(controls), "spec 5.0: Upcoming on accentContainer")
        val text = startTimeText(0, ZoneOffset.UTC, Locale.UK)
        assertTrue("1970" in text && "00:00" in text, text)
        val strings = read("video/src/commonMain/composeResources/values/strings.xml")
        assertTrue("<string name=\"video_live_starts\">Starts %1\$s</string>" in strings)
    }

    @Test fun theLiveCodeFollowsTheLook() {
        val source = read(pill)
        val text = code(pill)
        assertFalse(Regex("""Color\(0x|Color\.(White|Black|Red|Gray|Transparent)""").containsMatchIn(text), "no colour by hand")
        assertFalse("positionMs()" in text, "the playhead is the ticker's")
        assertFalse(Regex("""(?<![\w])live\(""").containsMatchIn(text), "no live palette read in composition")
        assertFalse(Regex("""(?i)youtube|\bYT\b|cookie|org\.schabi""").containsMatchIn(source), "no source name")
        assertFalse(Regex("""\btext\s*=\s*"""").containsMatchIn(text), "the label comes from the strings")
        for (name in listOf("GoLivePill", "PlayerControls", "PlayerScrubber")) {
            assertFalse("HazeState" in code("$player/$name.kt"), "$name: no backdrop blur over the video")
        }
        val window = read("shared/src/commonMain/kotlin/com/cosmotune/video/player/LiveWindow.kt")
        assertFalse(Regex("""(?i)youtube|org\.schabi|java\.""").containsMatchIn(window), "LiveWindow stays common and nameless")
    }
}
