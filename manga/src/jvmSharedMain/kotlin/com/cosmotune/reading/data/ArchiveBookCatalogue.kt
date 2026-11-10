package com.cosmotune.reading.data

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Historical editions from library collections. An item record must advertise the actual public file. */
internal class ArchiveBookCatalogue(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun browse(query: String, offset: Int): ReadingSearchPage {
        val term = query.take(200).replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ").trim()
        val filter = "mediatype:texts AND collection:(americana OR toronto OR europeanlibraries) AND year:[1450 TO 1929]" +
            " AND NOT collection:(inlibrary OR printdisabled) AND NOT access-restricted-item:true"
        val textFilter = if (term.isBlank()) "" else """ AND (title:"$term" OR creator:"$term")"""
        val url = ROOT.newBuilder().addPathSegment("advancedsearch.php").addQueryParameter("q", filter + textFilter)
            .addQueryParameter("rows", PAGE.toString()).addQueryParameter("page", (offset.coerceAtLeast(0) / PAGE + 1).toString())
            .addQueryParameter("sort[]", "downloads desc").addQueryParameter("output", "json")
            .apply { listOf("identifier", "title", "creator", "language", "year").forEach { addQueryParameter("fl[]", it) } }.build()
        val result = read(url)["response"] as? JsonObject ?: error("Internet Archive returned an invalid catalogue.")
        val total = result.value("numFound").toLongOrNull() ?: 0
        val books = (result["docs"] as? JsonArray).orEmpty().take(PAGE).mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val id = item.value("identifier").takeIf(IDENTIFIER::matches) ?: return@mapNotNull null
            val title = item.value("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val year = item.value("year").takeIf { it.toIntOrNull() in 1450..1929 } ?: return@mapNotNull null
            ReadingPublication("$ID:$id", title, authors = item.values("creator"), languages = item.values("language"),
                links = listOf(ReadingLink("View on Internet Archive", ROOT.newBuilder().addPathSegment("details").addPathSegment(id).build().toString(),
                    kind = ReadingLinkKind.DETAILS)), coverUrl = "https://archive.org/services/img/$id",
                publishedDate = year, sourceId = ID)
        }
        return ReadingSearchPage(ID, books, total, (offset + PAGE).takeIf { it < total && offset >= 0 && it <= MAX_OFFSET })
    }

    suspend fun details(publication: ReadingPublication): ReadingPublication {
        val id = requireNotNull(itemId(publication))
        val root = read(ROOT.newBuilder().addPathSegment("metadata").addPathSegment(id).build())
        return publication.copy(links = publication.links.filter { it.kind != ReadingLinkKind.ACQUIRE } + editions(root, id))
    }

    private suspend fun read(url: HttpUrl): JsonObject =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).awaitReading {
            Json.parseToJsonElement(it.catalogueText(8 * 1024 * 1024)).jsonObject
        }

    companion object {
        const val ID = "internetarchive"
        private const val PAGE = 24
        private const val MAX_OFFSET = 4800
        private val ROOT = "https://archive.org/".toHttpUrl()
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}")
        private val FILE_HOST = Regex("(?:ia|dn)\\d+\\.(?:us|eu)\\.archive\\.org")
        private val COLLECTIONS = setOf("americana", "toronto", "europeanlibraries")

        fun itemId(publication: ReadingPublication): String? = publication.id.removePrefix("$ID:")
            .takeIf { publication.sourceId == ID && publication.id.startsWith("$ID:") && IDENTIFIER.matches(it) }

        fun editions(root: JsonObject, id: String): List<ReadingLink> {
            val metadata = root["metadata"] as? JsonObject ?: return emptyList()
            val year = (metadata.value("year").ifBlank { metadata.value("date") }).take(4).toIntOrNull()
            val collections = metadata.values("collection")
            if (metadata.value("identifier") != id || !IDENTIFIER.matches(id) || year !in 1450..1929 ||
                collections.none(COLLECTIONS::contains) || collections.any { it == "inlibrary" || it == "printdisabled" } ||
                metadata.flag("access-restricted-item") || root.flag("is_dark")) return emptyList()
            return (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { file ->
                val name = file.value("name")
                val size = file.value("size").toLongOrNull()
                if (file.flag("private") || name.isBlank() || name.length > 220 ||
                    name.any { it == '/' || it == '\\' || it.isISOControl() } || size !in 1L..512L * 1024 * 1024 ||
                    Regex("(?i)(?:^|[._ -])(?:preview|sample|encrypted|protected)(?:[._ -]|$)").containsMatchIn(name)) return@mapNotNull null
                val mime = when {
                    file.value("format") == "EPUB" && name.endsWith(".epub", true) -> "application/epub+zip"
                    file.value("format") in setOf("PDF", "Text PDF", "Additional PDF") && name.endsWith(".pdf", true) -> "application/pdf"
                    else -> return@mapNotNull null
                }
                ReadingLink("", ROOT.newBuilder().addPathSegment("download").addPathSegment(id).addPathSegment(name).build().toString(),
                    mime, kind = ReadingLinkKind.ACQUIRE)
            }.distinctBy { it.mediaType }.sortedBy { it.mediaType }
        }

        fun isEdition(url: HttpUrl, id: String): Boolean = url.isHttps && url.port == 443 && url.host == ROOT.host &&
            url.query == null && url.pathSegments.size == 3 && url.pathSegments[0] == "download" &&
            url.pathSegments[1] == id && url.pathSegments[2].substringAfterLast('.').lowercase() in setOf("pdf", "epub")

        fun isRedirect(original: HttpUrl, target: HttpUrl): Boolean {
            val id = original.pathSegments[1]
            if (!target.isHttps || target.port != 443 || target.query != null) return false
            val sameFile = target.pathSegments.takeLast(2) == original.pathSegments.takeLast(2)
            return sameFile && (isEdition(target, id) || FILE_HOST.matches(target.host) &&
                target.pathSegments.size == 4 && target.pathSegments[0].all(Char::isDigit) && target.pathSegments[1] == "items")
        }

        private fun JsonObject.value(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.values(key: String) = when (val field = this[key]) {
            is JsonArray -> field.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(field.contentOrNull)
            else -> emptyList()
        }
        private fun JsonObject.flag(key: String) = value(key).lowercase() in setOf("true", "1", "yes")
    }
}
