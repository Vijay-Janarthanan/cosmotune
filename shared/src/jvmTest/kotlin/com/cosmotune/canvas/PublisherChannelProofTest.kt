package com.cosmotune.canvas

import com.opencanvas.core.stream.PublisherChannelProof
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*
import org.junit.Assume.assumeTrue

class PublisherChannelProofTest {
    @Test fun publicPublisherRelationshipCanBeCorroboratedWithoutAnAccount() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_PUBLISHER_PROOF_LIVE") == "1")
        assertTrue(PublisherChannelProof(OkHttpClient()).proves("UCj4VFqG94x8qqGz3Bj6VUWA"))
    }
    private val child = "UC" + "a".repeat(22)
    private fun root(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun parent(verified: Boolean, linked: Boolean): JsonObject = root("""{
      "metadata":{"channelMetadataRenderer":{"title":"Example Music"}},
      "header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{"title":{"imageName":"${if (verified) "CHECK_CIRCLE_FILLED" else "OTHER"}"}}}}},
      "contents":{"shelfRenderer":{"content":{"gridChannelRenderer":{"channelId":"${if (linked) child else "other"}","ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]}}}}
    }""")

    @Test fun verifiedPublisherMustFeatureTheExactChannelBack() {
        assertTrue(PublisherChannelProof.corroborates(parent(true, true), child))
        assertFalse(PublisherChannelProof.corroborates(parent(true, false), child))
        assertFalse(PublisherChannelProof.corroborates(parent(false, true), child), "a featured child's badge cannot verify its parent")
    }

    @Test fun selfDescriptionAndExternalWebsitesCannotSupplyTheProof() {
        val data = root("""{"description":"official verified publisher", "channelExternalLinkViewModel":{"urlEndpoint":{"url":"https://example.com/@Publisher"}}}""")
        assertTrue(PublisherChannelProof.channelLinks(data).isEmpty())
        assertFalse(PublisherChannelProof.corroborates(data, child))
    }

    @Test fun reciprocalPublicChannelRelationshipIsCachedWithoutANameAllowlist() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val data = if (chain.request().url.encodedPath == "/channel/$child/about") root("""{
              "metadata":{"channelMetadataRenderer":{"externalId":"$child"}},
              "channelExternalLinkViewModel":{"urlEndpoint":{"url":"https://www.youtube.com/@Publisher"}}
            }""") else parent(true, true)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<script>var ytInitialData = $data;</script>".toResponseBody()).build()
        }.build()
        val proof = PublisherChannelProof(http)
        assertTrue(proof.proves(child))
        assertTrue(proof.proves(child))
        assertEquals(2, requests)
    }
}
