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

    private val oldScreen get() = parameters(read("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt"), "NowPlayingScreen")

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
        for (path in listOf("app/src/main/java/com/music/bitchord/MainActivity.kt", "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt")) {
            val src = code(read(path))
            assertTrue(src.contains("import com.cosmotune.ui.nowplaying.NowPlayingSwitch"), "$path imports the switch")
            assertEquals(1, Regex("""\bNowPlayingSwitch\(""").findAll(src).count(), "$path calls it once")
            assertFalse(Regex("""\bNowPlayingScreen\(""").containsMatchIn(src), "$path no longer calls the old screen directly")
        }
    }

    // ---- the rules ------------------------------------------------------------------------------------------------------

    @Test fun livePaletteColoursAreReadOnlyWhileDrawing() {
        for (name in listOf("CosmoNowPlaying.kt", "NowPlayingControls.kt", "NowPlayingColors.kt", "NowPlayingSwitch.kt")) {
            assertFalse(code(nowPlaying(name)).contains(".live("), "$name reads no live colour in composition")
        }
        val media = code(nowPlaying("NowPlayingMedia.kt"))
        val cache = media.indexOf("drawWithCache {")
        assertTrue(cache > 0)
        for (hit in Regex("""\.live\(""").findAll(media)) assertTrue(hit.range.first > cache, "a live colour at ${hit.range.first} is inside the draw cache")
    }

    @Test fun theRevealIsAStateReadOnlyInLambdasAndTheClipsAlphaGoesThroughPresentationAlpha() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("val reveal = animateFloatAsState("), "reveal is the State itself, not a delegated value")
        for (line in screen.lines().filter { it.contains("reveal.value") }) {
            assertTrue(line.contains("derivedStateOf"), "the screen reads reveal.value only inside a derived state: $line")
        }
        val media = code(nowPlaying("NowPlayingMedia.kt"))
        assertTrue(media.contains("presentationAlpha = { reveal.value"), "the clip's alpha is its own parameter (a Compose layer over a TextureView is not reliable)")
        assertFalse(media.substringAfter("CanvasArtworkPlayer(").substringBefore("if (scrimsShown)").contains("graphicsLayer"), "no Compose layer around the clip")
    }

    @Test fun theVideoIsWantedOnlyOnceTheMorphHasSettledAndStartsHiddenUnderReducedMotion() {
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertTrue(screen.contains("derivedStateOf { shell?.playerSettled != false }"), "the settled flag is read in a derived state")
        assertTrue(screen.contains("val wantVideo = canvas != null && !videoHidden && settled"))
        assertTrue(screen.contains("rememberSaveable { mutableStateOf(reduced) }"), "reduced motion: no autoplay")
    }

    @Test fun theTitleStepsAndTheRowMeasuresInTheLayoutPhase() {
        val controls = code(nowPlaying("NowPlayingControls.kt"))
        assertTrue(controls.contains("NowPlayingMetrics.titleStep(arrival.value)"), "the title steps through its instances")
        assertTrue(controls.contains("NowPlayingMetrics.chipSlotDp(reveal.value)"), "the chip slot opens in the layout phase")
        assertTrue(controls.contains("fraction = { scrub.shown(position.positionMs, durationMs) }"), "the scrubber reads the playhead only while drawing")
    }

    @Test fun onlyTheTimesReadThePlayhead() {
        val controls = code(nowPlaying("NowPlayingControls.kt"))
        val reads = Regex("""position\.positionMs""").findAll(controls).map { it.range.first }.toList()
        // the scrubber's lambda, the restart-point derived state and the times
        assertEquals(3, reads.size, "three reads of the playhead")
        val screen = code(nowPlaying("CosmoNowPlaying.kt"))
        assertFalse(screen.contains("position.positionMs"), "the screen itself never reads the playhead")
    }

    @Test fun theCoverTellsTheShellWhereItRests() {
        val media = code(nowPlaying("NowPlayingMedia.kt"))
        assertTrue(media.contains("shell?.playerCoverBounds = bounds"))
        // the box that reports it is outside the layer that travels, so the rectangle is the resting one
        assertTrue(media.indexOf("onGloballyPositioned") < media.indexOf("graphicsLayer {\n                        val progress"))
    }

    @Test fun landscapeIsTheTwoPaneLayoutAndTheWideLayoutIsStillTaskElevens() {
        val screen = nowPlaying("CosmoNowPlaying.kt")
        assertTrue(screen.contains("Task 11"), "the wide stub says whose it is")
        assertTrue(screen.contains("NowPlayingLayout.LANDSCAPE -> landscapePage()"))
        assertTrue(screen.contains("NowPlayingLayout.WIDE -> page()"))
    }
}
