package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The theme choice on every surface: the desktop no longer hard-codes its dark theme, every window it opens reads the one setting,
 * Settings and the profile menus' quick switch write the same setting on both platforms, and Pure black stands under Theme on the
 * desktop. Read from the source, as the other contract tests do.
 */
class ThemeChoiceContractTest {
    private val desktopDir = "desktopApp/src/main/kotlin"
    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val desktopTheme = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopTheme.kt"
    private val appearance = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopAppearanceSettings.kt"
    private val accountUi = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopAccountUi.kt"
    private val lookToggles = "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt"
    private val drawer = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome/CosmoProfileDrawer.kt"
    private val lookTheme = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoLookTheme.kt"
    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val settingsSheet = "app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt"
    private val phoneChoice = "app/src/main/java/com/cosmotune/ui/PhoneThemeChoice.kt"

    private fun code(path: String) = SourceContract.code(SourceContract.read(path))
    private fun flat(path: String) = code(path).replace(Regex("\\s+"), " ")

    @Test fun theDesktopNoLongerHardCodesItsDarkTheme() {
        val src = code(desktopApp)
        assertFalse(Regex("""dark\s*=\s*true""").containsMatchIn(src), "DesktopApp.kt passes no fixed dark = true")
        val flat = flat(desktopApp)
        assertEquals(1, Regex(Regex.escape("val desktopDark = rememberDesktopDark()")).findAll(flat).count())
        assertTrue("CosmoLookTheme( dark = desktopDark," in flat, "the theme call takes the resolved choice")
        assertTrue("themeChoice = DesktopThemeChoice" in flat, "and the choice itself, for the profile menus")
        assertTrue("LocalContentColor provides (if (desktopDark) Color.White else LocalContentColor.current)" in flat, "no white ink forced on the light theme")
    }

    /** Every file of the desktop app that opens a window of its own, and how it gets the theme. */
    @Test fun everyDesktopWindowReadsTheThemeChoice() {
        val root = File(SourceContract.root, desktopDir)
        val opening = root.walk().filter { it.isFile && it.extension == "kt" }.filter { file ->
            Regex("""(?<![A-Za-z])(Window|DialogWindow)\(""").containsMatchIn(SourceContract.code(file.readText()))
        }.map { it.relativeTo(root).invariantSeparatorsPath.substringAfterLast('/') }.toSortedSet()
        // The visuals' full-screen window is opened from inside the main window's composition and draws the content it is given there,
        // so it stands inside the app's theme already.
        val insideTheApp = setOf("DesktopVisuals.kt")
        assertEquals(setOf("DesktopMiniPlayer.kt", "DesktopTrayFlyout.kt", "DesktopVisuals.kt", "Main.kt"), opening, "the desktop's windows; a new one must read the theme too")
        for (name in opening - insideTheApp) {
            val file = root.walk().first { it.name == name }
            assertTrue("rememberDesktopDark()" in SourceContract.code(file.readText()), "$name reads the theme choice")
        }
        assertTrue("dark = rememberDesktopDark()" in code("$desktopDir/com/cosmotune/music/desktop/DesktopMiniPlayer.kt"), "the mini player follows the choice")
        assertTrue("dark = rememberDesktopDark()" in code("$desktopDir/com/cosmotune/music/desktop/DesktopTrayFlyout.kt"), "the tray flyout follows the choice")
        assertTrue("val windowDark = rememberDesktopDark()" in code("$desktopDir/com/cosmotune/music/desktop/Main.kt"), "the main window's floor follows the choice")
    }

    @Test fun theDesktopSystemThemeIsAskedAgainWhileTheChoiceIsSystem() {
        val src = flat(desktopTheme)
        assertTrue("org.jetbrains.skiko.currentSystemTheme" in src, "Skiko's reading, which asks the system each time")
        assertFalse("isSystemInDarkTheme()" in src, "not Compose's, which is read once per process")
        assertTrue("if (choice == ThemeChoice.SYSTEM) { LaunchedEffect(Unit) { while (true) { systemDark = DesktopSystemTheme.dark()" in src, "polled only while following the system")
        assertTrue("return !newLook || choice.isDark(systemDark, videoFirst)" in src, "the classic look stays dark; Video is dark first")
    }

    @Test fun theDesktopKeepsTheChoiceWithItsOtherAppearanceFlags() {
        val src = flat(appearance)
        assertTrue("read = { DesktopPersistence().string(KEY_THEME_MODE, \"\").ifBlank { null } }" in src)
        assertTrue("write = { DesktopPersistence().saveString(KEY_THEME_MODE, it) }" in src)
        assertTrue("internal const val KEY_THEME_MODE = \"theme_mode\"" in src)
        assertTrue("MutableStateFlow(ThemeChoice.fromStored(read()))" in src, "nothing stored reads as System")
    }

    @Test fun theDesktopAppearancePageOpensWithThemeAndPureBlackUnderIt() {
        val app = flat(desktopApp)
        val group = app.indexOf("SettingsGroup(DesktopStrings[\"appearance\", \"Appearance\"]) {")
        assertTrue(group >= 0)
        assertTrue(app.indexOf("DesktopThemeRows { title, subtitle -> settingsRowVisible(title, subtitle) }", group) in group until app.indexOf("val reduceDynamicBlur", group), "Theme is the group's first row")
        val rows = flat(desktopTheme).substringAfter("internal fun DesktopThemeRows(")
        assertTrue(rows.indexOf("CosmoSettingsSegmented(") in 0 until rows.indexOf("Res.string.cosmo_amoled)"), "Pure black stands under the choice")
        assertTrue("enabled = dark," in rows && "CosmoSwitch(checked = amoled, onCheckedChange = look::setAmoled, enabled = dark)" in rows, "Pure black only for the dark theme")
        assertTrue("host?.choose(it) ?: DesktopAppearanceSettings.setThemeMode(it)" in rows, "the row goes through the theme choice, as the quick switch does")
        assertFalse("cosmo_amoled" in code(lookToggles), "Pure black is not offered twice")
    }

    @Test fun bothProfileMenusCarryTheQuickSwitchUnderTheirHead() {
        val drawer = flat(drawer)
        val head = drawer.indexOf("DrawerHead(name, subtitle, avatarUrl, onAccount)")
        val row = drawer.indexOf("com.cosmotune.ui.orbit.OrbitThemeRow(Modifier.padding(top = 12.dp), labelStart = 24.dp, end = 12.dp)")
        assertTrue(row > head && head >= 0, "the drawer's switch right under the head")
        assertTrue(row < drawer.indexOf("ModeSwitch(onChosen = onDismiss"), "and above the modes")
        assertTrue(".then(fadeSurface)" in drawer, "the drawer's own window cross-fades too")
        val popover = flat(accountUi)
        val card = popover.indexOf("OrbitAccountCard(")
        val switch = popover.indexOf("com.cosmotune.ui.orbit.OrbitThemeRow(Modifier.padding(top = 12.dp), labelStart = 14.dp)")
        assertTrue(switch > card && card >= 0, "the popover's switch under its account card")
        assertTrue(switch < popover.indexOf("\"Switch account\""), "and above the accounts")
    }

    @Test fun thePhoneDrawerAndSettingsWriteTheSameSetting() {
        assertTrue("themeChoice = com.cosmotune.ui.PhoneThemeChoice" in flat(mainActivity))
        val host = flat(phoneChoice)
        assertTrue("AppSettings.themeMode.collectAsStateWithLifecycle()" in host && "AppSettings.setThemeMode(choice.toMode())" in host)
        assertTrue("themeChoice?.choose(com.cosmotune.ui.theme.ThemeChoice.valueOf(mode.name)) ?: AppSettings.setThemeMode(mode)" in flat(settingsSheet))
    }

    @Test fun theLookThemeInstallsTheChoiceAroundTheLegacyThemeAndATimeFlipSnapsThePalette() {
        val theme = flat(lookTheme)
        assertTrue("ThemeChoiceRoot(themeChoice, reduced) { legacy {" in theme, "the choice and its fade around the whole app")
        assertTrue("val animate = showedCover && shell.palette.targetDark == dark" in theme, "a theme flip snaps the palette under the cross-fade")
    }
}
