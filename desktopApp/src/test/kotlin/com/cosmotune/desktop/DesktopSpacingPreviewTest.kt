package com.cosmotune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the desktop places the spacing pass fixed, as they draw now, in the dark and the light palettes at
 * 1.5x, written under the worktree's build/spacing/current for a person to look at. Nothing here reads or writes stored settings.
 */
class DesktopSpacingPreviewTest {
    private val out = File("../build/spacing/current").apply { mkdirs() }

    private fun render(name: String, dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) = runBlocking(Dispatchers.Swing) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        val scene = ImageComposeScene((width * 1.5f).toInt(), (height * 1.5f).toInt(), Density(1.5f), coroutineContext = Dispatchers.Swing) {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val shell = remember {
                val store = object : KeyValueStore {
                    override fun getBoolean(key: String, default: Boolean) = default
                    override fun putBoolean(key: String, value: Boolean) = Unit
                }
                CosmoShellState::class.java.getConstructor(CosmoLookSettings::class.java, Boolean::class.javaPrimitiveType)
                    .newInstance(CosmoLookSettings(store), dark)
            }
            val type = rememberCosmoType()
            MaterialTheme {
                val lifecycleOwner = remember {
                    object : androidx.lifecycle.LifecycleOwner {
                        override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                    }
                }
                CompositionLocalProvider(
                    LocalCosmoColors provides colors, LocalCosmoType provides type, LocalCosmoShell provides shell, LocalReducedMotion provides true,
                    androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
                ) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        var bytes: ByteArray? = null
        try {
            repeat(20) {
                bytes = scene.render(it * 100_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
                delay(40)
            }
        } finally {
            scene.close()
        }
        File(out, "$name.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 3_000)
    }

    /** The sidebar with no playlists yet: the line and its Open Library button. */
    @Test fun theSidebarsEmptyPlaylistsLineAndItsButton() {
        for (dark in listOf(true, false)) {
            render("desktop-sidebar-empty-${if (dark) "dark" else "light"}", dark, 240, 520) {
                CosmoDesktopSidebar(
                    nav = listOf(
                        CosmoDesktopNavItem(CosmoIcons.Home, CosmoIcons.HomeFill, "Home", selected = true, onClick = {}),
                        CosmoDesktopNavItem(CosmoIcons.Search, CosmoIcons.SearchFill, "Explore", selected = false, onClick = {}),
                        CosmoDesktopNavItem(CosmoIcons.Library, CosmoIcons.LibraryFill, "Library", selected = false, onClick = {}),
                    ),
                    playlists = emptyList(),
                    settingsSelected = false,
                    onOpenSettings = {},
                    query = "",
                    onQueryChange = {},
                    onSearch = {},
                    focusSearch = false,
                    onSearchFocused = {},
                )
            }
        }
    }

    /** Add to playlist (with no playlists yet), its New playlist form, and a one-field dialog: the content on the heading's start edge. */
    @Test fun theDesktopDialogsKeepTheHeadingsStartEdge() {
        val song = com.cosmotune.music.data.model.Song(videoId = "abc", title = "Golden Hour", artist = "Mira and the Coast", thumbnailUrl = null, durationText = "3:00")
        for (dark in listOf(true, false)) {
            val tag = if (dark) "dark" else "light"
            render("desktop-add-to-playlist-$tag", dark, 560, 420) {
                com.cosmotune.music.desktop.DesktopPlaylistDialog(
                    song = song, accountPlaylists = emptyList(), localPlaylists = emptyList(), signedIn = true, canUseAccount = true,
                    busy = false, error = null, onPickAccount = {}, onPickLocal = {}, onCreate = { _, _ -> }, onDismiss = {},
                )
            }
            render("desktop-new-playlist-$tag", dark, 560, 420) {
                com.cosmotune.music.desktop.DesktopPlaylistDialog(
                    song = null, accountPlaylists = emptyList(), localPlaylists = emptyList(), signedIn = true, canUseAccount = true,
                    busy = false, error = null, onPickAccount = {}, onPickLocal = {}, onCreate = { _, _ -> }, onDismiss = {},
                )
            }
            render("desktop-text-value-$tag", dark, 560, 360) {
                com.cosmotune.music.desktop.DesktopTextValueDialog(
                    title = "Activity name", message = "Shown on your profile while you listen. {title} and {artist} are filled in.",
                    initial = "", placeholder = "Listening to {title}", onSave = {}, onDismiss = {},
                )
            }
        }
    }
}
