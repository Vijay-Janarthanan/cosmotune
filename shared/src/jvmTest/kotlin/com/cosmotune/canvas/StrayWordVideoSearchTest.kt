package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.stream.MusicVideoQuery
import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A catalogue tag with a stray last word: the song "En Iniya Pon Nilave" is tagged "En Iniya Pon Nilave Pon". The search pages
 * answered (they are lenient), but every upload names the song without the stray word, so none counted as naming it and the
 * lookup ended with "no candidate video", without a word in the log. The results below are the ones youtube.com gave for the
 * lookup's own queries (2026-11-23), trimmed to what the matcher reads.
 */
class StrayWordVideoSearchTest {
    private val title = "En Iniya Pon Nilave Pon"
    private val artist = "K.J.Yesudas"

    private class Row(val id: String, val title: String, val channel: String, val seconds: Int, val verified: Boolean = false)

    private val answered = listOf(
        Row("G7LPa_rkoP0", "En Iniya Pon Nilaave | Remastered | Ilayaraja | Moodupani | K J Yesudas", "Music Wave", 254),
        Row("0VKFKi2In0w", "En Iniya Pon Nilave 4K Video Song 5 .1| என் இனிய | K. J. Yesudas | Evergreen 80's Love Melody Song", "Sri Annai 4K Tamil Hit Songs", 247, verified = true),
        Row("aRK4Dh2IelY", "En Iniya Pon Nilave", "K.J. Yesudas - Topic", 254),
        Row("Fkibbep8AtA", "En Iniya Pon Nilave – Moodu Pani 4K 60FPS HD Video Song | K. J. Yesudas | Ilaiyaraaja", "RJS Audios", 239),
        Row("fUrwo61b3Dg", "En Iniya Pon Nilave song with Lyrics | Moodu Pani | Ilaiyaraaja Hits | K J Yesudas Hits", "Saregama Tamil", 279, verified = true),
        Row("WzVkyBbRSaM", "\"En Iniya Pon Nilave\" - The Hidden Madness in Ilaiyaraaja's Masterpiece | Oru Naal Podhuma 186", "Tamil Nostalgia", 735),
        Row("narbW-5alQc", "Yen Iniya Pon Nilave | K J Yesudas Live Performance", "WTF tube !!", 236),
        Row("pdY-CjCK6mI", "En Iniya Pon Nilave | Moodu Pani | K. J. Yesudas | Ilaiyaraaja", "Saregama Tamil", 188, verified = true),
    )

    private fun page(rows: List<Row>): String = """{"contents":[""" + rows.joinToString(",") { row ->
        val badge = if (row.verified) "BADGE_STYLE_TYPE_VERIFIED" else "BADGE_STYLE_TYPE_UNKNOWN"
        """{"videoRenderer":{"videoId":"${row.id}","title":{"runs":[{"text":"${row.title.replace("\"", "\\\"")}"}]},""" +
            """"ownerText":{"runs":[{"text":"${row.channel}"}]},"lengthText":{"simpleText":"${row.seconds / 60}:%02d"},""".format(row.seconds % 60) +
            """"ownerBadges":[{"metadataBadgeRenderer":{"style":"$badge"}}]}}"""
    } + "]}"

    private fun searching(queries: MutableList<String>, body: (String) -> String) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val query = RecordedVideoSearch.queryOf(chain.request())
        synchronized(queries) { queries += query }
        RecordedVideoSearch.respond(chain.request(), body(query))
    }).build()

    @Test fun theUploadsNameTheSongWithoutTheStrayWord() {
        val upload = VideoCandidate("Fkibbep8AtA", answered[3].title, "RJS Audios", 239)
        assertTrue(VideoMatcher.namesSong(upload, title))
        assertTrue(VideoMatcher.hasFallbackEvidence(upload, title, artist))
        assertEquals("En Iniya Pon Nilave", VideoMatcher.shortenedTitle(title))
    }

    @Test fun aShortTitleKeepsItsLastWordUnlessItOnlyNamesTheTag() {
        assertNull(VideoMatcher.shortenedTitle("Oru Maina Maina"), "three words: one less would be another song")
        assertNull(VideoMatcher.shortenedTitle("Hangova"))
        assertEquals("Nee Yaro", VideoMatcher.shortenedTitle("Nee Yaro Song"))
        assertEquals("Ullam Uruguthaiyya", VideoMatcher.shortenedTitle("Ullam Uruguthaiyya Revival"))
    }

    @Test fun theSearchFindsTheVideoSongAndAsksForTheSongAsItsUploadsNameIt() = runBlocking {
        val queries = ArrayList<String>()
        val lines = ArrayList<String>()
        val search = MusicVideoSearch(searching(queries) { page(answered) }) { synchronized(lines) { lines += it } }
        val outcome = search.findOutcome(title, artist, 254)
        val found = outcome.candidates.map { it.videoId }
        assertTrue("Fkibbep8AtA" in found, "the film's video song upload, got $found")
        // The lyric video, the talk about the song and the live take stay out; the audio check decides among the rest.
        assertFalse("fUrwo61b3Dg" in found); assertFalse("WzVkyBbRSaM" in found); assertFalse("narbW-5alQc" in found)
        assertTrue("\"En Iniya Pon Nilave\" video song" in queries, "asked $queries")
        assertTrue("K.J.Yesudas En Iniya Pon Nilave" in queries, "asked $queries")
        assertEquals(queries, outcome.queries)
    }

    @Test fun anAnsweredSearchThatFindsNothingSaysWhy() = runBlocking {
        val lines = ArrayList<String>()
        val search = MusicVideoSearch(searching(ArrayList()) { page(answered.filter { it.id == "fUrwo61b3Dg" || it.id == "WzVkyBbRSaM" }) }) {
            synchronized(lines) { lines += it }
        }
        assertTrue(search.findOutcome(title, artist, 254).candidates.isEmpty())
        val said = lines.single { "none is the song's video" in it }
        assertTrue("fUrwo61b3Dg" in said && "WzVkyBbRSaM" in said, said)
    }

    @Test fun queriesAlreadyAskedAreNotAskedAgain() = runBlocking {
        val queries = ArrayList<String>()
        val search = MusicVideoSearch(searching(queries) { RecordedVideoSearch.EMPTY })
        val first = search.findOutcome(title, artist, 254)
        queries.clear()
        search.findOutcome(title, artist, 254, exhaustive = true, alreadyAsked = first.queries.toSet())
        assertTrue(queries.none { it in first.queries }, "asked again: ${queries.filter { it in first.queries }}")
        assertTrue(queries.isNotEmpty(), "the wider queries are still asked")
    }

    @Test fun aFilmNamedAfterAColonIsTheFilm() {
        val query = MusicVideoQuery.from("Kadhalaada :From \"Vivegam\" (Soundtrack)", null)
        assertEquals("Kadhalaada", query.title)
        assertEquals("Vivegam", query.album)
    }

    @Test fun captchasInARowKeepThePageClosedLonger() {
        assertEquals(90_000L, MusicVideoSearch.throttlePauseMs(1))
        assertEquals(180_000L, MusicVideoSearch.throttlePauseMs(2))
        assertEquals(30 * 60_000L, MusicVideoSearch.throttlePauseMs(12))
    }
}
