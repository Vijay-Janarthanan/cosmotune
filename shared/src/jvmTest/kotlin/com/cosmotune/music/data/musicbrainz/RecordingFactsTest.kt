package com.cosmotune.music.data.musicbrainz

import com.cosmotune.listening.TrackIdentifiers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingFactsTest {
    private val rec = "5b11f4ce-a62d-471e-81fc-a69a8278c7da"
    private val asked = mutableListOf<String>()
    private val recording = """{"id":"$rec","title":"Night Drive","length":252000,"first-release-date":"2019-01-10",
        "isrcs":["INS181900123"],"genres":[{"name":"film soundtrack","count":2}],"tags":[{"name":"tamil","count":3}]}"""
    private val search = """{"recordings":[
        {"id":"11111111-1111-4111-8111-111111111111","score":100,"title":"Night Drive (Remix)","length":200000,"artist-credit":[{"name":"Someone Else"}]},
        {"id":"$rec","score":96,"title":"Night Drive","length":251000,"artist-credit":[{"name":"The Singer"},{"name":"A Friend"}]}]}"""

    @BeforeTest fun setUp() {
        RecordingFactsLookup.clearForTests()
        RecordingFactsLookup.fetch = { url ->
            asked += url
            when {
                "/recording/$rec" in url -> recording
                "/isrc/" in url -> """{"recordings":[{"id":"$rec","title":"Night Drive"}]}"""
                "/recording?" in url -> search
                else -> null
            }
        }
    }

    @AfterTest fun tearDown() { RecordingFactsLookup.clearForTests() }

    @Test fun aRecordingAnswerGivesTheFirstReleaseIsrcAndGenres() {
        val facts = RecordingFactsLookup.parseRecording(recording)!!
        assertEquals("2019-01-10", facts.firstRelease)
        assertEquals(listOf("INS181900123"), facts.isrcs)
        assertEquals(listOf("Film soundtrack"), facts.genres)
        val tagged = RecordingFactsLookup.parseRecording("""{"id":"$rec","tags":[{"name":"pop","count":1},{"name":"rock","count":4},{"name":"x","count":0}]}""")!!
        assertEquals(listOf("Rock", "Pop"), tagged.genres, "without genres, the most-voted tags")
    }

    @Test fun theFilesOwnRecordingIdIsAskedForDirectly() = runTest {
        val facts = RecordingFactsLookup.lookup("Anything", "Anyone", null, TrackIdentifiers(recordingId = rec))
        assertEquals(rec, facts?.recordingId)
        assertTrue(facts!!.fromTags)
        assertEquals(1, asked.size, "no search")
    }

    @Test fun anIsrcFromTheTagsGoesThroughTheIsrcIndex() = runTest {
        val facts = RecordingFactsLookup.lookup("Night Drive", "The Singer", null, TrackIdentifiers(isrc = "INS181900123"))
        assertEquals(rec, facts?.recordingId)
        assertTrue(facts!!.fromTags)
        assertTrue(asked.first().contains("/isrc/INS181900123"))
    }

    @Test fun withoutIdsOnlyACloseMatchOnTitleArtistAndLengthIsTaken() = runTest {
        val facts = RecordingFactsLookup.lookup("Night Drive (Official Video)", "The Singer", 252_000, TrackIdentifiers())
        assertEquals(rec, facts?.recordingId, "the remix by someone else is passed over")
        assertEquals(false, facts?.fromTags)
        asked.clear()
        assertNull(RecordingFactsLookup.lookup("Night Drive", "Nobody Here", 252_000, TrackIdentifiers()), "no artist match, no facts")
    }

    @Test fun lengthsAndTitlesAreTidied() {
        assertEquals(252_000L, RecordingFactsLookup.durationMs("4:12"))
        assertEquals(3_723_000L, RecordingFactsLookup.durationMs("1:02:03"))
        assertNull(RecordingFactsLookup.durationMs("live"))
        assertEquals("Night Drive", RecordingFactsLookup.clean("Night Drive [Lyric Video] | Audio"))
    }
}
