package com.cosmotune.youtube.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSessionTest {
    private val login = "SID=1; SAPISID=secret; __Secure-1PSIDTS=ts0; SIDCC=cc0"
    private val other = "SID=9; SAPISID=another; __Secure-1PSIDTS=zz"

    /** Answers the rotation and the page as told, records every call, and can hold a rotation until [gate] completes. */
    private class FakeTransport : SessionTransport {
        val rotations = mutableListOf<Pair<String, String>>()
        val pages = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var rotate: (url: String, cookie: String) -> SessionTransport.Answer = { _, _ -> SessionTransport.Answer(200, emptyList()) }
        var page: (cookie: String) -> SessionTransport.Answer = { SessionTransport.Answer(200, emptyList(), SIGNED_IN_PAGE) }

        override suspend fun rotate(url: String, cookie: String, origin: String): SessionTransport.Answer {
            rotations += url to cookie
            gate?.await()
            return rotate(url, cookie)
        }

        override suspend fun page(url: String, cookie: String, authorization: String?): SessionTransport.Answer {
            pages += cookie
            return page(cookie)
        }
    }

    private class RecordingVault : CredentialVault {
        val stored = mutableListOf<String>()
        val storedGoogle = mutableListOf<String>()
        override fun store(cookie: String) { stored += cookie }
        override fun storeGoogle(cookie: String) { storedGoogle += cookie }
    }

    private fun TestScope.session(transport: SessionTransport? = null, persistMs: Long = 0L) =
        YouTubeSession(clock = { testScheduler.currentTime + START }, persistIntervalMs = persistMs, background = backgroundScope, transport = transport)

    // ---- State ---------------------------------------------------------------------------------------------------------

    @Test fun installDescribeAndSignOutDriveTheState() = runTest {
        val s = session()
        assertEquals(YouTubeAuthState.SignedOut, s.state.value)
        assertTrue(s.install(login))
        assertEquals(YouTubeAuthState.SignedIn(null, null), s.state.value)
        val account = YouTubeAccountInfo("a", "Asha", "asha@example.com", null)
        val profile = YouTubeProfileInfo("p", "Asha", "@asha", null, isBrand = false)
        s.describe(account, profile)
        assertEquals(YouTubeAuthState.SignedIn(account, profile), s.state.value)
        assertFalse(s.install(login), "the same value again is no change")
        assertTrue(s.install(null))
        assertEquals(YouTubeAuthState.SignedOut, s.state.value)
    }

    @Test fun aJarThatCannotSignIsNotSignedIn() = runTest {
        val s = session()
        s.install("SID=1; HSID=2")
        assertEquals(YouTubeAuthState.SignedOut, s.state.value)
    }

    @Test fun aNewSessionBumpsTheGenerationAndDropsTheVisitorId() = runTest {
        val s = session()
        s.install(login)
        s.visitorData = "Cgvisitor"
        val generation = s.generation
        s.install(other)
        assertEquals(generation + 1, s.generation)
        assertNull(s.visitorData)
    }

    // ---- Rotation from responses ----------------------------------------------------------------------------------------

    @Test fun setCookieFromAResponseIsKeptAndPersistedAndTheStoredCopyDoesNotUndoIt() = runTest {
        val s = session()
        val vault = RecordingVault()
        s.vault = vault
        s.install(login)
        val generation = s.generation
        s.absorbSetCookies(login, listOf("__Secure-1PSIDTS=ts1; Domain=.youtube.com; Secure", "SIDCC=cc1; Domain=.youtube.com"))
        val rotated = "SID=1; SAPISID=secret; __Secure-1PSIDTS=ts1; SIDCC=cc1"
        assertEquals(rotated, s.cookie)
        assertEquals(listOf(rotated), vault.stored)
        assertEquals(generation, s.generation, "a rotation is not a new session")
        assertFalse(s.install(login), "the platform's stored copy of the same login changes nothing")
        assertEquals(rotated, s.cookie)
        assertFalse(s.install(rotated))
    }

    @Test fun aRotationForAnotherLoginOrOneThatRemovesTheSecretIsIgnored() = runTest {
        val s = session()
        s.install(login)
        s.absorbSetCookies(other, listOf("SIDCC=zz; Domain=.youtube.com"))
        assertEquals(login, s.cookie)
        s.absorbSetCookies(login, listOf("SAPISID=; Max-Age=0; Domain=.youtube.com"))
        assertEquals(login, s.cookie, "an answer that takes the signing secret away is not a rotation")
        s.absorbSetCookies(null, listOf("SIDCC=zz"))
        assertEquals(login, s.cookie)
    }

    @Test fun persistenceIsThrottled() = runTest {
        val s = session(persistMs = 30_000)
        val vault = RecordingVault()
        s.vault = vault
        s.install(login)
        s.absorbSetCookies(login, listOf("SIDCC=cc1; Domain=.youtube.com"))
        assertEquals(1, vault.stored.size, "the first change is written at once")
        s.absorbSetCookies(login, listOf("SIDCC=cc2; Domain=.youtube.com"))
        s.absorbSetCookies(login, listOf("SIDCC=cc3; Domain=.youtube.com"))
        assertEquals(1, vault.stored.size)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, vault.stored.size, "the later ones are written together, once")
        assertTrue(vault.stored.last().contains("SIDCC=cc3"))
    }

    // ---- Expiry without a refresh -----------------------------------------------------------------------------------------

    @Test fun withoutATransportRefusalsInARowExpireAndAnAcceptedRequestClearsIt() = runTest {
        val s = session()
        s.install(login)
        repeat(YouTubeSession.STRIKES - 1) { s.reportRejected(login, 401) }
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        s.reportRejected(login, 500)
        s.reportRejected(login, 403)
        assertIs<YouTubeAuthState.Expired>(s.state.value)
        s.reportAccepted(login)
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        s.reportLoggedOut(login, "signed out")
        assertIs<YouTubeAuthState.Expired>(s.state.value)
        s.reportLoggedIn(login)
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        s.reportLoggedOut(other, "another login")
        assertIs<YouTubeAuthState.SignedIn>(s.state.value, "what another login was told is not this one's")
    }

    @Test fun thePlatformsExpiryStandsUntilTheNextSignIn() = runTest {
        val s = session()
        s.markExpired("could not renew")
        assertEquals(YouTubeAuthState.Expired("could not renew", null, null), s.state.value)
        s.install(login)
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
    }

    // ---- The silent refresh ---------------------------------------------------------------------------------------------

    @Test fun theFirstSignedRequestWaitsForARotationAndItsCookiesAreKept() = runTest {
        val transport = FakeTransport()
        transport.rotate = { url, _ ->
            if (url == YouTubeSession.ROTATE_YOUTUBE_URL) {
                SessionTransport.Answer(200, listOf("__Secure-1PSIDTS=ts1; Domain=.youtube.com; Secure", "__Secure-3PSIDTS=t3; Domain=.youtube.com"))
            } else {
                SessionTransport.Answer(200, listOf("__Secure-1PSIDTS=g1; Domain=.google.com"))
            }
        }
        val s = session(transport)
        val vault = RecordingVault()
        s.vault = vault
        s.install(login)
        s.installGoogle("SID=g; SAPISID=gsecret; __Secure-1PSIDTS=g0")
        s.ensureFresh()
        assertEquals(listOf(YouTubeSession.ROTATE_YOUTUBE_URL, YouTubeSession.ROTATE_GOOGLE_URL), transport.rotations.map { it.first })
        assertEquals(login, transport.rotations.first().second, "the YouTube jar goes to the YouTube rotation")
        assertTrue(s.cookie!!.contains("__Secure-1PSIDTS=ts1") && s.cookie!!.contains("__Secure-3PSIDTS=t3"))
        assertTrue(s.googleCookie!!.contains("__Secure-1PSIDTS=g1"))
        assertTrue(vault.stored.single().contains("ts1"))
        assertTrue(vault.storedGoogle.single().contains("g1"))
        assertTrue(transport.pages.isEmpty(), "a routine rotation checks no page")

        // Fresh: the next request does not rotate again until the rotation is old.
        s.ensureFresh()
        assertEquals(2, transport.rotations.size)
        advanceTimeBy(YouTubeSession.ROTATE_EVERY_MS + 1)
        s.ensureFresh()
        runCurrent()
        assertEquals(4, transport.rotations.size, "an old rotation starts another (both jars), in the background")
    }

    @Test fun aRefusedRequestRotatesAndIsWorthRetrying() = runTest {
        val transport = FakeTransport()
        transport.rotate = { _, _ -> SessionTransport.Answer(200, listOf("__Secure-1PSIDTS=ts1; Domain=.youtube.com")) }
        val s = session(transport)
        s.install(login)
        assertEquals(YouTubeSession.Refresh.ROTATED, s.refreshAfterRejection(login))
        assertEquals(1, transport.pages.size, "after a refusal the session is checked")
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        // A burst of refusals shares that one refresh.
        assertEquals(YouTubeSession.Refresh.ROTATED, s.refreshAfterRejection(login))
        assertEquals(1, transport.rotations.size)
    }

    @Test fun aSignedOutPageIsRecoveredFromThePlatformBeforeAnyExpiry() = runTest {
        val transport = FakeTransport()
        val fresher = "SID=1; SAPISID=secret; __Secure-1PSIDTS=fromWebView; SIDCC=cc9"
        transport.page = { cookie ->
            SessionTransport.Answer(200, emptyList(), if ("fromWebView" in cookie) SIGNED_IN_PAGE else SIGNED_OUT_PAGE)
        }
        val s = session(transport)
        val vault = RecordingVault()
        s.vault = vault
        var asked = 0
        s.recovery = SessionRecovery { asked += 1; SessionRecovery.Recovered(fresher) }
        s.install(login)
        val recoveries = s.recoveries
        assertEquals(YouTubeSession.Refresh.ROTATED, s.refresh(verify = true))
        assertEquals(1, asked)
        assertEquals(fresher, s.cookie)
        assertEquals(recoveries + 1, s.recoveries)
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        assertEquals(fresher, vault.stored.last())
    }

    @Test fun anotherLoginFromThePlatformIsRefusedAndOnlyThenTheSignInExpires() = runTest {
        val transport = FakeTransport()
        transport.page = { SessionTransport.Answer(200, emptyList(), SIGNED_OUT_PAGE) }
        val s = session(transport)
        s.recovery = SessionRecovery { SessionRecovery.Recovered(other) }
        s.install(login)
        assertEquals(YouTubeSession.Refresh.EXPIRED, s.refresh(verify = true, reason = "HTTP 401"))
        assertEquals(login, s.cookie, "another login's jar is never taken")
        assertEquals(YouTubeAuthState.Expired("HTTP 401", null, null), s.state.value)
    }

    @Test fun anUnreadablePageConcludesNothing() = runTest {
        val transport = FakeTransport()
        transport.page = { SessionTransport.Answer(503, emptyList()) }
        val s = session(transport)
        s.install(login)
        assertEquals(YouTubeSession.Refresh.UNKNOWN, s.refresh(verify = true))
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
    }

    @Test fun aLoggedOutPageWithATransportRefreshesBeforeExpiring() = runTest {
        val transport = FakeTransport()
        val s = session(transport)
        s.install(login)
        s.reportLoggedOut(login, "music.youtube.com is signed out")
        runCurrent()
        assertEquals(1, transport.pages.size, "the page is checked first")
        assertIs<YouTubeAuthState.SignedIn>(s.state.value, "it said signed in, so there is no expiry")
    }

    @Test fun refreshesAreSingleFlight() = runTest {
        val transport = FakeTransport()
        transport.gate = CompletableDeferred()
        val s = session(transport)
        s.install(login)
        val first = async { s.refresh(verify = false) }
        val second = async { s.refresh(verify = false) }
        runCurrent()
        transport.gate!!.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, transport.rotations.size, "the second waited for the first and took its answer")
    }

    @Test fun aRefreshThatEndsAfterASwitchIsDiscarded() = runTest {
        val transport = FakeTransport()
        transport.gate = CompletableDeferred()
        transport.rotate = { _, _ -> SessionTransport.Answer(401, listOf("__Secure-1PSIDTS=late; Domain=.youtube.com")) }
        transport.page = { SessionTransport.Answer(200, emptyList(), SIGNED_OUT_PAGE) }
        val s = session(transport)
        s.install(login)
        val running = async { s.refresh(verify = true) }
        runCurrent()
        s.install(other)
        transport.gate!!.complete(Unit)
        assertEquals(YouTubeSession.Refresh.DISCARDED, running.await())
        assertEquals(other, s.cookie, "the new session is untouched")
        assertIs<YouTubeAuthState.SignedIn>(s.state.value)
        assertTrue(transport.pages.isEmpty())
    }

    @Test fun signedOutThereIsNothingToRefresh() = runTest {
        val transport = FakeTransport()
        val s = session(transport)
        s.ensureFresh()
        assertEquals(YouTubeSession.Refresh.DISCARDED, s.refresh(verify = true))
        assertTrue(transport.rotations.isEmpty())
        assertEquals(YouTubeAuthState.SignedOut, s.state.value)
    }

    private companion object {
        const val START = 1_800_000_000_000L
        const val SIGNED_IN_PAGE = """<script>ytcfg.set({"LOGGED_IN":true,"INNERTUBE_CLIENT_VERSION":"2.20261101.07.00"})</script>"""
        const val SIGNED_OUT_PAGE = """<script>ytcfg.set({"LOGGED_IN":false})</script>"""
    }
}
