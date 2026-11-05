package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.toColorScheme
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.audio.*
import com.cosmotune.ui.nowplaying.ListeningDeviceSheet
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class ListeningDeviceInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedProfileBypassRestoresTheCapturedTuningWithoutTurningOnAutomaticReconnect() {
        var disk = ""
        val store = ListeningDeviceProfiles({ disk }, { disk = it })
        val key = listeningRouteKey("test", "headphones", "confirmed")
        val original = ListeningEq(enabled = true, manual = true, bands = listOf(2f, 1f, 0f, -1f, 0f, 1f, 2f), balance = .2f)
        var applied: ListeningEq? = null
        compose.setContent { MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
            ListeningDeviceSheet(ListeningDeviceFacts(key, "Confirmed headphones"), store, { original }, { applied = it }, null, {}, {})
        } }
        compose.onNodeWithText("Save current EQ for this device").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(assertNotNull(store.forRoute(key)).restoreOnConnect); assertNull(applied) }
        compose.onNodeWithText("Flat EQ bypass").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(assertNotNull(applied).enabled); assertEquals(original.bands, applied?.bands) }
        compose.onNodeWithText("Flat EQ bypass").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original, applied) }
        compose.onNodeWithText("Restore saved EQ on reconnect").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(assertNotNull(store.forRoute(key)).restoreOnConnect) }
        val out = File("../build/video-playback-review/listening-device-sheet.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNode(isRoot() and hasAnyDescendant(hasText("Listening device", substring = false))).captureToImage().toAwtImage(), "png", out)
    }
}
