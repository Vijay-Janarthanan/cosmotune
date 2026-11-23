package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search for another copy of a song whose own upload is gone. The fixtures are the catalogue answers seen for a
 * real removed upload: a compilation's row credited to one singer, while every catalogue files the film recording
 * under the two singers who sang it, sometimes with the film or an extra word in the title.
 */
class StandInMatcherTest {
    private fun row(id: String, title: String, artist: String, duration: String?, album: String? = null, artists: List<String> = emptyList()) =
        Song(id, title, artist, null, durationText = duration, albumName = album, artists = artists.map { ArtistRef(it) })

    private fun upload(id: String, title: String, channel: String, duration: String?) =
        Song(id, title, channel, null, durationText = duration, isVideo = true)

    /** The removed row: a compilation credits the song to one singer. */
    private val gone = TrackMatcher.Target("Kaala Megam Megam", "R. K. Sundaramoorthy", 306, "Maalai Neram Melodies", videoId = "dead0000000")

    /** What the catalogue answered for "kaala megam megam", in its order. */
    private val catalogue = listOf(
        row("s1", "O Megam Megam", "Harikumar", "4:25", "Kaadhalar"),
        row("s2", "Kaala Megam", "Ravi, M. S. Lata", "5:09", "Thozhilaali"),
        row("s3", "Kaala Megam Megam", "Ravi, M.S.Lata", "4:18", "Uzhavan Magan (Original Motion Pictures Soundtrack)"),
        row("s4", "Kaala Megam (From &quot;Thozhilaali&quot;)", "Ravi, M. S. Latha", "5:09", "Evergreen Songs of Ravi - M. S. Latha"),
        row("s5", "Kaala Megam Megam (From &quot;Thozhilaali&quot;)", "Ravi, M. S. Latha", "5:05", "Thalaivar Hits"),
        row("s6", "Kaala Kili", "Isaivendhan", "5:03", "Oru Ooril"),
        row("s7", "Megam Kaala Megam Paartha", "Chandran, Vaasan, R. K. Sundaramoorthy, S. Kamala", "4:11", "Marumagan"),
    )

    @Test fun theFilmRecordingStandsInForTheRemovedCompilationRow() {
        // Same title once the film is taken off, a second apart: the same master under a fuller credit.
        assertEquals(listOf("s5"), StandInMatcher.rankedCatalogue(catalogue, gone).map { it.videoId })
    }

    @Test fun aLongerTitleWithTheSameSingerAndLengthIsTheSameSong() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi, M. S. Latha", 306)
        val longer = row("k1", "Kaala Megam Megam Vaanam", "Ravi, M. S. Latha", "5:10", "Thozhilaali")
        val film = row("k2", "Kaala Megam Megam Vaanam (From \"Thozhilaali\")", "Ravi", "5:13")
        assertEquals(listOf("k1"), StandInMatcher.rankedCatalogue(listOf(longer), target).map { it.videoId })
        // An old film song may run a few seconds longer on a reissue: up to eight.
        assertEquals(listOf("k2"), StandInMatcher.rankedCatalogue(listOf(film), target).map { it.videoId })
    }

    @Test fun aSongSharingThePrefixIsNeverTheSong() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi, M. S. Latha", 306)
        // Another singer's drama song with the very same length.
        val drama = row("d1", "Kaala Megam Megam Vaanam", "Village Drama Studioz", "5:06")
        // Another film's song of the same name and singers, a minute shorter.
        val otherFilm = row("d2", "Kaala Megam Megam", "Ravi, M.S.Lata", "4:18", "Uzhavan Magan")
        // Three words more is another title, whoever sings it.
        val tooLong = row("d3", "Kaala Megam Megam Vaanam Paadum Paattu", "Ravi, M. S. Latha", "5:06")
        // A second singer alone is not the song: the other-language version shares one voice.
        val target2 = TrackMatcher.Target("Kolam", "Mohan Prakash & Sujana", 267, "Vettai")
        val otherLanguage = row("d4", "Kolam Ola", "Velan, Sujana Mohan, Harish Kumar", "4:22", "Vettai")
        // A one-word title does not stretch.
        val target3 = TrackMatcher.Target("Nila", "A. V. Raman", 300)
        val oneWord = row("d5", "Nila Vaname", "A. V. Raman", "5:00")
        assertTrue(StandInMatcher.rankedCatalogue(listOf(drama, otherFilm, tooLong), target).isEmpty())
        assertTrue(StandInMatcher.rankedCatalogue(listOf(otherLanguage), target2).isEmpty())
        assertTrue(StandInMatcher.rankedCatalogue(listOf(oneWord), target3).isEmpty())
    }

    @Test fun aDifferentCreditNeedsTheExactTitleAndLength() {
        // Exact title, other singers, three seconds out: not enough without a shared name.
        val off = row("c1", "Kaala Megam Megam", "Ravi, M. S. Latha", "5:09")
        // A longer title with other singers is refused even when the length agrees.
        val longer = row("c2", "Kaala Megam Megam Vaanam", "Ravi, M. S. Latha", "5:06")
        assertTrue(StandInMatcher.rankedCatalogue(listOf(off, longer), gone).isEmpty())
    }

    @Test fun takesAreNeverStandIns() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi, M. S. Latha", 306)
        val rows = listOf(
            row("t1", "Kaala Megam Megam (Remix)", "Ravi, M. S. Latha", "5:06"),
            row("t2", "Kaala Megam Megam - Karaoke", "Ravi, M. S. Latha", "5:06"),
            row("t3", "Kaala Megam Megam (Slowed + Reverb)", "Ravi, M. S. Latha", "5:06"),
            row("t4", "Kaala Megam Megam (Cover)", "Ravi, M. S. Latha", "5:06"),
            row("t5", "Kaala Megam Megam 8D", "Ravi, M. S. Latha", "5:06"),
            row("t6", "Kaala Megam Megam (Lyrics)", "Ravi, M. S. Latha", "5:06"),
        )
        assertTrue(StandInMatcher.rankedCatalogue(rows, target).isEmpty())
    }

    @Test fun aRowWithoutALengthCannotBeChecked() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi, M. S. Latha", 306)
        assertTrue(StandInMatcher.rankedCatalogue(listOf(row("n1", "Kaala Megam Megam", "Ravi", null)), target).isEmpty())
        assertTrue(StandInMatcher.rankedCatalogue(listOf(row("n2", "Kaala Megam Megam", "Ravi", "5:06")), target.copy(durationSec = null)).isEmpty())
    }

    @Test fun twoDifferentLongerTitlesAreAmbiguous() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi, M. S. Latha", 306)
        val a = row("a1", "Kaala Megam Megam Vaanam", "Ravi, M. S. Latha", "5:06")
        val b = row("a2", "Kaala Megam Megam Penne", "Ravi, M. S. Latha", "5:07")
        assertTrue(StandInMatcher.rankedCatalogue(listOf(a, b), target).isEmpty())
        // The exact title settles it.
        val exact = row("a3", "Kaala Megam Megam", "Ravi", "5:06")
        assertEquals(listOf("a3"), StandInMatcher.rankedCatalogue(listOf(a, b, exact), target).map { it.videoId })
    }

    @Test fun creditsFromTheStructuredListCount() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "M. S. Latha", 306)
        val listed = row("l1", "Kaala Megam Megam Vaanam", "Ravi", "5:08", artists = listOf("Ravi", "M. S. Latha"))
        assertEquals(listOf("l1"), StandInMatcher.rankedCatalogue(listOf(listed), target).map { it.videoId })
    }

    @Test fun theRemovedUploadItselfIsNeverItsOwnStandIn() {
        val target = TrackMatcher.Target("Kaala Megam Megam", "Ravi", 306, videoId = "dead0000000")
        assertTrue(StandInMatcher.rankedCatalogue(listOf(row("dead0000000", "Kaala Megam Megam", "Ravi", "5:06")), target).isEmpty())
        assertTrue(StandInMatcher.rankedUploads(listOf(upload("dead0000000", "Kaala Megam Megam Video Song | Ravi", "Ravi", "5:06")), target).isEmpty())
    }

    // ── Other uploads ──────────────────────────────────────────────────────

    private val film = TrackMatcher.Target("Kaala Megam Megam (From \"Thozhilaali\")", "Ravi, M. S. Latha", 306)

    private val uploads = listOf(
        upload("v1", "Kaala Megam Megam Video Song HD | Thozhilaali Tamil Movie Songs | Kumaresan | Nila | Isaivendhan", "Lotus Music", "4:56"),
        upload("v2", "Thozhilaali Tamil Movie Songs | Kaala Megam Video Song with lyrics | Kumaresan | Nila", "Music Master | Tamil Songs", "5:24"),
        upload("v3", "Uzhavan Magan Tamil Movie Songs | Kaala Megam Megam Video Song | MS Latha | Ravi | Silpi", "IndiaTamilClips", "4:19"),
        upload("v4", "Kaala Megam Megam Vaanam Video Song | Thozhilaali Movie Songs | Kumaresan | Isaivendhan | Lotus Music", "Lotus Music", "5:09"),
        upload("v5", "Kaala Megam Megam vaanam | Jpm Drama Song 2022 | #villagedrama #drama #dramasongstatus", "Village Drama Studioz", "5:06"),
        upload("v6", "Kaala Megam Megam Vaanam 8D Song | Thozhilaali | Use Headphones", "8D Tamil", "5:06"),
        upload("v7", "Kaala Megam Megam Vaanam Slowed Reverb | Thozhilaali", "Lofi Tamil", "5:06"),
        upload("v8", "Kaala Megam Megam Vaanam Karaoke | Thozhilaali", "Sing Along Tamil", "5:07"),
        upload("v9", "Kaala Megam Megam Vaanam Cover | Thozhilaali | Ravi", "Some Singer", "5:06"),
        upload("v10", "Kaala Megam Megam Vaanam Remix | Thozhilaali", "DJ Tamil", "5:06"),
    )

    @Test fun anotherUploadNamingTheFilmPlays() {
        // The film's label upload, three seconds longer; the drama song of the same length has nothing to vouch for it.
        assertEquals(listOf("v4"), StandInMatcher.rankedUploads(uploads, film).map { it.videoId })
    }

    @Test fun anUploadNeedsTheArtistOrTheFilm() {
        // No film known and none of the uploads name the singer the removed row credits.
        assertTrue(StandInMatcher.rankedUploads(uploads, gone).isEmpty())
        // The singer's name on the upload is enough.
        val named = upload("w1", "Kaala Megam Megam | R. K. Sundaramoorthy | Tamil Hits", "Tamil Melody Music", "5:08")
        assertEquals(listOf("w1"), StandInMatcher.rankedUploads(listOf(named), gone).map { it.videoId })
    }

    @Test fun labelAndArtistChannelsComeFirst() {
        val fan = upload("x1", "Kaala Megam Megam Vaanam Video Song | Thozhilaali", "Kumaresan Fan Club", "5:06")
        val label = upload("x2", "Kaala Megam Megam Vaanam Video Song | Thozhilaali", "Lotus Music", "5:09")
        assertEquals(listOf("x2", "x1"), StandInMatcher.rankedUploads(listOf(fan, label), film).map { it.videoId })
    }

    @Test fun uploadQueriesAskForTheSongAndTheFilm() {
        assertEquals(
            listOf("kaala megam megam ravi", "kaala megam megam thozhilaali video song"),
            StandInMatcher.uploadQueries(film),
        )
        assertEquals(
            listOf("kaala megam megam r. k. sundaramoorthy", "kaala megam megam video song"),
            StandInMatcher.uploadQueries(gone),
        )
    }
}
