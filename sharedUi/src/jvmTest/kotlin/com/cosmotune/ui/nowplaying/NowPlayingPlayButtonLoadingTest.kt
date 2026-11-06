package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.ui.orbit.OrbitLook
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Now Playing's play button, drawn for real in an offscreen scene: while the song loads, the moon circles it in the bite's
 * clear ring (as the player bar and the mini player already showed it), and once the song is in the ring is gone. Before this
 * the card's button looked idle while a tap on Play was still finding its stream.
 */
class NowPlayingPlayButtonLoadingTest {
    private val accent = Color(0xFFFFB98F)
    private val look = OrbitLook(true, Color.White, Color.Gray, Color.DarkGray, Color.DarkGray, accent, Color.Black)

    private fun transport(loading: Boolean) = NowPlayingTransport(
        song = Song(videoId = "v", title = "Levitating", artist = "Dua Lipa", thumbnailUrl = null, fromAutoplay = false), coverUrl = null, isPlaying = false,
        position = PlaybackPosition(), durationMs = 0L, scrub = PlayerScrub(), liked = false, shuffle = false, repeatMode = 0,
        hasPrevious = false, hasNext = false, onPlayPause = {}, onPrevious = {}, onNext = {}, onToggleShuffle = {},
        onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {}, onOpenArtist = {}, isLoading = loading,
    )

    /** The pixels of the ring band (between the 66 dp button and the 82 dp bite) that are not the black ground. */
    private fun ringPixels(loading: Boolean): Int {
        val size = 120
        val scene = ImageComposeScene(width = size, height = size, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                NowPlayingPlayButton(transport(loading), look, 66.dp, HERO_BITE_RING)
            }
        }
        try {
            val image = scene.render()
            val bitmap = Bitmap.makeFromImage(image)
            var lit = 0
            val c = size / 2.0
            for (y in 0 until size) for (x in 0 until size) {
                val d = Math.hypot(x + 0.5 - c, y + 0.5 - c)
                if (d < 35.0 || d > 41.0) continue // outside the band between the button's edge (33) and the bite's (41)
                val argb = bitmap.getColor(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r + g + b > 30) lit++
            }
            return lit
        } finally {
            scene.close()
        }
    }

    @Test fun aLoadingSongCirclesThePlayButton() = assertTrue(ringPixels(loading = true) > 100, "the orbit is drawn in the bite's ring")

    @Test fun aLoadedSongLeavesTheRingClear() = assertEquals(0, ringPixels(loading = false), "nothing round the button once loaded")
}
