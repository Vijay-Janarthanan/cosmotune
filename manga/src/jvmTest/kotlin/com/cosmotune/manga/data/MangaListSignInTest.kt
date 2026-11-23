package com.cosmotune.manga.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaListSignInTest {
    @Test fun withoutAClientIdThereIsNoSignIn() {
        assertEquals("Sign-in needs a client id", MangaListService.ANILIST.unavailable(clientId = null))
        assertNull(MangaListService.ANILIST.unavailable(clientId = "12345"))
    }

    @Test fun theListsUseWatchsReturnAddresses() {
        assertEquals("cosmotune://watch/tracker/anilist", MangaListService.ANILIST.redirect)
        assertEquals("cosmotune://watch/tracker/myanimelist", MangaListService.MYANIMELIST.redirect)
        assertEquals(MangaListService.MYANIMELIST, MangaListService.of(com.cosmotune.watch.AnimeTracker.MYANIMELIST))
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
