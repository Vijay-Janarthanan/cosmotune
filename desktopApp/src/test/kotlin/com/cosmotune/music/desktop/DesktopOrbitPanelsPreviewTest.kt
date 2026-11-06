package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.cosmotune.desktop.LocalCosmoPlaying
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.LyricsSidePanel
import com.cosmotune.music.ui.player.PartyUi
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerSettingsSource
import com.cosmotune.music.ui.player.QueueSidePanel
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the desktop screens converted to Orbit in the old-screens revamp (the Lyrics and Up next side panel,
 * the tray flyout's player and menu, the Listen together landing) drawn offscreen with the new look on, in the dark and the light
 * palettes, written under the worktree's build/old-screens/desktopui for a person to look at. Nothing here reads or writes the
 * desktop's stored settings.
 */
class DesktopOrbitPanelsPreviewTest {
    private val out = File("../build/old-screens/desktopui").apply { mkdirs() }

    private val palettes = listOf(true, false).map { dark ->
        (if (dark) "dark" else "light") to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
    }

    private fun isDark(name: String) = name == "dark"

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val file = File(out, "covers/$name.png").apply { parentFile.mkdirs() }
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = java.awt.Color(r, g, b); fillRect(0, 0, 300, 300)
            color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); fillOval(60, 60, 180, 180)
            dispose()
        }
        ImageIO.write(image, "png", file)
        return file.path
    }

    /** A player host that answers what the panels ask: the cover's address, the settings at their defaults, no party. */
    private fun hosts(block: () -> Unit) {
        val field = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = field.get(null)
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getLyricsOffsetMs" -> MutableStateFlow(0)
                "getTranslationLanguage", "getOpenCanvasResolution" -> MutableStateFlow("")
                "getLyricsSourceOrder" -> MutableStateFlow(emptyList<Any>())
                "getMeteredConnection", "getSmartMixBlend", "getSmartTransitionWindow" -> MutableStateFlow<Any?>(null)
                "getSyncedLyrics" -> MutableStateFlow(true)
                else -> if (method.returnType == Void.TYPE) Unit else MutableStateFlow(false)
            }
        } as PlayerSettingsSource
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> settings
                "getParty" -> MutableStateFlow(PartyUi())
                "getLyricsShareAvailable" -> false
                else -> if (method.returnType == Void.TYPE) Unit else error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { field.set(null, previous) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, palette: CosmoPaletteState, width: Int, height: Int, content: @Composable () -> Unit) {
        val dark = name.endsWith("-dark")
        val scene = ImageComposeScene((width * 1.5f).toInt(), (height * 1.5f).toInt(), Density(1.5f)) {
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
                CompositionLocalProvider(
                    LocalCosmoColors provides colors, LocalCosmoType provides type, LocalCosmoShell provides shell, LocalReducedMotion provides true,
                    LocalCosmoPlaying provides true,
                ) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        var bytes: ByteArray? = null
        repeat(30) {
            bytes = scene.render(it * 100_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
            Thread.sleep(40)
        }
        scene.close()
        File(out, "$name.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 3_000)
    }

    /** The page beside the panel: a heading, so the panel is seen against a page as it is in the window. */
    @Composable
    private fun Page(modifier: Modifier) {
        Column(modifier.padding(28.dp)) {
            Text("Library", style = orbitFonts().greeting, color = orbitLook().ink)
            Text("The page narrows to make room for the panel.", style = orbitFonts().body, color = orbitLook().dim)
        }
    }

    @Test fun theSidePanelShowsUpNextAndTheLyricsInOrbit() = hosts {
        val art = listOf(cover("q1", 168, 92, 40), cover("q2", 30, 120, 116), cover("q3", 120, 60, 140), cover("q4", 60, 110, 40), cover("q5", 150, 40, 70))
        val queue = listOf(
            Song("h1", "Morning Ferry", "Harbour Lights", art[4], "3:02"),
            Song("np", "Golden Hour", "Mira and the Coast", art[0], "4:05"),
            Song("u1", "Low Tide Letters", "Harbour Lights", art[1], "3:32", queueTier = QueueTier.USER_QUEUE),
            Song("c1", "Lantern Season", "Mira and the Coast", art[2], "3:18", albumName = "Lantern Season"),
            Song("c2", "Paper Boats", "Mira and the Coast", art[3], "2:57", albumName = "Lantern Season"),
            Song("a1", "Northern Lights", "Sora Vale", art[4], "4:20", queueTier = QueueTier.AUTOPLAY),
        )
        val lines = listOf(
            "The tide comes in to find us", "Golden hour on the water", "We said we'd stay till morning",
            "Every lantern on the harbour", "Burning slow and burning brighter", "Hold the light a little longer",
        ).mapIndexed { i, text -> LyricLine(timeMs = i * 4_000L, text = text) }
        val position = PlaybackPosition().apply { report(9_000L); advancing = false }
        for ((name, palette) in palettes) {
            render("side-panel-queue-$name", palette, 980, 720) {
                Row(Modifier.fillMaxSize()) {
                    Page(Modifier.weight(1f).fillMaxHeight())
                    DesktopSidePanelColumn(panel = DesktopSidePanel.QUEUE, onClose = {}) {
                        QueueSidePanel(
                            queue = queue, queueIndex = 1, autoplayEnabled = true, onJumpTo = {}, onRemove = {}, onMove = { _, _ -> },
                            onClear = {}, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            render("side-panel-lyrics-$name", palette, 980, 720) {
                Row(Modifier.fillMaxSize()) {
                    Page(Modifier.weight(1f).fillMaxHeight())
                    DesktopSidePanelColumn(panel = DesktopSidePanel.LYRICS, onClose = {}) {
                        LyricsSidePanel(
                            song = queue[1], isPlaying = false, position = position, lyrics = lines, lyricsSource = null,
                            lyricsProviderStates = emptyMap(), onSelectLyricsProvider = {}, lyricsUnavailable = false, onSeek = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    @Test fun theTrayFlyoutIsAFlatCoverCardAndAnOrbitMenu() {
        val art = cover("tray", 0xE5, 0x5B, 0x2D)
        DesktopTrayMenu.publish("Chinna Thamarai — Vijay Antony", playing = true)
        val entries = menuEntries(DesktopTrayMenu.entries())
        val song = DesktopMiniPlayer.NowPlaying("Chinna Thamarai", "Vijay Antony, Krish, Suchitra & Viveka", art, true, 0.42f)
        val player = DpSize(344.dp, 172.dp)
        val rows = entries.count { !it.separator }
        val separators = entries.count { it.separator }
        val menu = DpSize(252.dp, (16 + rows * 40 + separators * 9 + 58 + 9).dp)
        for ((name, palette) in palettes) {
            val dark = isDark(name)
            tray("tray-player-$name", player, dark) { DesktopTrayFlyoutContent(DesktopTrayFlyout.Mode.PLAYER, song, actions, entries, player, dark) {} }
            tray("tray-player-empty-$name", player, dark) { DesktopTrayFlyoutContent(DesktopTrayFlyout.Mode.PLAYER, DesktopMiniPlayer.NowPlaying(), actions, entries, player, dark) {} }
            tray("tray-menu-$name", menu, dark) { DesktopTrayFlyoutContent(DesktopTrayFlyout.Mode.MENU, song, actions, entries, menu, dark) {} }
        }
    }

    private val actions = DesktopMiniPlayer.Actions({}, {}, {}, {})

    /** The flyout brings its own colours (its window is outside the app's theme); behind it, the desktop the window floats over. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun tray(name: String, card: DpSize, dark: Boolean, content: @Composable () -> Unit) {
        val pad = 16.dp
        val scene = ImageComposeScene(((card.width + pad * 2).value * 2).toInt(), ((card.height + pad * 2).value * 2).toInt(), Density(2f)) {
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF2B3440) else Color(0xFFD9E2EC))) { content() }
        }
        var bytes: ByteArray? = null
        repeat(40) {
            bytes = scene.render(it * 50_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
            Thread.sleep(50)
        }
        scene.close()
        File(out, "$name.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 5_000)
    }

    @Test fun theListenTogetherLandingIsTheRoomsTwoDoors() {
        for ((name, palette) in palettes) {
            render("room-landing-$name", palette, 1100, 760) { DesktopRoomPanel(autoplayEnabled = true, onDismiss = {}) }
        }
    }
}
