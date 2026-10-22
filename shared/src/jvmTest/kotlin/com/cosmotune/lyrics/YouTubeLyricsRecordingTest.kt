package com.cosmotune.lyrics

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class YouTubeLyricsRecordingTest {
    // Metadata from the live Aadiyile Sedhi reproduction; no lyric body is stored in this fixture.
    private val videoId = "0-MbukW3ih4"
    private val audio = Song("AbAhag-m4yA", "Adiyile Sethi Solli", "Chitra", null,
        durationText = "4:59", albumId = "MPREb_Bs2qyi8Wg4J")
    private val linked = audio.copy(videoId = videoId)

    @Test fun exactVideoLinkVerifiesDifferentSpellingAndSingerCredit() = runTest {
        assertEquals(audio, YouTubeLyricsRecording.counterpart(videoId, listOf(audio)) { listOf(linked) })
    }

    @Test fun searchTitleAndDurationWithoutVideoLinkCannotVerifyRecording() = runTest {
        assertNull(YouTubeLyricsRecording.counterpart(videoId, listOf(audio)) { listOf(audio) })
    }

    @Test fun linkUnderDifferentTrackMetadataCannotVerifyCandidate() = runTest {
        for (wrong in listOf(linked.copy(title = "Different song"), linked.copy(artist = "Other artist"), linked.copy(durationText = "5:30"))) {
            assertNull(YouTubeLyricsRecording.counterpart(videoId, listOf(audio)) { listOf(wrong) })
        }
    }

    @Test fun uploadsAndRowsWithoutAnAlbumAreNotCatalogueCandidates() = runTest {
        assertNull(YouTubeLyricsRecording.counterpart(videoId, listOf(audio.copy(isVideo = true), audio.copy(albumId = null))) {
            fail("Ineligible candidates must not trigger album requests")
        })
    }

    @Test fun eachReleaseIsFetchedOnceAndLookupIsBounded() = runTest {
        var calls = 0
        val candidates = listOf(audio, audio.copy(videoId = "audio000002")) +
            (1..8).map { audio.copy(videoId = "audio00000$it", albumId = "MPREb_$it") }
        assertNull(YouTubeLyricsRecording.counterpart(videoId, candidates) { calls++; emptyList() })
        assertEquals(3, calls)
    }

    @Test fun failedAlbumCanFallThroughButCancellationPropagates() = runTest {
        val second = audio.copy(videoId = "audio000002", albumId = "MPREb_other")
        assertEquals(second, YouTubeLyricsRecording.counterpart(videoId, listOf(audio, second)) {
            if (it == audio.albumId) error("Temporary failure") else listOf(linked)
        })
        assertFailsWith<CancellationException> {
            YouTubeLyricsRecording.counterpart(videoId, listOf(audio)) { throw CancellationException() }
        }
    }

    private fun row(id: String) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},"flexColumns":[
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Adiyile Sethi Solli"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Chitra • 4:59"}]}}}]}}"""
    private fun album(contents: String) = Json.parseToJsonElement("""{
        "header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"En Aasai Machan"}]}}},
        "contents":[$contents]}""")

    @Test fun recommendationLinksNeverCountAsAlbumTrackEvidence() {
        val page = album("""{"musicShelfRenderer":{"contents":[${row("other000001")}] }},
            {"musicCarouselShelfRenderer":{"contents":[${row(videoId)}]}}""")
        assertEquals(listOf("other000001"), YouTubeLyricsRecording.albumTracks(page).map { it.videoId })
    }

    @Test fun primaryAlbumTracksRetainMetadataAndMalformedPagesAreIgnored() {
        val songs = YouTubeLyricsRecording.albumTracks(album("""{"musicShelfRenderer":{"contents":[${row(videoId)}]}}"""))
        assertEquals(videoId, songs.single().videoId)
        assertEquals(audio.title, songs.single().title)
        assertEquals(audio.artist, songs.single().artist)
        assertEquals(audio.durationText, songs.single().durationText)
        assertTrue(YouTubeLyricsRecording.albumTracks(Json.parseToJsonElement("""{"musicShelfRenderer":{"contents":[${row(videoId)}]}}""")).isEmpty())
    }
}
