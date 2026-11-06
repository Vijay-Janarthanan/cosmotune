package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to
 * be added): the seam of the entry switch, the two call sites, and the rules the new Now Playing was written under.
 */
class NowPlayingContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")
    private fun nowPlaying(name: String): String = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/$name")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    private fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    /** The parameters of `fun [name](...)` in [src], each as `name: type = default` with its white space collapsed. */
    private fun parameters(src: String, name: String): List<String> {
        val text = code(src).replace("->", "→")
        val start = text.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name( is in the file")
        val open = text.indexOf('(', start)
        var depth = 0
        var close = -1
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { close = i; break }
            }
            if (close >= 0) break
        }
        assertTrue(close > open, "the parameter list of $name is closed")
        val inside = text.substring(open + 1, close)
        val parts = ArrayList<String>()
        var level = 0
        var from = 0
        for (i in inside.indices) {
            when (inside[i]) {
                '(', '<', '{' -> level++
                ')', '>', '}' -> level--
                ',' -> if (level == 0) { parts += inside.substring(from, i); from = i + 1 }
            }
        }
        parts += inside.substring(from)
        return parts.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    private val oldScreen get() = parameters(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/player/NowPlayingScreen.kt"), "NowPlayingScreen")

    // ---- the seam -------------------------------------------------------------------------------------------------------

    @Test fun theSwitchTakesNowPlayingScreensParametersNameForNameAndTypeForType() {
        val old = oldScreen
        assertTrue(old.size > 40, "the old screen's list was read (${old.size})")
        assertEquals(old, parameters(nowPlaying("NowPlayingSwitch.kt"), "NowPlayingSwitch"))
    }

    @Test fun theNewScreenTakesTheSameList() = assertEquals(oldScreen, parameters(nowPlaying("CosmoNowPlaying.kt"), "CosmoNowPlaying"))

    @Test fun bothBranchesOfTheSwitchPassEveryParameterByName() {
        val switch = code(nowPlaying("NowPlayingSwitch.kt"))
        val body = switch.substringAfter("if (LocalCosmoShell.current != null)")
        val branches = listOf(body.substringBefore("} else {"), body.substringAfter("} else {"))
        for (name in oldScreen.map { it.substringBefore(":").trim() }) {
            for ((i, branch) in branches.withIndex()) {
                assertTrue(Regex("""\b$name = $name\b""").containsMatchIn(branch), "branch $i passes $name = $name")
            }
        }
    }

    @Test fun theOldBranchIsTheOldScreenAndTheNewOneIsTheNewScreen() {
        val switch = code(nowPlaying("NowPlayingSwitch.kt"))
        assertTrue(switch.substringAfter("if (LocalCosmoShell.current != null)").substringBefore("} else {").contains("CosmoNowPlaying("))
        assertTrue(switch.substringAfter("} else {").contains("NowPlayingScreen("))
    }

    @Test fun bothRootsCallTheSwitchAndNotTheOldScreen() {
        for (path in listOf("app/src/main/java/com/cosmotune/music/MainActivity.kt", "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")) {
            val src = code(read(path))
            assertTrue(src.contains("import com.cosmotune.ui.nowplaying.NowPlayingSwitch"), "$path imports the switch")
            assertEquals(1, Regex("""\bNowPlayingSwitch\(""").findAll(src).count(), "$path calls it once")
            assertFalse(Regex("""\bNowPlayingScreen\(""").containsMatchIn(src), "$path no longer calls the old screen directly")
        }
    }

    // ---- the rules ------------------------------------------------------------------------------------------------------

    @Test fun livePaletteColoursAreReadOnlyWhileDrawing() {
        for (name in listOf("CosmoNowPlaying.kt", "NowPlayingSwitch.kt", "NowPlayingCard.kt", "NowPlayingPhone.kt", "NowPlayingWide.kt", "NowPlayingTopRow.kt")) {
            assertFalse(code(nowPlaying(name)).contains(".live("), "$name reads no live colour in composition")
        }
        val video = code(nowPlaying("NowPlayingVideo.kt"))
        for (hit in Regex("""\.live\(""").findAll(video)) {
            assertTrue(video.lastIndexOf("drawBehind {", hit.range.first) > 0, "a live colour at ${hit.range.first} is read while drawing")
        }
    }

    @Test fun theRevealIsAStateReadOnlyInLambdasAndTheClipsAlphaGoesThroughPresentationAlpha() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("val reveal = animateFloatAsState("), "reveal is the State itself, not a delegated value")
        assertFalse(screen.contains("reveal.value"), "the screen hands reveal on; it is read in layers and draws")
        assertTrue(screen.contains("Modifier.fadedBy(reveal)"), "the record fades in its layer as the video comes in")
        val video = code(nowPlaying("NowPlayingVideo.kt"))
        assertFalse(video.substringAfter("Modifier.offset(box[0].dp").substringBefore("CanvasArtworkPlayer(").contains("graphicsLayer"), "no Compose layer around the clip")
    }

    @Test fun theVideoIsWantedOnlyOnceTheMorphHasSettledAndStartsHiddenUnderReducedMotion() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("derivedStateOf { shell?.playerSettled != false }"), "the settled flag is read in a derived state")
        assertTrue(screen.contains("val wantVideo = musicVideo && !videoHidden && settled"))
        assertTrue(screen.contains("val wantLoop = coverLoop && !videoHidden && settled"), "the cover loop is gated the same way")
        assertTrue(screen.contains("rememberSaveable { mutableStateOf(reduced) }"), "reduced motion: no autoplay")
        assertTrue(screen.contains("if (wantVideo && !cinemaShown && canvas != null)"), "the page's clip is not mounted while Cinema shows its own")
    }

    @Test fun onlyTheLabelTheLineAndTheRingReadThePlayhead() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        val reads = Regex("""position\.positionMs""").findAll(screen).toList()
        assertEquals(2, reads.size, "the screen reads the playhead twice")
        assertTrue("musicVideoLink(links, canvas, song.title, song.artist) { position.positionMs }" in screen, "in the music video action")
        // And in a derived state that changes once, when the song passes the point where previous restarts it (as the old
        // transport had it): never per frame in composition.
        assertTrue("remember(position) { derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS } }" in screen)
        val card = code(nowPlaying("NowPlayingCard.kt"))
        assertTrue(card.contains("fun progress(): Float = scrub.shown(position.positionMs, durationMs)"), "the line and the ring read it while drawing")
        assertEquals(2, Regex("""position\.positionMs""").findAll(card).count(), "the progress and the NOW ORBITING label")
    }

    @Test fun theRecordOrTheVideoTellsTheShellWhereTheCoverRests() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("val coverPlaced: (Rect) -> Unit = { shell?.playerCoverBounds = it }"))
        assertTrue(screen.contains("onPlaced = coverPlaced") && screen.contains("onFramePlaced = coverPlaced"))
    }

    @Test fun aCoverLoopPlaysInsideTheRecordsDiscAndAMusicVideoIsShownAsItsViewSays() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("val musicVideo = canvas != null && canvas.syncMap != null"))
        assertTrue(screen.contains("val coverLoop = canvas != null && canvas.syncMap == null"))
        assertTrue(screen.contains("NowPlayingCoverLoop(canvas, isPlaying, reveal"), "the loop is handed to the record")
        val record = SourceContract.functionBody(code(nowPlaying("NowPlayingVideo.kt")), "NowPlayingRecord")
        assertTrue(record.contains(".clip(CircleShape)") && record.indexOf("loop?.invoke(") > record.indexOf("Disc(coverUrl, disc)"), "inside the disc, cut round, over the still cover")
        assertTrue(record.indexOf("OrbitRing(") > record.indexOf("loop?.invoke("), "the orbit still round it")
    }

    @Test fun eachLayoutHasItsOwnPage() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("NowPlayingLayout.PORTRAIT -> phonePage()"))
        assertTrue(screen.contains("NowPlayingLayout.LANDSCAPE -> phoneLandscapePage()"))
        assertTrue(screen.contains("NowPlayingLayout.WIDE -> widePage()"), "wide is its own page")
        assertTrue(screen.contains("NowPlayingLayout.choose(windowWidth.value, windowHeight.value)"), "one chooser decides, from the window")
        assertTrue(screen.contains("(cinema || (landscape && !leftTurnedCinema)) && wantVideo"), "a phone on its side shows its video in Cinema")
    }
}
