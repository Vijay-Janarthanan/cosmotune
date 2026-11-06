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

    // ---- Up next ------------------------------------------------------------------------------------------------------

    @Test fun upNextIsHomesRowsOnThePagesGroundInBothThemes() {
        val src = code(nowPlaying("NowPlayingUpNext.kt"))
        assertTrue(src.contains("OrbitSongRow("), "Home's song rows")
        assertFalse(src.contains("Color.White"), "no white text: the rows take the page's ink, light or dark")
        assertFalse(src.contains(".live("))
        assertTrue(src.contains("splitQueue(queue, queueIndex, showHistory)"), "the old queue's groups")
        assertTrue(src.contains("rememberQueueDrag(listState, tracks, onMove, onDragActiveChange)"), "and its drag")
    }

    @Test fun theScreenHandsUpNextEveryHandler() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        for (arg in listOf(
            "queue = queue", "queueIndex = queueIndex", "autoplayEnabled = autoplayEnabled", "controlsLocked = controlsLocked",
            "onJumpTo = onJumpTo", "onRemove = onRemoveFromQueue", "onMove = onMoveInQueue", "onClear = onClearQueue",
            "onToggleAutoplay = onToggleAutoplay", "onDragActiveChange = onQueueDragActiveChange",
        )) assertTrue(src.contains(arg), arg)
    }

    // ---- the sheets and the back stack ------------------------------------------------------------------------------------

    @Test fun backReachesTheInnermostLayerFirstInTheOrderOfTheOldScreen() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        val handlers = Regex("""PlayerBackHandler\(enabled = ([\w.]+)""").findAll(src).map { it.groupValues[1] }.toList()
        // a later call is the one back reaches first: the lyrics, the queue, then the sheets
        assertEquals(
            listOf("panels.lyricsOpen", "panels.queueOpen", "panels.relatedOpen", "cinemaShown", "flags.output", "flags.providers", "flags.members", "flags.details", "flags.pipeline", "flags.cast", "lyricsOffsetOpen"),
            handlers,
        )
        assertTrue(src.indexOf("PlayerBackHandler(enabled = panels.queueOpen && !wide)") in 0 until src.indexOf("NowPlayingSheets(\n                song"), "the panels register before the sheets")
        assertTrue(src.contains("PlayerBackHandler(enabled = panels.lyricsOpen && !wide)"), "in a wide window the tabs are not layers back peels off")
    }

    @Test fun everySheetIsDrawnOverThePlayersOwnHazeStateAndTheLyricsStaysAwake() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        for (sheet in listOf("AudioOutputSheet(", "LyricsProviderSheet(", "ListenTogetherMembersSheet(", "LyricsOffsetSheet(", "AudioPipelineDialog(", "CastDialog(")) {
            val call = src.substring(src.indexOf(sheet)).take(200)
            assertTrue(call.contains("hazeState = hazeState"), "$sheet takes the player's haze state")
        }
        assertTrue(src.contains("currentSource = lyricsSource") && src.contains("states = lyricsProviderStates") && src.contains("onSelect = onSelectLyricsProvider"))
        assertTrue(src.contains("KeepScreenOn(enabled = (if (wide) panels.widePanel == NowPlayingPanel.LYRICS else panels.lyricsOpen) || cinemaShown)"))
        assertTrue(src.contains("flags.providers = true") || src.contains("sheets.providers = true"), "the lyrics status opens the provider sheet")
        for (page in listOf("NowPlayingPhone.kt", "NowPlayingWide.kt")) {
            assertTrue(code(nowPlaying(page)).contains("Box(modifier.fillMaxSize().hazeSource(hazeState))"), "$page: the page is what the sheets blur")
        }
    }

    // ---- the phone ------------------------------------------------------------------------------------------------------

    @Test fun onThePhoneAChipOpensItsPanelInTheMediasPlaceAndTheCardStays() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(src.contains("media = { m -> if (panelOpen) phonePanel(m) else pageMedia(m, 26.dp, 300.dp, true) }"))
        assertTrue(src.contains("panelOpen -> PhoneMedia.ROOM"), "a panel takes all the room above the card")
        assertTrue(src.contains("LyricsStyle.Portrait"))
        assertTrue(src.contains("showLine = wantVideo || panelOpen"), "the seek line whenever the record's ring is not there")
        val phone = code(nowPlaying("NowPlayingPhone.kt"))
        assertTrue(phone.contains("closable = true"), "a tap on the open chip closes it")
        assertTrue(phone.contains("val cardPlaced = measurables[1].measure("), "the card is laid out first; the media takes the rest")
    }

    @Test fun thePhonesVideoIsWholeAcrossTheTopFillTakesTheRoomAndAPinchSwitches() {
        val src = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(src.contains("keptView == VideoView.FILL -> PhoneMedia.ROOM"))
        assertTrue(src.contains("else -> PhoneMedia.WHOLE"))
        assertTrue(src.contains("onPinch = if (phone) setView else null"), "a pinch on the phone's video switches Whole and Fill")
        val phone = code(nowPlaying("NowPlayingPhone.kt"))
        assertTrue(phone.contains("PhoneMedia.WHOLE -> (width / (if (aspect > 0f) aspect else com.cosmotune.design.VideoView.DEFAULT_ASPECT)).toInt()"), "whole: as tall as the width gives its shape")
    }
}
