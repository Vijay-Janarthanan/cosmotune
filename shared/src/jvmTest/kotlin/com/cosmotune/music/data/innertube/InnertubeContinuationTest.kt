package com.cosmotune.music.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Next-page tokens in the shapes YouTube Music serves, read by the shared parser both the phone and desktop page with.
 */
class InnertubeContinuationTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun renderer(endpoint: String) = """{"continuationItemRenderer":{"continuationEndpoint":$endpoint}}"""
    private val plain = """{"continuationCommand":{"token":"PLAIN"}}"""
    private val wrapped = """{"commandExecutorCommand":{"commands":[
        {"openPopupAction":{"popup":{"voting":true}}},
        {"continuationCommand":{"token":"WRAPPED","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}]}}"""

    @Test fun theOlderDirectCommandIsRead() {
        assertEquals("PLAIN", InnertubeParser.continuationToken(json("""{"contents":[${renderer(plain)}]}""")))
    }

    @Test fun aCommandWrappedInACommandExecutorIsRead() {
        assertEquals("WRAPPED", InnertubeParser.continuationToken(json("""{"contents":[{"x":1},${renderer(wrapped)}]}""")))
    }

    @Test fun onlyTheFirstRenderersEndpointCountsSoAnotherShelfsTokenIsNotFollowed() {
        val first = renderer("""{"commandExecutorCommand":{"commands":[{"openPopupAction":{}}]}}""")
        assertNull(InnertubeParser.continuationToken(json("""{"a":[$first],"b":[${renderer(plain)}]}""")))
    }

    @Test fun aTokenDeeperThanTheTwoKnownDepthsIsIgnored() {
        val deep = renderer("""{"commandExecutorCommand":{"commands":[{"wrapper":{"continuationCommand":{"token":"DEEP"}}}]}}""")
        assertNull(InnertubeParser.continuationToken(json("""{"contents":[$deep]}""")))
    }

    @Test fun theLegacyContinuationsArrayIsStillTheFallback() {
        assertEquals("LEGACY", InnertubeParser.continuationToken(json(
            """{"continuations":[{"nextContinuationData":{"continuation":"LEGACY"}}]}""",
        )))
        assertNull(InnertubeParser.continuationToken(json("""{"contents":[]}""")))
    }

    @Test fun aPlaylistShelfPagesWithTheWrappedToken() {
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(json(
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${renderer(wrapped)}]}}}""",
        )))
        assertEquals("WRAPPED", page.continuation)
        assertEquals(emptyList(), page.songs)
    }
}
