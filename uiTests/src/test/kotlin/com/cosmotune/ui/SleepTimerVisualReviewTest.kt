package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.RepeatModes
import com.cosmotune.music.ui.player.SystemVolume
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.NowPlayingActive
import com.cosmotune.ui.nowplaying.NowPlayingStatusChips
import com.cosmotune.ui.nowplaying.activeChips
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoPlayerBar
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.sleep.SleepTimerControl
import com.cosmotune.ui.sleep.SleepTimerUi
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * The sleep timer while it runs, where the owner asked to see it: the pill in the Now Playing chip row (with the other
 * features that are on as solid chips beside it), its menu with the time left, Extend and Turn off, the phone's small player
 * and the desktop's player bar. Drawn offscreen in the dark and the light palettes and written under build/sleep; the
 * assertions are only that each surface says the time left.
 */
class SleepTimerVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    /** A timer frozen at 23:41 left (or at the end of the song), so the pictures do not depend on when they were drawn. */
    private class FrozenTimer(left: Long?, after: Boolean) : SleepTimerControl {
        override val minutes = MutableStateFlow(if (left != null) 30 else null)
        override val afterTrack = MutableStateFlow(after)
        private val left = left
        override fun remainingMs(): Long? = left
        override fun start(minutes: Int) {}
        override fun startAfterTrack() {}
        override fun extend() {}
        override fun cancel() {}
    }

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {
            it.color = java.awt.Color(r, g, b); it.fillRect(0, 0, 320, 320)
            it.color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); it.fillOval(50, 50, 220, 220)
            it.dispose()
        }
        return temporary.newFile("$name.png").also { ImageIO.write(image, "png", it) }.path
    }

    private fun hosts(timer: SleepTimerControl, block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = playerField.get(null)
        val previousTimer = SleepTimerUi.control
        val volume = java.lang.reflect.Proxy.newProxyInstance(SystemVolume::class.java.classLoader, arrayOf(SystemVolume::class.java)) { _, method, _ ->
            when (method.name) {
                "getLevel" -> MutableStateFlow(0.6f)
                else -> null
            }
        } as SystemVolume
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                "getVolume" -> volume
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        SleepTimerUi.control = timer
        try { block() } finally {
            playerField.set(null, previous)
            SleepTimerUi.control = previousTimer
        }
    }

    private val palettes = listOf(
        "dark" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true),
        "light" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false),
    )

    private fun page(palette: CosmoPaletteState, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(width, height).testTag("sleep:review").background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    private fun capture(name: String, popup: Boolean = false) {
        compose.waitForIdle()
        val file = File("../build/sleep/$name.png").apply { parentFile.mkdirs() }
        val node = if (popup) compose.onAllNodes(isRoot())[1] else compose.onNodeWithTag("sleep:review")
        ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
    }

    private fun says(text: String) = assertTrue(
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
        "nothing on screen says \"$text\"",
    )

    /** The phone's chip row: the panels, then the timer's pill and what else is on, solid. */
    @Composable
    private fun PhoneChipRow(active: NowPlayingActive) {
        val fonts = orbitFonts()
        val look = orbitLook()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbitChip("Lyrics", fonts, look, {}, leading = null)
            OrbitChip("Up next", fonts, look, {}, leading = null)
            OrbitChip("Related", fonts, look, {}, leading = null)
            NowPlayingStatusChips(activeChips(active, transportOnCard = false, {}, {}, {}, {}, {}), fonts, look)
        }
    }

    @Test fun thePillAndItsMenu() = hosts(FrozenTimer(23 * 60_000L + 41_000L, after = false)) {
        for ((name, palette) in palettes) {
            page(palette, 760.dp, 220.dp) {
                Column(Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    PhoneChipRow(NowPlayingActive())
                    PhoneChipRow(NowPlayingActive(shuffle = true, repeatMode = RepeatModes.ONE, automix = true, equaliser = true, castingTo = "Living room", inRoom = true))
                }
            }
            says("23:41")
            capture("now-playing-chips-$name")
            compose.onAllNodesWithText("23:41")[0].performClick()
            compose.waitForIdle()
            says("SLEEP TIMER · 23:41 LEFT")   // the kit sets a menu's header in capitals
            says("Extend")
            says("Turn off timer")
            capture("sleep-menu-$name", popup = true)
        }
    }

    @Test fun afterThisSong() = hosts(FrozenTimer(null, after = true)) {
        for ((name, palette) in palettes) {
            page(palette, 760.dp, 120.dp) {
                Box(Modifier.fillMaxSize().padding(top = 24.dp)) { PhoneChipRow(NowPlayingActive(repeatMode = RepeatModes.ONE)) }
            }
            says("After this song")
            capture("now-playing-chips-after-song-$name")
        }
    }

    @Test fun theDockAndThePlayerBar() = hosts(FrozenTimer(23 * 60_000L + 41_000L, after = false)) {
        val song = Song("v1", "Golden Hour", "Mira and the Coast", cover("d", 170, 90, 30), durationText = "4:05")
        val tabs = listOf(
            CosmoTab("Home", CosmoIcons.Home, CosmoIcons.HomeFill),
            CosmoTab("Explore", CosmoIcons.Explore, CosmoIcons.Explore),
            CosmoTab("Library", CosmoIcons.Library, CosmoIcons.Library),
        )
        for ((name, palette) in palettes) {
            page(palette, 390.dp, 300.dp) {
                Box(Modifier.fillMaxSize()) {
                    CosmoBottomChrome(
                        tabs = tabs, selectedIndex = 0, onTabSelected = {}, song = song, isPlaying = true, isLoading = false,
                        position = PlaybackPosition().apply { report(112_000) }, durationMs = 245_000, onPlayPause = {}, onNext = {}, onExpand = {},
                        controlsLocked = false, onBlockedControl = {}, hazeState = null, onHeightChanged = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            says("23:41")
            capture("phone-dock-$name")
            page(palette, 1100.dp, 140.dp) {
                Box(Modifier.fillMaxSize().padding(20.dp)) {
                    CosmoPlayerBar(
                        song = song, isPlaying = true, isLoading = false, position = PlaybackPosition().apply { report(112_000) }, durationMs = 245_000,
                        liked = false, shuffle = false, repeatMode = RepeatModes.ONE, controlsLocked = false, lyricsOn = false, queueOn = false,
                        onPlayPause = {}, onPrevious = {}, onNext = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {},
                        onSeekFraction = {}, onLyrics = {}, onQueue = {}, onOutput = {}, onExpand = {}, onBlockedControl = {}, hazeState = null,
                        embedded = true,
                    )
                }
            }
            says("23:41")
            capture("desktop-player-bar-$name")
        }
    }
}
