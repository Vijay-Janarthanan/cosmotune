package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.LrcLib
import com.cosmotune.music.data.lyrics.LrcRed
import com.cosmotune.music.data.lyrics.uploadCredits
import com.cosmotune.music.data.lyrics.LyricsRepository
import com.cosmotune.music.data.lyrics.LyricsSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RecordingMatchTest {
    private fun accepts(title: String, candidate: String, duration: Long? = 240_000) =
        RecordingMatch.accepts(title, "Deva", 240_000, candidate, "Deva", duration)

    @Test fun differentSongBySameComposerIsNotTheSameRecording() {
        assertFalse(accepts("Aadiyile Sedhi", "Telugu Song"))
        assertFalse(accepts("Song (Tamil)", "Song (Telugu)"))
        assertTrue(accepts("Aadiyile Sedhi (From \"Film\")", "Aadiyile Sedhi"))
    }

    @Test fun titleScriptDoesNotDetermineTheLanguageOfItsLyrics() {
        assertTrue(accepts("Aadiyile Sedhi", "Aadiyile Sedhi"))
        assertTrue(accepts("தமிழ் பாடல்", "தமிழ் பாடல்"))
        // Tamil vowel signs distinguish these names and cannot be stripped as accents.
        assertFalse(accepts("படம்", "பாடம்"))
        assertFalse(accepts("కలం", "కాలం"))
    }

    @Test fun versionsSurviveQueryCleaning() {
        assertFalse(accepts("Song (Remix)", "Song"))
        assertFalse(accepts("Song [Live]", "Song"))
        assertFalse(accepts("Song (Sped Up)", "Song (Slowed)"))
        assertTrue(accepts("Song (Official Video)", "Song"))
        assertTrue(accepts("Song (Lyrical)", "Song"))
        assertTrue(accepts("Song (Remix)", "Song - Remix"))
    }

    @Test fun durationCannotSelectAnUnrelatedEdit() {
        assertTrue(accepts("Song", "Song", 243_000))
        assertFalse(accepts("Song", "Song", 243_001))
        assertFalse(accepts("Song", "Song", null))
        assertFalse(RecordingMatch.accepts("Song", "Deva", 240_000, "Song", "Other Artist", 240_000))
        assertTrue(RecordingMatch.accepts("Song", "Deva & Singer", 240_000, "Song", "Deva", 240_000))
    }

    @Test fun lrcLibRequiresIdentityAndACloseDurationForSearchAndExactResults() {
        fun hit(title: String, duration: Double, text: String) = Json.parseToJsonElement(
            """{"trackName":"$title","artistName":"Deva","duration":$duration,"syncedLyrics":"$text"}""",
        ) as JsonObject
        val wrong = hit("Other song", 240.0, "wrong")
        val edit = hit("Aadiyile Sedhi", 250.0, "different edit")
        val right = hit("Aadiyile Sedhi", 241.0, "right")
        assertEquals(LrcLib.Hit("right", timed = true), LrcLib.bestHit(listOf(wrong, edit, right), "Aadiyile Sedhi", "Deva", 240_000))
        // Only the length differs: the words, never the timing that belongs to the other edit.
        assertEquals(LrcLib.Hit("different edit", timed = false), LrcLib.bestHit(listOf(wrong, edit), "Aadiyile Sedhi", "Deva", 240_000))
        assertNull(LrcLib.bestHit(listOf(wrong), "Aadiyile Sedhi", "Deva", 240_000))
    }

    @Test fun anUploadsPipedCreditsCountAsItsArtists() {
        val upload = "Rathinamo - Official Music Video | Saurav Srisan | Tanisha Gnanavel | Vidhu, Yogalakshmi |TamilIndie"
        val credits = upload.uploadCredits()
        assertEquals(listOf("Saurav Srisan", "Tanisha Gnanavel", "Vidhu, Yogalakshmi", "TamilIndie"), credits)
        // The channel is the second singer; the catalogue files the song under the first. Without the credits it is refused.
        assertFalse(RecordingMatch.accepts("Rathinamo", "Tanisha Gnanavel", 191_000, "Rathinamo", "Saurav Srisan", 190_000))
        assertTrue(RecordingMatch.accepts("Rathinamo", "Tanisha Gnanavel", 191_000, "Rathinamo", "Saurav Srisan", 190_000, credits))
        assertTrue(RecordingMatch.accepts("Rathinamo", "Tanisha Gnanavel", 191_000, "Rathinamo", "Yogalakshmi", 190_000, credits))
        assertFalse(RecordingMatch.accepts("Rathinamo", "Tanisha Gnanavel", 191_000, "Rathinamo", "Someone Else", 190_000, credits))
        assertEquals(emptyList(), "Blinding Lights (Official Video)".uploadCredits())
    }

    @Test fun onlyTheLengthDifferingIsItsOwnVerdict() {
        assertEquals(RecordingMatch.Verdict.SAME, RecordingMatch.judge("Song", "Deva", 240_000, "Song", "Deva", 242_000))
        assertEquals(RecordingMatch.Verdict.OTHER_LENGTH, RecordingMatch.judge("Song", "Deva", 240_000, "Song", "Deva", 250_000))
        assertEquals(RecordingMatch.Verdict.NO, RecordingMatch.judge("Song", "Deva", 240_000, "Song", "Deva", null))
        assertEquals(RecordingMatch.Verdict.NO, RecordingMatch.judge("Song", "Deva", 240_000, "Song (Remix)", "Deva", 250_000))
        assertEquals(RecordingMatch.Verdict.NO, RecordingMatch.judge("Song", "Deva", 240_000, "Song", "Other", 250_000))
    }

    @Test fun lrcLibsWordsWithoutTheirTimingAreUntimedLines() {
        val lines = LrcLib.untimed("[00:12.00] First line\n[00:15.00]\n[00:18.00] Second line")
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == 0L })
        val plain = LrcLib.untimed("Verse one\nline two\n\n\nVerse two")
        assertEquals(listOf("Verse one", "line two", "", "Verse two"), plain.map { it.text })
        assertTrue(plain.all { it.timeMs == 0L })
        assertTrue(LyricsRepository.untimedForAnotherLength(LyricsSource.LRCLIB, plain))
        assertFalse(LyricsRepository.untimedForAnotherLength(LyricsSource.LRCLIB, LrcLib.parseLrc("[00:12.00] One\n[00:15.00] Two")))
        assertFalse(LyricsRepository.untimedForAnotherLength(LyricsSource.YOUTUBE_MUSIC, plain))
    }

    @Test fun lrcRedDistinguishesExplicitDubbedVersionsAndIndicVowels() {
        fun hit(title: String) = LrcRed.Hit("INS181303031", title, "Deva", duration = 240.0)
        assertNull(LrcRed.best(listOf(hit("Song (Telugu)")), "Song (Tamil)", "Deva", 240_000))
        assertNull(LrcRed.best(listOf(hit("பாடம்")), "படம்", "Deva", 240_000))
        assertEquals("INS181303031", LrcRed.best(listOf(hit("Song (Tamil)")), "Song (Tamil)", "Deva", 240_000)?.isrc)
        assertNull(LrcRed.best(listOf(hit("Song").copy(duration = null)), "Song", "Deva", 240_000))
    }

    @Test fun lrcRedRetainsRemasterAndSoundtrackMatchingPolicy() {
        val remaster = LrcRed.Hit("GBUM71029604", "Bohemian Rhapsody (Remastered 2011)", "Queen", duration = 356.52)
        assertEquals(remaster, LrcRed.best(listOf(remaster), "Bohemian Rhapsody", "Queen", 355_000))
        val film = LrcRed.Hit("INS181303031", "Tum Hi Ho (From \"Aashiqui 2\")", "Arijit Singh", duration = 261.974)
        assertEquals(film, LrcRed.best(listOf(film), "Tum Hi Ho", "Mithoon, Arijit Singh", 262_000))
    }
}
