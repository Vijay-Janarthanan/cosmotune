package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hooks into upstream files that no other contract test pinned (found by the verification pass, P4-17): the theme call on
 * both roots, the palette that follows the playing song, the phone's ground, the way to the "New look" switch in Settings on both
 * platforms, and the desktop's credits for the fonts and the colour library. Each is one line in an upstream file; an upstream
 * merge that drops one of them would compile and silently switch part of the new look off (or the way to turn it on), so the
 * literal text is pinned here, with the look-off half where there is one.
 */
class UpstreamHooksContractTest {
    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val settingsSheet = "app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val desktopLicenses = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLicenses.kt"

    private fun flat(path: String): String = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    /** As [flat] with the comments kept: `code` cuts a line at its first `//`, which would cut an address inside a string. */
    private fun raw(path: String): String = SourceContract.read(path).replace(Regex("\\s+"), " ")

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    // ---- the theme (hook 4) -------------------------------------------------------------------------------------------------

    @Test fun thePhoneThemeIsTheLookThemeAroundTheOldOne() {
        val src = flat(mainActivity)
        assertEquals(
            1,
            count(src, "com.cosmotune.ui.theme.CosmoLookTheme(dark = darkTheme, legacy = { CosmotuneTheme(darkTheme = darkTheme, content = it) }) {"),
            "the phone's theme call is the look theme with the old CosmotuneTheme as its legacy theme",
        )
    }

    @Test fun theDesktopThemeIsTheLookThemeAroundTheOldOne() {
        val src = flat(desktopApp)
        assertEquals(
            1,
            count(src, "com.cosmotune.ui.theme.CosmoLookTheme( dark = true, legacy = { MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography(), content = it) }, ) {"),
            "the desktop's theme call is the look theme with the old MaterialTheme as its legacy theme (the desktop is dark only)",
        )
    }

    // ---- the palette follows the song ---------------------------------------------------------------------------------------

    @Test fun thePhoneRootReadsTheShellFirstAndKeepsThePaletteOnThePlayingSong() {
        val src = flat(mainActivity)
        val shell = "val cosmoShell = com.cosmotune.ui.theme.LocalCosmoShell.current"
        val follow = "cosmoShell?.FollowCover(player.song, player.queue.getOrNull(player.queueIndex + 1))"
        assertEquals(1, count(src, shell), "CosmotuneApp reads the shell once")
        assertEquals(1, count(src, follow), "the cover colours follow the playing song and preload the next one's")
        assertTrue(src.indexOf(shell) < src.indexOf(follow))
        assertTrue("import com.cosmotune.ui.theme.FollowCover" in src)
    }

    @Test fun theDesktopRootReadsTheShellFirstAndKeepsThePaletteOnThePlayingSong() {
        val src = flat(desktopApp)
        val theme = src.indexOf("com.cosmotune.ui.theme.CosmoLookTheme(")
        val first = "val cosmoShell = com.cosmotune.ui.theme.LocalCosmoShell.current cosmoShell?.FollowCover(selectedSong, liveQueue.songs.getOrNull(liveQueue.index + 1))"
        assertEquals(1, count(src, first), "the first two lines inside the theme: the shell, then the palette follows the song")
        assertTrue(theme in 0 until src.indexOf(first), "inside the look theme")
        assertTrue("import com.cosmotune.ui.theme.FollowCover" in src)
    }

    // ---- the phone's ground -------------------------------------------------------------------------------------------------

    @Test fun thePhoneRootLiesOnTheAmbientGroundOnlyWithTheLookOn() {
        val src = flat(mainActivity)
        assertEquals(
            1,
            count(src, ".then(if (cosmoShell != null) Modifier.cosmoAmbientGround() else Modifier.background(MaterialTheme.colorScheme.background)),"),
            "the new look's ground under every page; with the look off the old background, as it was",
        )
    }

    // ---- the way to the switch ----------------------------------------------------------------------------------------------

    @Test fun thePhoneSettingsShowTheLookRowsInBothLooksAndFindThemBySearch() {
        val src = flat(settingsSheet)
        val call = "row(\"new look\", \"colours follow the cover\", \"preview the look\", \"cosmotune\", \"colour\", \"color\", \"intensity\") { com.cosmotune.ui.CosmoLookSettingsRows() }"
        assertEquals(1, count(src, call), "the look's rows sit in Settings, found by the settings search under these words")
        val before = src.substring(0, src.indexOf(call)).takeLast(200)
        assertTrue("cosmoShell" !in before && "LocalCosmoShell" !in before, "not behind the look: the switch must be reachable with the look off")
        val rows = flat("app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt")
        val body = rows.substringAfter("internal fun CosmoLookSettingsRows() {")
        assertTrue(
            body.indexOf("onClick = { settings.setNewLook(!newLook) },") in 0 until body.indexOf("if (newLook)"),
            "the New look row comes first and is drawn whatever the look",
        )
    }

    @Test fun theDesktopSettingsShowTheLookTogglesInBothLooks() {
        val src = flat(desktopApp)
        val call = "com.cosmotune.desktop.CosmoLookDesktopToggles { title, subtitle, checked, onChange -> SettingsToggle(title, subtitle, checked, onChange) }"
        assertEquals(1, count(src, call), "the look's toggles in the desktop Settings, drawn by its own SettingsToggle")
        val settingsScreen = src.indexOf("fun DesktopSettingsScreen(")
        assertTrue(settingsScreen in 0 until src.indexOf(call), "in the Settings screen")
        val toggles = flat("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt")
        val body = toggles.substringAfter("internal fun CosmoLookDesktopToggles(")
        assertTrue(
            body.indexOf("row(stringResource(Res.string.cosmo_new_look), stringResource(Res.string.cosmo_new_look_summary), newLook, settings::setNewLook)") in 0 until body.indexOf("if (newLook)"),
            "the New look toggle comes first and is drawn whatever the look",
        )
    }

    // ---- credits ------------------------------------------------------------------------------------------------------------

    @Test fun theDesktopCreditsTheTwoFontsAndTheColourLibraryAndTheTextsShip() {
        val src = raw(desktopLicenses)
        for (entry in listOf(
            "DesktopLicense( \"Archivo\", \"OFL-1.1\", \"https://github.com/Omnibus-Type/Archivo\",",
            "DesktopLicense( \"Hanken Grotesk\", \"OFL-1.1\", \"https://github.com/marcologous/hanken-grotesk\",",
            "DesktopLicense( \"Material Color Utilities (MaterialKolor)\", \"Apache-2.0 AND MIT\", \"https://github.com/jordond/materialkolor\",",
        )) assertEquals(1, count(src, entry), "the licence list names $entry")
        for (text in listOf("Archivo-OFL.txt", "HankenGrotesk-OFL.txt", "MaterialKolor-LICENSE.txt", "MaterialColorUtilities-LICENSE.txt")) {
            assertTrue(File(SourceContract.root, "licenses/$text").isFile, "licenses/$text is in the repository")
            assertTrue(text in SourceContract.read("NOTICE.md"), "NOTICE.md points to $text")
        }
    }
}
