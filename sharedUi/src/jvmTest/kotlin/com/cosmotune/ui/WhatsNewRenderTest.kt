package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.cosmotune.HighlightIcon
import com.cosmotune.ReleaseHighlights
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.whatsnew.WhatsNew
import com.cosmotune.ui.whatsnew.WhatsNewAction
import com.cosmotune.ui.whatsnew.WhatsNewCard
import com.cosmotune.ui.whatsnew.WhatsNewSheet
import com.cosmotune.ui.whatsnew.WhatsNewSurface
import com.cosmotune.ui.whatsnew.highlightIcon
import com.cosmotune.ui.whatsnew.whatsNewMeta
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the "What's new" card drawn offscreen from the real release notes (docs/releases), as the phone's
 * sheet, the desktop's dialog card and the TV's card, in the dark and the light palettes, written under the worktree's
 * build/whats-new for a person to look at. Each picture is checked only for having been drawn.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WhatsNewRenderTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val out = File(root, "build/whats-new").apply { mkdirs() }

    private fun notes(version: String) = File(root, "docs/releases/$version.md").readText()

    private fun shoot(name: String, dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        val colors = CosmoColors(palette)
        val scene = ImageComposeScene(width = width * 2, height = height * 2, density = Density(2f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides rememberCosmoType(), LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        try {
            var nanos = 0L
            var image = scene.render(nanos)
            repeat(30) { nanos += 16_000_000L; image = scene.render(nanos) }
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("no PNG for $name")
            val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name was drawn")
        } finally {
            scene.close()
        }
    }

    private fun update(version: String, meta: String = whatsNewMeta("1.0.20", version, 41_200_000)) = WhatsNew(
        version = version,
        highlights = ReleaseHighlights.of(notes(version)),
        primary = WhatsNewAction("Update now") {},
        secondary = WhatsNewAction("Later") {},
        onFullNotes = {},
        meta = meta,
    )

    private fun welcome(version: String) = WhatsNew(
        version = version,
        highlights = ReleaseHighlights.of(notes(version)),
        primary = WhatsNewAction("Got it") {},
        onFullNotes = {},
    )

    @Test fun phoneSheet() {
        for (dark in listOf(true, false)) {
            shoot("phone-update-1.0.21", dark, 390, 844) { WhatsNewSheet(update("1.0.21"), {}) }
            shoot("phone-welcome-1.0.17", dark, 390, 844) { WhatsNewSheet(welcome("1.0.17"), {}) }
            shoot("phone-downloading", dark, 390, 844) {
                WhatsNewSheet(
                    WhatsNew("1.0.21", ReleaseHighlights.of(notes("1.0.21")), WhatsNewAction("Hide") {}, WhatsNewAction("Cancel") {}, {}, progress = 0.42f),
                    {},
                )
            }
            shoot("phone-fallback-1.0.16", dark, 390, 844) { WhatsNewSheet(update("1.0.16", whatsNewMeta("1.0.15", "1.0.16", null)), {}) }
        }
    }

    @Test fun desktopDialog() {
        for (dark in listOf(true, false)) {
            shoot("desktop-update-1.0.21", dark, 1100, 760) { WhatsNewCard(update("1.0.21"), {}, WhatsNewSurface.DESKTOP) }
            shoot("desktop-welcome-1.0.17", dark, 1100, 760) { WhatsNewCard(welcome("1.0.17"), {}, WhatsNewSurface.DESKTOP) }
            shoot("desktop-failed", dark, 1100, 760) {
                WhatsNewCard(
                    WhatsNew("1.0.21", ReleaseHighlights.of(notes("1.0.21")), WhatsNewAction("Try again") {}, WhatsNewAction("Later") {}, {}, problem = "Download failed: HTTP 503"),
                    {}, WhatsNewSurface.DESKTOP,
                )
            }
        }
    }

    /** The TV's card at a TV's 960 by 540 dp, with the peach action holding D-pad focus as it does when the card opens. */
    @Test fun tvCard() {
        for (dark in listOf(true, false)) {
            shoot("tv-update-1.0.21", dark, 960, 540) { WhatsNewCard(update("1.0.21"), {}, WhatsNewSurface.TV) }
            shoot("tv-update-1.0.17", dark, 960, 540) { WhatsNewCard(update("1.0.17", whatsNewMeta("1.0.16", "1.0.17", 61_000_000)), {}, WhatsNewSurface.TV) }
        }
    }

    @Test fun everyIconHasADrawing() {
        assertEquals(HighlightIcon.entries.size, HighlightIcon.entries.map { highlightIcon(it) }.size)
        assertEquals("1.0.20 → 1.0.21 · 39 MB", whatsNewMeta("1.0.20", "1.0.21", 41_200_000))
        assertEquals("1.0.20 → 1.0.21 · 8.6 MB", whatsNewMeta("1.0.20", "1.0.21", 9_000_000))
        assertEquals("1.0.20 → 1.0.21", whatsNewMeta("1.0.20", "1.0.21", null))
    }
}
