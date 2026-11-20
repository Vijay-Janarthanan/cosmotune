package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every automatic lyrics lookup goes through the one store ([com.cosmotune.music.data.lyrics.LyricsStore]): the phone's player
 * screen and its playback service share it (so a song is fetched once, not twice), downloads reuse it, the desktop keeps its
 * answers too, and both platforms look the next song up ahead. Read from the source.
 */
class LyricsStoreWiringContractTest {
    private val service = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt"))
    private val tag = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/download/LyricsTag.kt"))
    private val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLyricsClient.kt"))
    private val desktopApp = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))

    @Test fun thePlayerScreenAndTheServiceShareOneLookup() {
        val load = SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/ui/MainViewModel.kt"), "loadLyrics")
        assertTrue("LyricsStore.lyrics(" in load && "LyricsRepository.lyrics(" !in load, "the player screen's automatic lookup is the store's")
        assertTrue("onSourceStarted = { source -> providerStarted(generation, source) }" in load, "and still reports each provider to the drawer")
        assertTrue("LyricsRepository.lyrics(" in SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/ui/MainViewModel.kt"), "fetchLyricsProvider"),
            "a provider picked by hand still asks it directly")
        val serviceLoad = SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt"), "loadLyricsForCurrentTrack")
        assertTrue("LyricsStore.lyrics(" in serviceLoad, "the service's lookup is the same store's")
        assertTrue("awaitTrackDuration(currentSong.videoId)" in serviceLoad, "asked with the player's length, as the screen is, so the two meet")
        assertFalse("LyricsRepository.lyrics(" in service, "the service never asks the providers itself")
        assertTrue("LyricsStore.lyrics(" in tag, "a download reuses the lyrics found while playing")
    }

    @Test fun theNextSongIsLookedUpAheadOnBothPlatforms() {
        assertTrue("prefetchNextLyrics()" in SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt"), "loadLyricsForCurrentTrack"))
        assertTrue("LyricsStore.prefetch(" in SourceContract.functionBody(SourceContract.read("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt"), "prefetchNextLyrics"))
        assertTrue("liveQueue.upcoming.firstOrNull()?.let(DesktopLyricsClient::prefetch)" in desktopApp, "the desktop's next song")
        assertTrue("LyricsStore.prefetch(" in desktop)
    }

    @Test fun theDesktopKeepsItsAnswersAndForgetsARejectedOne() {
        val lookup = SourceContract.functionBody(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLyricsClient.kt"), "lookup")
        assertTrue("if (only == null) LyricsStore.lyrics(request) else LyricsRepository.lyrics(" in lookup)
        assertTrue("LyricsStore.forget(videoId)" in SourceContract.functionBody(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopLyricsClient.kt"), "invalidate"))
    }
}
