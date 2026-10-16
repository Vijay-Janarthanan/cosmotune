package com.cosmotune.video.ui

import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyAction
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.player.ChromeMetrics
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.SPEEDS
import com.cosmotune.video.ui.player.TapZone
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.preferredSubtitle
import com.cosmotune.video.ui.player.snapSpeed
import com.cosmotune.video.ui.player.speedLabel
import com.cosmotune.video.ui.player.tapZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The player chrome (plan task Y-15, step 5): what no render can show, read from the source (the playhead and the live palette only
 * in draw-phase code or the ticker, content descriptions from the strings, 48 dp targets, the spec's numbers from [VideoMetrics], glass
 * with no backdrop, no colour by hand), and the small pieces of logic the chrome is made of, run.
 */
class PlayerChromeStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/player"
    private val names = listOf(
        "VideoPlayerBox", "PlayerControls", "PlayerScrubber", "PlayerGestures", "PlayerSettingsSheet", "SubtitleOverlay",
        "SkipSegmentButton", "SeekPill", "PlayerStats", "PlayerPlatform",
    )
    private val files: List<Pair<String, String>> get() = names.map { it to read("$dir/$it.kt") } + listOf(
        "PlayerPlatform.android" to read("video/src/androidMain/kotlin/com/cosmotune/video/ui/player/PlayerPlatform.android.kt"),
        "PlayerPlatform.jvm" to read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/player/PlayerPlatform.jvm.kt"),
    )
    private fun file(name: String) = read("$dir/$name.kt")
    private val allCode: String get() = files.joinToString("\n") { VideoSourceContract.code(it.second) }

    // ---- The contract ----------------------------------------------------------------------------------------------------

    @Test fun theBoxHasTheSignatureOfTheBrief() {
        assertEquals(
            listOf(
                "playback: VideoPlayback", "surface: VideoSurfaceProvider?", "layout: PlayerLayout", "modifier: Modifier = Modifier",
                "onCollapse: (() -> Unit)? = null", "onToggleFullscreen: () -> Unit", "onEnterPip: (() -> Unit)? = null",
                "onBoundsChanged: (Rect) -> Unit = {}", "title: String? = null", "desktopFullWindow: Boolean = false", "chrome: Boolean = true",
            ),
            VideoSourceContract.parameters(file("VideoPlayerBox"), "VideoPlayerBox"),
        )
        assertTrue("enum class PlayerLayout { PAGE, FULLSCREEN, WINDOW }" in file("VideoPlayerBox"))
        assertEquals(PlayerLayout.entries.map { it.name }, listOf("PAGE", "FULLSCREEN", "WINDOW"))
    }

    @Test fun thePlatformIsAnExpectWithBothActuals() {
        assertTrue("expect fun rememberPlayerPlatform(playback: VideoPlayback): PlayerPlatformControls" in file("PlayerPlatform"))
        val android = read("video/src/androidMain/kotlin/com/cosmotune/video/ui/player/PlayerPlatform.android.kt")
        val desktop = read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/player/PlayerPlatform.jvm.kt")
        assertTrue("actual fun rememberPlayerPlatform(" in android && "actual fun rememberPlayerPlatform(" in desktop)
        assertTrue("screenBrightness" in android && "STREAM_MUSIC" in android, "Android: the window's brightness and the music volume")
        assertTrue("BRIGHTNESS_OVERRIDE_NONE" in android, "the brightness goes back to the system's")
        assertTrue("override val swipeControls: Boolean get() = false" in desktop && "engine.setVolume" in desktop)
    }

    // ---- The playhead and the palette only while drawing -------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 160), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$""")
    private val topLevelFun = Regex("""(?m)^(?:(?:internal|private|public)\s+)?fun\s+(?:<[^>]*>\s*)?([\w.<>?]+\.)?\w+\s*\(""")

    private fun inDrawScopeFunction(code: String, index: Int): Boolean {
        val header = topLevelFun.findAll(code).lastOrNull { it.range.first <= index } ?: return false
        return header.groupValues[1] == "DrawScope."
    }

    private fun lineOf(code: String, at: Int) = code.take(at).count { it == '\n' } + 1

    @Test fun thePlayheadIsReadOnlyWhileDrawingOrInTheTicker() {
        var seen = 0
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            var from = 0
            while (true) {
                val at = code.indexOf("positionMs()", from)
                if (at < 0) break
                from = at + 1
                seen++
                val heads = enclosingHeads(code, at)
                val inDraw = heads.any { drawHead.containsMatchIn(it) }
                val inTicker = heads.any { "class Playhead" in it }
                assertTrue(inDraw || inTicker, "$name: positionMs() at line ${lineOf(code, at)} is neither in a draw lambda nor in the ticker")
            }
        }
        assertTrue(seen >= 3, "the check found the chrome's playhead reads (scrubber, skip line, ticker)")
        val box = VideoSourceContract.code(file("VideoPlayerBox"))
        assertEquals(1, Regex("""withFrameNanos\(onFrame\)""").findAll(box).count(), "one frame ticker, with one lambda for every frame")
        assertTrue("playing && (shown || scrubbing.value || state.pendingSkip != null) -> TickMode.FRAME" in box, "every frame only while playing and something shows it")
        assertEquals(1, Regex("""withFrame(Nanos|Millis)\s*[({]""").findAll(allCode).count(), "no other frame loop")
    }

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
                val inDraw = enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDraw || inDrawScopeFunction(code, at), "$name: live( at line ${lineOf(code, at)} is neither in a draw lambda nor in a DrawScope function")
            }
        }
        assertTrue(seen > 0, "the check found the chrome's live reads")
    }

    @Test fun theLineIsDrawnWithoutAllocating() {
        val scrubber = VideoSourceContract.code(file("PlayerScrubber"))
        val body = scrubber.substringAfter("private fun DrawScope.drawScrubLine(").substringBefore("private fun DrawScope.piece(")
        for (word in listOf("listOf(", "Brush", "Path(", "arrayOf(", "toFloatArray", "map {", "filter {", "String")) {
            assertFalse(word in body.substringAfter("{"), "the line's drawing allocates: $word")
        }
        assertTrue("toFloatArray()" in scrubber && "remember(chapters, duration)" in scrubber, "the gaps are worked out once, in composition")
        assertTrue(Regex("""\.graphicsLayer\(\)\s*\.drawBehind""").containsMatchIn(scrubber), "the line has its own layer")
    }

    @Test fun nothingRemembersALiveColourOrABrush() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""")
        for ((name, source) in files) assertNull(remembering.find(VideoSourceContract.code(source)), "$name remembers a brush or a live colour")
    }

    // ---- Content descriptions and targets ----------------------------------------------------------------------------------

    /** The text of every call of [function] (from its name to the matching parenthesis). */
    private fun calls(code: String, function: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = Regex("""\b${Regex.escape(function)}\(""").find(code, from) ?: break
            val open = at.range.last
            var depth = 0
            var i = open
            while (i < code.length) {
                when (code[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
                i++
            }
            out += code.substring(at.range.first, i + 1)
            from = i
        }
        return out
    }

    @Test fun everyIconButtonHasAContentDescriptionFromTheStrings() {
        var seen = 0
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            for (call in calls(code, "CosmoIconButton")) {
                seen++
                assertTrue("contentDescription = stringResource(" in call, "$name: a CosmoIconButton without a content description from the strings: ${call.take(120)}")
            }
            assertFalse(Regex("""contentDescription\s*=\s*"""").containsMatchIn(code), "$name: a content description written in the code")
            assertFalse(Regex("""\btext\s*=\s*"""").containsMatchIn(code), "$name: a label written in the code")
        }
        assertTrue(seen >= 9, "the check found the chrome's icon buttons ($seen)")
    }

    @Test fun theOtherControlsAreNamedForAssistiveTechnology() {
        val scrubber = VideoSourceContract.code(file("PlayerScrubber"))
        assertTrue("stringResource(Res.string.video_seek_bar)" in scrubber && "contentDescription = label" in scrubber && "setProgress" in scrubber)
        val controls = VideoSourceContract.code(file("PlayerControls"))
        assertTrue("onClickLabel = toggle" in controls && "stringResource(Res.string.video_time_toggle)" in controls, "the time is a button with a label")
        val skip = VideoSourceContract.code(file("SkipSegmentButton"))
        assertTrue("stringResource(Res.string.video_skip_segment, stringResource(segmentName(segment.category)))" in skip)
        val gestures = VideoSourceContract.code(file("PlayerGestures"))
        assertTrue("contentDescription = label" in gestures, "the meter says what it is")
    }

    @Test fun touchTargetsAreFortyEightDp() {
        // A CosmoIconButton of 40 dp reserves a 48 dp target; the chrome's are 40, 48, 56 and 64.
        for (size in listOf(VideoMetrics.CHROME_ICON_DP, VideoMetrics.QUEUE_BUTTON_DP, VideoMetrics.CENTRE_BUTTON_DP, VideoMetrics.CENTRE_PLAY_DP)) {
            assertTrue(size >= 40f)
        }
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            for (call in calls(code, "CosmoIconButton")) {
                assertTrue(Regex("""size\s*=\s*(VideoMetrics\.\w+|size)\.dp""").containsMatchIn(call), "$name: an icon button's size is not one of the chrome's: ${call.take(120)}")
            }
            var from = 0
            while (true) {
                val at = code.indexOf(".clickable(", from)
                if (at < 0) break
                from = at + 1
                assertTrue("minimumInteractiveComponentSize()" in code.substring(maxOf(0, at - 200), at), "$name: a clickable at line ${lineOf(code, at)} without a 48 dp target")
            }
        }
        val controls = VideoSourceContract.code(file("PlayerControls"))
        assertTrue("internal const val SCRUB_TOUCH_DP = 48f" in controls, "the scrubber's band is the touch target")
    }

    // ---- The spec's numbers ------------------------------------------------------------------------------------------------

    @Test fun theNumbersOfSpecSixFiveComeFromVideoMetrics() {
        val code = allCode
        for (literal in listOf("56.dp", "64.dp", "3000", "3_000", "72.dp", "96.dp", "120.dp", "160.dp", "28.dp", "150)")) {
            assertFalse(literal in code, "a literal $literal in the chrome")
        }
        for (name in listOf(
            "CENTRE_BUTTON_DP", "CENTRE_PLAY_DP", "CENTRE_GAP_PAGE_DP", "CENTRE_GAP_FULL_DP", "QUEUE_BUTTON_DP", "CHROME_ICON_DP",
            "SCRIM_TOP_ALPHA", "SCRIM_TOP_PAGE_DP", "SCRIM_TOP_FULL_DP", "SCRIM_BOTTOM_ALPHA", "SCRIM_BOTTOM_PAGE_DP", "SCRIM_BOTTOM_FULL_DP",
            "TIME_SP", "SCRUB_LINE_DP", "SCRUB_DOT_DP", "SCRUB_LINE_DRAG_DP", "SCRUB_DOT_DRAG_DP", "SCRUB_FULL_SIDE_DP", "SCRUB_FULL_BOTTOM_DP",
            "CHAPTER_GAP_DP", "STORYBOARD_W_DP", "STORYBOARD_H_DP", "FADE_MS", "DOUBLE_TAP_ZONE", "SEEK_PILL_H_DP", "METER_W_DP", "METER_H_DP",
            "SKIP_BUTTON_H_DP", "SKIP_BOTTOM_PAGE_DP", "SKIP_BOTTOM_FULL_DP", "SUB_SP_PHONE", "SUB_SP_PHONE_FULL", "SUB_SP_DESKTOP",
            "SUB_SP_DESKTOP_FULL", "SUB_BOTTOM_FRACTION", "FULL_WINDOW_BAR_MAX_DP", "FULL_WINDOW_BAR_BOTTOM_DP",
        )) {
            assertTrue("VideoMetrics.$name" in code, "VideoMetrics.$name is used")
        }
        val visibility = read("shared/src/commonMain/kotlin/com/cosmotune/video/player/ChromeVisibility.kt")
        assertTrue("VideoMetrics.HIDE_AFTER_MS" in visibility && "3000" !in visibility && "3_000" !in visibility)
    }

    @Test fun theMetricsOfEachLayoutAreTheSpecs() {
        val page = ChromeMetrics.of(PlayerLayout.PAGE, fullWindow = false)
        assertEquals(28f, page.centreGapDp)
        assertEquals(72f, page.scrimTopDp)
        assertEquals(96f, page.scrimBottomDp)
        assertEquals(12f, page.bottomSideDp)
        assertEquals(8f, page.bottomBottomDp)
        assertEquals(48f, page.skipBottomDp)
        val full = ChromeMetrics.of(PlayerLayout.FULLSCREEN, fullWindow = false)
        assertEquals(40f, full.centreGapDp)
        assertEquals(120f, full.scrimTopDp)
        assertEquals(160f, full.scrimBottomDp)
        assertEquals(24f, full.bottomSideDp)
        assertEquals(16f, full.bottomBottomDp)
        assertEquals(96f, full.skipBottomDp)
        assertEquals(full.centreGapDp, ChromeMetrics.of(PlayerLayout.WINDOW, fullWindow = false).centreGapDp, "the desktop page takes full screen's numbers")
        assertTrue(ChromeMetrics.of(PlayerLayout.WINDOW, fullWindow = true).fullWindow)
        assertEquals(56f, page.topRowDp, "8 + 40 + 8")
    }

    // ---- The look ---------------------------------------------------------------------------------------------------------

    @Test fun everyGlassSurfaceHasNoBackdropBlur() {
        var seen = 0
        for ((name, source) in files) {
            for (call in calls(VideoSourceContract.code(source), "cosmoGlass")) {
                seen++
                assertTrue("hazeState = null" in call, "$name: a glass surface with a backdrop: $call")
            }
        }
        assertTrue(seen >= 6, "the bar, bubble, pill, meter, toast and stats card are glass ($seen)")
        assertFalse("HazeState" in allCode, "no haze state in the chrome")
    }

    @Test fun noColourIsPickedByHand() {
        for ((name, source) in files) {
            val code = VideoSourceContract.code(source)
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b""").containsMatchIn(code), "$name uses a named colour")
        }
        // Black: the band around the picture, the subtitles' background and shadow (media scrims, the documented exception).
        val blacks = files.filter { "Color.Black" in VideoSourceContract.code(it.second) }.map { it.first }.toSet()
        assertEquals(setOf("VideoPlayerBox", "SubtitleOverlay"), blacks)
        // White: text over the video (title, time, error, subtitles) and the scrubber's line over the video.
        val whites = files.filter { "Color.White" in VideoSourceContract.code(it.second) }.map { it.first }.toSet()
        assertEquals(setOf("PlayerControls", "PlayerScrubber", "SubtitleOverlay"), whites)
        // The segments' colours are SponsorBlock's category colours (the documented exception on the scrubber).
        assertTrue("Color(range.third)" in VideoSourceContract.code(file("PlayerScrubber")))
    }

    @Test fun theScrimsAreThePalettesScrimFadedWhileDrawing() {
        val box = VideoSourceContract.code(file("VideoPlayerBox"))
        assertTrue("colors.live(CoverField.SCRIM)" in box)
        assertTrue(Regex("""onDrawBehind \{\s*val alpha = visibility\.value""").containsMatchIn(box), "the fade is read while drawing")
        assertTrue("if (reduced) snap() else tween(VideoMetrics.FADE_MS)" in box, "150 ms, none under reduced motion")
    }

    @Test fun nothingNamesTheSourceOrTouchesAnAccount() {
        for ((name, source) in files) {
            assertFalse("org.schabi" in source, "$name imports the extractor")
            assertFalse(Regex("""(?i)youtube|\bYT\b|cookie""").containsMatchIn(source), "$name names the source or touches cookies")
        }
        for (pure in listOf("ChromeVisibility", "PlayerKeyMap", "ScrubberGeometry")) {
            val source = read("shared/src/commonMain/kotlin/com/cosmotune/video/player/$pure.kt")
            assertFalse(Regex("""(?i)youtube|org\.schabi|java\.""").containsMatchIn(source), "$pure stays common and nameless")
        }
    }

    // ---- What the chrome offers ------------------------------------------------------------------------------------------------

    @Test fun anEndedVideoOffersReplay() {
        val controls = VideoSourceContract.code(file("PlayerControls"))
        assertTrue("state.status == PlayerStatus.ENDED && item != null" in controls)
        assertTrue("stringResource(Res.string.video_replay)" in controls && "playback.play(item.id, startMs = 0L)" in controls)
    }

    @Test fun theSettingsAreASheetOnThePhoneAndAMenuOnTheDesktop() {
        val sheet = VideoSourceContract.code(file("PlayerSettingsSheet"))
        assertTrue("SurfaceSheetSwitch(" in sheet, "the phone's sheet is the app's sheet host with CosmoSheetSurface")
        assertTrue(".cosmoMenuSurface()" in sheet && "menu = menu" in sheet, "the desktop's menu has 44 dp rows")
        val main = sheet.substringAfter("SettingsPage.MAIN -> {").substringBefore("SettingsPage.QUALITY -> {")
        val order = listOf("VideoIcons.Hd, quality", "VideoIcons.Speed, speed", "VideoIcons.Captions, captions", "CosmoIcons.Translate, audio", "Res.string.video_loop", "Res.string.video_stats")
        val at = order.map { main.indexOf(it) }
        assertTrue(at.all { it >= 0 } && at == at.sorted(), "Quality, Speed, Captions, Audio track, Loop, Stats in that order: $at")
        val box = VideoSourceContract.code(file("VideoPlayerBox"))
        assertTrue("layout == PlayerLayout.WINDOW && settingsOpen" in box && "layout != PlayerLayout.WINDOW && settingsOpen" in box)
    }

    @Test fun theSpeedsAreQuartersFromAQuarterToFour() {
        assertEquals(16, SPEEDS.size)
        assertEquals(0.25f, SPEEDS.first())
        assertEquals(4f, SPEEDS.last())
        assertEquals("1", speedLabel(1f))
        assertEquals("1.25", speedLabel(1.25f))
        assertEquals("0.5", speedLabel(0.5f))
        assertEquals("4", speedLabel(4f))
        assertEquals("0.75", speedLabel(0.75f))
        assertEquals(1.05f, snapSpeed(1.03f), 0.0001f)
        assertEquals(0.25f, snapSpeed(0.01f))
        assertEquals(4f, snapSpeed(9f))
    }

    @Test fun theDoubleTapZonesAreTheOuterFortyPercent() {
        assertEquals(TapZone.LEFT, tapZone(0.39f))
        assertEquals(TapZone.MIDDLE, tapZone(0.41f))
        assertEquals(TapZone.MIDDLE, tapZone(0.59f))
        assertEquals(TapZone.RIGHT, tapZone(0.61f))
    }

    private fun track(language: String, auto: Boolean = false) = SubtitleTrack("https://example.com/$language.vtt", "text/vtt", language, language, auto)

    @Test fun theCaptionsButtonPrefersTheSettingsLanguageThenAPersonsTrack() {
        val state = PlaybackUiState.Idle.copy(subtitles = listOf(track("de", auto = true), track("en-US"), track("fr")))
        assertEquals("en-US", preferredSubtitle(state, VideoSettings(captionsLanguage = "en")), "a language-prefix match")
        assertEquals("fr", preferredSubtitle(state, VideoSettings(captionsLanguage = "fr")))
        assertEquals("en-US", preferredSubtitle(state, VideoSettings()), "no setting: the first track made by a person")
        assertNull(preferredSubtitle(PlaybackUiState.Idle, VideoSettings()))
    }

    @Test fun theKeysAreCarriedOut() {
        val item = FakeVideoSource.sampleItem("fakevideo01")!!
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.READY, durationMs = 200_000, speed = 3.9f))
        assertTrue(performPlayerKey(PlayerKeyAction.TogglePlay, playback))
        assertTrue(performPlayerKey(PlayerKeyAction.SeekToFraction(0.5f), playback))
        assertTrue(performPlayerKey(PlayerKeyAction.Speed(0.25f), playback))
        assertTrue(performPlayerKey(PlayerKeyAction.SeekBy(-5_000), playback))
        assertFalse(performPlayerKey(PlayerKeyAction.Theatre, playback), "theatre is the page's")
        assertFalse(performPlayerKey(PlayerKeyAction.Volume(0.05f), playback), "no volume without the platform")
        assertEquals(listOf("togglePlayPause()", "seekTo(100000)", "setSpeed(4.0)", "seekBy(-5000)"), playback.calls)
        var full = 0
        assertTrue(performPlayerKey(PlayerKeyAction.FullWindow, playback, onFullWindow = { full++ }))
        assertEquals(1, full)
        val ended = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.ENDED, durationMs = 200_000))
        performPlayerKey(PlayerKeyAction.TogglePlay, ended)
        assertEquals(listOf("play(fakevideo01)"), ended.calls, "Space on an ended video replays it")
    }
}
