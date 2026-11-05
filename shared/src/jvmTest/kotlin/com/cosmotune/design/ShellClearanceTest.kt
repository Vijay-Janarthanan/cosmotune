package com.cosmotune.design

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review Focus 3: the old pages pad their lists for the old bars, and the new floating chrome must fit inside that
 * padding, or the last rows could never be scrolled into view. The phone half reads `MainActivity`'s padding; the desktop
 * half (task P3-11, then P4-20) reads `DesktopFrame`'s, which has a line of its own for the new shell: exactly the floating
 * chrome's footprint, no slack. The padding is read from the source file, so a change to it fails this test.
 */
class ShellClearanceTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"

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

    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"

    /**
     * What `DesktopFrame` pads the page's bottom with while the new shell is on: the line `LocalCosmoShell.current != null -> X`,
     * where X is the player bar's footprint in a wide window and the phone chrome's height (the capsules, the compact window's bar)
     * in a compact one. The page pads for exactly what floats over it, no more: the bar is 80 dp and two 14 dp margins (108), not
     * the 168 dp that the phone's chrome plus slack had been (the last row scrolled 60 dp further than the bar needed).
     */
    private val desktopShellPadding =
        Regex("""LocalCosmoShell\.current != null ->\s*com\.cosmotune\.design\.ShellMetrics\.desktopPageBottomDp\(compact, songShown = LocalNowPlaying\.current != null\)\.dp""")

    /** The padding under the page, in dp, while the new shell is on, worked out from the source line the way the line is written. */
    private fun desktopPaddingWithShell(compact: Boolean, songShown: Boolean = true): Float {
        val file = File(root, desktopApp)
        if (!file.isFile) fail("$desktopApp is not where this test expects it; update ShellClearanceTest")
        if (!desktopShellPadding.containsMatchIn(file.readText()))
            fail(
                "Could not find `LocalCosmoShell.current != null -> ShellMetrics.desktopPageBottomDp(compact, songShown = LocalNowPlaying.current != null).dp` " +
                    "in $desktopApp (the bottom padding of DesktopFrame's content while the new " +
                    "shell is on). The line moved, changed or is missing: re-check that the last row can be scrolled clear of the floating " +
                    "chrome and is not padded for more than it, then update this test.",
            )
        return ShellMetrics.desktopPageBottomDp(compact, songShown)
    }

    @Test
    fun theWideDesktopPagePadsForExactlyThePlayerBarAndTheCompactOneForThePhoneChrome() {
        assertTrue(desktopPaddingWithShell(compact = false) == 108f, "80 dp for the bar and 14 dp above and below it: ${desktopPaddingWithShell(compact = false)}")
        assertTrue(desktopPaddingWithShell(compact = true) == 154f, "the compact window's capsules (16 + 64 + 10 + 64 dp): ${desktopPaddingWithShell(compact = true)}")
    }

    @Test
    fun theDesktopPageLeavesRoomForTheFloatingChromeWhileTheNewShellIsOn() {
        // The wide window floats the bar, the compact one the phone's capsules: each has its own padding, so each is what it needs.
        assertTrue(desktopPaddingWithShell(compact = false) >= ShellMetrics.desktopBarFootprintDp(), "the bar's footprint fits under the wide page")
        assertTrue(
            desktopPaddingWithShell(compact = true) >= ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true),
            "the capsules fit under the compact page",
        )
    }

    @Test
    fun theDesktopPageReservesRoomOnlyWhileTheBarOrTheCapsulesAreThere() {
        // no song, no bar in a wide window: the page's content scrolls to the window's own edge; the compact window keeps its capsule
        assertTrue(desktopPaddingWithShell(compact = false, songShown = false) == 0f, "nothing floats over a wide page without a song")
        assertTrue(desktopPaddingWithShell(compact = true, songShown = false) == 80f, "the tab capsule alone floats over a compact one (16 + 64 dp)")
    }

    @Test
    fun theDesktopPlayerBarNeverNeedsMoreThanTheCompactChromeDoes() {
        assertTrue(ShellMetrics.desktopBarFootprintDp() <= ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true), "the wide bar is the smaller of the two")
    }

    @Test
    fun theOldDesktopPaddingIsStillThereForTheSwitchOffPath() {
        val text = File(root, desktopApp).readText()
        assertTrue("compact -> 128.dp" in text && "else -> 16.dp" in text, "with the new look off the desktop pads 128 dp (compact) or 16 dp, as before")
    }
}
