package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.design.AppMode
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.*
import com.cosmotune.ui.shell.DOCK_MINI_TAG
import com.cosmotune.video.ui.shell.VideoBottomChrome
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.ui.theme.*
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class VideoMiniPlayerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val item = VideoItem("fixture-video", "A playing video", null, "Channel", null, null, 120, null, null, null)

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
        val type = remember { CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(600f, FontFamily.Default, FontFamily.Default) }
        val shell = remember {
            val settings = CosmoLookSettings(object : KeyValueStore {
                override fun getBoolean(key: String, default: Boolean) = default
                override fun putBoolean(key: String, value: Boolean) = Unit
            }).apply { setMode(AppMode.VIDEO) }
            CosmoShellState::class.java.getConstructor(CosmoLookSettings::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(settings, true).apply { capsuleHidden = true }
        }
        MaterialTheme { CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalCosmoShell provides shell, LocalReducedMotion provides true) { content() } }
    }

    @Test fun backLeavesPauseReopenAndStopAvailableAtPhoneAndDesktopWidths() {
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.READY, playing = true, durationMs = 120_000))
        playback.position = 42_000
        val navigator = VideoNavigator().apply { push(VideoRoute.Watch(item.id)) }
        val width = mutableStateOf(360.dp)
        compose.setContent { Theme {
            Box(Modifier.size(width.value, 170.dp)) {
                // The shared dock's mini player: with the tabs on a phone, alone on a window whose tabs stand in the sidebar.
                VideoBottomChrome(navigator, playback, hazeState = null, capsuleShown = navigator.top !is VideoRoute.Watch, showTabs = width.value < 980.dp)
            }
        } }
        compose.runOnIdle { navigator.pop() }
        compose.onNodeWithTag(DOCK_MINI_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertHasClickAction().performClick()
        compose.runOnIdle { assertFalse(playback.state.value.playing); assertEquals(42_000, playback.positionMs()) }
        compose.onNodeWithTag(DOCK_MINI_TAG).performClick()
        compose.runOnIdle { assertEquals(VideoRoute.Watch(item.id), navigator.top); assertEquals(42_000, playback.positionMs()); navigator.pop(); width.value = 1100.dp }
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag(DOCK_MINI_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(PlayerStatus.IDLE, playback.state.value.status); assertTrue("stop()" in playback.calls) }
    }

    @Test fun videoControlsDoNotDependOnMusicHostOrHiddenMorphState() {
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.READY, playing = true))
        compose.setContent { Theme {
            Box(Modifier.size(360.dp, 100.dp)) { VideoBottomChrome(VideoNavigator(), playback, hazeState = null, capsuleShown = true, showTabs = false) }
        } }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val out = File("../build/video-playback-review/video-mini-phone.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag(DOCK_MINI_TAG).captureToImage().toAwtImage(), "png", out)
    }
}
