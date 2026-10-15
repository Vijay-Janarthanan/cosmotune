package com.cosmotune.design

import java.io.File
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review Focus 3: the old pages pad their lists for the old bars, and the new floating chrome must fit inside that
 * padding, or the last rows could never be scrolled into view. The phone half reads `MainActivity`'s padding; the desktop
 * half (task P3-11) reads `DesktopFrame`'s, which has a line of its own for the new shell. The padding is read from the
 * source file, so a change to it fails this test.
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

    // ---- The desktop half ----

    private val desktopApp = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"

    /** The page padding `DesktopFrame` hands its content while the new shell is on: `LocalCosmoShell.current != null -> N.dp`. */
    private val desktopShellPadding = Regex("""LocalCosmoShell\.current != null -> (\d+)\.dp""")

    private fun desktopPaddingWithShell(): Int {
        val file = File(root, desktopApp)
        if (!file.isFile) fail("$desktopApp is not where this test expects it; update ShellClearanceTest")
        val match = desktopShellPadding.find(file.readText())
            ?: fail(
                "Could not find `LocalCosmoShell.current != null -> N.dp` in $desktopApp (the bottom padding of DesktopFrame's content " +
                    "while the new shell is on). The line moved, changed or is missing: the last row could not be scrolled clear of the " +
                    "floating chrome. Re-check the clearance, then update this test.",
            )
        return match.groupValues[1].toInt()
    }

    /** The most the desktop's floating chrome takes from the bottom of the window: the wide window's player bar, or the compact one's phone chrome. */
    private fun desktopChromeNeedsDp() =
        max(ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true), ShellMetrics.desktopBarFootprintDp())

    @Test
    fun theDesktopPageLeavesRoomForTheFloatingChromeWhileTheNewShellIsOn() {
        val padding = desktopPaddingWithShell()
        val needed = desktopChromeNeedsDp()
        assertTrue(needed >= 154f, "the compact window's capsules (16 + 64 + 10 + 64 dp) are the tallest chrome: $needed dp")
        assertTrue(padding >= needed, "the new chrome needs $needed dp under the page, DesktopFrame leaves $padding dp")
    }

    @Test
    fun theDesktopPlayerBarIsInsideThePaddingAndNeverBiggerThanTheCompactChrome() {
        assertTrue(ShellMetrics.desktopBarFootprintDp() <= desktopPaddingWithShell(), "the 80 dp bar and its two 14 dp margins fit the page padding")
        assertTrue(ShellMetrics.desktopBarFootprintDp() <= ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true), "so the one padding serves both modes")
    }

    @Test
    fun theOldDesktopPaddingIsStillThereForTheSwitchOffPath() {
        val text = File(root, desktopApp).readText()
        assertTrue("compact -> 128.dp" in text && "else -> 16.dp" in text, "with the new look off the desktop pads 128 dp (compact) or 16 dp, as before")
    }
}
