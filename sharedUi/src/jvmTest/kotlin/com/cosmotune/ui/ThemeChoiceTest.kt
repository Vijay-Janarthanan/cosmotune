package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.chrome.CosmoProfileDrawer
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.ORBIT_THEME_ORDER
import com.cosmotune.ui.orbit.OrbitThemeRow
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.LocalThemeChoice
import com.cosmotune.ui.theme.ThemeChoice
import com.cosmotune.ui.theme.ThemeChoiceHost
import com.cosmotune.ui.theme.ThemeChoiceRoot
import com.cosmotune.ui.theme.rememberCosmoType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The theme choice (System, Light, Dark) that the phone's and the desktop's Settings and the profile menus' quick switch share: how
 * it is stored and resolved, the switch drawn in both themes (written under build/theme for a person to look at), and the change
 * going through the root without a fade under reduced motion.
 */
class ThemeChoiceTest {
    private val out = File("../build/theme").apply { mkdirs() }

    @Test fun aStoredNameReadsBackAndAnythingElseIsSystem() {
        for (choice in ThemeChoice.entries) assertEquals(choice, ThemeChoice.fromStored(choice.name))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored(null), "nothing stored yet follows the system")
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored(""))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored("dark"), "names are matched exactly")
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored("SEPIA"))
    }

    @Test fun theRoundButtonCyclesSystemLightDark() {
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.SYSTEM.next())
        assertEquals(ThemeChoice.DARK, ThemeChoice.LIGHT.next())
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.DARK.next())
    }

    @Test fun eachChoiceResolvesAgainstTheSystemAndVideoIsDarkFirst() {
        assertTrue(ThemeChoice.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeChoice.SYSTEM.isDark(systemDark = false))
        assertTrue(ThemeChoice.SYSTEM.isDark(systemDark = false, videoFirst = true), "Video makes following the system dark")
        assertFalse(ThemeChoice.LIGHT.isDark(systemDark = true, videoFirst = true), "an explicit Light is light even in Video")
        assertTrue(ThemeChoice.DARK.isDark(systemDark = false))
    }

    @Test fun theSwitchStandsSunMoonThenAuto() {
        assertEquals(listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM), ORBIT_THEME_ORDER)
    }

    /** A host kept in memory: what was chosen, and how many times the store was written. */
    private class MemoryHost(initial: ThemeChoice) : ThemeChoiceHost {
        var stored by mutableStateOf(initial)
        var writes = 0
        @Composable override fun collect(): ThemeChoice = stored
        override fun choose(choice: ThemeChoice) { writes++; stored = choice }
    }

    private fun palette(dark: Boolean) = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, dark: Boolean, width: Int, height: Int, host: ThemeChoiceHost?, minBytes: Int = 1_000, content: @Composable () -> Unit): Bitmap {
        val palette = palette(dark)
        val scene = ImageComposeScene(width * 2, height * 2, Density(2f)) {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true, LocalThemeChoice provides host) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        try {
            var image = scene.render(0)
            for (frame in 1..12) image = scene.render(frame * 50_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(bytes)
            assertTrue(bytes.size > minBytes, "$name was drawn")
            return Bitmap.makeFromImage(image)
        } finally {
            scene.close()
        }
    }

    @Test fun theSwitchIsDrawnInBothThemesWithTheChoiceUnderTheInkThumb() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            val ink = Color(palette(dark).target.textPrimary)
            for (choice in ThemeChoice.entries) {
                val bitmap = render("switch-$theme-${choice.name.lowercase()}", dark, 320, 64, MemoryHost(choice)) {
                    OrbitThemeRow(Modifier.padding(12.dp).width(296.dp), labelStart = 12.dp)
                }
                // The switch ends at the row's end (12 dp in, 296 dp wide): 3 segments of 44 dp after 3 dp of track, 2 px a dp.
                val trackStart = (12 + 296 - 138) * 2
                val centreY = (12 + 20) * 2
                ORBIT_THEME_ORDER.forEachIndexed { at, option ->
                    // Just inside the segment's top edge, clear of the glyph: the thumb's ink, or the chip fill.
                    val x = trackStart + (3 + at * 44 + 6) * 2
                    val argb = bitmap.getColor(x, centreY)
                    val pixel = Color(argb)
                    val close = kotlin.math.abs(pixel.red - ink.red) + kotlin.math.abs(pixel.green - ink.green) + kotlin.math.abs(pixel.blue - ink.blue) < 0.08f
                    if (option == choice) assertTrue(close, "$theme ${choice.name}: the chosen segment wears the ink thumb")
                    else assertFalse(close, "$theme ${choice.name}: ${option.name} is not under the thumb")
                }
            }
        }
    }

    @Test fun theDrawerShowsTheSwitchUnderItsHeadInBothThemes() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            render("drawer-$theme", dark, 412, 760, MemoryHost(ThemeChoice.SYSTEM)) {
                CosmoProfileDrawer(
                    open = true, onDismiss = {}, name = "Vijay", subtitle = "@vijay", avatarUrl = null,
                    entries = listOf(CosmoDrawerEntry(CosmoIcons.Settings, "Settings") {}, CosmoDrawerEntry(CosmoIcons.Download, "Downloads") {}),
                    onAccount = {},
                )
            }
        }
    }

    @Test fun withoutAChoiceInstalledTheRowDrawsNothing() {
        val bitmap = render("switch-none", true, 320, 64, null, minBytes = 0) {
            Column { OrbitThemeRow(Modifier.padding(12.dp)) }
        }
        val ground = bitmap.getColor(10, 10)
        assertEquals(ground, bitmap.getColor(560, 64), "no switch where the app installed no theme choice")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun underReducedMotionTheRootStoresTheChoiceAtOnce() {
        val host = MemoryHost(ThemeChoice.SYSTEM)
        var provided: ThemeChoiceHost? = null
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            ThemeChoiceRoot(host, reduced = true) {
                provided = LocalThemeChoice.current
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
        try {
            scene.render(0)
            val fading = provided!!
            assertNotEquals<ThemeChoiceHost>(host, fading, "the root hands out its own host, which fades")
            fading.choose(ThemeChoice.LIGHT)
            assertEquals(ThemeChoice.LIGHT, host.stored, "no frame waited for: there is no fade under reduced motion")
            assertEquals(1, host.writes)
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun withMotionTheChoiceIsStoredAfterTheOldFrameIsTakenAndTheFadeEnds() {
        val host = MemoryHost(ThemeChoice.DARK)
        var provided: ThemeChoiceHost? = null
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            ThemeChoiceRoot(host, reduced = false) {
                provided = LocalThemeChoice.current
                Box(Modifier.fillMaxSize().background(if (host.collect() == ThemeChoice.LIGHT) Color.White else Color.Black))
            }
        }
        try {
            scene.render(0)
            provided!!.choose(ThemeChoice.LIGHT)
            var time = 0L
            repeat(40) { time += 16_000_000L; scene.render(time) }
            assertEquals(ThemeChoice.LIGHT, host.stored, "stored once the old frame was taken")
            assertEquals(1, host.writes)
            val after = Bitmap.makeFromImage(scene.render(time + 16_000_000L))
            assertEquals(Color.White, Color(after.getColor(100, 100)), "the fade has ended: the new theme alone is on screen")
        } finally {
            scene.close()
        }
    }
}
