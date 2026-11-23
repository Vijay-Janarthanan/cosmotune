package com.cosmotune.watch

import com.cosmotune.watch.tracking.ListSignIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one AniList and MyAnimeList sign-in Watch and Manga share: one client id, one set of return addresses, each return to its asker. */
class ListSignInTest {
    private var ids: Map<AnimeTracker, String> = emptyMap()

    @BeforeTest fun setUp() { ListSignIn.savedClientId = { ids[it] } }
    @AfterTest fun tearDown() { ListSignIn.savedClientId = { null } }

    @Test fun withoutAClientIdThereIsNoSignIn() {
        ids = mapOf(AnimeTracker.MYANIMELIST to "12 34; drop")
        assertNull(ListSignIn.clientId(AnimeTracker.ANILIST))
        assertNull(ListSignIn.clientId(AnimeTracker.MYANIMELIST), "a malformed id counts as none")
        assertNull(ListSignIn.start(AnimeTracker.ANILIST, ListSignIn.Asker.MANGA))
    }

    @Test fun bothModesSignInOnTheSameReturnAddresses() {
        ids = mapOf(AnimeTracker.ANILIST to "12345", AnimeTracker.MYANIMELIST to "abcdef0123")
        val anilist = ListSignIn.start(AnimeTracker.ANILIST, ListSignIn.Asker.MANGA)!!.toHttpUrl()
        assertEquals("anilist.co", anilist.host)
        assertEquals("token", anilist.queryParameter("response_type"))
        assertEquals("12345", anilist.queryParameter("client_id"))
        val mal = ListSignIn.start(AnimeTracker.MYANIMELIST, ListSignIn.Asker.WATCH)!!.toHttpUrl()
        assertEquals("cosmotune://watch/tracker/myanimelist", mal.queryParameter("redirect_uri"))
        assertEquals("plain", mal.queryParameter("code_challenge_method"))
    }

    @Test fun eachReturnReachesTheModeThatAsked() = runTest {
        ids = mapOf(AnimeTracker.ANILIST to "12345", AnimeTracker.MYANIMELIST to "abcdef0123")
        val watchState = ListSignIn.start(AnimeTracker.MYANIMELIST, ListSignIn.Asker.WATCH)!!.toHttpUrl().queryParameter("state")!!
        val mangaState = ListSignIn.start(AnimeTracker.MYANIMELIST, ListSignIn.Asker.MANGA)!!.toHttpUrl().queryParameter("state")!!
        assertTrue(watchState != mangaState, "both modes' sign-ins stay open side by side")
        assertNull(ListSignIn.claim("cosmotune://watch/tracker/myanimelist?code=c&state=other"), "a return of no sign-in started here")

        val got = launch { val back = ListSignIn.returns.first(); assertEquals(ListSignIn.Asker.MANGA, back.started.asker); assertEquals("def+1", back.code) }
        yield()
        assertTrue(ListSignIn.deliver("cosmotune://watch/tracker/myanimelist?code=def%2B1&state=$mangaState"), "Manga's return goes out to Manga")
        got.join()

        val watchLink = "cosmotune://watch/tracker/myanimelist?code=w&state=$watchState"
        assertFalse(ListSignIn.deliver(watchLink), "Watch's return is left for Watch")
        assertEquals(ListSignIn.Asker.WATCH, ListSignIn.startedFor(watchLink)?.asker)
        val back = ListSignIn.claim(watchLink)!!
        assertEquals("w", back.code)
        assertTrue(back.started.verifier.isNotEmpty())
        assertNull(ListSignIn.claim(watchLink), "a return is taken once")
    }

    @Test fun aClientIdSavedInWatchServesMangaWithoutWatchOpening() = runTest {
        // What the apps do at start: only Watch's store is read, no Watch repository or anime tracking is made.
        val dir = kotlin.io.path.createTempDirectory("list-sign-in").toFile()
        try {
            val store = com.cosmotune.watch.store.WatchStore(java.io.File(dir, "watch.json"), com.cosmotune.watch.store.UnsealedWatchFiles)
            store.update { it.copy(settings = it.settings + (ListSignIn.savedClientIdKey(AnimeTracker.MYANIMELIST) to "abcdef0123")) }
            var opened = 0
            ListSignIn.useSavedClientIds { opened++; store }
            assertEquals(0, opened, "the store is opened only when an id is asked for")
            assertEquals("abcdef0123", ListSignIn.clientId(AnimeTracker.MYANIMELIST))
            assertNull(ListSignIn.clientId(AnimeTracker.ANILIST))
            assertTrue(ListSignIn.start(AnimeTracker.MYANIMELIST, ListSignIn.Asker.MANGA)!!.contains("client_id=abcdef0123"))
            assertEquals(1, opened)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aniListsTokenWithoutAStateAnswersTheLatestAniListSignIn() {
        ids = mapOf(AnimeTracker.ANILIST to "12345")
        ListSignIn.start(AnimeTracker.ANILIST, ListSignIn.Asker.WATCH)
        val back = ListSignIn.claim("cosmotune://watch/tracker/anilist#access_token=tok.en&token_type=Bearer&expires_in=31536000")!!
        assertEquals("tok.en", back.token)
        assertEquals(31_536_000L, back.expiresInS)
        assertEquals(ListSignIn.Asker.WATCH, back.started.asker)
    }
}
