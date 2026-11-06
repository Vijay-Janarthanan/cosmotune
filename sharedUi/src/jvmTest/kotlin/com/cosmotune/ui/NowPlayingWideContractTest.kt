package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to
 * be added): the wide Now Playing of the Orbit board (sections 1 and 3).
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
        val at = listOf("CosmoIcons.ChevronDown", "\"FROM · \${source.uppercase()}\"", "PanelChips(", "CosmoIcons.More", "OrbitMenu(")
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
        assertTrue(src.indexOf("cosmoAmbientGround(artwork = true)") < src.indexOf("media(Modifier.weight(1f)"), "the ground wears the cover, under everything")
        assertTrue(src.indexOf("media(Modifier.weight(1f)") < src.indexOf("card(Modifier.fillMaxWidth()"), "the card is under the media")
        assertTrue(src.indexOf("card(Modifier.fillMaxWidth()") < src.indexOf("panel(Modifier.weight(1f - split)"), "the panel is the right column")
        assertTrue(src.contains("WindowCaptionArea("), "the top row is the window's caption: an empty stretch of it still moves the window")
        assertFalse(src.contains("position.positionMs"), "the page reads no playhead")
    }

    @Test fun theVideoIsRounded34AndWithoutOneTheRecordIsTheProgress() {
        val src = screen
        assertTrue(src.contains("media = { m -> pageMedia(m, 34.dp, 440.dp, false) }"), "the video is rounded 34")
        val media = src.substring(src.indexOf("val pageMedia"), src.indexOf("val likeButton"))
        assertTrue(media.contains("onDoubleTap = { cinema = true }"), "a double click enters Cinema")
        assertTrue(media.contains("RecordBox("), "without a video, the record")
        val record = SourceContract.functionBody(code(nowPlaying("NowPlayingVideo.kt")), "NowPlayingRecord")
        assertTrue(record.contains("OrbitRing("), "the ring round the disc is the progress")
        assertTrue(record.contains("detectDragGestures("), "drag round the ring to seek")
        assertTrue(src.contains("progress = transport::progress, playing = isPlaying, onSeek = transport::seek"))
    }

    @Test fun theSongCardIsTheBiteCardWithPlayInTheBiteAndEveryControl() {
        val body = SourceContract.functionBody(card, "NowPlayingSongCard")
        assertTrue(body.contains("coverFill(t.coverUrl, t.song.title)"), "the cover's colour")
        assertTrue(body.contains("OrbitBiteCard(fill, modifier, bite = { OrbitPlayButton(t.isPlaying, t.onPlayPause, look) })"), "play / pause in the bite")
        assertTrue(body.contains("OrbitProgressLine(t::progress, ink"), "the seek line")
        assertTrue(card.contains("\"NOW ORBITING\""), "the mono label")
        val call = screen.substring(screen.indexOf("card = { m, wideCard ->"), screen.indexOf("panel = { m ->"))
        for (b in listOf("CosmoIcons.Shuffle", "CosmoIcons.Repeat", "previousButton", "nextButton", "likeButton", "screenButton")) {
            assertTrue(call.contains(b), "$b is on the card")
        }
        assertTrue(call.contains("volume = true"), "the desktop's volume is on the card: there is no bar inside the player")
    }

    @Test fun thePanelIsUpNextAsSongRowsTheLyricsInTheWideFaceOrRelated() {
        val src = screen
        assertTrue(src.contains("WIDE_LYRICS_STYLE.copy(fontFamily = orbitType.wide)"), "the lyrics in the wide heading face")
        assertTrue(src.contains("NowPlayingUpNext(") && src.contains("NowPlayingRelated("))
        val upNext = code(nowPlaying("NowPlayingUpNext.kt"))
        assertTrue(upNext.contains("OrbitSongRow("), "Home's song rows")
        for (keep in listOf("onJumpTo(track.timelineIndex)", "onRemove(track.timelineIndex)", "drag.start(track.key, section)", "onClear", "onToggleAutoplay")) {
            assertTrue(upNext.contains(keep), "the queue still does $keep")
        }
        assertTrue(src.contains("onPanel = { panels.panel = it }"), "the chips are the panel")
    }

    @Test fun theMoreMenuKeepsEveryActionTheOldTopBarHad() {
        val src = screen
        val more = src.substring(src.indexOf("val moreItems"), src.indexOf("val sourceLabel"))
        for (a in listOf("onOpenMenu", "openAudioOutput", "together", "watchMusicVideo", "visualsOpen = true", "sheets.details = true", "sheets.providers = true")) {
            assertTrue(more.contains(a), "$a is in more")
        }
    }

    @Test fun fEntersCinemaOnTheWidePage() {
        val src = screen
        assertTrue(src.contains("event.key == Key.F"))
        assertTrue(src.contains("modifier = Modifier.cinemaKey(enabled = musicVideo && !videoHidden) { cinema = true }"))
    }
}
