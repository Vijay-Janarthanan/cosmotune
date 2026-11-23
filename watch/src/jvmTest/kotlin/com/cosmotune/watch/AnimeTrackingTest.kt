package com.cosmotune.watch

import com.cosmotune.watch.tracking.AnimeTrackingService.Auth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeTrackingTest {
    @Test fun myAnimeListSignInUsesPkceAndTheRegisteredReturnAddress() {
        val verifier = Auth.randomToken(64)
        assertEquals(64, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        val url = Auth.authorizeUrl(AnimeTracker.MYANIMELIST, "abc123", "st4te", verifier)
        assertTrue(url.startsWith("https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=abc123&state=st4te"), url)
        assertTrue("code_challenge=$verifier&code_challenge_method=plain" in url, url)
        assertTrue("redirect_uri=cosmotune%3A%2F%2Fwatch%2Ftracker%2Fmyanimelist" in url, url)
    }

    @Test fun aniListSignInAsksForATokenWithNoSecret() {
        val url = Auth.authorizeUrl(AnimeTracker.ANILIST, "12345", "s", "v")
        assertEquals("https://anilist.co/api/v2/oauth/authorize?client_id=12345&response_type=token&state=s", url)
    }

    @Test fun readsBothServicesReturns() {
        val mal = Auth.parseReturn("cosmotune://watch/tracker/myanimelist?code=def%2B1&state=st4te")!!
        assertEquals(AnimeTracker.MYANIMELIST, mal.service)
        assertEquals("def+1", mal.code)
        assertEquals("st4te", mal.state)
        val ani = Auth.parseReturn("cosmotune://watch/tracker/anilist#access_token=tok.en&token_type=Bearer&expires_in=31536000")!!
        assertEquals(AnimeTracker.ANILIST, ani.service)
        assertEquals("tok.en", ani.accessToken)
        assertEquals(31_536_000L, ani.expiresInS)
        assertEquals("access_denied", Auth.parseReturn("cosmotune://watch/tracker/anilist?error=access_denied")!!.error)
        assertNull(Auth.parseReturn("cosmotune://watch/addon?url=x"))
    }

    @Test fun progressOnlyMovesForward() {
        assertEquals(Auth.Update(5, false), Auth.update(4, false, 5, 12))
        assertEquals(Auth.Update(12, true), Auth.update(11, false, 12, 12))
        assertEquals(Auth.Update(3, false), Auth.update(null, false, 3, null), "a show still airing has no total")
        assertNull(Auth.update(7, false, 5, 12), "never lowers a list")
        assertNull(Auth.update(5, false, 5, 12))
        assertNull(Auth.update(12, true, 3, 12), "a finished show is left as it is (a rewatch)")
        assertNull(Auth.update(0, false, 13, 12), "an episode past the entry's end belongs to another entry")
    }
}
