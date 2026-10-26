package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to
 * be added): the rules the lyrics, the queue, the sheets and the landscape layout of the new Now Playing were written under.
 */
class NowPlayingPanelsContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun nowPlaying(name: String): String =
        File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/$name").readText().replace("\r\n", "\n")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    private fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    // ---- the clock ------------------------------------------------------------------------------------------------------

    @Test fun theClockIsTheOldPanelsDriverLoopOverTheSameEngine() {
        val raw = nowPlaying("CosmoLyricClock.kt")
        assertTrue(raw.contains("PlayerLyrics.kt:608-660"), "it names where it was copied from")
        val src = code(raw)
        assertTrue(src.contains("internal fun rememberCosmoLyricClock("))
        assertTrue(src.contains("LyricClock(clock.longValue)"), "the same engine")
        assertTrue(src.contains("engine.hold("), "paused: each reading is the truth")
        assertTrue(src.contains("engine.restart()"), "playing: a fresh run from the latest reading")
        assertTrue(src.contains("withFrameNanos"), "playing: one reading a frame")
        assertTrue(src.contains("engine.frame("))
        assertTrue(src.contains("rememberIsForeground()"), "the loop stops in the background")
        assertTrue(src.contains("SAME_CLOCK_NANOS = 250_000_000L"))
    }

    // ---- the lyrics -----------------------------------------------------------------------------------------------------

    @Test fun theLineBeingSungIsFilledByAClippedAccentCopyOfTheSameText() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        // a line stamped only at its start fills over its slot; one with word timings fills a word at a time (LyricsParityContractTest)
        assertTrue(src.contains("sweepFill(layoutRef.value, SungFraction.of(env.lines, index, env.clock.longValue))"))
        assertTrue(src.contains("clipRect("), "the copy is clipped")
        assertTrue(src.contains("accent = Color(palette.accentStrong)"), "the copy is accentStrong")
        assertTrue(src.contains("primary = Color(palette.textPrimary)"), "under it the line is textPrimary")
        assertTrue(src.contains("past = Color(palette.textTertiary)") && src.contains("upcoming = Color(palette.textSecondary)"))
    }

    @Test fun theClockIsReadOnlyInADerivedStateAndInDrawLambdas() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        val reads = LyricSourceRules.readsOf(src, "clock.longValue")
        assertTrue(reads.isNotEmpty())
        // the current line, the sweep, the lift, the dots of an instrumental: each in a derived state or a draw lambda, never in composition
        for (at in reads) assertTrue(LyricSourceRules.readsInDrawOrDerived(src, at), "composition reads the clock at offset $at")
        assertTrue(src.contains("derivedStateOf { if (synced) currentLineAt(lines, clock.longValue) else -1 }"), "the current line is a derived state")
        assertTrue(src.lastIndexOf(".drawBehind {", src.indexOf("val through = ((clock.longValue")) > src.indexOf("private fun GapDots("), "the dots read it while drawing")
        assertTrue(src.indexOf("if (opened <= 0f) return@drawBehind") in src.lastIndexOf(".drawBehind {", src.indexOf("val through = ((clock.longValue"))..src.indexOf("val through = ((clock.longValue"), "a closed break does not read the clock")
    }

    @Test fun aScaleIsALayerNeverAFontSize() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        assertTrue(src.contains("scaleX = k") && src.contains("scaleY = k"), "the scale is drawn by a graphics layer")
        assertTrue(src.contains("TransformOrigin(LyricsMetrics.originFraction(onEnd, textRtl), 0.5f)"), "from the edge the line is written from: the start edge, or the end edge of a duet's second voice")
        assertTrue(src.contains("CosmoMotion.slow(reduced)"), "on the slow spring")
        // the one font size set in the file is the romanisation's
        val sizes = Regex("""fontSize\s*=\s*[^,)\n]+""").findAll(src).map { it.value.replace(Regex("\\s+"), " ") }.toList()
        assertEquals(listOf("fontSize = LyricsMetrics.SUB_SIZE_SP.sp", "fontSize = 13.sp"), sizes, "no line's font size follows its state")
    }

    @Test fun everyLineIsLaidOutOnceAtAWidthThatLeavesRoomForItsLargestScale() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        assertTrue(src.contains("LyricsMetrics.layoutWidthPx(constraints.maxWidth, lanePx, env.style.currentScale)"), "what is left after a duet's lane, over the largest scale")
        assertTrue(src.contains("type.title(style.baseStep)"), "title(baseStep), the same text for every state")
    }

    @Test fun theListFollowsTheSongAndAFingerPausesIt() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        assertTrue(src.contains("animateScrollToItem(index, offset)"))
        assertTrue(src.contains("LyricsMetrics.followOffsetPx(viewport)"), "one third of the height down")
        assertTrue(src.contains("source == NestedScrollSource.UserInput"), "only a person's scroll counts")
        assertTrue(src.contains("delay(LyricsMetrics.FOLLOW_PAUSE_MS)"), "2.5 s")
        assertTrue(src.contains("if (reduced)") || src.contains("|| reduced"), "reduced motion takes the line at once")
    }

    @Test fun aTapOnALineSeeksAndNoLyricsAreSaidInTheMiddle() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        assertTrue(src.contains("LyricRows.OnTap.SEEK -> env.onSeek(line.timeMs)"))
        assertTrue(src.contains("emptyText: String = stringResource(Res.string.lyrics_not_available)"))
        assertTrue(src.contains("contentAlignment = Alignment.Center"))
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))"), "with the offset applied")
    }

    @Test fun theLyricsViewTakesTheBriefsParametersAndLeavesOutTheLivePaletteReads() {
        val src = code(nowPlaying("CosmoLyricsView.kt"))
        val head = src.substring(src.indexOf("internal fun CosmoLyricsView("), src.indexOf(") {", src.indexOf("internal fun CosmoLyricsView(")))
        for (name in listOf("lines:", "subLines:", "trackKey:", "playhead:", "isPlaying:", "onSeekToLine:", "style: LyricsStyle", "modifier:")) {
            assertTrue(head.contains(name), "CosmoLyricsView has $name")
        }
        assertFalse(src.contains(".live("), "the lyrics read the palette once per cover, in composition")
        assertTrue(src.contains("val Portrait = LyricsStyle(LyricsMetrics.PORTRAIT_BASE_STEP, LyricsMetrics.PORTRAIT_CURRENT_SCALE, LyricsMetrics.PORTRAIT_PAST_SCALE)"))
    }

    // ---- the queue ------------------------------------------------------------------------------------------------------

    @Test fun theQueueIsADarkCardOfTwentyEightFilledWithTheScrimInBothThemes() {
        val src = code(nowPlaying("CosmoQueuePanel.kt"))
        assertTrue(src.contains("SuperellipseShape(CosmoRadii.xl)"), "CosmoRadii.xl is 28 dp")
        assertTrue(src.contains("const val QUEUE_SCRIM_ALPHA = 0.9f"))
        val draw = src.indexOf("drawBehind {")
        assertTrue(draw > 0 && src.indexOf(".live(CoverField.SCRIM)") > draw, "the scrim is read in the draw phase")
        assertEquals(1, Regex("""\.live\(""").findAll(src).count())
        assertFalse(src.contains("dark"), "no theme branch: the same card in both")
    }

    @Test fun theQueueCardHoldsTheInlineQueueWithEveryHandlerPassedOn() {
        val src = code(nowPlaying("CosmoQueuePanel.kt"))
        for (arg in listOf(
            "queue = queue", "currentIndex = queueIndex", "autoplayEnabled = autoplayEnabled", "controlsLocked = controlsLocked",
            "onJumpTo = onJumpTo", "onRemove = onRemoveFromQueue", "onMove = onMoveInQueue", "onClear = onClearQueue",
            "onDragActiveChange = onQueueDragActiveChange",
        )) assertTrue(src.contains(arg), arg)
    }

    // ---- the sheets and the back stack ------------------------------------------------------------------------------------

    @Test fun backReachesTheInnermostLayerFirstInTheOrderOfTheOldScreen() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        val handlers = Regex("""PlayerBackHandler\(enabled = ([\w.]+)""").findAll(src).map { it.groupValues[1] }.toList()
        // a later call is the one back reaches first: the lyrics, the queue, then the sheets
        assertEquals(
            listOf("panels.lyricsOpen", "panels.queueOpen", "flags.output", "flags.providers", "flags.members", "flags.pipeline", "flags.cast", "lyricsOffsetOpen"),
            handlers,
        )
        assertTrue(src.indexOf("PlayerBackHandler(enabled = panels.queueOpen && !wide)") in 0 until src.indexOf("NowPlayingSheets(\n                flags"), "the panels register before the sheets")
        assertTrue(src.contains("PlayerBackHandler(enabled = panels.lyricsOpen && !wide)"), "in a wide window the tabs are not layers back peels off")
    }

    @Test fun everySheetIsDrawnOverThePlayersOwnHazeStateAndTheLyricsStaysAwake() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        for (sheet in listOf("AudioOutputSheet(", "LyricsProviderSheet(", "ListenTogetherMembersSheet(", "LyricsOffsetSheet(", "AudioPipelineDialog(", "CastDialog(")) {
            val call = src.substring(src.indexOf(sheet)).take(200)
            assertTrue(call.contains("hazeState = hazeState"), "$sheet takes the player's haze state")
        }
        assertTrue(src.contains("currentSource = lyricsSource") && src.contains("states = lyricsProviderStates") && src.contains("onSelect = onSelectLyricsProvider"))
        assertTrue(src.contains("KeepScreenOn(enabled = if (wide) !panels.queueOpen else panels.lyricsOpen)"))
        assertTrue(src.contains("flags.providers = true") || src.contains("sheets.providers = true"), "the lyrics status opens the provider sheet")
        assertTrue(code(nowPlaying("NowPlayingMedia.kt")).contains("Box(modifier.hazeSource(hazeState))"), "portrait: the ground and the video are what the sheets blur")
        assertTrue(code(nowPlaying("NowPlayingLandscape.kt")).contains(".hazeSource(hazeState)"), "landscape: the ground and the media are")
    }

    // ---- the page -------------------------------------------------------------------------------------------------------

    @Test fun theLyricsAndTheQueueFillTheRoomBetweenTheTopBarAndTheStack() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(src.contains("TOP_BAR_BELOW_STATUS_DP.dp.roundToPx()"))
        assertTrue(src.contains("constraints.maxHeight - stackHeightPx.value"), "the stack's height is read in the layout phase")
        assertTrue(src.contains("LyricsStyle.Portrait"))
        assertTrue(src.contains("alpha = coverShown.value"), "the large cover goes while a panel has the page")
    }

    @Test fun theStackStepsTheTitleDownFoldsForTheQueueAndKeepsOnlyThreeReadsOfThePlayhead() {
        val raw = nowPlaying("NowPlayingControls.kt")
        val src = code(raw)
        assertTrue(src.contains("type.title(if (title.length > TypeScale.LONG_TITLE_CHARS) TypeScale.TITLE_M else TypeScale.TITLE_L)"))
        assertTrue(src.contains("if (if (landscape) panel != null else queueOpen) 1f else 0f"), "a panel folds away what its card covers")
        // Y-26 (Video mode): the fourth is the "Watch the music video" action, read only when the tool is pressed.
        assertEquals(4, Regex("""position\.positionMs""").findAll(src).count())
        assertTrue(src.contains("playSize = if (landscape) LandscapeMetrics.PLAY_BUTTON_DP.dp else 80.dp"))
        assertTrue(src.contains("LandscapeMetrics.TITLE_STEP"), "display(DISPLAY_S) in two panes")
        assertTrue(src.contains("val arrival = remember(song.videoId)"), "the title's arrival lives in the stack: a title folded away and back does not arrive again")
    }

    @Test fun landscapeIsTwoPanesWithTheVideoCroppedAndTheCoverSizedToFit() {
        val src = code(nowPlaying("NowPlayingLandscape.kt"))
        assertTrue(src.contains("contentMode = CanvasContentMode.FIT_PORTRAIT"))
        assertTrue(src.contains("presentationAlpha = { reveal.value.coerceIn(0f, 1f) }"), "the clip's own alpha, not a layer")
        assertTrue(src.contains("LandscapeMetrics.coverSideDp(maxWidth.value, maxHeight.value)"))
        assertTrue(src.contains("CosmoRadii.coverNowPlaying"), "radius 28")
        assertTrue(src.contains("shell?.playerCoverBounds = it.boundsInRoot()"))
        assertFalse(src.contains(".live("))
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("NowPlayingLayout.LANDSCAPE -> landscapePage()"))
        assertTrue(screen.contains("stack(Modifier.weight(1f), true,"), "the lyrics or the queue replace the title block; the transport stays")
    }
}
