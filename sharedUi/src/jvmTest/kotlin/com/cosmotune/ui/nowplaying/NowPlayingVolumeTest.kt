package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.music.ui.player.SystemVolume
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop song card always carries the volume. With the six buttons (shuffle, repeat, previous, next, like, video) the line
 * no longer fits beside them; it then moves to the card's top right, short, and under that to a speaker button. Drawn for real
 * in an offscreen scene at the widths the card can take: with the volume on, the picture differs from the card without it.
 */
class NowPlayingVolumeTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private class FakeVolume : SystemVolume {
        override val level: StateFlow<Float> = MutableStateFlow(0.5f)
        override fun set(level: Float) = Unit
        override fun refresh() = Unit
    }

    private val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true)

    private fun transport() = NowPlayingTransport(
        song = Song(videoId = "v", title = "Levitating", artist = "Dua Lipa", thumbnailUrl = null, fromAutoplay = false), coverUrl = null, isPlaying = true,
        position = PlaybackPosition(), durationMs = 200_000L, scrub = PlayerScrub(), liked = false, shuffle = false, repeatMode = 0,
        hasPrevious = true, hasNext = true, onPlayPause = {}, onPrevious = {}, onNext = {}, onToggleShuffle = {},
        onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {}, onOpenArtist = {},
    )

    private val sixButtons = List(6) { CardButton(CosmoIcons.Shuffle, "Button $it", {}) }

    private fun render(name: String, widthDp: Int, wide: Boolean, volume: Boolean, slim: Boolean): Bitmap {
        val scene = ImageComposeScene(width = widthDp, height = 330, density = Density(1f)) {
            CompositionLocalProvider(LocalCosmoColors provides shell.colors) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14110F))) {
                    val fonts = orbitFonts()
                    val look = orbitLook()
                    if (slim) {
                        NowPlayingSlimCard(transport(), fonts, look, sixButtons.take(4), Modifier.width(widthDp.dp), volume = volume, volumeSource = FakeVolume())
                    } else {
                        NowPlayingSongCard(
                            transport(), fonts, look, sixButtons, Modifier.width(widthDp.dp), wide = wide, showLine = wide, volume = volume,
                            volumeSource = FakeVolume(),
                        )
                    }
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            val dir = File("build/volume-card").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return bitmap
        } finally {
            scene.close()
        }
    }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until minOf(a.height, b.height)) for (x in 0 until minOf(a.width, b.width)) if (a.getColor(x, y) != b.getColor(x, y)) return true
        return false
    }

    private fun check(name: String, widthDp: Int, wide: Boolean, slim: Boolean = false) {
        val with = render("$name-volume", widthDp, wide, true, slim)
        val without = render("$name-novolume", widthDp, wide, false, slim)
        assertTrue(differs(with, without), "$name at $widthDp dp: the volume is drawn")
    }

    @Test fun narrowCardWithSixButtonsShowsTheSpeakerButton() = check("narrow", 280, wide = false)

    @Test fun midCardWithSixButtonsShowsTheShortLineTopRight() = check("mid", 380, wide = false)

    @Test fun roomyCardKeepsTheLineBesideTheButtons() = check("roomy", 640, wide = false)

    @Test fun wideCardWithVideoShowsTheLineUnderTheButtons() = check("wide", 900, wide = true)

    @Test fun cinemasSlimCardCarriesTheSpeaker() = check("slim", 760, wide = false, slim = true)
}
