package com.cosmotune.manga.data

import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Public-domain novels from Wikisource, through Wikimedia's open API (no key, no account): the works in its Novels category,
 * each work's contents page as the chapters, and each chapter's text without the site's header, page numbers and notes.
 */
class WikisourceNovelSource(override val config: MangaSourceConfig, client: OkHttpClient) : MangaSource, MangaTextSource {
    private val language = config.language.ifBlank { "en" }
    private val api = "https://$language.wikisource.org/w/api.php".toHttpUrl()
    private val http = client.newBuilder().followRedirects(true).followSslRedirects(false).build()
    override val verificationWebsite: String? get() = null
    init { require(config.kind == KIND && language.matches(Regex("[a-z]{2,3}"))) }

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
        require(offset in 0..10_000 && query.length <= 300)
        val terms = listOfNotNull(CATEGORY[language]?.let { "incategory:\"$it\"" }, query.trim().takeIf { it.isNotEmpty() }).joinToString(" ")
        val body = get(mapOf("action" to "query", "list" to "search", "srsearch" to terms.ifEmpty { "*" }, "srnamespace" to "0", "srlimit" to "30",
            "sroffset" to offset.toString(), "srprop" to "", "srsort" to if (query.isBlank()) "incoming_links_desc" else "relevance"))
        return searchPage(config, body)
    }

    override suspend fun details(book: MangaBook): MangaBook {
        require(book.sourceId == config.id)
        val title = pageTitle(config, book.url) ?: error("This is not a Wikisource work")
        val html = parse(title)
        val chapters = chapters(config, book, title, chapterLinks(title, html))
        val intro = MangaNovelText.parse(html, "", REMOVE).blocks.firstOrNull { it.kind == MangaNovelBlock.Kind.PARAGRAPH && it.text.length >= 80 }?.text.orEmpty()
        return book.copy(authors = authors(html).ifEmpty { book.authors }, description = intro.take(2_000), language = language, chapters = chapters)
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = error("This chapter is text")

    override suspend fun chapterText(chapter: MangaChapter): MangaNovelChapter {
        require(chapter.sourceId == config.id)
        val title = pageTitle(config, chapter.url) ?: error("This is not a Wikisource chapter")
        return chapterText(chapter.title, parse(title)).also { require(it.blocks.isNotEmpty()) { "This chapter has no text" } }
    }

    private suspend fun parse(title: String): String {
        val body = get(mapOf("action" to "parse", "page" to title, "prop" to "text", "redirects" to "1", "disableeditsection" to "1", "disabletoc" to "1"))
        (body["error"] as? JsonObject)?.let { error((it["info"]?.jsonPrimitive?.contentOrNull ?: "Wikisource could not open this page").take(200)) }
        return ((body["parse"] as? JsonObject)?.get("text"))?.jsonPrimitive?.contentOrNull ?: error("Wikisource sent no text for this page")
    }

    private suspend fun get(query: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val url = api.newBuilder().apply {
            (query + mapOf("format" to "json", "formatversion" to "2")).forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Wikisource returned HTTP ${response.code}" }
            val bytes = response.body.byteStream().use { it.readNBytes(LIMIT + 1) }
            require(bytes.size <= LIMIT) { "This Wikisource page is too large" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("Wikisource sent an unexpected answer")
        }
    }

    /** A link on a contents page to one of the work's own pages ([page], its title) and the words it shows. */
    data class Link(val page: String, val title: String)

    companion object {
        const val KIND = "wikisource"
        private const val LIMIT = 6 * 1024 * 1024
        private const val USER_AGENT = "Cosmotune/1.0 (reader app; Wikisource novels)"
        private val json = Json { ignoreUnknownKeys = true }
        /** The category of novels on each Wikisource; elsewhere the whole site is searched. */
        private val CATEGORY = mapOf("en" to "Novels")
        /** The site's header, page numbers, notes and editing links, which are not part of the story. */
        private const val REMOVE = ".ws-noexport, .noprint, .wst-header, #headertemplate, .ws-pagenum, .pagenum, .mw-editsection, sup.reference, " +
            "ol.references, .reflist, .mw-references-wrap, .ws-summary, .licenseContainer, .wst-license-container, .navbox, table.toccolours"

        fun config(language: String = "en") = MangaSourceConfig("$KIND:$language", KIND, "Wikisource novels", language, "https://$language.wikisource.org")
        val descriptors = listOf(MangaSourceDescriptor("$KIND:en", "Wikisource novels", "en", KIND))

        internal fun searchPage(config: MangaSourceConfig, body: JsonObject): MangaBrowsePage {
            val results = ((body["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty()
            val books = results.mapNotNull { item ->
                val title = ((item as? JsonObject)?.get("title"))?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && '/' !in it } ?: return@mapNotNull null
                MangaBook(id(config, "book", title), config.id, title.take(500), address(config, title), language = config.language)
            }
            val next = ((body["continue"] as? JsonObject)?.get("sroffset"))?.jsonPrimitive?.intOrNull
            return MangaBrowsePage(books, next?.takeIf { results.isNotEmpty() })
        }

        /** The work's own pages linked from its contents page, in the order they stand there, each once. */
        internal fun chapterLinks(work: String, html: String): List<Link> = Jsoup.parse(html).select("a[href]").mapNotNull { link ->
            val href = link.attr("href").substringBefore('#')
            if (!href.startsWith("/wiki/")) return@mapNotNull null
            val page = runCatching { URLDecoder.decode(href.removePrefix("/wiki/"), Charsets.UTF_8) }.getOrNull()?.replace('_', ' ') ?: return@mapNotNull null
            if (!page.startsWith("$work/") || page.length == work.length + 1) return@mapNotNull null
            Link(page, link.text().trim().ifEmpty { page.substringAfterLast('/') }.take(500))
        }.distinctBy { it.page }.take(5_000)

        internal fun authors(html: String): List<String> = Jsoup.parse(html).select(".wst-header a[href^=\"/wiki/Author:\"], #ws-author")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().take(5)

        /** The chapters of [book]: its linked pages, or the work's own page when it has none (a short story). */
        internal fun chapters(config: MangaSourceConfig, book: MangaBook, work: String, links: List<Link>): List<MangaChapter> =
            (links.ifEmpty { listOf(Link(work, work)) }).mapIndexed { index, link ->
                MangaChapter(id(config, "chapter", link.page), book.id, config.id, link.title, address(config, link.page), (index + 1).toFloat(), config.language, text = true)
            }

        internal fun chapterText(title: String, html: String): MangaNovelChapter = MangaNovelText.parse(html, title, REMOVE)

        /** The page title a Wikisource address of this source's language names, or null for any other address. */
        internal fun pageTitle(config: MangaSourceConfig, address: String): String? {
            val url = runCatching { address.toHttpUrl() }.getOrNull() ?: return null
            if (!url.isHttps || url.host != "${config.language.ifBlank { "en" }}.wikisource.org" || url.pathSegments.firstOrNull() != "wiki") return null
            return url.pathSegments.drop(1).joinToString("/").replace('_', ' ').takeIf { it.isNotBlank() }
        }

        private fun address(config: MangaSourceConfig, title: String) =
            "https://${config.language.ifBlank { "en" }}.wikisource.org/wiki/" + title.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment.replace(' ', '_'), Charsets.UTF_8).replace("%28", "(").replace("%29", ")").replace("%2C", ",").replace("%27", "'")
                    .replace("%3A", ":").replace("%21", "!")
            }

        private fun id(config: MangaSourceConfig, kind: String, value: String) = config.id + ":" +
            MessageDigest.getInstance("SHA-256").digest("$kind\n$value".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
