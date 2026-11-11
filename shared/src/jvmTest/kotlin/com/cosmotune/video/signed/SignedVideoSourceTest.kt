package com.cosmotune.video.signed

import com.cosmotune.video.account.InnerTubeComments
import com.cosmotune.video.account.YoutubeWeb
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** When the signed path answers and when anonymous NewPipe does; the anonymous source here is the fake one, which says what it was asked. */
class SignedVideoSourceTest {
    private val video = "fakevideo01"

    private class Web : YoutubeWeb {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val answers = ArrayDeque<Any>()
        var fallback: JsonObject = buildJsonObject {}

        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += endpoint to buildJsonObject(body)
            return when (val answer = answers.removeFirstOrNull()) {
                is Throwable -> throw answer
                is JsonObject -> answer
                else -> fallback
            }
        }
    }

    private class Harness(signedIn: Boolean = true) {
        var signedIn = signedIn
        var identity: Any? = 1L
        var now = SignedFixtures.NOW_MS
        val anonymous = FakeVideoSource()
        val http = FakeSignedHttp()
        val web = Web()
        val source = SignedVideoSource(
            anonymous = anonymous,
            signedIn = { this.signedIn },
            identity = { identity },
            resolver = SignedFixtures.resolver(SignedFixtures.session(), http),
            web = web,
            clock = { now },
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    // ---- Signed out ---------------------------------------------------------------------------------------------------

    @Test fun signedOutEveryPlaybackCallIsTheAnonymousSources() = runTest {
        val h = Harness(signedIn = false)
        h.source.details(video)
        h.source.streams(video)
        h.source.related(video)
        h.source.playlist(FakeVideoSource.PLAYLIST_ID)
        assertTrue(h.http.sent.isEmpty(), "no signed request")
        assertTrue(h.web.calls.isEmpty())
        assertTrue(h.anonymous.calls.any { it.startsWith("details") } && h.anonymous.calls.any { it.startsWith("streams") } && h.anonymous.calls.any { it.startsWith("playlist") })
    }

    // ---- Signed in ----------------------------------------------------------------------------------------------------

    @Test fun signedInTheWatchPageComesFromTheSignedClientsNotTheAnonymousSource() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val details = h.source.details(video)
        assertEquals("Private film", details.item.title)
        assertTrue(h.anonymous.calls.none { it.startsWith("details") })
        assertEquals("next", h.web.calls.single().first)
        assertEquals(video, h.web.calls.single().second["videoId"]!!.jsonPrimitive.content)
    }

    @Test fun whenEverySignedClientFailsTheAnonymousSourceAnswers() = runTest {
        val h = Harness()
        val details = h.source.details(video)
        assertEquals(FakeVideoSource.sampleItem(video)!!.title, details.item.title)
        assertEquals(SignedPlayerClient.SIGNED.size, h.http.sent.size, "each signed client was asked first")
        assertTrue(h.anonymous.calls.any { it.startsWith("details") })
    }

    @Test fun signedClientsThatKeepItWaitingLoseToTheAnonymousSourceAfterTheirHeadStart() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        h.http.takes(SignedPlayerClient.TV_SAMSUNG, 10_000)
        val details = h.source.details(video)
        assertEquals(FakeVideoSource.sampleItem(video)!!.title, details.item.title, "the anonymous answer came first")
        assertEquals(SignedVideoSource.ANONYMOUS_AFTER_MS, testScheduler.currentTime, "asked after the head start, answered at once")
    }

    @Test fun aSignedAnswerWithinTheHeadStartNeverAsksTheAnonymousSource() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        h.http.takes(SignedPlayerClient.TV_SAMSUNG, SignedVideoSource.ANONYMOUS_AFTER_MS - 1)
        assertEquals("Private film", h.source.details(video).item.title)
        assertTrue(h.anonymous.calls.none { it.startsWith("details") })
    }

    @Test fun whatNeitherCouldReadIsTriedWithTheAppClientsInOrder() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.ANDROID_VR, PlayerJson.PLAYABLE)
        val details = h.source.details("nosuchvideo")
        assertEquals("Private film", details.item.title)
        assertEquals(
            (SignedPlayerClient.SIGNED + SignedPlayerClient.RESCUE).map { it.userAgent },
            h.http.sent.map { it.userAgent },
        )
    }

    @Test fun whenTheAppClientsFailTooTheAnonymousSourcesErrorIsWhatLeaves() = runTest {
        val h = Harness()
        assertFailsWith<VideoSourceError.NotAvailable> { h.source.details("nosuchvideo") }
    }

    @Test fun detailsAreKeptTenMinutes() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val first = h.source.details(video)
        assertSame(first, h.source.details(video))
        assertEquals(1, h.http.sent.size)
        h.now += SignedVideoSource.DETAILS_TTL_MS
        h.source.details(video)
        assertEquals(2, h.http.sent.size)
    }

    // ---- A set that was refused ---------------------------------------------------------------------------------------

    @Test fun aSetAskedForAgainSoonWasRefusedAndItsClientSitsOut() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        h.http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, h.source.streams(video).requestHeaders["User-Agent"])
        assertEquals(SignedPlayerClient.WEB_EMBEDDED.userAgent, h.source.streams(video).requestHeaders["User-Agent"])
    }

    @Test fun aSetAskedForLongAfterIsAnExpiryAndNoClientSitsOut() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        h.source.streams(video)
        h.now += SignedVideoSource.REFUSED_MS
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, h.source.streams(video).requestHeaders["User-Agent"])
    }

    @Test fun whenEverySignedClientHasBeenRefusedTheAnonymousSourceAnswers() = runTest {
        val h = Harness()
        for (client in SignedPlayerClient.SIGNED) h.http.answer(client, PlayerJson.PLAYABLE)
        repeat(SignedPlayerClient.SIGNED.size) { h.source.streams(video) }
        assertTrue(h.anonymous.calls.none { it.startsWith("streams") })
        h.source.streams(video)
        assertTrue(h.anonymous.calls.any { it.startsWith("streams") })
    }

    @Test fun anotherAccountStartsClean() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        h.http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        val first = h.source.details(video)
        assertEquals(SignedPlayerClient.WEB_EMBEDDED.userAgent, h.source.streams(video).requestHeaders["User-Agent"], "asked again: the first was refused")
        h.identity = 2L
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, h.source.streams(video).requestHeaders["User-Agent"], "what the last account was refused is gone")
        h.identity = 3L
        assertTrue(first !== h.source.details(video), "and so are its details")
    }

    @Test fun aRotationIsNoNewIdentity() = runTest {
        val h = Harness()
        h.http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val first = h.source.details(video)
        // the identity is the session's generation and the chosen channel: a rotation changes neither
        assertSame(first, h.source.details(video))
    }

    // ---- Comments -----------------------------------------------------------------------------------------------------

    @Test fun commentsComeFromTheSitesEndpointSignedInOrOut() = runTest {
        val h = Harness(signedIn = false)
        h.web.answers += json("""{"contents":{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"START"}}}}]}}}""")
        h.web.answers += json("""{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}}]}""")
        val page = h.source.comments(video, CommentSort.TOP, null)
        assertEquals(InnerTubeComments.TOKEN_PREFIX + "NEXT", page.next?.value)
        assertTrue(h.anonymous.calls.none { it.startsWith("comments") })
        assertEquals("START", h.web.calls[1].second["continuation"]!!.jsonPrimitive.content)
    }

    @Test fun whenTheyCannotBeReadTheAnonymousSourceAnswersAndItsTokensStayItsOwn() = runTest {
        val h = Harness()
        h.web.answers += java.io.IOException("offline")
        val page = h.source.comments(video, CommentSort.TOP, null)
        assertTrue(h.anonymous.calls.any { it.startsWith("comments") })
        val token = page.next!!
        assertFalse(token.value.startsWith(InnerTubeComments.TOKEN_PREFIX))
        h.web.calls.clear()
        h.source.comments(video, CommentSort.TOP, token)
        assertTrue(h.web.calls.isEmpty(), "a page of the anonymous source's goes back to it")
        val replies = page.items.first { it.replies != null }.replies!!
        h.source.replies(replies)
        assertTrue(h.web.calls.isEmpty())
    }

    @Test fun aLaterPageThatFailsIsASourceErrorNotAFallback() = runTest {
        val h = Harness()
        h.web.answers += java.io.IOException("offline")
        assertIs<VideoSourceError.Network>(assertFailsWith<VideoSourceError> { h.source.comments(video, CommentSort.TOP, PageToken(InnerTubeComments.TOKEN_PREFIX + "T")) })
    }

    // ---- Private playlists --------------------------------------------------------------------------------------------

    private val playlistAnswer = """
        {"metadata":{"playlistMetadataRenderer":{"title":"My private list","description":"Only me"}},
         "header":{"playlistHeaderRenderer":{"ownerText":{"simpleText":"Me"},"numVideosText":{"simpleText":"12 videos"},"ownerEndpoint":{"browseEndpoint":{"browseId":"UCme"}}}},
         "contents":{"list":{"playlistVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Clip"},"lengthText":{"simpleText":"1:00"}}},
           "more":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MORE"}}}}}}
    """.trimIndent()

    @Test fun signedInAnAccountPlaylistIsReadWithTheSession() = runTest {
        val h = Harness()
        h.web.answers += json(playlistAnswer)
        val page = h.source.playlist("VLPLprivate")
        assertEquals("browse", h.web.calls.single().first)
        assertEquals("VLPLprivate", h.web.calls.single().second["browseId"]!!.jsonPrimitive.content)
        assertEquals("My private list", page.playlist.title)
        assertEquals("PLprivate", page.playlist.id)
        assertEquals("Only me", page.description)
        assertEquals("Me", page.playlist.ownerName)
        assertEquals(12L, page.playlist.videoCount)
        assertEquals(listOf("abcdefghijk"), page.first.items.map { it.id })
        assertEquals(InnerTubeComments.TOKEN_PREFIX + "MORE", page.first.next?.value)
        assertTrue(h.anonymous.calls.none { it.startsWith("playlist") })
    }

    @Test fun itsNextPageGoesOnWithTheContinuation() = runTest {
        val h = Harness()
        h.web.answers += json("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"playlistVideoRenderer":{"videoId":"abcdefghijl","title":{"simpleText":"Clip 2"}}}]}}]}""")
        val page = h.source.playlist("PLprivate", PageToken(InnerTubeComments.TOKEN_PREFIX + "MORE"))
        assertEquals("MORE", h.web.calls.single().second["continuation"]!!.jsonPrimitive.content)
        assertEquals(listOf("abcdefghijl"), page.first.items.map { it.id })
    }

    @Test fun aPlaylistTheSessionCannotReadFallsBackToTheAnonymousPage() = runTest {
        val h = Harness()
        h.web.answers += java.io.IOException("offline")
        val page = h.source.playlist(FakeVideoSource.PLAYLIST_ID)
        assertTrue(h.anonymous.calls.any { it.startsWith("playlist") })
        assertFalse(page.first.next?.value.orEmpty().startsWith(InnerTubeComments.TOKEN_PREFIX))
    }

    @Test fun anAnonymousPagesTokenStaysWithTheAnonymousSource() = runTest {
        val h = Harness()
        val first = h.anonymous.playlist(FakeVideoSource.PLAYLIST_ID)
        h.source.playlist(FakeVideoSource.PLAYLIST_ID, first.first.next)
        assertTrue(h.web.calls.isEmpty())
    }
}
