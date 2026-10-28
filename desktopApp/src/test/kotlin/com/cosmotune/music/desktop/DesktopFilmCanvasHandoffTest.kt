package com.cosmotune.music.desktop

import com.cosmotune.music.data.canvas.CanvasArtwork
import com.opencanvas.core.sync.SyncMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class DesktopFilmCanvasHandoffTest {
    @Test fun anExpiredVideoAddressDoesNotStayInTheDesktopCanvasCache() = runBlocking {
        var lookups = 0
        val key = "expired-film-${System.nanoTime()}"
        repeat(2) {
            DesktopCanvasClient.resolve(key, withAlbum = true) {
                lookups++
                DesktopCanvasArtwork("https://example.invalid/video?expire=1")
            }
        }
        assertEquals(2, lookups)
    }

    @Test fun aVerifiedFilmVideoSurvivesTheDesktopMetadataHandoff() {
        for (title in listOf("Appadipodu", "Kokkorakko", "Arjunar Villu")) {
            val requested = "$title (From \"Gilli\")"
            val shared = CanvasArtwork(
                url = "https://example.invalid/verified.mp4", title = title,
                artist = "Vidyasagar, Singer", album = "Gilli (Original Motion Picture Soundtrack)",
                syncMap = SyncMap.constant(100), videoDurationMs = 300_000,
            )
            val desktop = DesktopOpenCanvasProvider.artwork(shared)
            assertTrue(desktop.matches(requested.cleanedForCanvas(), "Vidyasagar, Singer", shared.album), title)
            assertFalse(desktop.matches("Another Song", "Vidyasagar, Singer", shared.album))
            assertFalse(desktop.matches(title, "Someone Else", shared.album))
            assertFalse(desktop.matches(title, "Vidyasagar, Singer", "Another Film"))
        }
    }
}
