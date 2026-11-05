package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.nowplaying.PlayerVisualsAction
import com.cosmotune.ui.nowplaying.PlayerVisualsContent
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerVisualsInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val song = Song("visual-fixture", "A song with a longer title", "Artist", null)

    @Test fun phoneActionOpensNamedScenesAndClosesWithARealButton() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(360.dp, 780.dp)) { PlayerVisualsAction(song) } } }
        compose.onNodeWithContentDescription("Visuals").assertHasClickAction().performClick()
        compose.onNodeWithText("Waveform").performClick()
        compose.onNodeWithText("Play audio on this device to see the visualization.").assertExists()
        compose.onNodeWithContentDescription("Close visuals").assertHasClickAction().performClick()
        compose.onNodeWithTag("player:visuals").assertDoesNotExist()
    }

    @Test fun narrowAndWideViewsKeepSceneAndControlsSeparate() {
        val wide = mutableStateOf(false)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(if (wide.value) 1200.dp else 360.dp, 780.dp)) { PlayerVisualsContent(song, {}) } } }
        compose.onNodeWithText("Artwork").assertIsDisplayed()
        compose.onNodeWithText("Pulse").performClick()
        val scene = compose.onNodeWithTag("player:visuals:scene").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close visuals").fetchSemanticsNode().boundsInRoot
        assertTrue(close.bottom <= scene.top)
        val output = File("../build/ui-parity-review/visuals-phone.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("player:visuals").captureToImage().toAwtImage(), "png", output)
        compose.runOnIdle { wide.value = true }
        compose.onNodeWithText("Artwork").performClick()
        compose.onNodeWithContentDescription("Close visuals").assertIsDisplayed()
        ImageIO.write(compose.onNodeWithTag("player:visuals").captureToImage().toAwtImage(), "png", File(output.parentFile, "visuals-desktop.png"))
    }

    @Test fun allModesAndDismissRemainUsableAtLargeText() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(320.dp, 740.dp)) { PlayerVisualsContent(song, {}) } }
            }
        }
        listOf("Artwork", "Pulse", "Waveform").forEach { name ->
            compose.onNodeWithText(name).assertIsDisplayed().performClick().assertIsSelected()
        }
        compose.onNodeWithContentDescription("Close visuals").assertIsDisplayed().assertHasClickAction()
    }
}
