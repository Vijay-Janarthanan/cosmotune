package com.cosmotune.manga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaListSignInTest {
    @Test fun withoutAClientIdThereIsNoSignIn() {
        val clients = MangaListClients(anilist = "", myanimelist = " ")
        assertNull(clients.id(MangaListService.ANILIST))
        assertNull(clients.id(MangaListService.MYANIMELIST))
        assertNull(MangaListSignIn(clients).start(MangaListService.ANILIST))
        assertEquals("Sign-in needs a client id", MangaListService.ANILIST.unavailable(clients))
        assertNull(MangaListService.ANILIST.unavailable(MangaListClients(anilist = "12345")))
        assertNull(MangaListClients(anilist = "12 34; drop").id(MangaListService.ANILIST), "a malformed id counts as none")
    }

    @Test fun theSignInPagesUseTheWatchReturnAddresses() {
        val signIn = MangaListSignIn(MangaListClients(anilist = "12345", myanimelist = "abcdef0123"))
        val anilist = signIn.start(MangaListService.ANILIST)!!.toHttpUrl()
        assertEquals("anilist.co", anilist.host)
        assertEquals("token", anilist.queryParameter("response_type"))
        assertEquals("12345", anilist.queryParameter("client_id"))
        val mal = signIn.start(MangaListService.MYANIMELIST)!!.toHttpUrl()
        assertEquals("myanimelist.net", mal.host)
        assertEquals("cosmotune://watch/tracker/myanimelist", mal.queryParameter("redirect_uri"))
        assertEquals("plain", mal.queryParameter("code_challenge_method"))
        val challenge = mal.queryParameter("code_challenge")!!
        assertTrue(challenge.length in 43..128 && challenge.all { it.isLetterOrDigit() || it in "-._~" })
        assertEquals("cosmotune://watch/tracker/anilist", MangaListService.ANILIST.redirect)
    }

    @Test fun onlyTheReturnOfASignInMangaStartedIsTaken() {
        val signIn = MangaListSignIn(MangaListClients(anilist = "12345", myanimelist = "abcdef0123"))
        assertNull(signIn.claim("cosmotune://watch/tracker/anilist#access_token=t&state=x"), "nothing was started")
        val state = signIn.start(MangaListService.MYANIMELIST)!!.toHttpUrl().queryParameter("state")!!
        assertNull(signIn.claim("cosmotune://watch/tracker/myanimelist?code=c&state=other"), "another attempt's return is left for Watch")
        assertNull(signIn.claim("cosmotune://watch/pack?d=abc"))
        val back = signIn.claim("cosmotune://watch/tracker/myanimelist?code=def%2B1&state=$state")!!
        assertEquals(MangaListService.MYANIMELIST, back.service)
        assertEquals("def+1", back.code)
        assertTrue(back.verifier.isNotEmpty())
        assertNull(signIn.claim("cosmotune://watch/tracker/myanimelist?code=def&state=$state"), "a return is taken once")
        val aniState = signIn.start(MangaListService.ANILIST)!!.toHttpUrl().queryParameter("state")!!
        val ani = signIn.claim("cosmotune://watch/tracker/anilist#access_token=tok.en&token_type=Bearer&expires_in=31536000&state=$aniState")!!
        assertEquals("tok.en", ani.token)
        assertEquals(31_536_000L, ani.expiresInS)
        val denied = signIn.start(MangaListService.ANILIST)!!.toHttpUrl().queryParameter("state")!!
        assertEquals("access_denied", signIn.claim("cosmotune://watch/tracker/anilist?error=access_denied&state=$denied")!!.error)
    }

    @Test fun searchAnswersBecomeMatches() {
        val anilist = Json.parseToJsonElement("""{"data":{"Page":{"media":[
            {"id":30013,"title":{"romaji":"One Piece","english":"One Piece"},"format":"MANGA","startDate":{"year":1997},"coverImage":{"medium":"https://s4.anilist.co/c.jpg"},"siteUrl":"https://anilist.co/manga/30013"},
            {"id":2,"title":{"romaji":"Wan Pīsu Party","english":null},"format":"ONE_SHOT","startDate":{"year":null},"coverImage":null,"siteUrl":"https://anilist.co/manga/2"}
        ]}}}""") as JsonObject
        val ani = MangaListApi.aniListMatches(anilist)
        assertEquals(listOf("30013", "2"), ani.map { it.remoteId })
        assertEquals(listOf("One Piece", "Wan Pīsu Party"), ani.map { it.title })
        assertEquals("Manga · 1997", ani[0].detail)
        assertEquals("One shot", ani[1].detail)
        val mal = Json.parseToJsonElement("""{"data":[{"node":{"id":13,"title":"One Piece","main_picture":{"medium":"https://cdn.myanimelist.net/13.jpg"},"media_type":"manga","start_date":"1997-07-22"}}]}""") as JsonObject
        val found = MangaListApi.malMatches(mal).single()
        assertEquals("13", found.remoteId)
        assertEquals("Manga · 1997", found.detail)
        assertEquals("https://myanimelist.net/manga/13", found.url)
    }

    @Test fun aListIsNeverMovedBackwards() {
        assertTrue(MangaListApi.shouldSend(read = 12, listed = 10))
        assertFalse(MangaListApi.shouldSend(read = 9, listed = 10))
        assertFalse(MangaListApi.shouldSend(read = 0, listed = 0))
    }
}
