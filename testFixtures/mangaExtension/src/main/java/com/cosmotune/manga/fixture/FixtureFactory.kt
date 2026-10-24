package com.cosmotune.manga.fixture

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import eu.kanade.tachiyomi.network.decodeFromJsonResponse
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody

class FixtureFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(FixtureHttpSource(), FixtureDirectSource())
}

class FixtureHttpSource : HttpSource(), ConfigurableSource {
    override val id = 42L
    override val name = "HTTP API fixture"
    override val lang = "en"
    override val baseUrl = "https://example.invalid"
    override val supportsLatest = false
    override suspend fun getPopularManga(page: Int): MangasPage {
        check(client.interceptors.first().javaClass.simpleName == "UncaughtExceptionInterceptor")
        check(client.interceptors.any { it.javaClass.simpleName == "CloudflareInterceptor" })
        check(client.interceptors.any { it.javaClass.simpleName == "UserAgentInterceptor" })
        check(client.cookieJar is eu.kanade.tachiyomi.network.AndroidCookieJar)
        val response = Response.Builder().request(Request.Builder().url(baseUrl).build()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Fixture").body("\"தமிழ்\"".toResponseBody()).build()
        check(decodeFromJsonResponse(Json, JsonElement.serializer(), response).jsonPrimitive.content == "தமிழ்")
        return MangasPage(listOf(book()), false)
    }
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = getPopularManga(page)
    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = update(manga)
    override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, imageUrl = "$baseUrl/page.png"))
    override fun imageRequest(page: Page): Request = Request.Builder().url(requireNotNull(page.imageUrl))
        .header("X-Fixture-Request", "source-owned").post("page=0&token=fixture-body".toRequestBody()).build()
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply { key = "fixture_value"; title = "Fixture setting" })
    }
}

class FixtureDirectSource : Source {
    override val id = 43L
    override val name = "Direct API fixture"
    override val lang = "en"
    override val supportsLatest = false
    override suspend fun getPopularManga(page: Int) = MangasPage(listOf(book()), false)
    override suspend fun getLatestUpdates(page: Int) = getPopularManga(page)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = getPopularManga(page)
    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = update(manga)
    override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, imageUrl = "https://example.invalid/page.png"))
}

private fun book() = SManga.create().apply {
    url = "/fixture"; title = "Fixture comic"; author = "Fixture author"
    memo = buildJsonObject { put("fixture", "preserved") }
}
private fun update(manga: SManga) = SMangaUpdate(manga.copy(), listOf(SChapter.create().apply {
    url = "/fixture/chapter-1"; name = "Chapter one"; chapter_number = 1f
    memo = buildJsonObject { put("chapter", "preserved") }
}))
