package com.cosmotune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.components.contextClick
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** The desktop-owned row modifier delegates to this same secondary-button primitive. */
class ContextClickInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun secondaryClickOpensTheMenuWithoutStartingPlayback() {
        var plays = 0
        var menus = 0
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("song")
                .contextClick { menus++ }
                .clickable { plays++ })
        }
        compose.onNodeWithTag("song").performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle {
            assertEquals(1, menus)
            assertEquals(0, plays)
        }
        compose.onNodeWithTag("song").performMouseInput { click(button = MouseButton.Primary) }
        compose.runOnIdle {
            assertEquals(1, menus)
            assertEquals(1, plays)
        }
    }
}
