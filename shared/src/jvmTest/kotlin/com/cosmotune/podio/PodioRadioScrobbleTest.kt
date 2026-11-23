package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioRadioScrobbleTest {
    @Test fun aSongCountsWhenTheNextOneComesOn() {
        val scrobble = PodioRadioScrobble()
        assertNull(scrobble.heard("r1", "Artist - First", 0))
        assertNull(scrobble.heard("r1", "Artist - First", 60_000), "the same title again is the same song")
        assertEquals(PodioRadioListen("Artist", "First", 0, 200), scrobble.heard("r1", "Other – Second", 200_000))
        assertNull(scrobble.heard("r1", "Jingle - Station ID", 210_000), "too short to count")
        assertNull(scrobble.heard("r1", "Morning Show with Ann", 300_000), "not a song: no artist and title")
        assertNull(scrobble.stopped(400_000))
    }

    @Test fun anotherStationOrAStopEndsTheSong() {
        val scrobble = PodioRadioScrobble()
        scrobble.heard("r1", "A - One", 0)
        assertNull(scrobble.heard("r2", "B - Two", 100_000), "a song cut off by changing station is not counted")
        assertEquals(PodioRadioListen("B", "Two", 100, 60), scrobble.stopped(160_000))
    }
}
