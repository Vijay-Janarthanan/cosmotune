package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Force the deferred tooltip body to compose using the application's desktop Material version. */
@OptIn(ExperimentalTestApi::class)
class IconTooltipInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var clicks = 0

    private fun show() {
        // Supply only the control's palette; the application theme also requires a playback host.
        // The constructor is Kotlin-internal, but this separate test JVM deliberately has no friend module.
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CosmoIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { clicks++ },
                            Modifier.testTag("back-button"))
                    }
                }
            }
        }
    }

    @Test fun longPressRendersTooltipWithPackagedMaterialAbi() {
        show()
        compose.onNodeWithTag("back-button").performTouchInput { longClick() }
        compose.onNodeWithText("Back").assertIsDisplayed()
        assertEquals(0, clicks)
    }

    @Test fun clickingTheButtonKeepsItsActionWorking() {
        show()
        compose.onNodeWithTag("back-button").performClick().performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }
}
