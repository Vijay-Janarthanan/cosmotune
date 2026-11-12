package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hooks into upstream files that no other contract test pinned (found by the verification pass, P4-17): the theme call on
 * both roots, the palette that follows the playing song, the phone's ground, the look's rows in Settings on both platforms, and
 * the desktop's credits for the fonts and the colour library. Each is one line in an upstream file; an upstream merge that drops
 * one of them would compile and silently switch part of the look off, so the literal text is pinned here.
 */
class UpstreamHooksContractTest {
    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val settingsSheet = "app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val desktopLicenses = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLicenses.kt"

    private fun flat(path: String): String = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    /** As [flat] with the comments kept, for text that is matched exactly as it is written in the file. */
    private fun raw(path: String): String = SourceContract.read(path).replace(Regex("\\s+"), " ")

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    // ---- the theme (hook 4) -------------------------------------------------------------------------------------------------

    @Test fun thePhoneThemeIsTheLookThemeAroundTheOldOne() {
        val src = flat(mainActivity)
        assertEquals(
            1,
            count(src, "com.cosmotune.ui.theme.CosmoLookTheme(dark = darkTheme, legacy = { CosmotuneTheme(darkTheme = darkTheme, content = it) }, themeChoice = com.cosmotune.ui.PhoneThemeChoice) {"),
            "the phone's theme call is the look theme with the old CosmotuneTheme as its legacy theme, and the theme choice for the drawer",
        )
    }

    @Test fun theDesktopThemeIsTheLookThemeAroundTheOldOne() {
        val src = flat(desktopApp)
        assertEquals(
            1,
            count(src, "val desktopDark = rememberDesktopDark() com.cosmotune.ui.theme.CosmoLookTheme( dark = desktopDark, legacy = { MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography(), content = it) }, themeChoice = DesktopThemeChoice, ) {"),
            "the desktop's theme call is the look theme with the old MaterialTheme as its legacy theme, dark or light as the theme choice says",
        )
    }

    // ---- the palette follows the song ---------------------------------------------------------------------------------------

    @Test fun thePhoneRootReadsTheShellFirstAndKeepsThePaletteOnThePlayingSong() {
        val src = flat(mainActivity)
        val shell = "val cosmoShell = checkNotNull(com.cosmotune.ui.theme.LocalCosmoShell.current) {"
        val follow = "cosmoShell.FollowCover(player.song, player.queue.getOrNull(player.queueIndex + 1))"
        assertEquals(1, count(src, shell), "CosmotuneApp reads the shell once, and it is always there")
        assertTrue("cosmoShell?." !in src, "the shell is never null on the phone root")
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

    @Test fun thePhoneRootLiesOnTheAmbientGround() {
        val src = flat(mainActivity)
        assertEquals(
            1,
            count(src, "Box( Modifier .fillMaxSize() .cosmoAmbientGround(), ) {"),
            "the look's ground under every page, unconditionally",
        )
        assertTrue("cosmoShell != null" !in src && "cosmoShell == null" !in src, "no branch on the shell is left")
    }

    // ---- the way to the switch ----------------------------------------------------------------------------------------------

    @Test fun thePhoneSettingsShowTheLookRowsAndFindThemBySearch() {
        val src = flat(settingsSheet)
        val call = "row(\"colours follow the cover\", \"preview the look\", \"cosmotune\", \"colour\", \"color\", \"intensity\") { com.cosmotune.ui.CosmoLookSettingsRows() }"
        assertEquals(1, count(src, call), "the look's rows sit in Settings, found by the settings search under these words")
        val rows = flat("app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt")
        val body = rows.substringAfter("internal fun CosmoLookSettingsRows() {")
        val order = listOf(
            "onClick = { settings.setCoverColours(!coverColours) },",
            "onClick = { settings.setAmoled(!amoled) },",
            "onSelect = settings::setIntensity,",
            "onClick = { previewOpen = true },",
        ).map { body.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "cover colours, AMOLED, intensity, then the preview: $order")
        assertTrue("newLook" !in body && "NewLook" !in body, "there is no New look row any more")
    }

    @Test fun theDesktopSettingsShowTheLookToggles() {
        val src = flat(desktopApp)
        val call = "com.cosmotune.desktop.CosmoLookDesktopToggles { title, subtitle, checked, onChange -> SettingsToggle(title, subtitle, checked, onChange) }"
        assertEquals(1, count(src, call), "the look's toggles in the desktop Settings, drawn by its own SettingsToggle")
        val settingsScreen = src.indexOf("fun DesktopSettingsScreen(")
        assertTrue(settingsScreen in 0 until src.indexOf(call), "in the Settings screen")
        val toggles = flat("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt")
        val body = toggles.substringAfter("internal fun CosmoLookDesktopToggles(")
        val first = "row(stringResource(Res.string.cosmo_cover_colours), stringResource(Res.string.cosmo_cover_colours_summary), coverColours, settings::setCoverColours)"
        assertTrue(
            body.substringAfter("{").substringAfter("collectAsState()").substringAfter("collectAsState()").trim().startsWith(first),
            "the toggles start with Colours follow the cover",
        )
        assertTrue("newLook" !in body && "cosmo_new_look" !in body, "there is no New look toggle any more")
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
