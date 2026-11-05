package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoSearchResilienceTest {
    @Test fun aStalledFirstTryDoesNotHoldTheSearchBack() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) Thread.sleep(4_000)
            response(chain.request(), page("OWNCHANNEL1", "Artist - Song Name (Official Video)", "Artist", "4:45", verified = true))
        }.build()
        val started = System.nanoTime()
        val found = MusicVideoSearch(http).find("Song Name", "Artist")
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals("OWNCHANNEL1", found.first().videoId)
        assertTrue(tookMs < 3_500, "the second try should have answered at about 2 s, took $tookMs ms")
        assertTrue(calls.get() >= 2)
    }

    @Test fun aSearchPageWithoutVideosIsAnAnswerNotAFailure() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), "<script>var ytInitialData = {\"contents\":[]};</script>")
        }.build()
        assertTrue(MusicVideoSearch(http).find("Song Name", "Artist", exhaustive = true).isEmpty())
        assertTrue(calls.get() < 40, "an empty page was retried as if it had failed: ${calls.get()} calls")
    }

    @Test fun aTeaserFarShorterThanTheSongIsNotItsVideo() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), page("TEASER00001", "Sakkarakatti - Marudaani Video | A.R. Rahman | Shanthnu", "SonyMusicSouthVEVO", "1:28", verified = true))
        }.build()
        assertTrue(MusicVideoSearch(http).find("Marudaani", "A.R. Rahman", 388, album = "Sakkarakatti (Original Motion Picture Soundtrack)").isEmpty())
    }

    @Test fun anUploadByAnyoneIsTheFallbackWhenNoPublisherHasTheVideo() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), page("FANFULL0001", "Marudhani 4K Video Song | Santhanu Bakiyaraj | A.R. Rahman | Sakkarakatti", "Scene Songs", "6:30", verified = false))
        }.build()
        val found = MusicVideoSearch(http).find("Marudaani", "A.R. Rahman, Madhushree", 388, album = "Sakkarakatti (Original Motion Picture Soundtrack)")
        assertEquals("FANFULL0001", found.first().videoId)
    }

    @Test fun aPublishersVideoStillComesBeforeAnUpload() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.queryParameter("search_query").orEmpty().contains("official"))
                page("OWNCHANNEL1", "A.R. Rahman - Marudaani (Official Video)", "A.R. Rahman", "6:40", verified = true)
            else page("FANFULL0001", "Marudaani Video Song Sakkarakatti A.R. Rahman", "Scene Songs", "6:30", verified = false)
            response(chain.request(), body)
        }.build()
        assertEquals("OWNCHANNEL1", MusicVideoSearch(http).find("Marudaani", "A.R. Rahman", 388).first().videoId)
    }

    @Test fun aLyricVideoOrAnUnrelatedUploadIsNotTheFallback() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), page("LYRICVID001", "Marudaani (Lyrics) - A.R. Rahman", "Supersonic Play", "6:33", verified = false))
        }.build()
        assertTrue(MusicVideoSearch(http).find("Marudaani", "A.R. Rahman", 388).isEmpty())
        val other = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), page("OTHERSONG01", "Chinnama Chilakamma Video Song | A.R. Rahman", "Scene Songs", "5:36", verified = false))
        }.build()
        assertTrue(MusicVideoSearch(other).find("Marudaani", "A.R. Rahman", 388).isEmpty())
    }

    @Test fun anAlbumNamedAsASoundtrackIsTheFilmItIsNamedAfter() {
        assertEquals("Sakkarakatti", VideoMatcher.cleanFilmName("Sakkarakatti (Original Motion Picture Soundtrack)"))
        assertEquals("Ayyo", VideoMatcher.cleanFilmName("Ayyo [Original Soundtrack]"))
        assertEquals("Thani Oruvan", VideoMatcher.cleanFilmName("Thani Oruvan - Original Motion Picture Soundtrack"))
        assertEquals("3", VideoMatcher.cleanFilmName("3"))
        assertEquals("Original Soundtrack", VideoMatcher.cleanFilmName("Original Soundtrack"))
        assertTrue(VideoMatcher.hasFilmContext("Sakkarakatti - Chinnama Chilakamma Video Song", "Sakkarakatti (Original Motion Picture Soundtrack)"))
    }

    private fun response(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()

    private fun page(id: String, title: String, channel: String, length: String, verified: Boolean): String {
        val badge = if (verified) "BADGE_STYLE_TYPE_VERIFIED_ARTIST" else "BADGE_STYLE_TYPE_UNKNOWN"
        return "<script>var ytInitialData = " +
            """{"contents":[{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"$channel"}]},"lengthText":{"simpleText":"$length"},"ownerBadges":[{"metadataBadgeRenderer":{"style":"$badge"}}]}}]}""" +
            ";</script>"
    }
}
