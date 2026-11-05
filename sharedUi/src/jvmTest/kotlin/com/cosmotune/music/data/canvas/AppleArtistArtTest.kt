package com.cosmotune.music.data.canvas

import com.cosmotune.ui.pages.artistMotionPlays
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The artist header's photo/motion lookup, against fixtures shaped like Apple's public search and page JSON. */
class AppleArtistArtTest {

    private val originalSearch = AppleArtistArtRepository.searchEndpoint

    @AfterTest fun restore() {
        AppleArtistArtRepository.searchEndpoint = originalSearch
        AppleArtistArtRepository.clearForTest()
    }

    private fun page(header: String) =
        """<html><script type="application/json" id="serialized-server-data">[{"data":{"sections":[{"items":[$header]}]}}]</script></html>"""

    private val withMotion = page(
        """{"artistLogo":null,"keyColor":"fa2d48",
            "artwork":{"dictionary":{"url":"https://img.example/hero/{w}x{h}{c}.{f}","width":2400,"height":1200,"bgColor":"101010","textColor1":"f0f0f0"}},
            "videoArtwork":{"dictionary":{"motionArtistSquare1x1":{"video":"https://video.example/loop.m3u8","previewFrame":{"bgColor":"203040","textColor1":"a0b0c0"}}}}}""",
    )

    @Test fun motionHeaderUsesTheClipAndTheClipsOwnColours() {
        val art = assertNotNull(AppleArtistArtRepository.parse(withMotion))
        assertEquals("https://img.example/hero/1200x600bb.jpg", art.heroUrl)
        assertEquals("https://video.example/loop.m3u8", art.videoUrl)
        assertEquals(0xFF203040.toInt(), art.background)
        assertEquals(0xFFA0B0C0.toInt(), art.accent)
        assertEquals(0xFFFA2D48.toInt(), art.keyColor)
        assertNull(art.logoUrl)
    }

    @Test fun stillOnlyHeaderKeepsThePhotographColoursAndNoClip() {
        val art = assertNotNull(AppleArtistArtRepository.parse(page(
            """{"artistLogo":{"dictionary":{"url":"https://img.example/logo/{w}x{h}.{f}","width":500,"height":100}},
                "artwork":{"dictionary":{"url":"https://img.example/hero/{w}x{h}{c}.{f}","width":1000,"height":1000,"bgColor":"112233"}},
                "videoArtwork":{"dictionary":{"motionArtistSquare1x1":{"video":"not-a-url"}}}}""",
        )))
        assertNull(art.videoUrl)
        assertEquals(0xFF112233.toInt(), art.background)
        assertEquals(art.background, art.accent)
        assertEquals("https://img.example/logo/1000x200.png", art.logoUrl)
        assertEquals(5f, art.logoAspect)
    }

    @Test fun pagesWithoutUsableHeaderDataGiveNoArt() {
        assertNull(AppleArtistArtRepository.parse("<html>nothing embedded</html>"))
        assertNull(AppleArtistArtRepository.parse(page("""{"artistLogo":null}""")))
        // A template without the size placeholders cannot be requested at a known size.
        assertNull(AppleArtistArtRepository.parse(page(
            """{"artistLogo":null,"artwork":{"dictionary":{"url":"https://img.example/fixed.jpg","width":10,"height":10,"bgColor":"000000"}}}""",
        )))
    }

    @Test fun onlyTheSameNamedArtistIsAccepted() {
        val search = """{"results":[
            {"artistName":"Beyonce Tribute Band","artistLinkUrl":"https://music.apple.com/us/artist/tribute/1"},
            {"artistName":"Beyoncé","artistLinkUrl":"https://music.apple.com/us/artist/beyonce/2?uo=4"}]}"""
        assertEquals("https://music.apple.com/us/artist/beyonce/2", AppleArtistArtRepository.artistPageFor(search, "BEYONCE"))
        assertNull(AppleArtistArtRepository.artistPageFor(search, "Somebody Else"))
        assertEquals(
            "https://music.apple.com/us/artist/77",
            AppleArtistArtRepository.artistPageFor("""{"results":[{"artistName":"A.B.","artistId":77}]}""", "ab"),
        )
        assertNull(AppleArtistArtRepository.artistPageFor("""{"results":[]}""", " "))
    }

    @Test fun realHttpLookupSettlesOnceAndKeepsAMissButNotAFailure() = runBlocking {
        val searches = AtomicInteger()
        val pages = AtomicInteger()
        var failSearch = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/search") { exchange ->
            searches.incrementAndGet()
            val term = exchange.requestURI.query.substringAfter("term=").substringBefore('&')
            val body = if (term == "Fixture+Artist" || term == "Fixture%20Artist") {
                """{"results":[{"artistName":"Fixture Artist","artistLinkUrl":"$base/artist/fixture"}]}"""
            } else {
                """{"results":[{"artistName":"Not The One","artistLinkUrl":"$base/artist/other"}]}"""
            }
            val status = if (failSearch && term.startsWith("Flaky")) 503 else 200
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/artist/fixture") { exchange ->
            pages.incrementAndGet()
            val bytes = withMotion.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            AppleArtistArtRepository.clearForTest()
            AppleArtistArtRepository.searchEndpoint = "$base/search"

            val art = assertNotNull(AppleArtistArtRepository.artFor("Fixture Artist"))
            assertEquals("https://video.example/loop.m3u8", art.videoUrl)
            assertEquals(art, AppleArtistArtRepository.cached("fixture artist"))
            assertEquals(art, AppleArtistArtRepository.artFor("Fixture Artist"))
            assertEquals(1, searches.get())
            assertEquals(1, pages.get())

            // A different artist in the results is a settled miss: cached, and the page is never fetched.
            assertNull(AppleArtistArtRepository.artFor("Unmatched"))
            assertNull(AppleArtistArtRepository.artFor("Unmatched"))
            assertEquals(2, searches.get())
            assertEquals(1, pages.get())

            // A server error is not an answer, so the next visit asks again.
            assertNull(AppleArtistArtRepository.artFor("Flaky"))
            failSearch = false
            assertNull(AppleArtistArtRepository.artFor("Flaky"))
            assertEquals(4, searches.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun mutedHeaderMotionFollowsTheMotionAndMobileDataSettings() {
        assertTrue(artistMotionPlays(animated = true, reducedMotion = false, metered = false, overCellular = false))
        assertTrue(artistMotionPlays(animated = true, reducedMotion = false, metered = null, overCellular = false))
        assertTrue(artistMotionPlays(animated = true, reducedMotion = false, metered = true, overCellular = true))
        assertFalse(artistMotionPlays(animated = true, reducedMotion = false, metered = true, overCellular = false))
        assertFalse(artistMotionPlays(animated = false, reducedMotion = false, metered = false, overCellular = true))
        assertFalse(artistMotionPlays(animated = true, reducedMotion = true, metered = false, overCellular = true))
    }
}
