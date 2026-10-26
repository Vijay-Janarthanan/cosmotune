package com.cosmotune.podio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import kotlin.test.*

class SpotifyPublicPodcastsTest {
    @Test fun episodeMetadataUsesAnAnonymousSessionWithoutRequestingAudioOrKeys() = runBlocking {
        val requests = ArrayList<Request>()
        val id = "1234567890123456789012"
        val api = SpotifyPublicPodcasts { request ->
            requests += request
            when (request.url.host) {
                "git.gay" -> """{"1":[1,2,3,4]}"""
                "open.spotify.com" -> if (request.url.encodedPath.endsWith("server-time")) """{"serverTime":1234567890}"""
                    else """{"isAnonymous":true,"clientId":"public-client","accessToken":"anonymous-fixture","accessTokenExpirationTimestampMs":9999999999999}"""
                "clienttoken.spotify.com" -> {
                    assertEquals("application/json", request.body?.contentType()?.toString())
                    val text = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals("public-client", Json.parseToJsonElement(text).jsonObject.getValue("client_data").jsonObject.getValue("client_id").jsonPrimitive.content)
                    """{"granted_token":{"token":"public-client-token"}}"""
                }
                "api-partner.spotify.com" -> """{"data":{"episodeUnionV2":{"name":"Public episode","audio":{"items":[{"format":"MP4_128","url":"https://metadata.example/abcdef123456"}]}}}}"""
                else -> error("Unexpected endpoint")
            }
        }
        assertEquals("Public episode", api.feed("https://open.spotify.com/episode/$id").episodes.single().title)
        assertTrue(requests.all { it.header("Cookie") == null })
        assertTrue(requests.none { it.url.toString().contains("license") || it.url.toString().contains("widevine") })
        assertTrue(requests.none { it.url.toString().contains("storage-resolve") })
        assertNull(SpotifyPublicPodcasts.link("https://open.spotify.com.evil.example/episode/$id"))
        assertNull(SpotifyPublicPodcasts.link("https://user:password@open.spotify.com/episode/$id"))
        assertEquals("episode" to id, SpotifyPublicPodcasts.link("https://open.spotify.com/intl-en/episode/$id?si=public"))
    }
}
