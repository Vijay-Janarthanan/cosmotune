package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import kotlin.test.*

class OnlinePreferenceModelTest {
    private val favorite = Song("a", "First", "Artist A", null, durationText = "3:00", albumName = "Album A")
    private val skipped = Song("b", "Second", "Artist B", null, durationText = "3:00", albumName = "Album B")

    @Test fun onlineFeedbackLearnsAndGeneralizesWithinTheSizeAndInfluenceBudget() {
        val model = OnlinePreferenceModel()
        assertEquals(0f, model.adjustment(null, favorite, 12, 20_000))
        repeat(100) {
            model.learn(null, favorite, true, 12, 20_000)
            model.learn(null, skipped, false, 12, 20_000)
        }
        val unheard = favorite.copy(title = "Unheard song", videoId = "new")
        val disliked = skipped.copy(title = "Another song", videoId = "new2")
        assertTrue(model.adjustment(null, unheard, 12, 20_000) > model.adjustment(null, disliked, 12, 20_000))
        assertTrue(model.adjustment(null, favorite, 12, 20_000) in -0.15f..0.15f)
        val bytes = model.encode()
        assertEquals(65_568, bytes.size)
        assertTrue(bytes.size + 4_176_439 < 5_000_000)
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Artist A"))
        val restored = OnlinePreferenceModel.decode(bytes)
        assertEquals(model.adjustment(null, favorite, 12, 20_000), restored.adjustment(null, favorite, 12, 20_000))
        restored.setEnabled(false)
        restored.learn(null, favorite, true, 12, 20_000)
        assertEquals(200, restored.observations)
        assertEquals(0f, restored.adjustment(null, favorite, 12, 20_000))
        restored.reset()
        assertEquals(0, restored.observations)
        assertFalse(restored.enabled)
    }

    @Test fun stalePreferencesFadeAndCorruptedOrOversizedStateIsRejected() {
        val model = OnlinePreferenceModel()
        repeat(80) { model.learn(null, favorite, true, 4, 20_000) }
        val recent = model.adjustment(null, favorite, 4, 20_000)
        val old = model.adjustment(null, favorite, 4, 21_000)
        assertTrue(old < recent)
        val bytes = model.encode()
        bytes[100] = (bytes[100].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { OnlinePreferenceModel.decode(bytes) }
        assertFailsWith<IllegalArgumentException> { OnlinePreferenceModel.decode(ByteArray(1_000_000)) }
    }

    @Test fun pausesDoNotBecomeSkipsOrListeningAndLongGapsCannotProduceCompletions() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val session = PreferencePlaybackSession { _, song, liked -> events += song.videoId to liked }
        session.sample(favorite, 180_000, 0)
        session.sample(favorite, 180_000, 5_000)
        session.pause()
        session.sample(favorite, 180_000, 1_000_000)
        assertTrue(events.isEmpty())
        session.skipped("stale-track")
        assertTrue(events.isEmpty())
        session.skipped(favorite.videoId)
        session.sample(skipped, 180_000, 1_000_001)
        assertEquals(listOf("a" to false), events)
        session.sample(skipped, 180_000, 10_000_000)
        assertEquals(1, events.size)
        repeat(18) { session.sample(skipped, 180_000, 10_000_000L + (it + 1) * 8_000) }
        assertEquals(listOf("a" to false, "b" to true), events)
        session.sample(skipped, 180_000, 20_000_000)
        assertEquals(2, events.size)
    }

    @Test fun sourceFailuresAndAutomaticTrackChangesNeverTrainDislikes() {
        val events = mutableListOf<Boolean>()
        val session = PreferencePlaybackSession { _, _, liked -> events += liked }
        session.sample(favorite, 180_000, 0)
        session.sample(favorite, 180_000, 5_000)
        session.pause()
        session.sample(skipped, 180_000, 5_001)
        assertTrue(events.isEmpty())
    }

    @Test fun podcastsAndRadioCannotTrainMusicPreferencesOrCountTheirGapAsMusic() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val session = PreferencePlaybackSession { _, song, liked -> events += song.videoId to liked }
        session.sample(favorite, 180_000, 0)
        session.sample(favorite, 180_000, 5_000)
        for (kind in listOf("radio", "episode")) repeat(50) {
            session.sample(favorite.copy(videoId = "podio:$kind:one"), 180_000, 10_000L + it * 8_000)
        }
        session.skipped("podio:episode:one")
        session.sample(favorite, 180_000, 1_000_000)
        assertTrue(events.isEmpty())
    }
}
