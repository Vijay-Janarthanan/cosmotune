package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import kotlin.test.*

class MusicFeedbackTest {
    private val song = Song("private-id", "Private title", "Private artist", null, durationText = "3:00")

    @Test fun explicitFeedbackSurvivesSourceChangesAndPersistsOnlyBoundedFingerprints() {
        val ledger = ExplicitMusicFeedback()
        assertTrue(ledger.update(song, MusicFeedback.LESS_LIKE_THIS))
        assertFalse(ledger.update(song, MusicFeedback.LESS_LIKE_THIS))
        assertEquals(MusicFeedback.LESS_LIKE_THIS, ledger.value(song.copy(videoId = "another-provider")))
        val bytes = ledger.encode()
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("Private")); assertFalse(text.contains("private-id"))
        assertEquals(MusicFeedback.LESS_LIKE_THIS, ExplicitMusicFeedback.decode(bytes).value(song))
        assertTrue(ledger.update(song, MusicFeedback.CLEAR)); assertNull(ledger.value(song))
        repeat(ExplicitMusicFeedback.LIMIT + 1) { ledger.update(song.copy(title = "Song $it"), MusicFeedback.LIKE) }
        assertTrue(ledger.encode().size < ExplicitMusicFeedback.MAX_BYTES)
        assertNull(ledger.value(song.copy(title = "Song 0")))
        assertEquals(MusicFeedback.LIKE, ExplicitMusicFeedback.decode(ledger.encode()).value(song.copy(title = "Song ${ExplicitMusicFeedback.LIMIT}")))
        assertFailsWith<IllegalArgumentException> { ExplicitMusicFeedback.decode(ByteArray(ExplicitMusicFeedback.MAX_BYTES + 1)) }
    }

    @Test fun explicitLikesAndDislikesPreventDuplicateCompletionAndMeaninglessSkips() {
        val automatic = mutableListOf<Boolean>()
        val playback = PreferencePlaybackSession { _, _, liked -> automatic += liked }
        playback.sample(song, 180_000, 0)
        playback.sample(song, 180_000, 5_000)
        assertFalse(playback.explicitFeedback(song.videoId, true))
        repeat(30) { playback.sample(song, 180_000, 5_000L + (it + 1) * 8_000) }
        playback.skipped(song.videoId)
        assertTrue(automatic.isEmpty())
        assertTrue(playback.explicitFeedback(song.videoId, true))
        assertFalse(playback.explicitFeedback(song.videoId, false))
        val next = song.copy(videoId = "next")
        playback.sample(next, 180_000, 300_000)
        assertFalse(playback.explicitFeedback(song.videoId, true))
        playback.sample(next, 180_000, 305_000)
        playback.skipped(next.videoId)
        assertEquals(listOf(false), automatic)
        assertEquals(65_568, OnlinePreferenceModel().encode().size)
        assertTrue(4_176_439 + OnlinePreferenceModel.STATE_BYTES + ExplicitMusicFeedback.MAX_BYTES < 5_000_000)
    }
}
