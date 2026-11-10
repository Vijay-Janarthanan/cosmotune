package com.cosmotune.reading.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** OpenStax's public CMS advertises the same textbook PDF as the publisher's book page. */
internal class OpenStaxBookCatalogue(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val lock = Mutex()
    private var cached: List<ReadingPublication>? = null

    suspend fun browse(query: String, offset: Int): ReadingSearchPage {
        val books = lock.withLock { cached ?: fetch().also { cached = it } }
        val words = query.take(200).trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = if (words.isEmpty()) books else books.filter { book ->
            val searchable = "${book.title} ${book.description}".lowercase()
            words.all(searchable::contains)
        }
        val start = offset.coerceIn(0, matches.size)
        val end = minOf(start + 24, matches.size)
        return ReadingSearchPage(ID, matches.subList(start, end), matches.size.toLong(), end.takeIf { it < matches.size })
    }

    private suspend fun fetch(): List<ReadingPublication> {
        val results = mutableListOf<ReadingPublication>()
        for (offset in 0..400 step 100) {
            val url = ENDPOINT.newBuilder().addQueryParameter("type", "books.Book")
                .addQueryParameter("fields", "description,cover_url,pdf_url,license_url")
                .addQueryParameter("limit", "100").addQueryParameter("offset", offset.toString()).build()
            val root = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).awaitReading {
                Json.parseToJsonElement(it.catalogueText(3 * 1024 * 1024)).jsonObject
            }
            val items = root["items"] as? JsonArray ?: error("OpenStax returned an invalid catalogue.")
            results += items.mapNotNull { (it as? JsonObject)?.let(::publication) }
            val total = (root["meta"] as? JsonObject)?.value("total_count")?.toIntOrNull()
            if (items.size < 100 || (total != null && offset + items.size >= total)) break
        }
        return results.distinctBy { it.id }.sortedBy { it.title.lowercase() }
            .also { check(it.isNotEmpty()) { "OpenStax did not offer any public PDF editions." } }
    }

    companion object {
        const val ID = "openstax"
        private val ENDPOINT = "https://openstax.org/apps/cms/api/v2/pages/".toHttpUrl()

        fun isEdition(url: HttpUrl): Boolean = url.isHttps && url.host == "assets.openstax.org" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null &&
            url.pathSegments.size == 4 && url.pathSegments[0].startsWith("oscms-") &&
            url.pathSegments[1] == "media" && url.pathSegments[2] == "documents" && url.pathSegments[3].endsWith(".pdf", true)

        fun publication(item: JsonObject): ReadingPublication? {
            val id = item.value("id").toLongOrNull()?.takeIf { it > 0 } ?: return null
            val meta = item["meta"] as? JsonObject ?: return null
            val title = item.value("title").takeIf(String::isNotBlank) ?: return null
            val slug = meta.value("slug").takeIf { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) } ?: return null
            val license = item.value("license_url").toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.host == "creativecommons.org" && it.pathSegments.firstOrNull() in setOf("licenses", "publicdomain") }
                ?: return null
            val pdf = item.value("pdf_url").toHttpUrlOrNull()?.takeIf(::isEdition) ?: return null
            val cover = item.value("cover_url").toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == pdf.host }
            return ReadingPublication("$ID:$id", title, authors = listOf("OpenStax"),
                languages = listOf(meta.value("locale")).filter(String::isNotBlank),
                description = item.value("description"), coverUrl = cover?.toString(),
                links = listOf(
                    ReadingLink("", pdf.toString(), "application/pdf", kind = ReadingLinkKind.ACQUIRE),
                    ReadingLink("View on OpenStax", "https://openstax.org/details/books/$slug", kind = ReadingLinkKind.DETAILS),
                    ReadingLink("Edition license", license.toString(), kind = ReadingLinkKind.DETAILS),
                ), sourceId = ID, accessEvidence = "publisher-offered-open-textbook")
        }
        private fun JsonObject.value(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
