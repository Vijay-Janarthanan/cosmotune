package com.cosmotune.music

import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.canvas.CanvasRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Switching OpenCanvas off, or changing its stream resolution, drops what the canvas lookup remembered, so a video found
 * under the old setting is not served under the new one.
 */
class CanvasRepositoryTest {

    private fun song(videoId: String) = Song(videoId, "Song", "Artist", null)

    private fun songKey(videoId: String) = com.cosmotune.canvas.canvasSongKey(videoId,
        AppSettings.openCanvasEnabled.value, AppSettings.openCanvasResolution.value) +
        "|spotifyFirst=${AppSettings.prioritizeSpotifyCanvas.value}"

    private val clip = CanvasArtwork(url = "https://example.invalid/clip.mp4", title = "Song", artist = "Artist")

    @Test
    fun `a canvas found earlier is not served once the remembered answers are dropped`() = runBlocking {
        CanvasRepository.resolve(songKey("clear-1"), withAlbum = false) { clip }
        assertNotNull("the hit is remembered", CanvasRepository.cached(song("clear-1")))

        CanvasRepository.forgetAll()

        assertNull("and gone after the switch or the resolution changed", CanvasRepository.cached(song("clear-1")))
    }

    @Test
    fun `a lookup that was running when the answers were dropped is not remembered afterwards`() = runBlocking {
        CanvasRepository.resolve(songKey("clear-2"), withAlbum = false) {
            CanvasRepository.forgetAll()
            clip
        }

        assertNull(CanvasRepository.cached(song("clear-2")))
    }
}
