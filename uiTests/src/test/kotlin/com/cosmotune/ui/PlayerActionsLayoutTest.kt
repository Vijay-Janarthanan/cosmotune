package com.cosmotune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.player.PlayerActionLayout
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerActionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowPhoneKeepsFullTouchTargetsAndResizeRestoresASingleRow() {
        var width by mutableStateOf(280.dp) // 320dp phone, with its two 20dp gutters.
        var visualsOpened = 0
        var queueOpened = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width)) {
                    PlayerActionLayout(
                        lyrics = { Box(Modifier.size(44.dp).testTag("lyrics")) },
                        visuals = { Box(Modifier.size(48.dp).testTag("visuals").clickable { visualsOpened++ }) },
                        capsule = { Box(Modifier.size(158.dp, 44.dp).testTag("capsule")) },
                        queue = { Box(Modifier.size(44.dp).testTag("queue").clickable { queueOpened++ }) },
                    )
                }
            }
        }
        compose.onNodeWithTag("queue").assertIsDisplayed().assertWidthIsEqualTo(44.dp)
        compose.onNodeWithTag("visuals").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("capsule").assertWidthIsEqualTo(158.dp)
        val compactCapsule = compose.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val compactVisuals = compose.onNodeWithTag("visuals").fetchSemanticsNode().boundsInRoot
        assertTrue(compactCapsule.top >= compactVisuals.bottom, "the capsule owns a second row rather than shrinking queue")
        compose.onNodeWithTag("visuals").performClick()
        compose.onNodeWithTag("queue").performClick()
        compose.runOnIdle { assertEquals(1, visualsOpened); assertEquals(1, queueOpened); width = 480.dp }
        compose.waitForIdle()
        val lyrics = compose.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot
        val visuals = compose.onNodeWithTag("visuals").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithTag("queue").fetchSemanticsNode().boundsInRoot
        assertEquals(lyrics.top, capsule.top)
        assertEquals(lyrics.top, queue.top)
        assertTrue(visuals.right <= capsule.left && capsule.right <= queue.left, "wide controls are ordered and never overlap")
    }
}
