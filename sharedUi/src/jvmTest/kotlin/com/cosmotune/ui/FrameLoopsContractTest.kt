package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A loop that waits for a frame for as long as something plays (a ring's playhead, the lyric clock, Canvas's sync, a video's
 * ticker) asks for a frame on every frame, so a Compose UI test that waits for idle never sees it idle and hangs: it did, in
 * PodioInteractionTest, once the Podio ring ran the live playhead. Such loops wait with `withInfiniteAnimationFrameNanos`, which
 * behaves like `withFrameNanos` in the apps and which the test harness's idling leaves out, as it does `rememberInfiniteTransition`.
 * A plain `withFrameNanos` stays right for a wait of a frame or two, or a loop that ends by itself (a spring settling, a drag's
 * edge scroll that lets go when the list stops).
 */
class FrameLoopsContractTest {
    private val root = SourceContract.root

    /** Every loop that runs on the frame clock for as long as the song, the video or the Short plays. */
    private val playingLoops = listOf(
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/playback/LivePlayhead.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/player/CanvasSync.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/player/PlayerLyrics.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/player/ThinSlider.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/CosmoLyricClock.kt",
        "video/src/jvmMain/kotlin/com/cosmotune/video/player/smooth/SmoothingClock.kt",
        "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/player/VideoPlayerBox.kt",
        "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shorts/ShortsProgressLine.kt",
        "wearos/src/main/java/com/cosmotune/music/wear/ui/NowPlayingScreen.kt",
        "tv/src/main/java/com/cosmotune/music/ui/tv/player/TvNowPlayingParts.kt",
        "tv/src/main/java/com/cosmotune/music/ui/tv/player/TvWatchPlayer.kt",
    )

    private val plainFrame = Regex("""(?<![A-Za-z])withFrame(Nanos|Millis)\b""")

    @Test fun thePlayingLoopsWaitOnInfiniteAnimationFrames() {
        for (path in playingLoops) {
            val code = SourceContract.code(SourceContract.read(path))
            assertTrue("withInfiniteAnimationFrameNanos" in code, "$path waits on the infinite-animation frame clock")
            val plain = code.lines().filter { plainFrame.containsMatchIn(it) }
            assertTrue(plain.isEmpty(), "$path waits on a plain frame, which keeps a UI test from going idle: $plain")
        }
    }

    /**
     * Across the sources, a `while` loop that waits on a plain frame must have a way out of its own (a `break` or a `return`);
     * an endless one belongs on the infinite-animation clock. A held row's edge scroll is the one loop that ends only when the
     * finger lets go, which a test always does.
     */
    @Test fun noOtherEndlessLoopWaitsOnAPlainFrame() {
        val allowed = setOf("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/playlist/PlaylistReorder.kt")
        val dirs = listOf("sharedUi/src", "shared/src", "video/src", "watch/src", "manga/src", "providers/src", "app/src/main",
            "tv/src/main", "wearos/src/main", "desktopApp/src/main")
        val found = mutableListOf<String>()
        for (dir in dirs) {
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path in allowed || Regex("""/src/[A-Za-z]*[Tt]est/""").containsMatchIn(path) || "/build/" in path) return@forEach
                val code = SourceContract.code(file.readText().replace("\r\n", "\n"))
                for (body in whileBodies(code)) {
                    if (plainFrame.containsMatchIn(body) && !Regex("""\b(break|return)\b""").containsMatchIn(body)) {
                        found += "$path: ${body.lineSequence().first().take(90)}"
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "endless loops on a plain frame (use withInfiniteAnimationFrameNanos): $found")
    }

    /** The source of every `while (...)` loop: its condition and its braced body, or its single statement up to the line's end. */
    private fun whileBodies(code: String): List<String> {
        val out = mutableListOf<String>()
        for (m in Regex("""\bwhile\s*\(""").findAll(code)) {
            var i = m.range.last + 1
            var depth = 1
            while (i < code.length && depth > 0) { if (code[i] == '(') depth++ else if (code[i] == ')') depth--; i++ }
            var j = i
            while (j < code.length && code[j] == ' ') j++
            if (j < code.length && code[j] == '{') {
                var d = 1
                var k = j + 1
                while (k < code.length && d > 0) { if (code[k] == '{') d++ else if (code[k] == '}') d--; k++ }
                out += code.substring(m.range.first, k)
            } else {
                val end = code.indexOf('\n', j).let { if (it < 0) code.length else it }
                out += code.substring(m.range.first, end)
            }
        }
        return out
    }
}
