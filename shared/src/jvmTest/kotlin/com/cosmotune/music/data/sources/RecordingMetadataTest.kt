package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RecordingMetadataTest {
    @Test fun onlyThePlayedIdCanSupplyMissingAlbumAndRuntime() {
        val target = TrackMatcher.Target("Naa Manasuney", "SPB & Chitra", videoId = "abcdefghijk")
        val song = Song("abcdefghijk", "Naa Manasuney", "S. P. Balasubrahmanyam", null,
            albumName = "Manmadhudu", durationText = "4:06")
        val enriched = RecordingMetadata.merge(target, song)
        assertEquals("Manmadhudu", enriched.album)
        assertEquals(246, enriched.durationSec)
        assertEquals(target.artist, enriched.artist)
        assertEquals(target, RecordingMetadata.merge(target, song.copy(videoId = "anotherId__")))
        assertEquals(target, RecordingMetadata.merge(target, song.copy(title = "Another Song")))
        assertEquals(target.copy(album = "Already Known"), RecordingMetadata.merge(target.copy(album = "Already Known"), song)
            .copy(durationSec = null))
    }

    @Test fun knownMetadataDoesNotNeedAnotherNetworkLookup() = runBlocking {
        val target = TrackMatcher.Target("A Recording", "Artist", 240, "Film", videoId = "abcdefghijk")
        assertEquals(target, RecordingMetadata.enrich(target) { error("unneeded request") })
    }

    @Test fun missingMetadataFailureLeavesTheExistingRecordingUntouched() = runBlocking {
        val target = TrackMatcher.Target("A Recording", "Artist", videoId = "noDetails__")
        assertEquals(target, RecordingMetadata.enrich(target) { throw IllegalStateException("unavailable") })
        assertEquals(target.copy(videoId = "jiosaavn:1"), RecordingMetadata.enrich(target.copy(videoId = "jiosaavn:1")) {
            error("provider IDs must never reach YouTube")
        })
    }
}
