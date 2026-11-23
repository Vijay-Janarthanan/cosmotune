package com.cosmotune.music

import com.cosmotune.music.playback.MusicLink
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** A file opened with Cosmotune from another app is Music's only when it is a song. */
class OpenedAudioFileTest {
    @Test fun theTypeDecidesWhenTheOpeningAppGivesOne() {
        assertTrue(MusicLink.isAudioFile("audio/mpeg", "anything.bin"))
        assertTrue(MusicLink.isAudioFile("AUDIO/FLAC", null))
        assertFalse(MusicLink.isAudioFile("video/mp4", "song.mp3"))
    }

    @Test fun withoutATypeTheNameDecides() {
        assertTrue(MusicLink.isAudioFile(null, "Track 01.FLAC"))
        assertTrue(MusicLink.isAudioFile(null, "voice.opus"))
        assertFalse(MusicLink.isAudioFile(null, "clip.mp4"))
        assertFalse(MusicLink.isAudioFile(null, null))
    }
}
