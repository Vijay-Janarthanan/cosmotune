package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BC-17: the player can take the status bar off the window, and nothing it lays out may move when it does. Android's
 * `WindowInsets.statusBars` (and `systemBars`, which contains it) drops to zero the moment the bar is hidden, so every player
 * surface reads the stable insets (`statusBarsIgnoringVisibility` on Android) for its top edge instead. This cannot be seen
 * without an Android window, so it is pinned by reading the sources (`SourceContract`); the Android hide/cutout behaviour itself
 * needs a phone with a camera cutout (see FEATURE_TRACKER.md BC-17).
 */
class StatusBarInsetsContractTest {
    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune"
    private val playerFolders = listOf("$ui/ui/nowplaying", "$ui/music/ui/player")

    private fun code(path: String) = SourceContract.code(SourceContract.read(path))

    private fun playerFiles(): List<String> = playerFolders.flatMap { folder ->
        File(SourceContract.root, folder).listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty().map { "$folder/${it.name}" }
    }.sorted()

    @Test fun bothPlayersHideTheBarFromTheSettingAndOnlyWhileTheyAreComposed() {
        for (file in listOf("$ui/ui/nowplaying/CosmoNowPlaying.kt", "$ui/music/ui/player/NowPlayingScreen.kt")) {
            val src = code(file).replace(Regex("\\s+"), " ")
            assertTrue("PlayerSettings.hideStatusBar.collectAsStateWithLifecycle()" in src, "$file reads the player setting")
            assertTrue(Regex("""HideStatusBar\(hideStatusBar\)""").containsMatchIn(src), "$file hands it to the platform hook")
        }
    }

    @Test fun noPlayerSurfaceReadsAVisibilityDependentStatusBarInsetForItsTop() {
        val files = playerFiles()
        assertTrue(files.size > 20, "both player folders are read (found ${files.size})")
        val offenders = mutableListOf<String>()
        for (file in files) {
            val lines = code(file).lines()
            lines.forEachIndexed { i, line ->
                if ("import " in line) return@forEachIndexed
                // A bare statusBars inset, or the system bars without the stable status bar folded in or the top cut off.
                if (Regex("""WindowInsets\.statusBars\b""").containsMatchIn(line)) offenders += "$file:${i + 1}: $line"
                if (Regex("""WindowInsets\.systemBars\b""").containsMatchIn(line) &&
                    "stableStatusBarInsets()" !in line && "WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom" !in line
                ) offenders += "$file:${i + 1}: $line"
                if (Regex("""\.(statusBarsPadding|systemBarsPadding)\(\)""").containsMatchIn(line)) offenders += "$file:${i + 1}: $line"
            }
        }
        assertEquals(emptyList(), offenders, "these player lines would jump when the status bar is hidden")
    }

    @Test fun theStableInsetsIgnoreVisibilityOnAndroidAndTheHookDoesNothingOnDesktop() {
        val android = code("sharedUi/src/androidMain/kotlin/com/cosmotune/music/ui/theme/StatusBarIcons.android.kt").replace(Regex("\\s+"), " ")
        assertTrue("actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBarsIgnoringVisibility" in android)
        assertTrue("controller.hide(WindowInsetsCompat.Type.statusBars())" in android, "the hook hides only the status bar")
        assertFalse("Type.navigationBars()" in android || "Type.systemBars()" in android, "the navigation bar is left alone")
        assertTrue("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE" in android, "a swipe still shows it for a moment")
        assertTrue("onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) controller.systemBarsBehavior = behavior" in android,
            "leaving the player shows it again and restores the previous behaviour")
        // Before Android 15 a hidden bar would push the window below the cutout; short edges keeps it in, and is undone.
        assertTrue("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES" in android && "Build.VERSION_CODES.VANILLA_ICE_CREAM" in android)
        assertEquals(2, Regex("LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT").findAll(android).count(), "the default mode is checked and restored")

        val desktop = code("sharedUi/src/jvmMain/kotlin/com/cosmotune/music/ui/theme/StatusBarIcons.jvm.kt").replace(Regex("\\s+"), " ")
        assertTrue("actual fun HideStatusBar(hidden: Boolean) = Unit" in desktop, "a desktop window has no status bar to hide")
        val desktopHost = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopPlayerHost.kt")
        assertTrue("override val hideStatusBar = MutableStateFlow(false)" in desktopHost)
    }

    @Test fun theAndroidSettingIsPersistedAndShownWithItsTranslations() {
        val settings = code("app/src/main/java/com/cosmotune/music/data/settings/AppSettings.kt")
        assertTrue("prefs.getBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, false)" in settings, "off unless chosen")
        assertTrue("prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, value).apply()" in settings)
        assertTrue("override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer" in
            code("app/src/main/java/com/cosmotune/music/ui/player/AndroidPlayerHost.kt"))
        val sheet = code("app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt")
        assertTrue("Switch(checked = hideStatusBar, onCheckedChange = AppSettings::setHideStatusBarInPlayer)" in sheet)
        val res = File(SourceContract.root, "app/src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").isFile }.orEmpty()
            .filter { "hide_status_bar" in File(it, "strings.xml").readText() }
        assertTrue(folders.size >= 10, "the row is translated (found ${folders.size} string files)")
        for (folder in folders) {
            val text = File(folder, "strings.xml").readText()
            assertTrue("name=\"hide_status_bar_in_player\"" in text && "name=\"hide_status_bar_in_player_subtitle\"" in text, "${folder.name} has both strings")
        }
    }
}
