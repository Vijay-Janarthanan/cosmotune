package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaTemplateControls
import com.cosmotune.ui.orbit.OrbitInputField
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The old-look leftovers of Manga, Read and Podio, now Orbit: the custom website dialog (its list, then its editor with the Orbit
 * fields, switch and pill buttons), in the dark and the light palettes, under build/old-screens/mangapodio.
 */
@OptIn(ExperimentalTestApi::class)
class OldScreensMangaPodioReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun page(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()),
                typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(420.dp, 860.dp).testTag("old:review").background(Color(colors.palette.backgroundBottom))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/old-screens/mangapodio/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("old:review").captureToImage().toAwtImage(), "png", file)
    }

    @Test fun customWebsiteDialogInDarkAndLight() {
        for (dark in listOf(true, false)) {
            val tag = if (dark) "dark" else "light"
            val repository = MangaRepository(MangaStore(temporary.newFolder())) { config, _ -> error("not used ${config.id}") }
            runBlocking { repository.store.source(MangaSourceConfig("fixture:site", "template", "Moon Reader", baseUrl = "https://example.org", language = "en")) }
            page(dark) { MangaTemplateControls(repository) }
            compose.onAllNodesWithText("Custom websites and templates").onFirst().performClick()
            compose.waitForIdle()
            capture("custom-websites-list-$tag")
            compose.onAllNodesWithText("Add website").onFirst().performClick()
            compose.waitForIdle()
            capture("custom-websites-editor-$tag")
        }
    }

    @Test fun inputFieldInDarkAndLight() {
        for (dark in listOf(true, false)) {
            var text = androidx.compose.runtime.mutableStateOf("https://example.org/feed.xml")
            page(dark) { androidx.compose.foundation.layout.Column(Modifier.size(420.dp, 300.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                OrbitInputField(text.value, { text.value = it }, "Podcast feed or show link", modifier = Modifier.fillMaxWidth())
                OrbitInputField("", {}, "OPML document", modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 5)
            } }
            capture("input-field-${if (dark) "dark" else "light"}")
        }
    }

}
