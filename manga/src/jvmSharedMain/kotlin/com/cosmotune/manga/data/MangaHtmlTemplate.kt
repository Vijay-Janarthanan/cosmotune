package com.cosmotune.manga.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

@Serializable data class MangaListTemplate(val endpoint: String = "/", val method: String = "GET", val pagination: String = "page",
    val pageParam: String = "page", val pageSize: Int = 20, val itemSelector: String, val titleSelector: String = "a",
    val linkSelector: String = "a", val coverSelector: String = "img", val searchEndpoint: String = "/",
    val searchParam: String = "s", val searchParams: Map<String, String> = emptyMap(), val parameters: Map<String, String> = emptyMap())
@Serializable data class MangaDetailTemplate(val titleSelector: String = "h1", val coverSelector: String = "",
    val descriptionSelector: String = "", val authorSelector: String = "", val genreSelector: String = "")
@Serializable data class MangaChapterTemplate(val selector: String, val titleSelector: String = "a", val linkSelector: String = "a",
    val newestFirst: Boolean = true, val endpoint: String = "", val method: String = "GET", val parameters: Map<String, String> = emptyMap())
@Serializable data class MangaPageTemplate(val imageSelector: String)

/** Declarative HTML selectors only. Templates do not execute JavaScript or grant app credentials. */
@Serializable data class MangaHtmlTemplate(val name: String, val version: String = "1", val type: String = "html",
    val mangaList: MangaListTemplate, val mangaDetail: MangaDetailTemplate = MangaDetailTemplate(),
    val chapterList: MangaChapterTemplate, val pageList: MangaPageTemplate) {
    fun validate(): MangaHtmlTemplate = apply {
        require(type == "html") { "This template needs an unsupported content type" }
        require(name.isNotBlank() && name.length <= 120 && version.length <= 32)
        require(mangaList.pageSize in 1..100 && mangaList.pagination in setOf("page", "offset", "path", "wordpress", "none"))
        require(mangaList.method in setOf("GET", "POST") && chapterList.method in setOf("GET", "POST"))
        val selectors = listOf(mangaList.itemSelector, mangaList.titleSelector, mangaList.linkSelector, mangaList.coverSelector,
            mangaDetail.titleSelector, mangaDetail.coverSelector, mangaDetail.descriptionSelector, mangaDetail.authorSelector,
            mangaDetail.genreSelector, chapterList.selector, chapterList.titleSelector, chapterList.linkSelector, pageList.imageSelector)
        require(mangaList.itemSelector.isNotBlank() && chapterList.selector.isNotBlank() && pageList.imageSelector.isNotBlank())
        val document = Jsoup.parse("<html></html>")
        selectors.forEach { selector ->
            require(selector.length <= 1_024 && !selector.contains(":matches", true)) { "Template selector is too complex" }
            if (selector.isNotBlank()) document.select(selector)
        }
        listOf(mangaList.endpoint, mangaList.searchEndpoint, chapterList.endpoint).forEach { require(it.length <= 4_096) }
        listOf(mangaList.parameters, mangaList.searchParams, chapterList.parameters).forEach { values ->
            require(values.size <= 50 && values.all { it.key.length <= 120 && it.value.length <= 4_096 })
        }
    }
    fun encode(): String = json.encodeToString(this)
    fun configuration(baseUrl: String, sourceName: String, language: String = "", localNetwork: Boolean = false): MangaSourceConfig {
        validate()
        val url = baseUrl.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a site address without credentials or query parameters" }
        require(url.isHttps || localNetwork) { "Use HTTPS, or explicitly allow a local server" }
        require(sourceName.isNotBlank() && sourceName.length <= 120 && language.length <= 32)
        val canonical = url.toString().trimEnd('/')
        val id = MessageDigest.getInstance("SHA-256").digest((canonical + "\n" + name).toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
        return MangaSourceConfig("template:$id", "template", sourceName.trim(), language.trim(), canonical,
            mapOf("template" to encode(), "allowLocalNetwork" to localNetwork.toString()))
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun parse(text: String): MangaHtmlTemplate {
            require(text.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Template exceeds 128 KB" }
            return json.decodeFromString<MangaHtmlTemplate>(text).validate()
        }
        val presets: List<MangaHtmlTemplate> = listOf(
            MangaHtmlTemplate("WordPress Madara", mangaList = MangaListTemplate(endpoint = "/manga/", pagination = "wordpress", pageSize = 12,
                itemSelector = ".page-item-detail, .c-tabs-item__content", titleSelector = ".post-title a", linkSelector = ".post-title a",
                searchParams = mapOf("post_type" to "wp-manga")),
                mangaDetail = MangaDetailTemplate(coverSelector = ".summary_image img", descriptionSelector = ".summary__content, .description-summary", authorSelector = ".author-content a", genreSelector = ".genres-content a"),
                chapterList = MangaChapterTemplate("li.wp-manga-chapter", endpoint = "{bookUrl}/ajax/chapters/", method = "POST"),
                pageList = MangaPageTemplate(".reading-content img, .read-container img")),
            MangaHtmlTemplate("WordPress MangaThemesia", mangaList = MangaListTemplate(endpoint = "/manga/", itemSelector = ".bs .bsx", titleSelector = ".tt", linkSelector = "a"),
                mangaDetail = MangaDetailTemplate(coverSelector = ".thumb img", descriptionSelector = ".entry-content", genreSelector = ".mgen a"),
                chapterList = MangaChapterTemplate("#chapterlist li", titleSelector = ".chapternum"),
                pageList = MangaPageTemplate("#readerarea img, .reader-area img")),
            MangaHtmlTemplate("WordPress MangaStream", mangaList = MangaListTemplate(endpoint = "/manga/", itemSelector = ".bs .bsx, .listupd .bs", titleSelector = ".tt, h2", linkSelector = "a"),
                mangaDetail = MangaDetailTemplate(coverSelector = ".thumb img", descriptionSelector = ".entry-content, .desc"),
                chapterList = MangaChapterTemplate("#chapterlist li, .eplister li", titleSelector = ".chapternum, .epl-num"),
                pageList = MangaPageTemplate("#readerarea img, .reader-area img")),
        )
    }
}
