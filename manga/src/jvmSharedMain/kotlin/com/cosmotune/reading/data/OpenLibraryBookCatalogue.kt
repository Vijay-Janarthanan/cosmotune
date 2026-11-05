package com.cosmotune.reading.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Searches commercial and public-domain book metadata without treating a search hit as a readable copy. */
class OpenLibraryBookCatalogue internal constructor(private val userAgent: String, client: OkHttpClient, private val endpoint: HttpUrl) {
    constructor(userAgent: String, client: OkHttpClient = OkHttpClient()) : this(userAgent, client, "https://openlibrary.org/search.json".toHttpUrl())
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = Mutex()
    private val cache = LinkedHashMap<String, Pair<Long, JsonObject>>()
    private var lastRequest = 0L
    init { require(userAgent.isNotBlank() && userAgent.none { it == '\r' || it == '\n' }) { "Identify the app and its contact in the API user agent." } }

    /** One explicit user search/page; the caller must debounce, cache and respect the provider's published rate limits. */
    suspend fun search(query: String, offset: Int = 0): ReadingSearchPage = searchPage(query, offset, null)
    // Provider-documented Lucene range: a bare wildcard is rejected by the public search API (HTTP 422).
    suspend fun trending(offset: Int = 0): ReadingSearchPage = searchPage("trending_z_score:{0 TO *]", offset, "trending")

    private suspend fun searchPage(query: String, offset: Int, sort: String?): ReadingSearchPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..500 && offset in 0..10_000) { "Use a search of 2–500 characters and a bounded offset." }
        val url = endpoint.newBuilder().addQueryParameter("q", query.trim()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("fields", "key,title,author_name,language,isbn,cover_i,ebook_access,edition_key")
            .apply { sort?.let { addQueryParameter("sort", it) } }.build()
        currentCoroutineContext().ensureActive()
        val root = getJson(url)
        val docs = (root["docs"] as? JsonArray).orEmpty()
        require(docs.size <= PAGE_SIZE) { "The metadata provider returned too many results." }
        val books = docs.mapNotNull { entry ->
            val item = entry.jsonObject
            val key = item.text("key")
            if (!key.matches(Regex("/works/OL[0-9]+W|/books/OL[0-9]+M"))) return@mapNotNull null
            val cover = (item["cover_i"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ReadingPublication(id = "openlibrary:$key", title = item.text("title"), authors = item.strings("author_name"),
                languages = item.strings("language"), identifiers = item.strings("isbn").take(20),
                coverUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                links = listOf(ReadingLink("Open Library", "https://openlibrary.org$key", "text/html", "alternate", ReadingLinkKind.DETAILS)),
                accessEvidence = item.text("ebook_access").ifBlank { "metadata-only" }, sourceId = "openlibrary")
        }
        val total = (root["numFound"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: books.size.toLong()
        val next = (offset + docs.size).takeIf { docs.isNotEmpty() && it < total && it <= 10_000 }
        ReadingSearchPage("openlibrary", books, total, next)
    }

    /** Real editions carry physical binding, publisher/date and ISBN metadata; no paperback format is invented for a work. */
    suspend fun editions(publication: ReadingPublication, offset: Int = 0): ReadingSearchPage = withContext(Dispatchers.IO) {
        require(offset in 0..1_000)
        val key = publication.id.removePrefix("openlibrary:")
        require(key.matches(Regex("/works/OL[0-9]+W|/books/OL[0-9]+M")))
        val edition = key.startsWith("/books/")
        val url = endpoint.newBuilder().encodedPath(key + if (edition) ".json" else "/editions.json").query(null)
            .apply { if (!edition) { addQueryParameter("offset", offset.toString()); addQueryParameter("limit", PAGE_SIZE.toString()) } }.build()
        val response = getJson(url)
        val entries = if (edition) listOf(response) else (response["entries"] as? JsonArray).orEmpty().map { it.jsonObject }
        require(entries.size <= PAGE_SIZE)
        val books = entries.mapNotNull { item ->
            val id = item.text("key").takeIf { it.matches(Regex("/books/OL[0-9]+M")) } ?: return@mapNotNull null
            val cover = (item["covers"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val description = item["description"].let { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("value") }.orEmpty()
            publication.copy(id = "openlibrary:$id", title = item.text("title").ifBlank { publication.title },
                description = description.ifBlank { publication.description },
                identifiers = (item.strings("isbn_13") + item.strings("isbn_10")).distinct(),
                coverUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" } ?: publication.coverUrl,
                languages = (item["languages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("key")?.substringAfterLast('/') },
                editionFormats = listOf(item.text("physical_format"), item.text("edition_name")).filter(String::isNotBlank),
                publishers = item.strings("publishers"), publishedDate = item.text("publish_date").takeIf(String::isNotBlank),
                links = listOf(ReadingLink("Open Library edition", "https://openlibrary.org$id", "text/html", "alternate", ReadingLinkKind.DETAILS)),
                accessEvidence = "Edition metadata · check source for readable or borrowable copies")
        }
        val total = (response["size"] as? JsonPrimitive)?.longOrNull ?: books.size.toLong()
        ReadingSearchPage("openlibrary", books, total, (offset + entries.size).takeIf { !edition && entries.isNotEmpty() && it < total && it <= 1_000 })
    }

    private suspend fun getJson(url: HttpUrl): JsonObject = requests.withLock {
        val now = System.currentTimeMillis()
        cache[url.toString()]?.takeIf { now - it.first in 0 until 30 * 60_000L }?.let { return@withLock it.second }
        delay((lastRequest + 1_100L - now).coerceAtLeast(0))
        lastRequest = System.currentTimeMillis()
        val data = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build())
            .awaitReading { response ->
                check(response.isSuccessful && !response.isRedirect) { "Book metadata search returned HTTP ${response.code}." }
                require(response.body.contentLength() <= MAX_BYTES) { "Book metadata exceeds the response limit." }
                val output = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BYTES) { "Book metadata exceeds the response limit." }
                        output.write(buffer, 0, count)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }
        currentCoroutineContext().ensureActive()
        val parsed = json.parseToJsonElement(data).jsonObject
        cache[url.toString()] = System.currentTimeMillis() to parsed
        while (cache.size > 64) cache.remove(cache.keys.first())
        parsed
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.strings(name: String) = (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    companion object { private const val PAGE_SIZE = 20; private const val MAX_BYTES = 2 * 1024 * 1024 }
}
