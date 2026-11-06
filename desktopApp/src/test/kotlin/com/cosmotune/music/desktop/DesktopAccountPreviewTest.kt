package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the account switcher and the sign-in dialog drawn offscreen at a desktop window's size in the dark
 * and the light palettes, written under build/account-preview for a person to look at.
 */
class DesktopAccountPreviewTest {
    private val palettes = listOf(
        "dark" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true),
        "light" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false),
    )

    private val accounts = listOf(
        DesktopGoogleAccount(
            "a1", "Vijay", "vijay@example.com",
            profiles = listOf(
                DesktopYouTubeProfile("p1", "Vijay", "@vijay"),
                DesktopYouTubeProfile("p2", "Night Shift Radio", "@nightshift", isBrandAccount = true),
            ),
        ),
        DesktopGoogleAccount("a2", "Studio", "studio@example.com", profiles = listOf(DesktopYouTubeProfile("p3", "Studio", ""))),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, palette: CosmoPaletteState, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(1100, 1100, Density(1.5f)) {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        var bytes: ByteArray? = null
        repeat(8) { bytes = scene.render(it * 100_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes }
        scene.close()
        File("build/account-preview").mkdirs()
        File("build/account-preview/$name.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 3_000)
    }

    @Test fun theSwitcherAndTheSignIn() {
        for ((name, palette) in palettes) {
            render("switcher-$name", palette) {
                DesktopAccountSelector(accounts, "a1", "p1", busy = false, onSelect = { _, _ -> }, onAddAccount = {}, onRemoveAccount = {}, onOpenSettings = {}, onDismiss = {})
            }
            render("switcher-empty-$name", palette) {
                DesktopAccountSelector(emptyList(), null, null, busy = false, onSelect = { _, _ -> }, onAddAccount = {}, onRemoveAccount = {}, onOpenSettings = {}, onDismiss = {})
            }
            render("sign-in-$name", palette) {
                DesktopSignInDialog(webSignIn = true, onWebSignIn = {}, onImport = {}, onPaste = {}, busy = null, error = null, onDismiss = {})
            }
        }
    }
}
