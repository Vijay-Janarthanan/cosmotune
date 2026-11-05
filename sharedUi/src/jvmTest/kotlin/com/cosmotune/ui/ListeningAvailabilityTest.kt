package com.cosmotune.ui

import com.cosmotune.listening.ListeningTrack
import com.cosmotune.ui.listening.desktopTrackAvailable
import java.nio.file.Files
import kotlin.test.*

class ListeningAvailabilityTest {
    @Test fun androidContentGrantsDoNotAppearPlayableOnDesktop() {
        assertFalse(desktopTrackAvailable(ListeningTrack("id", "Song", "Artist", uri = "content://media/external/audio/1")))
        assertTrue(desktopTrackAvailable(ListeningTrack("id", "Song", "Artist")))
    }

    @Test fun portableFileUrisAndPathsRequireExistingFiles() {
        val file = Files.createTempFile("cosmotune-local-", ".mp3").toFile()
        try {
            assertTrue(desktopTrackAvailable(ListeningTrack("id", "Song", "Artist", uri = file.toURI().toString())))
            assertTrue(desktopTrackAvailable(ListeningTrack("id", "Song", "Artist", path = file.path)))
        } finally { file.delete() }
        assertFalse(desktopTrackAvailable(ListeningTrack("id", "Song", "Artist", path = file.path)))
    }
}
