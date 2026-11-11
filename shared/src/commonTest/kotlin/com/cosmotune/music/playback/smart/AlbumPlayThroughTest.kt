package com.cosmotune.music.playback.smart

import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlbumPlayThroughTest {
    private fun track(id: String, album: String? = "MPREb_abc", source: String? = "MPREb_abc", type: PlaybackSourceType? = PlaybackSourceType.BROWSE,
        tier: QueueTier = QueueTier.CONTEXT, number: Int? = null) = Song(
        videoId = id, title = id, artist = "Artist", thumbnailUrl = null, albumId = album, albumName = "Album",
        queueTier = tier, playbackSource = "Album", playbackSourceType = type, playbackSourceId = source, trackNumber = number,
    )

    @Test fun anAlbumPlayedThroughFromItsPageIsGapless() {
        assertTrue(isAlbumPlayThrough(track("a"), track("b"), shuffled = false, nextFollowsDirectly = true))
        // Album page rows often carry no album link of their own; the page is the evidence.
        assertTrue(isAlbumPlayThrough(track("a", album = null), track("b", album = null), false, true))
        assertTrue(isAlbumPlayThrough(track("a", number = 3), track("b", number = 4), false, true))
    }

    @Test fun shuffleManualQueueRadioAndWrapAreMixed() {
        assertFalse(isAlbumPlayThrough(track("a"), track("b"), shuffled = true, nextFollowsDirectly = true))
        assertFalse(isAlbumPlayThrough(track("a"), track("b"), shuffled = false, nextFollowsDirectly = false))
        assertFalse(isAlbumPlayThrough(track("a"), track("b", tier = QueueTier.USER_QUEUE), false, true))
        assertFalse(isAlbumPlayThrough(track("a"), track("b", tier = QueueTier.AUTOPLAY), false, true))
        assertFalse(isAlbumPlayThrough(track("a"), track("a"), false, true))
    }

    @Test fun albumSiblingsOnAPlaylistOrSearchAreMixed() {
        // A playlist page is a browse source too, but not a release.
        assertFalse(isAlbumPlayThrough(track("a", source = "VLPL123"), track("b", source = "VLPL123"), false, true))
        assertFalse(isAlbumPlayThrough(track("a", source = null, type = PlaybackSourceType.SEARCH), track("b", source = null, type = PlaybackSourceType.SEARCH), false, true))
        assertFalse(isAlbumPlayThrough(track("a"), track("b", source = "MPREb_other"), false, true))
        assertFalse(isAlbumPlayThrough(track("a"), track("b", album = "MPREb_other"), false, true))
        assertFalse(isAlbumPlayThrough(track("a", number = 3), track("b", number = 7), false, true))
    }

    @Test fun thePlannerOnlyGoesGaplessWhenToldTheAlbumIsPlayingThrough() {
        val current = TransitionTrackInfo("a", 200_000, "A", "Artist", "Album", "MPREb_abc")
        val next = TransitionTrackInfo("b", 200_000, "B", "Artist", "Album", "MPREb_abc")
        val gapless = planTransition(currentTrack = current, nextTrack = next, currentTime = 199.8, duration = 200.0,
            mode = CrossfadeMode.SMART, albumSequential = true)
        assertEquals(TransitionStyle.GAPLESS, gapless.transitionStyle)
        val mixed = planTransition(currentTrack = current, nextTrack = next, currentTime = 199.8, duration = 200.0,
            mode = CrossfadeMode.SMART, albumSequential = false)
        assertNotEquals(TransitionStyle.GAPLESS, mixed.transitionStyle)
    }
}
