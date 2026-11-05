package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.Song
import kotlin.test.*

class CatalogueTitleVariantsTest {
    private fun song(title: String, artist: String = "YOASOBI") = Song("fixture0001", title, artist, null, durationText = "4:20", albumName = "The Book")

    @Test fun wholeTranslatedTitlesMatchBothWaysIncludingShortCjkTitles() {
        assertTrue(TrackMatcher.matches(song("Racing Into The Night"), "夜に駆ける (Racing Into The Night)", "YOASOBI", 260))
        assertTrue(TrackMatcher.matches(song("夜に駆ける"), "Racing Into The Night (夜に駆ける)", "YOASOBI", 260))
        assertTrue(TrackMatcher.matches(song("Love"), "愛 (Love)", "YOASOBI", 260))
        assertTrue(JioSaavnMatcher.sameRecording(TrackMatcher.Target("愛 (Love)", "YOASOBI", 260, "The Book"), song("Love")))
        val target = TrackMatcher.Target("夜に駆ける (Racing Into The Night)", "YOASOBI", 260, "The Book")
        assertTrue("racing into the night" in TrackMatcher.queries(target))
        assertTrue("racing into the night" in JioSaavnMatcher.queries(target))
        assertNotNull(TrackMatcher.bestOfficialAudioForVideo(listOf(song("Racing Into The Night")), target.copy(isVideo = true)))
    }

    @Test fun translationEvidenceCannotBorrowAFilmNameOrOnlyPartOfATitle() {
        assertFalse(TrackMatcher.matches(song("夜に駆ける"), "Another Song (From 夜に駆ける)", "YOASOBI", 260))
        assertFalse(TrackMatcher.matches(song("Night"), "夜に駆ける (Racing Into The Night)", "YOASOBI", 260))
        assertFalse(TrackMatcher.matches(song("Other Name"), "Another Song (Other Name)", "YOASOBI", 260))
        assertFalse(TrackMatcher.matches(song("Racing Into The Night (Live)"), "夜に駆ける (Racing Into The Night)", "YOASOBI", 260))
        assertFalse(TrackMatcher.matches(song("Racing Into The Night", "Another Artist"), "夜に駆ける (Racing Into The Night)", "YOASOBI"))
    }

    @Test fun portugueseGuestCreditsDoNotBecomeRecordingParts() {
        assertTrue(TrackMatcher.matches(song("O Mundo É Mal", "Djonga"), "O Mundo É Mal (part. Clara Lima)", "Djonga", 260))
        assertFalse(TrackMatcher.matches(song("O Mundo É Mal", "Djonga"), "O Mundo É Mal (part. Clara Lima) (Live)", "Djonga", 260))
        assertEquals("part of me", TrackMatcher.searchableTitle("Part of Me"))
        assertEquals("part 2", TrackMatcher.searchableTitle("Part. 2"))
        assertEquals(listOf("song"), TrackMatcher.queries(TrackMatcher.Target("Song", "Música")))
    }

    @Test fun indicVowelSignsAndProtectedArtistNamesRetainIdentity() {
        assertFalse(TrackMatcher.matches(song("கலை", "Singer"), "காலை", "Singer", 260))
        assertFalse(TrackMatcher.matches(song("कला", "Singer"), "काला", "Singer", 260))
        assertTrue(TrackMatcher.sharesArtist("AC/DC", "AC/DC"))
        assertFalse(TrackMatcher.sharesArtist("AC/DC", "AC"))
    }
}
