package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.PageToken
import com.cosmotune.video.source.VideoSourceError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.Page
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NewPipe's `Page` as an opaque `PageToken` and back (plan task Y-4, step 5): JSON, the body in base64. */
class PageCodecTest {

    private fun roundTrip(page: Page, context: String? = null): PageCodec.Decoded = PageCodec.decode(PageCodec.encode(page, context))

    private fun assertSamePage(expected: Page, actual: Page) {
        assertEquals(expected.url, actual.url)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.ids, actual.ids)
        assertEquals(expected.cookies, actual.cookies)
        assertContentEquals(expected.body, actual.body)
    }

    @Test
    fun aPageWithEveryFieldRoundTrips() {
        val page = Page(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
            "continuation-token-1",
            listOf("id-1", "id-2", "id-3"),
            mapOf("CONSENT" to "YES+", "PREF" to "f6=40000000"),
            """{"continuation":"EogDEgtrdXJ6Z2VzYWd0..."}""".toByteArray(),
        )
        assertSamePage(page, roundTrip(page).page)
    }

    @Test
    fun aNullBodyStaysNullAndAnEmptyBodyStaysEmpty() {
        val nullBody = Page("https://example.com/next", "token", null, null, null)
        assertNull(roundTrip(nullBody).page.body)
        val emptyBody = Page("https://example.com/next", "token", null, null, ByteArray(0))
        val decoded = roundTrip(emptyBody).page.body
        assertEquals(0, decoded?.size)
    }

    @Test
    fun nullCookiesAndNullIdsStayNullAndEmptyOnesStayEmpty() {
        val nulls = Page("https://example.com/next", null, null, null, null)
        val decodedNulls = roundTrip(nulls).page
        assertNull(decodedNulls.id)
        assertNull(decodedNulls.ids)
        assertNull(decodedNulls.cookies)

        val empties = Page("https://example.com/next", "x", emptyList(), emptyMap(), null)
        val decodedEmpties = roundTrip(empties).page
        assertEquals(emptyList(), decodedEmpties.ids)
        assertEquals(emptyMap(), decodedEmpties.cookies)
    }

    @Test
    fun aPageThatOnlyHasIdsRoundTrips() {
        val page = Page(listOf("a", "b"))
        val decoded = roundTrip(page).page
        assertNull(decoded.url)
        assertEquals(listOf("a", "b"), decoded.ids)
        assertTrue(Page.isValid(decoded))
    }

    @Test
    fun bytesThatAreNotTextSurvive() {
        val body = ByteArray(256) { it.toByte() }
        val page = Page("https://example.com/next", "t", null, null, body)
        assertContentEquals(body, roundTrip(page).page.body)
    }

    @Test
    fun unicodeSurvivesInTheAddressTheIdAndTheCookies() {
        val page = Page("https://example.com/näxt?q=日本語&x=\"quoted\"", "tökén ✓", listOf("ü"), mapOf("k€y" to "välue"), null)
        assertSamePage(page, roundTrip(page).page)
    }

    @Test
    fun theContextRoundTripsAndDefaultsToNull() {
        val page = Page("https://example.com/next", "t", null, null, null)
        assertEquals("jNQXAC9IVRw", roundTrip(page, "jNQXAC9IVRw").context)
        assertNull(roundTrip(page).context)
        assertNull(roundTrip(page, null).context)
    }

    @Test
    fun theTokenIsJsonWithTheBodyInBase64() {
        val page = Page("https://example.com/next", "t", null, null, byteArrayOf(0, 1, 2, 3, -1))
        val token = PageCodec.encode(page, null)
        val json = Json.parseToJsonElement(token.value).jsonObject
        assertEquals("AAECA/8=", json["b"]?.toString()?.trim('"'))
        assertFalse(token.value.contains("\n"))
        assertTrue(json.containsKey("v"))
    }

    @Test
    fun theSamePageAlwaysGivesTheSameToken() {
        val a = Page("https://example.com/next", "t", listOf("x"), mapOf("b" to "2", "a" to "1"), byteArrayOf(1, 2))
        val b = Page("https://example.com/next", "t", listOf("x"), mapOf("a" to "1", "b" to "2"), byteArrayOf(1, 2))
        assertEquals(PageCodec.encode(a, "ctx"), PageCodec.encode(b, "ctx"))
    }

    @Test
    fun differentPagesGiveDifferentTokens() {
        val a = Page("https://example.com/next", "token-a", null, null, null)
        val b = Page("https://example.com/next", "token-b", null, null, null)
        assertNotEquals(PageCodec.encode(a), PageCodec.encode(b))
        assertNotEquals(PageCodec.encode(a, "one"), PageCodec.encode(a, "two"))
    }

    @Test
    fun aTokenThatIsNotOurJsonIsAParsingError() {
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("not json at all")) }
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("")) }
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("[1,2,3]")) }
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("""{"v":1,"b":"***not base64***","u":"x"}""")) }
    }

    @Test
    fun aTokenOfAnotherVersionIsRefused() {
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("""{"v":99,"u":"https://example.com"}""")) }
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("""{"u":"https://example.com"}""")) }
    }

    @Test
    fun aTokenWithNeitherAnAddressNorIdsIsRefused() {
        assertFailsWith<VideoSourceError.Parsing> { PageCodec.decode(PageToken("""{"v":1,"i":"only-an-id"}""")) }
    }
}
