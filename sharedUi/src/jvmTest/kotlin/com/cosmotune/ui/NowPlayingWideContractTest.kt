package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to
 * be added): the rules the wide Now Playing (the desktop, `V2.deskNow`) was written under. The numbers themselves are
 * `WideNowPlayingMetricsTest`'s.
 */
class NowPlayingWideContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun nowPlaying(name: String): String =
        File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/$name").readText().replace("\r\n", "\n")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    private fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private val wide get() = code(nowPlaying("NowPlayingWide.kt"))
    private val screen get() = code(nowPlaying("CosmoNowPlaying.kt"))

    // ---- the page --------------------------------------------------------------------------------------------------------

    @Test fun thePiecesAreWhereThePlanSaysAndKeepClearOfTheSystemBars() {
        val src = wide
        assertTrue(src.contains("WideNowPlayingMetrics.plan(windowWidth.value - (left + right).value, windowHeight.value - (top + bottom).value, fontScale)"), "the plan accounts for insets and the user's text size")
        assertFalse(src.contains("BoxWithConstraints"), "nothing is composed during layout: the lyrics' follow effect scrolls a list, which must not happen in a measure pass")
        assertTrue(src.contains("WindowInsets.systemBars.union(WindowInsets.displayCutout)"), "clear of the cutout and the bars on all four sides")
        for (piece in listOf("plan.cover", "plan.video", "plan.header", "plan.lyrics")) assertTrue(src.contains("placed($piece, left, top)"), "$piece is placed")
        assertTrue(src.contains("Modifier.offset(left + plan.bar.x.dp, top + plan.bar.y.dp).width(plan.bar.width.dp)"), "the bar gets its place and width; it is 80 dp tall by itself")
        // the ambient ground, with the media over it, is what the sheets blur
        assertTrue(src.contains("Box(Modifier.fillMaxSize().hazeSource(hazeState))"))
        assertTrue(src.indexOf("cosmoAmbientGround()") < src.indexOf("media(Modifier.placed(plan.cover"), "the ground is under the media")
        assertTrue(src.indexOf("media(Modifier.placed(plan.cover") < src.indexOf("header(Modifier.placed"), "and the header, the lyrics and the bar are over it")
    }

    @Test fun theWidePageIsOnlyMadeInAWideWindowAndDoesNotReadThePlayhead() {
        assertFalse(wide.contains("position.positionMs"), "the page reads no playhead: the bar and the lyrics read it, in their own draw phases")
        assertFalse(wide.contains("LocalReducedMotion"), "reduced motion arrives as a parameter, from the screen")
        // wide is a layout of the one chooser; nothing else in the screen decides it
        assertEquals(1, Regex("""NowPlayingLayout\.choose\(""").findAll(screen).count())
        assertTrue(screen.contains("val wide = layout == NowPlayingLayout.WIDE"))
    }

    // ---- the header ------------------------------------------------------------------------------------------------------

    @Test fun theHeaderIsTheCoverTheTitleTheTabsAndTheButtonsInThatOrder() {
        val src = wide
        val header = src.substring(src.indexOf("internal fun NowPlayingWideHeader("), src.indexOf("internal fun NowPlayingWidePanels("))
        val at = listOf(
            "CoverImage(coverUrl, WideNowPlayingMetrics.HEADER_COVER_DP.dp, CosmoRadii.coverDesktopHead)",
            "Text(song.title, style = title",
            "CosmoSegmented(listOf(stringResource(Res.string.open_lyrics), stringResource(Res.string.queue)), if (queueSelected) 1 else 0, onSelectTab)",
            "CosmoIcons.More",
            "CosmoIcons.ChevronDown",
        ).map { header.indexOf(it).also { i -> assertTrue(i >= 0, "$it is in the header") } }
        assertEquals(at.sorted(), at, "in the order of the board")
        assertTrue(header.contains("spacedBy(WideNowPlayingMetrics.HEADER_GAP_DP.dp)"), "14 dp apart")
        assertTrue(header.contains("fontSize = 17.sp") && header.contains("FontWeight.Bold") && header.contains("fontSize = 13.5.sp"), "17 sp over 13.5 sp")
        assertTrue(header.contains("LocalCosmoPlayerDismiss.current") && header.contains("Res.string.cosmo_collapse_player"), "the last button closes the player")
        assertTrue(header.contains("if (hasVideo) {"), "the video button is there when the song has a video: otherwise nothing could turn it on")
        assertTrue(header.contains("""joinToString(" · ")"""), "artist, then where it is playing from")
    }

    // ---- the media -------------------------------------------------------------------------------------------------------

    @Test fun theCardIsASuperellipseOf32WithAGlowShadowAndTheCoverTellsTheShellWhereItRests() {
        val src = wide
        assertTrue(src.contains("SuperellipseShape(WideNowPlayingMetrics.VIDEO_RADIUS_DP.dp)"))
        assertTrue(src.contains("CosmoRadii.coverNowPlaying"), "the cover has the player cover's radius, which the grow lands on")
        assertTrue(src.contains("shell?.playerCoverBounds = it.boundsInRoot()"), "the resting rectangle goes to the shell")
        assertTrue(src.contains("colors.live(CoverField.GLOW)") && src.contains("GLOW_SHADOW_ALPHA = 0.55f"), "the key shadow is the glow at 55 %")
        // the shadow's colour is the lambda that reads the live palette: a draw-phase lambda of the layer
        val spot = src.indexOf("spot = {")
        assertEquals(src.lastIndexOf(".live("), src.indexOf(".live(", spot), "the only live colour is inside the shadow's lambda")
        assertTrue(src.contains("presentationAlpha = { reveal.value.coerceIn(0f, 1f) }"), "the clip's own alpha, not a layer: on the phone it is a TextureView")
        assertTrue(src.contains("contentMode = CanvasContentMode.FIT_PORTRAIT"))
        assertTrue(src.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"), "the cover's shadow is not cut at its bounds while it fades")
    }

    // ---- what the screen gives it ----------------------------------------------------------------------------------------

    @Test fun theBarInsideThePlayerIsTheCapsuleAsAnEmbeddedBarWithEveryControlOfItsOwn() {
        val src = screen
        val call = src.substring(src.indexOf("CosmoPlayerBar("), src.indexOf("embedded = true") + "embedded = true".length)
        for (arg in listOf(
            "liked = likeStatus == LikeStatus.LIKE", "shuffle = shuffleEnabled", "repeatMode = repeatMode", "controlsLocked = controlsLocked",
            "lyricsOn = !panels.queueOpen", "queueOn = panels.queueOpen", "onLyrics = panels::openLyrics", "onQueue = panels::openQueue",
            "onOutput = openAudioOutput", "onBlockedControl = onBlockedControl", "hazeState = playerHaze", "onSeekFraction = onSeekFraction",
            "onPlayPause = onPlayPause", "onPrevious = onPrevious", "onNext = onNext", "onToggleLike = onToggleLike",
        )) assertTrue(call.contains(arg), arg)
        assertTrue(call.contains("embedded = true"), "inside the player the bar is not the capsule the player grows out of")
    }

    @Test fun theWideLyricsAreTitleLWithTheBoardsScalesAndTheQueueIsTheSamePanelAsEverywhere() {
        val src = screen
        assertTrue(src.contains("LyricsStyle(WideNowPlayingMetrics.LYRICS_BASE_STEP, WideNowPlayingMetrics.LYRICS_CURRENT_SCALE, WideNowPlayingMetrics.LYRICS_PAST_SCALE)"))
        val panel = src.substring(src.indexOf("panel = { m ->"), src.indexOf("media = { cover, video, side ->"))
        assertTrue(panel.contains("lyricsPanel(Modifier.fillMaxSize(), WIDE_LYRICS_STYLE, WIDE_LYRICS_TOP_PADDING)") && panel.contains("queuePanel(Modifier.fillMaxSize())"), "the same panels as portrait and landscape")
        assertTrue(src.contains("onSelectTab = { if (it == 0) panels.openLyrics() else panels.openQueue() }"), "the tabs are the panels' flags")
    }

    @Test fun inAWideWindowThePageStaysInTheThemeAndTheTabsAreNotLayersBackPeelsOff() {
        val src = screen
        assertTrue(src.contains("hasVideo = canvas != null && !wide, videoShown = videoShown && !wide"), "a card of video on the ground does not turn the page dark")
        assertTrue(src.contains("PlayerStatusBarIcons(reveal = if (wide) noReveal else reveal, lightTheme = lightTheme)"))
        assertTrue(src.contains("PlayerBackHandler(enabled = panels.lyricsOpen && !wide)") && src.contains("PlayerBackHandler(enabled = panels.queueOpen && !wide)"), "Escape closes the player, not a tab")
    }
}
