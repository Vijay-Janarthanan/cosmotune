package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.BiniLyrics
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LrcRed
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

    @Test fun biniValidatesEveryHitBeforeSharingItsIsrc() {
        val wrong = BiniLyrics.Hit("Other Song", "Deva", duration = 240, isrc = "WRONG")
        val right = BiniLyrics.Hit("Aadiyile Sedhi", "Deva", duration = 241, isrc = "RIGHT")
        assertEquals(right, BiniLyrics.bestHit(listOf(wrong, right), "Aadiyile Sedhi", "Deva", 240_000))
        assertNull(BiniLyrics.bestHit(listOf(wrong), "Aadiyile Sedhi", "Deva", 240_000))
        assertNull(BiniLyrics.bestHit(listOf(right.copy(duration = 260)), "Aadiyile Sedhi", "Deva", 240_000))
    }

    @Test fun explicitRecordingIdCannotBeReplacedByTheFirstResult() {
        val wrong = BiniLyrics.Hit(isrc = "WRONG")
        val right = BiniLyrics.Hit(isrc = "INABC1234567")
        assertEquals(right, BiniLyrics.bestHit(listOf(wrong, right), "Song", "Artist", 240_000, " inabc1234567 "))
        assertNull(BiniLyrics.bestHit(listOf(wrong), "Song", "Artist", 240_000, "INABC1234567"))
    }

    @Test fun lrcLibRequiresIdentityAndACloseDurationForSearchAndExactResults() {
        fun hit(title: String, duration: Double, text: String) = Json.parseToJsonElement(
            """{"trackName":"$title","artistName":"Deva","duration":$duration,"syncedLyrics":"$text"}""",
        ) as JsonObject
        val wrong = hit("Other song", 240.0, "wrong")
        val edit = hit("Aadiyile Sedhi", 250.0, "different edit")
        val right = hit("Aadiyile Sedhi", 241.0, "right")
        assertEquals("right", LrcLib.bestHit(listOf(wrong, edit, right), "Aadiyile Sedhi", "Deva", 240_000))
        assertNull(LrcLib.bestHit(listOf(wrong, edit), "Aadiyile Sedhi", "Deva", 240_000))
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
