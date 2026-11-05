package com.cosmotune.music.data.innertube.potoken

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The account-bound token of Video's signed clients: restarted only by another identity, and sharing the one page with Music's path. */
class SessionBoundPoTokenTest {
    private class Page(val number: Int) : PoTokenMinter {
        val minted = mutableListOf<String>()
        override var isExpired = false
        override var isDead = false
        var closed = false

        override suspend fun generatePoToken(identifier: String): String {
            minted += identifier
            return "p$number:$identifier"
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness(available: Boolean = true) {
        val pages = mutableListOf<Page>()
        val generator = PoTokenGenerator(createMinter = { Page(pages.size + 1).also { pages += it } }, available = { available })
    }

    @Test fun theStreamingTokenIsBoundToTheIdentityAndThePlayerTokenToTheVideo() = runTest {
        val h = Harness()
        val tokens = assertNotNull(h.generator.getSessionBoundTokens("vid", "ACCOUNT"))
        assertEquals("p1:ACCOUNT", tokens.streamingToken)
        assertEquals("p1:vid", tokens.playerToken)
    }

    @Test fun theSameIdentityMintsItsStreamingTokenOnce() = runTest {
        val h = Harness()
        h.generator.getSessionBoundTokens("one", "ACCOUNT")
        val again = assertNotNull(h.generator.getSessionBoundTokens("two", "ACCOUNT"))
        assertEquals("p1:ACCOUNT", again.streamingToken)
        assertEquals(listOf("ACCOUNT", "one", "two"), h.pages.single().minted, "a rotation of the cookies leaves the identity, so nothing restarts")
    }

    @Test fun anotherIdentityMintsAgainOnTheSamePage() = runTest {
        val h = Harness()
        h.generator.getSessionBoundTokens("one", "ACCOUNT")
        val other = assertNotNull(h.generator.getSessionBoundTokens("two", "OTHER"))
        assertEquals("p1:OTHER", other.streamingToken)
        assertEquals(1, h.pages.size, "the page is not bound to anyone")
    }

    @Test fun aPageWhoseIntegrityTokenRanOutIsReplacedAndTheTokenMintedAgain() = runTest {
        val h = Harness()
        h.generator.getSessionBoundTokens("one", "ACCOUNT")
        h.pages[0].isExpired = true
        val fresh = assertNotNull(h.generator.getSessionBoundTokens("two", "ACCOUNT"))
        assertEquals("p2:ACCOUNT", fresh.streamingToken)
        assertEquals(true, h.pages[0].closed)
    }

    @Test fun aPlatformWithNoBrowserMintsNothing() = runTest {
        assertNull(Harness(available = false).generator.getSessionBoundTokens("vid", "ACCOUNT"))
    }

    @Test fun musicsPathUsesThePageVideoMadeAndVideoKeepsItsTokenAcrossIt() = runTest {
        val h = Harness()
        h.generator.getSessionBoundTokens("one", "ACCOUNT")
        val music = assertNotNull(h.generator.getWebClientPoToken("track", "VISITOR"))
        assertEquals("p1:VISITOR", music.playerRequestPoToken)
        assertEquals(1, h.pages.size, "no second page")
        assertEquals("p1:ACCOUNT", h.generator.getSessionBoundTokens("two", "ACCOUNT")?.streamingToken)
        assertEquals(listOf("ACCOUNT", "one", "VISITOR", "track", "two"), h.pages.single().minted)
    }

    @Test fun musicsPathStillReplacesThePageForAnotherOfItsSessions() = runTest {
        val h = Harness()
        h.generator.getWebClientPoToken("track", "VISITOR")
        h.generator.getWebClientPoToken("track", "VISITOR2")
        assertEquals(2, h.pages.size)
    }
}
