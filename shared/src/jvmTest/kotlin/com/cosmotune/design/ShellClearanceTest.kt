package com.cosmotune.design

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review Focus 3: the old pages pad their lists for the old bars, and the new floating chrome must fit inside that
 * padding, or the last rows could never be scrolled into view. The phone half lives here (the desktop half is part of
 * task P3-11). The padding is read from the source file, so a change to it fails this test.
 */
class ShellClearanceTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val mainActivity = "app/src/main/java/com/music/bitchord/MainActivity.kt"

    private val paddings = Regex("""bottom = if \(player\.song != null\) (\d+)\.dp else (\d+)\.dp""")

    private fun oldPaddings(): Pair<Int, Int> {
        val file = File(root, mainActivity)
        if (!file.isFile) fail("$mainActivity is not where this test expects it; update ShellClearanceTest")
        val match = paddings.find(file.readText())
            ?: fail(
                "Could not find `bottom = if (player.song != null) N.dp else M.dp` in $mainActivity (the old pages' bottom " +
                    "padding for the bars). The line moved or changed: re-check the new chrome's clearance, then update this test.",
            )
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    @Test
    fun theOldPagePaddingIsStillWhatWeMeasured() {
        val (withPlayer, withoutPlayer) = oldPaddings()
        assertTrue(withPlayer >= withoutPlayer, "with a player the padding is at least the one without: $withPlayer, $withoutPlayer")
    }

    @Test
    fun theNewChromeFitsInsideTheOldPaddingAtEveryNavigationInset() {
        val (withPlayer, withoutPlayer) = oldPaddings()
        for (inset in floatArrayOf(0f, 24f, 48f)) {
            val tall = ShellMetrics.phoneChromeHeightDp(inset, withMiniPlayer = true)
            val short = ShellMetrics.phoneChromeHeightDp(inset, withMiniPlayer = false)
            assertTrue(tall <= withPlayer, "with the mini player at inset $inset: chrome $tall dp, padding $withPlayer dp")
            assertTrue(short <= withoutPlayer, "without the mini player at inset $inset: chrome $short dp, padding $withoutPlayer dp")
        }
    }
}
