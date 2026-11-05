package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A user-configured Komga library. Authentication (an API key, or a user name and password) is added only to requests for that
 * configured origin, never stored in page records, and never forwarded across redirects.
 */
class KomgaSource(override val config: MangaSourceConfig, client: OkHttpClient) : MangaSource, MangaImageTransport {
    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val authorization: Pair<String, String>? get() {
        config.settings["apiKey"]?.takeIf { it.isNotBlank() }?.let { return "X-API-Key" to it }
        val user = config.settings["username"]?.takeIf { it.isNotBlank() } ?: return null
        return "Authorization" to Credentials.basic(user, config.settings["password"].orEmpty(), Charsets.UTF_8)
    }
    // Avoid forwarding a private server key across redirects.
    override val imageClient: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    init { require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) }

    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    override fun imageRequest(page: MangaPage): Request {
        require(page.requestMethod == "GET") { "Reload this chapter to refresh its image request" }
        val url = requireNotNull(page.url) { "Page address is missing" }.toHttpUrl()
        require(sameOrigin(url)) { "Komga pages must come from the configured server" }
        return Request.Builder().url(url).apply { authorization?.let { (key, value) -> header(key, value) } }.build()
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("api/v1/$path").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply { authorization?.let { (key, value) -> header(key, value) } }.build()
        try {
            imageClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw MangaSourceFailure("Komga rejected the API key or sign-in. Check them in Manga sources.")
                check(!response.isRedirect) { "Komga answered with a redirect. Use the server's final address, including https:// if it has one." }
                check(response.isSuccessful) { "Komga returned HTTP ${response.code}" }
                val body = response.body
                require(body.contentLength() <= 2 * 1024 * 1024)
                val bytes = body.byteStream().use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024) { "Komga response exceeds the metadata limit" }
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            }
        } catch (blocked: java.net.UnknownServiceException) {
            // Android's network policy refuses plain HTTP to other hosts.
            throw MangaSourceFailure("This device only connects to Komga over HTTPS. Use the server's https:// address.", blocked)
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
                    metadata["numberSort"]?.jsonPrimitive?.floatOrNull ?: 0f, language = book.language)
            }
            page++
        } while (root["last"]?.jsonPrimitive?.booleanOrNull == false && items.isNotEmpty() && page < 100)
        return book.copy(chapters = chapters)
    }
    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = get("books/${chapter.url}/pages").jsonArray.map { value ->
        val number = value.jsonObject["number"]?.jsonPrimitive?.int ?: error("Missing Komga page number")
        val url = base.newBuilder().addPathSegments("api/v1/books/${chapter.url}/pages/$number").build()
        MangaPage("${chapter.id}:$number", url = url.toString())
    }
    private fun convert(item: JsonObject): MangaBook {
        val id = item.text("id")
        val metadata = item["metadata"]?.jsonObject ?: JsonObject(emptyMap())
        val credits = item["booksMetadata"]?.jsonObject?.get("authors")?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }.distinct().take(50)
        val tags = (metadata.list("genres") + metadata.list("tags")).distinct().take(100)
        return MangaBook("${config.id}:$id", config.id, metadata.text("title").ifBlank { item.text("name") },
            coverUrl = base.newBuilder().addPathSegments("api/v1/series/$id/thumbnail").build().toString(),
            authors = credits, description = metadata.text("summary").ifBlank { item["booksMetadata"]?.jsonObject?.text("summary").orEmpty() },
            tags = tags, language = metadata.text("language"))
    }
    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.list(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }

    companion object {
        /** One source per server address, so saving the same server again replaces its sign-in instead of adding a second copy. */
        fun configuration(server: String, apiKey: String = "", username: String = "", password: String = ""): MangaSourceConfig {
            // The id hashes the address as typed (trimmed), as every earlier saved Komga source did, so existing series keep their ids.
            val address = server.trim()
            val url = address.trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use the server address without a user name or query" }
            require(apiKey.isNotBlank() || username.isNotBlank()) { "Enter an API key, or a user name and password" }
            val id = "komga:" + java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            val settings = if (apiKey.isNotBlank()) mapOf("apiKey" to apiKey.trim()) else mapOf("username" to username.trim(), "password" to password)
            return MangaSourceConfig(id, "komga", "Komga · ${url.host}", baseUrl = address, settings = settings)
        }
    }
}
