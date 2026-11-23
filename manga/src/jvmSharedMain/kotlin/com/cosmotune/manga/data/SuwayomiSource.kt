package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A catalogue on the listener's Suwayomi server: its library, or one of the extension sources installed there. */
data class SuwayomiCatalogue(val id: String, val name: String, val language: String)

/**
 * The listener's own Suwayomi server (Suwayomi-Server, MPL-2.0; client only), through its GraphQL API: the server's library, or
 * one of the Mihon extensions installed on it ([MangaSourceConfig.settings] "source"), so the desktop and the TV read every
 * extension the server has. The optional sign-in is added only to requests for the configured server, never to page records.
 */
class SuwayomiSource(override val config: MangaSourceConfig, client: OkHttpClient) : MangaSource, MangaImageTransport {
    private val base = config.baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val catalogue = config.settings["source"].orEmpty()
    override val imageClient: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    override val verificationWebsite: String? get() = null
    private val authorization: String? get() = config.settings["username"]?.takeIf { it.isNotBlank() }?.let { Credentials.basic(it, config.settings["password"].orEmpty(), Charsets.UTF_8) }
    init { require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) }

    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    override fun imageRequest(page: MangaPage): Request {
        val url = requireNotNull(page.url) { "Page address is missing" }.toHttpUrl()
        require(sameOrigin(url)) { "Suwayomi pages must come from the configured server" }
        return Request.Builder().url(url).apply { authorization?.let { header("Authorization", it) } }.build()
    }

    private suspend fun query(text: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("api/graphql").build()
        val body = buildJsonObject { put("query", text); put("variables", variables) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").apply { authorization?.let { header("Authorization", it) } }.build()
        try {
            imageClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw MangaSourceFailure("Suwayomi rejected the sign-in. Check it in Settings › Manga.")
                check(!response.isRedirect) { "Suwayomi answered with a redirect. Use the server's final address." }
                check(response.isSuccessful) { "Suwayomi returned HTTP ${response.code}" }
                val bytes = response.body.byteStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }
                require(bytes.size <= 4 * 1024 * 1024) { "Suwayomi answered with too much data" }
                val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                (root["errors"] as? JsonArray)?.firstOrNull()?.let { error ->
                    throw MangaSourceFailure("Suwayomi: " + ((error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "the request failed").take(200))
                }
                root["data"]?.jsonObject ?: error("Suwayomi sent no data")
            }
        } catch (blocked: java.net.UnknownServiceException) {
            throw MangaSourceFailure("This device only connects to Suwayomi over HTTPS. Use the server's https:// address.", blocked)
        }
    }

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
        if (catalogue.isNotBlank()) {
            val page = offset + 1
            val result = query("""mutation(${'$'}input: FetchSourceMangaInput!) { fetchSourceManga(input: ${'$'}input) { hasNextPage mangas { id title thumbnailUrl author artist description genre status } } }""",
                buildJsonObject { putJsonObject("input") {
                    put("source", catalogue); put("page", page); put("type", if (query.isBlank()) "POPULAR" else "SEARCH")
                    if (query.isNotBlank()) put("query", query)
                } })["fetchSourceManga"]!!.jsonObject
            return MangaBrowsePage(result["mangas"]!!.jsonArray.map { convert(it.jsonObject) }, if (result["hasNextPage"]?.jsonPrimitive?.booleanOrNull == true) offset + 1 else null)
        }
        val result = query("""query(${'$'}offset: Int!, ${'$'}title: String!) { mangas(condition: { inLibrary: true }, filter: { title: { includesInsensitive: ${'$'}title } }, orderBy: TITLE, first: 40, offset: ${'$'}offset) { pageInfo { hasNextPage } nodes { id title thumbnailUrl author artist description genre status } } }""",
            buildJsonObject { put("offset", offset); put("title", query) })["mangas"]!!.jsonObject
        val books = result["nodes"]!!.jsonArray.map { convert(it.jsonObject) }
        return MangaBrowsePage(books, if (result["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true) offset + books.size else null)
    }

    override suspend fun details(book: MangaBook): MangaBook {
        val id = book.url.toInt()
        // Asking the server to fetch the chapter list also refreshes it from the extension.
        val chapters = query("""mutation(${'$'}id: Int!) { fetchChapters(input: { mangaId: ${'$'}id }) { chapters { id name chapterNumber scanlator uploadDate sourceOrder } } }""",
            buildJsonObject { put("id", id) })["fetchChapters"]!!.jsonObject["chapters"]!!.jsonArray.map { it.jsonObject }
        val manga = query("""query(${'$'}id: Int!) { manga(id: ${'$'}id) { id title thumbnailUrl author artist description genre status } }""", buildJsonObject { put("id", id) })["manga"]!!.jsonObject
        return convert(manga).copy(addedAtMs = book.addedAtMs, chapters = chapters.sortedBy { it.long("sourceOrder") }.reversed().map { item ->
            MangaChapter("${config.id}:chapter:${item.text("id")}", book.id, config.id, item.text("name").ifBlank { "Chapter ${item.text("chapterNumber")}" }, item.text("id"),
                item["chapterNumber"]?.jsonPrimitive?.floatOrNull?.coerceAtLeast(0f) ?: 0f, scanlator = item.text("scanlator").ifBlank { null },
                uploadDate = item.long("uploadDate"))
        })
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val pages = query("""mutation(${'$'}id: Int!) { fetchChapterPages(input: { chapterId: ${'$'}id }) { pages } }""", buildJsonObject { put("id", chapter.url.toInt()) })["fetchChapterPages"]!!.jsonObject["pages"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        return pages.mapIndexed { index, path -> MangaPage("${chapter.id}:$index", url = absolute(path)) }
    }

    /** Marks a chapter (its server id) read on the server, for the listener's other Suwayomi readers. */
    suspend fun markRead(chapterId: String) {
        query("""mutation(${'$'}id: Int!) { updateChapter(input: { id: ${'$'}id, patch: { isRead: true } }) { chapter { id } } }""", buildJsonObject { put("id", chapterId.toInt()) })
    }

    /** The server's extension sources, for choosing which to add as Cosmotune sources. */
    suspend fun catalogues(): List<SuwayomiCatalogue> = query("""query { sources { nodes { id displayName lang isNsfw } } }""")["sources"]!!.jsonObject["nodes"]!!.jsonArray
        .map { it.jsonObject }.filter { it["isNsfw"]?.jsonPrimitive?.booleanOrNull != true && it.text("id") != "0" }
        .map { SuwayomiCatalogue(it.text("id"), it.text("displayName"), it.text("lang")) }
        .filterNot { MangaSourcePolicy.blocked(it.id, it.name) }

    private fun absolute(path: String): String = if (path.startsWith("http")) path.toHttpUrl().also { require(sameOrigin(it)) }.toString()
        else base.newBuilder().encodedPath("/").build().resolve(path)!!.toString()

    private fun convert(item: JsonObject): MangaBook {
        val id = item.text("id")
        return MangaBook("${config.id}:$id", config.id, item.text("title"), id, item.text("thumbnailUrl").takeIf { it.isNotBlank() }?.let(::absolute),
            listOf(item.text("author"), item.text("artist")).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            item.text("description"), (item["genre"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(30), config.language,
            parserData = MangaParserBookData("", state = when (item.text("status")) { "ONGOING" -> "ONGOING"; "COMPLETED" -> "FINISHED"; else -> null }))
    }
    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    companion object {
        /** The server's library ([catalogue] blank) or one of its extension sources, as a Cosmotune source; one per server and catalogue. */
        fun configuration(server: String, username: String = "", password: String = "", catalogue: SuwayomiCatalogue? = null): MangaSourceConfig {
            val address = server.trim().trimEnd('/')
            val url = address.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use the server address without a user name or query" }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
            val settings = buildMap {
                if (username.isNotBlank()) { put("username", username.trim()); put("password", password) }
                catalogue?.let { put("source", it.id) }
            }
            return MangaSourceConfig("suwayomi:$hash:${catalogue?.id ?: "library"}", "suwayomi", catalogue?.let { "${it.name} · Suwayomi" } ?: "Suwayomi · ${url.host}",
                language = catalogue?.language?.takeIf { it != "all" && it != "localsourcelang" }.orEmpty(), baseUrl = address, settings = settings)
        }
    }
}
