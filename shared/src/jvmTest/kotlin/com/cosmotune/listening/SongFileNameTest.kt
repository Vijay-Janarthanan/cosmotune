package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals

class SongFileNameTest {
    @Test fun aSharedFileHasAReadableSafeName() {
        assertEquals("Cosmotune - The Singer - Night Drive.flac", SongFileName.of("The Singer", "Night: Drive", "FLAC"))
        assertEquals("Cosmotune - Song.audio", SongFileName.of(" ", "/", ""))
        assertEquals("opus", SongFileName.extension("track.opus", "audio/ogg"))
        assertEquals("mp3", SongFileName.extension("1234", "audio/mpeg"))
        assertEquals("audio", SongFileName.extension(null, null))
    }
}
