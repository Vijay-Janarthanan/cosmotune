package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's screenshot: the seek line ran under the bite's ring, its length squeezed beneath play / pause. Drawn for real with
 * the song at its last second (the moon at the line's end), the strip of card just before the ring, [BITE_GAP] wide, down the
 * whole height of the ring, holds nothing but the card's own colour. And the volume sits in the controls' row, not under it.
 */
class NowPlayingCardBiteTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private class FakeVolume : SystemVolume {
        override val level: StateFlow<Float> = MutableStateFlow(1f)
        override fun set(level: Float) = Unit
        override fun refresh() = Unit
    }

    private val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true)

    private fun transport() = NowPlayingTransport(
        song = Song(videoId = "v", title = "Levitating", artist = "Dua Lipa", thumbnailUrl = null, fromAutoplay = false), coverUrl = null, isPlaying = true,
        position = PlaybackPosition().apply { report(199_500L); advancing = false }, durationMs = 200_000L, scrub = PlayerScrub(),
        liked = false, shuffle = false, repeatMode = 0, hasPrevious = true, hasNext = true, onPlayPause = {}, onPrevious = {}, onNext = {},
        onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {}, onOpenArtist = {},
    )

    private val sixButtons = listOf(
        CardButton(CosmoIcons.Shuffle, "Shuffle on", {}, on = true, movable = true),
        CardButton(CosmoIcons.Repeat, "Repeat", {}, movable = true),
        CardButton(CosmoIcons.Previous, "Previous", {}),
        CardButton(CosmoIcons.Next, "Next", {}),
        CardButton(CosmoIcons.Heart, "Like", {}),
        CardButton(CosmoIcons.Lyrics, "How the video is shown", {}, movable = true),
    )

    private fun render(widthDp: Int, wide: Boolean): Pair<Bitmap, IntSize> {
        var size = IntSize.Zero
        val scene = ImageComposeScene(width = widthDp + 40, height = 400, density = Density(1f)) {
            CompositionLocalProvider(LocalCosmoColors provides shell.colors) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14110F))) {
                    NowPlayingSongCard(
                        transport(), orbitFonts(), orbitLook(), sixButtons,
                        Modifier.width(widthDp.dp).then(if (wide) Modifier.height(188.dp) else Modifier).onGloballyPositioned { size = it.size },
                        wide = wide, volume = true, volumeSource = FakeVolume(),
                    )
                }
            }
        }
        try {
            scene.render()
            val image = scene.render()
            // For a person to look at: build/bite-card/<width>-<layout>.png.
            val dir = java.io.File("build/bite-card").apply { mkdirs() }
            java.io.File(dir, "$widthDp-${if (wide) "wide" else "column"}.png")
                .writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            return Bitmap.makeFromImage(image) to size
        } finally {
            scene.close()
        }
    }

    private fun assertClearBeforeTheRing(widthDp: Int, wide: Boolean) {
        val (bitmap, size) = render(widthDp, wide)
        assertTrue(size.width == widthDp && size.height > 120, "the card was laid out ($size)")
        val ringStart = size.width - 87
        val fill = bitmap.getColor(ringStart - BITE_GAP.value.toInt() - 2, size.height - 8)
        val strip = (ringStart - BITE_GAP.value.toInt() + 1) until ringStart
        val band = (size.height - 87) until (size.height - 4)
        val stray = strip.flatMap { x -> band.map { y -> x to y } }.filter { (x, y) -> bitmap.getColor(x, y) != fill }
        assertEquals(emptyList(), stray.take(5), "$widthDp dp ${if (wide) "wide" else "column"} card: nothing in the $BITE_GAP before the ring")
    }

    @Test fun theWideCardsSeekLineAndTimesStopBeforeTheRing() = assertClearBeforeTheRing(1000, wide = true)

    @Test fun theNarrowestWideCardStopsBeforeTheRingToo() = assertClearBeforeTheRing(640, wide = true)

    @Test fun theColumnCardsTimesAndButtonsStopBeforeTheRing() {
        assertClearBeforeTheRing(640, wide = false)
        assertClearBeforeTheRing(380, wide = false)
    }

    @Test fun theWideCardsVolumeFitsTheRowOrBecomesTheSpeaker() {
        assertEquals(VolumeFit.Line, volumeFit(600.dp, 6, 40.dp, 6.dp))
        assertEquals(VolumeFit.Short, volumeFit(400.dp, 6, 40.dp, 6.dp))
        assertEquals(VolumeFit.Button, volumeFit(300.dp, 6, 40.dp, 6.dp))
    }
}
