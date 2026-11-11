package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.account.AccountExpiredBanner
import com.cosmotune.ui.kit.KitErrorState
import com.cosmotune.ui.kit.KitOfflineBanner
import com.cosmotune.ui.states.CosmoSignInBanner
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test

/**
 * Not an assertion: the notices and states of the spacing pass (the sign-in expired banner at phone and desktop widths, beside the
 * sign-in and offline banners and the error state it sits among), in the dark and the light palettes, written under the worktree's
 * build/spacing/current for a person to look at.
 */
class SpacingVisualReviewTest {
    @get:Rule val compose = createComposeRule()

    private fun page(dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(width.dp, height.dp).testTag("spacing:review").background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/spacing/current/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("spacing:review").captureToImage().toAwtImage(), "png", file)
    }

    @Test fun banners() {
        for (dark in listOf(true, false)) {
            val tag = if (dark) "dark" else "light"
            for ((surface, width) in listOf("phone" to 390, "desktop" to 820)) {
                page(dark, width, 560) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccountExpiredBanner({}, onDismiss = {})
                        AccountExpiredBanner({}, message = "Your YouTube sign-in has expired. Videos you saved on this device still play.", actionLabel = "Sign in")
                        CosmoSignInBanner({})
                        KitOfflineBanner()
                        KitErrorState("The server did not answer in time.", onRetry = {})
                    }
                }
                capture("banners-$surface-$tag")
            }
        }
    }
}
