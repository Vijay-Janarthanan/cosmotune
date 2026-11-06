package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cinema never shows a black window while its video has no frame: the cover's disc and the circling moon stand in under the
 * video until the first frame is in (the feature check saw about 50 s of black the first time Cinema opened on a paused,
 * freshly restored song), and they are gone once it is.
 */
class CinemaStandInTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true)

    private fun litPixels(reveal: Float): Int {
        val w = 320
        val h = 640
        val shown = mutableFloatStateOf(reveal)
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            CompositionLocalProvider(LocalCosmoColors provides shell.colors) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CinemaStandIn(coverUrl = null, reveal = shown, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var lit = 0
            for (y in 0 until h) for (x in 0 until w) {
                val argb = bitmap.getColor(x, y)
                if (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF) > 30) lit++
            }
            return lit
        } finally {
            scene.close()
        }
    }

    @Test fun beforeTheFirstFrameTheCoverStandsIn() =
        assertTrue(litPixels(reveal = 0f) > 500, "the disc and its orbit are drawn while the video has no frame")

    @Test fun onceTheVideoIsInTheStandInIsGone() = assertEquals(0, litPixels(reveal = 1f), "nothing over the video once it shows")

    @Test fun cinemaDrawsTheStandInUnderItsVideo() {
        val src = File(sourceRoot(), "NowPlayingCinema.kt").readText()
        val body = src.substringAfter("internal fun NowPlayingCinema(")
        val standIn = body.indexOf("CinemaStandIn(coverUrl, reveal")
        val video = body.indexOf("NowPlayingVideo(")
        assertTrue(standIn in 0 until video, "the stand-in is drawn first, under the video")
    }

    private fun sourceRoot(): File {
        val rel = "src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying"
        return listOf(File(rel), File("sharedUi/$rel")).first { it.isDirectory }
    }
}
