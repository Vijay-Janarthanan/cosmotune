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
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card's button row never runs under the bite: when it is short of room shuffle, repeat and the video view move into a More
 * menu (their on state kept there), and previous, next and like stay in the row.
 */
class CardButtonsOverflowTest {
    private val movable = listOf(true, true, false, false, false, true)

    @Test fun everythingStaysWhenItFits() {
        val plan = planButtons(movable, 400.dp, 40.dp, 8.dp)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan.inline)
        assertTrue(plan.overflow.isEmpty())
    }

    @Test fun shuffleAndRepeatGoFirstThenTheVideoView() {
        // Four in the row and More: 5 * 40 + 4 * 8 = 232.
        val a = planButtons(movable, 240.dp, 40.dp, 8.dp)
        assertEquals(listOf(2, 3, 4, 5), a.inline)
        assertEquals(listOf(0, 1), a.overflow)
        // Three and More: 4 * 40 + 3 * 8 = 184.
        val b = planButtons(movable, 190.dp, 40.dp, 8.dp)
        assertEquals(listOf(2, 3, 4), b.inline)
        assertEquals(listOf(0, 1, 5), b.overflow)
    }

    @Test fun previousNextAndLikeNeverMove() {
        for (w in 100..900 step 5) {
            val plan = planButtons(movable, w.dp, 40.dp, 8.dp)
            assertTrue(plan.inline.containsAll(listOf(2, 3, 4)), "at $w dp")
            if (w >= 160) {
                val count = plan.inline.size + if (plan.overflow.isEmpty()) 0 else 1
                assertTrue(plan.size * count + plan.gap * (count - 1) <= w.dp, "at $w dp the row fits: $count buttons of ${plan.size}")
            }
        }
    }

    @Test fun veryNarrowShrinksTheButtonsRatherThanOverflowing() {
        val plan = planButtons(movable, 166.dp, 40.dp, 8.dp)
        assertEquals(36.dp, plan.size)
        assertEquals(listOf(2, 3, 4), plan.inline)
    }

    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true)

    private fun transport() = NowPlayingTransport(
        song = Song(videoId = "v", title = "Levitating", artist = "Dua Lipa", thumbnailUrl = null, fromAutoplay = false), coverUrl = null, isPlaying = true,
        position = PlaybackPosition(), durationMs = 200_000L, scrub = PlayerScrub(), liked = false, shuffle = false, repeatMode = 0,
        hasPrevious = true, hasNext = true, onPlayPause = {}, onPrevious = {}, onNext = {}, onToggleShuffle = {},
        onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {}, onOpenArtist = {},
    )

    private val buttons = listOf(
        CardButton(CosmoIcons.Shuffle, "Shuffle on", {}, on = true, movable = true),
        CardButton(CosmoIcons.Repeat, "Repeat", {}, movable = true),
        CardButton(CosmoIcons.Previous, "Previous", {}),
        CardButton(CosmoIcons.Next, "Next", {}),
        CardButton(CosmoIcons.Heart, "Like", {}),
        CardButton(CosmoIcons.Lyrics, "How the video is shown", {}, movable = true),
    )

    /** Renders the card with six buttons at [widthDp]; the pictures go to build/card-buttons for a look. */
    private fun render(widthDp: Int, wide: Boolean = false): Bitmap {
        val scene = ImageComposeScene(width = widthDp, height = 330, density = Density(1f)) {
            CompositionLocalProvider(LocalCosmoColors provides shell.colors) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14110F))) {
                    NowPlayingSongCard(transport(), orbitFonts(), orbitLook(), buttons, Modifier.width(widthDp.dp), wide = wide, showLine = wide, volume = true, volumeSource = FakeVolumeSource)
                }
            }
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            val dir = File("build/card-buttons").apply { mkdirs() }
            File(dir, "w$widthDp.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return bitmap
        } finally {
            scene.close()
        }
    }

    @Test fun theCardDrawsAtEveryWidthWithoutFailing() {
        for (w in listOf(280, 320, 380, 640, 900)) assertTrue(render(w).width == w)
    }
}

internal object FakeVolumeSource : com.cosmotune.music.ui.player.SystemVolume {
    override val level = kotlinx.coroutines.flow.MutableStateFlow(0.5f)
    override fun set(level: Float) = Unit
    override fun refresh() = Unit
}
