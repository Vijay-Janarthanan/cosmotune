package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A user-configured Komga library. Authentication is sent only to that configured origin. */
class KomgaSource(override val config: MangaSourceConfig, private val client: OkHttpClient) : MangaSource {
    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val headers: Map<String, String> get() = config.settings["apiKey"]?.takeIf { it.isNotBlank() }
        ?.let { mapOf("X-API-Key" to it) }.orEmpty()
    init { require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("api/v1/$path").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        // Avoid forwarding a private server API key across redirects.
        client.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Komga returned HTTP ${response.code}" }
            val body = response.body ?: error("Empty Komga response")
            require(body.contentLength() <= 2 * 1024 * 1024)
            val bytes = body.byteStream().use { it.readNBytes(2 * 1024 * 1024 + 1) }
            require(bytes.size <= 2 * 1024 * 1024) { "Komga response exceeds the metadata limit" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }
    override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
        val root = get("series", mapOf("search" to query, "page" to (offset / 40).toString(), "size" to "40", "sort" to "metadata.titleSort,asc")).jsonObject
        val books = root["content"]?.jsonArray.orEmpty().map { convert(it.jsonObject) }
        return MangaBrowsePage(books, if (root["last"]?.jsonPrimitive?.booleanOrNull == true || books.isEmpty()) null else offset + 40)
    }
    override suspend fun details(book: MangaBook): MangaBook {
        val seriesId = book.id.substringAfterLast(':')
        val chapters = ArrayList<MangaChapter>()
        var page = 0
        do {
            val root = get("series/$seriesId/books", mapOf("page" to page.toString(), "size" to "100", "sort" to "metadata.numberSort,asc")).jsonObject
            val items = root["content"]?.jsonArray.orEmpty()
            chapters += items.map { value ->
                val item = value.jsonObject
                val id = item.text("id")
                val metadata = item["metadata"]?.jsonObject ?: JsonObject(emptyMap())
                MangaChapter("${config.id}:book:$id", book.id, config.id, metadata.text("title").ifBlank { item.text("name") }, id,
                    metadata["numberSort"]?.jsonPrimitive?.floatOrNull ?: 0f)
            }
            page++
        } while (root["last"]?.jsonPrimitive?.booleanOrNull == false && items.isNotEmpty() && page < 100)
        return book.copy(chapters = chapters)
    }
    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = get("books/${chapter.url}/pages").jsonArray.map { value ->
        val number = value.jsonObject["number"]?.jsonPrimitive?.int ?: error("Missing Komga page number")
        val url = base.newBuilder().addPathSegments("api/v1/books/${chapter.url}/pages/$number").build()
        MangaPage("${chapter.id}:$number", url = url.toString(), headers = headers)
    }
    private fun convert(item: JsonObject): MangaBook {
        val id = item.text("id")
        val metadata = item["metadata"]?.jsonObject ?: JsonObject(emptyMap())
        return MangaBook("${config.id}:$id", config.id, metadata.text("title").ifBlank { item.text("name") },
            description = metadata.text("summary"), language = metadata.text("language"))
    }
    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
