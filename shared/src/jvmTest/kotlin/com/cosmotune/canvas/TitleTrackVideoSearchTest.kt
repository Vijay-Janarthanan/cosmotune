package com.cosmotune.canvas

import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleTrackVideoSearchTest {
    @Test fun unrelatedVerifiedArtistDoesNotHideThePublisherVideoWithoutAnAlbum() = runBlocking {
        verifyFallback(null, "Life is Beautiful - Deep Music (House) [Official Video]", "Deep Music")
    }

    @Test fun sameNamedFilmDoesNotMakeAnotherSongAnExactTitleTrackHit() = runBlocking {
        verifyFallback("Life Is Beautiful", "Beautiful Girl Full Video Song - Life is Beautiful Movie", "Aditya Music")
    }

    @Test fun aCreditedArtistsOwnVideoStillNeedsOnlyOneSearch() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            response(chain.request(), page("OWNCHANNEL1", "Artist - Song Name (Official Video)", "Artist", true))
        }.build()
        assertEquals("OWNCHANNEL1", MusicVideoSearch(http).find("Song Name", "Artist").first().videoId)
        assertEquals(1, calls)
    }

    private suspend fun verifyFallback(album: String?, wrongTitle: String, wrongArtist: String) {
        val queries = ArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val query = chain.request().url.queryParameter("search_query").orEmpty()
            queries += query
            val body = if (query == "\"Life Is Beautiful\" video song") {
                page("C-5VT5JxuEQ", "Life Is Beautiful Video Song - Life Is Beautiful Movie", "Aditya Music", false)
            } else {
                page("WRONGVIDEO1", wrongTitle, wrongArtist, true)
            }
            response(chain.request(), body)
        }.build()
        val found = MusicVideoSearch(http).find("Life Is Beautiful", "K.K", 323, album = album)
        assertTrue(found.any { it.videoId == "C-5VT5JxuEQ" })
        assertTrue("\"Life Is Beautiful\" video song" in queries)
    }

    private fun response(request: okhttp3.Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()

    private fun page(id: String, title: String, channel: String, artist: Boolean): String {
        val badge = if (artist) "BADGE_STYLE_TYPE_VERIFIED_ARTIST" else "BADGE_STYLE_TYPE_VERIFIED"
        return "<script>var ytInitialData = " +
            """{"contents":[{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"$channel"}]},"lengthText":{"simpleText":"4:45"},"ownerBadges":[{"metadataBadgeRenderer":{"style":"$badge"}}]}}]}""" +
            ";</script>"
    }
}
