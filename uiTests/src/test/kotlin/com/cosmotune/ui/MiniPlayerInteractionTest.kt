package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.player.MiniPlayerPanel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The desktop mini player's panel at its window size: transport buttons reach the player, the state is shown. */
@OptIn(ExperimentalTestApi::class)
class MiniPlayerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transportButtonsReachThePlayerAndThePlayStateIsShown() {
        val calls = mutableListOf<String>()
        var playing by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(380.dp, 124.dp)) {
                MiniPlayerPanel("Kannalane", "A. R. Rahman", playing, 0.4f, artwork = { Box(it) },
                    onPlayPause = { calls += "play"; playing = !playing }, onNext = { calls += "next" },
                    onPrevious = { calls += "previous" }, onOpen = { calls += "open" })
            }
        }
        compose.onNodeWithTag("mini:title").assertTextEquals("Kannalane")
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithTag("mini:playpause").performClick()
        compose.onNodeWithContentDescription("Pause").assertExists()
        listOf("mini:next", "mini:previous", "mini:open").forEach { compose.onNodeWithTag(it).assertIsDisplayed().performClick() }
        assertEquals(listOf("play", "next", "previous", "open"), calls)
    }

    @Test fun anEmptyPlayerSaysSo() {
        compose.setContent {
            Box(Modifier.size(380.dp, 124.dp)) {
                MiniPlayerPanel("", "", false, 3f, artwork = { Box(it) }, onPlayPause = {}, onNext = {}, onPrevious = {}, onOpen = {})
            }
        }
        compose.onNodeWithTag("mini:title").assertTextEquals("Nothing playing")
    }
}
