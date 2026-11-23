package com.cosmotune.reading.data

import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The reader's Hardcover account: the key Hardcover gave them (never shown), who it is, and how the last send went. */
@Serializable data class ReadingHardcover(val token: String, val username: String = "", val lastResult: String = "")

/** A book on Hardcover that a book in the library was matched to, and the reader's entry for it there. */
@Serializable data class ReadingHardcoverBook(val bookId: Int, val title: String, val pages: Int = 0, val userBookId: Int? = null, val readId: Int? = null,
    val status: Int = 0, val sentPages: Int = -1)

/**
 * Hardcover (hardcover.app), the reading-tracker: Read tells it which books the reader is reading and has finished, and how far
 * they are, through its public GraphQL API with the reader's own API key (made under Settings › Hardcover API on its website).
 */
internal class ReadingHardcoverClient(client: OkHttpClient, private val endpoint: String = ENDPOINT) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val ENDPOINT = "https://api.hardcover.app/v1/graphql"
        const val WANT = 1
        const val READING = 2
        const val READ = 3

        /** The key as pasted: with or without its "Bearer " start. */
        fun bearer(token: String) = "Bearer " + token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        /** The search hit that is this book: the same title (and author, when both say), else the first hit. */
        internal fun pick(results: JsonElement?, title: String, author: String): ReadingHardcoverBook? {
            val hits = ((results as? JsonObject)?.get("hits") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("document") as? JsonObject }
            fun key(text: String) = text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
            fun authors(doc: JsonObject) = (doc["author_names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val best = hits.firstOrNull { doc -> key(doc.text("title")) == key(title) && (author.isBlank() || authors(doc).any { key(it).contains(key(author).substringAfterLast(' ')) }) }
                ?: hits.firstOrNull { key(it.text("title")) == key(title) } ?: hits.firstOrNull() ?: return null
            val id = best.text("id").toIntOrNull() ?: return null
            return ReadingHardcoverBook(id, best.text("title"), best["pages"]?.jsonPrimitive?.intOrNull ?: 0)
        }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /** Who the key belongs to; fails with a plain reason when Hardcover does not take it. */
    suspend fun me(token: String): String {
        val data = query(token, "query { me { username } }")
        return ((data["me"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull
            ?: error("Hardcover did not recognise that key.")
    }

    /** The Hardcover book for [title] by [author], with the reader's entry for it when they have one. */
    suspend fun find(token: String, title: String, author: String): ReadingHardcoverBook? {
        val search = query(token, "query (\$q: String!) { search(query: \$q, query_type: \"Book\", per_page: 5, page: 1) { results } }",
            buildJsonObject { put("q", "$title $author".trim().take(200)) })
        val book = pick(search["search"]?.jsonObject?.get("results"), title, author) ?: return null
        val mine = query(token, "query (\$id: Int!) { me { user_books(where: {book_id: {_eq: \$id}}, limit: 1) { id status_id user_book_reads(order_by: {id: desc}, limit: 1) { id } } } }",
            buildJsonObject { put("id", book.bookId) })
        val entry = (((mine["me"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("user_books") as? JsonArray)?.firstOrNull() as? JsonObject
        return book.copy(userBookId = entry?.get("id")?.jsonPrimitive?.intOrNull, status = entry?.get("status_id")?.jsonPrimitive?.intOrNull ?: 0,
            readId = ((entry?.get("user_book_reads") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull)
    }

    /**
     * Sets [book]'s status ([READING] or [READ]) and, while reading, the page reached ([fraction] of its pages); gives the entry as
     * it now stands on Hardcover.
     */
    suspend fun send(token: String, book: ReadingHardcoverBook, status: Int, fraction: Double, today: String): ReadingHardcoverBook {
        var entry = book
        if (entry.userBookId == null) {
            val made = query(token, "mutation (\$id: Int!, \$s: Int!) { insert_user_book(object: {book_id: \$id, status_id: \$s}) { id error } }",
                buildJsonObject { put("id", book.bookId); put("s", status) })["insert_user_book"]?.jsonObject
            made?.get("error")?.jsonPrimitive?.contentOrNull?.let { error(it) }
            entry = entry.copy(userBookId = made?.get("id")?.jsonPrimitive?.intOrNull ?: error("Hardcover did not add the book."), status = status)
        } else if (entry.status != status) {
            val changed = query(token, "mutation (\$id: Int!, \$s: Int!) { update_user_book(id: \$id, object: {status_id: \$s}) { id error } }",
                buildJsonObject { put("id", entry.userBookId!!); put("s", status) })["update_user_book"]?.jsonObject
            changed?.get("error")?.jsonPrimitive?.contentOrNull?.let { error(it) }
            entry = entry.copy(status = status)
        }
        val pages = if (entry.pages > 0) (entry.pages * fraction.coerceIn(0.0, 1.0)).toInt().coerceIn(if (status == READ) entry.pages else 1, entry.pages) else 0
        if (pages <= 0 || pages == entry.sentPages) return entry
        val read = buildJsonObject {
            put("progress_pages", pages)
            if (entry.readId == null) put("started_at", today)
            if (status == READ) put("finished_at", today)
        }
        entry = if (entry.readId == null) {
            val made = query(token, "mutation (\$id: Int!, \$r: DatesReadInput!) { insert_user_book_read(user_book_id: \$id, user_book_read: \$r) { id error } }",
                buildJsonObject { put("id", entry.userBookId!!); put("r", read) })["insert_user_book_read"]?.jsonObject
            made?.get("error")?.jsonPrimitive?.contentOrNull?.let { error(it) }
            entry.copy(readId = made?.get("id")?.jsonPrimitive?.intOrNull)
        } else {
            val changed = query(token, "mutation (\$id: Int!, \$r: DatesReadInput!) { update_user_book_read(id: \$id, object: \$r) { id error } }",
                buildJsonObject { put("id", entry.readId!!); put("r", read) })["update_user_book_read"]?.jsonObject
            changed?.get("error")?.jsonPrimitive?.contentOrNull?.let { error(it) }
            entry
        }
        return entry.copy(sentPages = pages)
    }

    private suspend fun query(token: String, text: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject { put("query", text); put("variables", variables) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).header("authorization", bearer(token)).header("content-type", "application/json")
            .header("user-agent", "Cosmotune Read").post(body).build()
        val answer = http.newCall(request).awaitReading { response ->
            check(response.code != 401 && response.code != 403) { "Hardcover did not accept the key. Make a new one under Settings › Hardcover API on hardcover.app." }
            check(response.code != 429) { "Hardcover asks Read to slow down. It tries again later." }
            check(response.isSuccessful) { "Hardcover answered HTTP ${response.code}." }
            json.parseToJsonElement(response.catalogueText(2 * 1024 * 1024)).jsonObject
        }
        (answer["errors"] as? JsonArray)?.firstOrNull()?.let { problem ->
            error("Hardcover: " + ((problem as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "the request was refused."))
        }
        return answer["data"] as? JsonObject ?: error("Hardcover gave an answer Read does not understand.")
    }
}
