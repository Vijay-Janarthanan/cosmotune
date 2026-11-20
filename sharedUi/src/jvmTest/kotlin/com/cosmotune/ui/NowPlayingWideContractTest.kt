package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to
 * be added): the wide Now Playing of the Now Playing board (section D).
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
    private val top get() = code(nowPlaying("NowPlayingTopRow.kt"))
    private val card get() = code(nowPlaying("NowPlayingCard.kt"))

    @Test fun theTopRowIsCloseTheSourceTheChipsAndMoreInThatOrder() {
        val body = SourceContract.functionBody(top, "NowPlayingTopRow")
        val at = listOf("CosmoIcons.ChevronDown", "\"FROM · \${source.uppercase()}\"", "PanelChips(", "MoreButton(more,")
            .map { body.indexOf(it).also { i -> assertTrue(i >= 0, "$it is in the top row") } }
        assertEquals(at.sorted(), at, "in the order of the board")
        assertTrue(body.contains("LocalCosmoPlayerDismiss.current"), "the chevron closes the player")
        val chips = SourceContract.functionBody(top, "PanelChips")
        for (name in listOf("\"Lyrics\"", "\"Up next\"", "\"Related\"")) assertTrue(chips.contains(name), "$name is a chip")
        assertTrue(chips.contains("selected = on"), "the chip showing is the solid one")
    }

    @Test fun theMediaIsOverTheCardOnTheLeftAndThePanelOnTheRightClearOfTheBars() {
        val src = wide
        assertTrue(src.contains("WindowInsets.systemBars.union(WindowInsets.displayCutout)"), "clear of the cutout and the bars on all four sides")
        assertTrue(src.contains("Box(modifier.fillMaxSize().hazeSource(hazeState))"), "the page is what the sheets blur")
        assertTrue(src.indexOf("cosmoAmbientGround(artwork = true)") < src.indexOf("media(Modifier.fillMaxSize())"), "the ground wears the cover, under everything")
        assertTrue(src.indexOf("media(Modifier.fillMaxSize())") < src.indexOf("card(Modifier.fillMaxWidth()"), "the card is under the media")
        assertTrue(src.indexOf("card(Modifier.fillMaxWidth()") < src.indexOf("tabs(Modifier.fillMaxWidth())"), "the tabs head the right column")
        assertTrue(src.indexOf("tabs(Modifier.fillMaxWidth())") < src.indexOf("panel(Modifier.weight(1f).fillMaxWidth())"), "the panel the tabs chose under them")
        assertTrue(src.contains("WindowCaptionArea("), "the top row is the window's caption: an empty stretch of it still moves the window")
        assertFalse(src.contains("position.positionMs"), "the page reads no playhead")
    }

    @Test fun theVideoRunsToTheWindowsEdgeFadingIntoItsColoursAndWithoutOneTheCoverSitsOverItsOwnBlur() {
        val src = screen
        assertTrue(src.contains("media = { m -> pageMedia(m, 0.dp, false) }"), "edge to edge, no rounding")
        val media = src.substring(src.indexOf("val pageMedia"), src.indexOf("val cardQuality"))
        assertTrue(media.contains("onDoubleTap = { cinema = true }"), "a double click enters Cinema")
        assertTrue(media.contains("NowPlayingCover(") && media.contains("centred = !phone"), "without a video, the cover, centred in a wide window")
        assertTrue(wide.contains(".bleedStart(left + SIDE_START)"), "the picture runs past the page's margin to the window's edge")
        assertTrue(wide.contains("cosmoPlayerGroundEdges(top = SOFT_TOP, bottom = SOFT_BOTTOM)"), "and fades into the page at its top and foot")
        val cover = SourceContract.functionBody(code(nowPlaying("NowPlayingVideo.kt")), "NowPlayingCover")
        assertTrue(cover.contains("blur(44.dp"), "the blurred copy of the cover fills the box")
        assertTrue(cover.contains("Modifier.size(side)"), "the cover at its own size in the middle")
    }

    @Test fun theRightColumnsTabsAreTheChipsWithUpNextsCount() {
        assertTrue(screen.contains("tabs = { m -> PanelChips(panels.widePanel, { panels.panel = it }, orbitType, orbitInk, closable = false, modifier = m, upNextCount ="))
        assertTrue(screen.contains("chips = false,"), "the top row no longer carries them")
        assertTrue(top.contains("CountBadge(upNextCount, fonts, look)"), "the count is a small peach badge")
    }

    @Test fun theSongCardIsTheBiteCardWithPlayInTheBiteAndEveryControl() {
        val body = SourceContract.functionBody(card, "NowPlayingSongCard")
        assertTrue(body.contains("coverFill(t.coverUrl, t.song.title)"), "the cover's colour")
        assertTrue(body.contains("OrbitBiteCard(fill, modifier, bite = { NowPlayingPlayButton(t, look, 66.dp, HERO_BITE_RING) })"), "play / pause in the bite")
        val button = SourceContract.functionBody(card, "NowPlayingPlayButton")
        assertTrue(button.contains("OrbitPlayButton(t.isPlaying, t.onPlayPause, look, size = size)"), "the bite's button is play / pause")
        assertTrue(button.contains("if (t.isLoading)") && button.contains("OrbitRing("), "the moon circles it while the song loads")
        assertTrue(body.contains("OrbitProgressLine(t::progress, ink"), "the seek line")
        assertTrue(body.contains("CardTimes(t, fonts, ink, quality,"), "with the times and the measured quality under it")
        assertTrue(card.contains("\"NOW ORBITING\""), "the mono label")
        val call = screen.substring(screen.indexOf("card = { m, wideCard ->"), screen.indexOf("panel = { m ->"))
        for (b in listOf("CosmoIcons.Shuffle", "CosmoIcons.Repeat", "previousButton", "nextButton", "likeButton", "screenButton")) {
            assertTrue(call.contains(b), "$b is on the card")
        }
        assertTrue(call.contains("volume = true"), "the desktop's volume is on the card: there is no bar inside the player")
    }

    @Test fun thePanelIsUpNextAsSongRowsTheLyricsInTheWideFaceOrRelated() {
        val src = screen
        // The lyrics board's Orbit Read: the line being sung in wide Archivo (the view's read face), the others in the text face.
        assertTrue(src.contains("lyricsPanel(lm, WIDE_LYRICS_STYLE, WIDE_LYRICS_TOP_PADDING)"), "the lyrics in the text face, the sung line in the read face")
        assertTrue(src.contains("NowPlayingUpNext(") && src.contains("NowPlayingRelated("))
        val upNext = code(nowPlaying("NowPlayingUpNext.kt"))
        assertTrue(upNext.contains("OrbitQueueList("), "the shared queue's flat rows")
        val queue = code(File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitQueue.kt").readText())
        for (keep in listOf("onJumpTo(track.timelineIndex)", "onRemove(track.timelineIndex)", "drag.start(track.key, section)", "onClear", "onToggleAutoplay")) {
            assertTrue(queue.contains(keep), "the queue still does $keep")
        }
        assertTrue(src.contains("onPanel = { panels.panel = it }"), "the chips are the panel")
    }

    @Test fun theMoreMenuKeepsEveryActionTheOldTopBarHad() {
        val src = screen
        val more = src.substring(src.indexOf("val moreItems"), src.indexOf("val sourceLabel"))
        for (a in listOf("chooseVideoOpen = true", "onOpenMenu", "openAudioOutput", "together", "watchMusicVideo", "visualsOpen = true", "sheets.details = true", "sheets.providers = true")) {
            assertTrue(more.contains(a), "$a is in more")
        }
    }

    @Test fun fEntersCinemaOnTheWidePage() {
        val src = screen
        assertTrue(src.contains("event.key == Key.F"))
        assertTrue(src.contains("modifier = Modifier.cinemaKey(enabled = musicVideo && !videoHidden) { cinema = true }"))
    }
}
