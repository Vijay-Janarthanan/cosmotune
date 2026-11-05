package com.cosmotune.reading.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Project Gutenberg's documented OPDS; only a selected book resolves its advertised acquisition feed. */
class GutenbergBookCatalogue internal constructor(private val client: OkHttpClient, private val endpoint: HttpUrl) {
    constructor(client: OkHttpClient = OkHttpClient()) : this(client, "https://www.gutenberg.org/ebooks/search.opds/".toHttpUrl())
    private fun catalogue() = PersonalOpdsCatalogue("gutenberg", endpoint.toString(), client = client,
        allowInsecureHttp = !endpoint.isHttps, navigationAsPublications = true)

    suspend fun browse(query: String = "", page: Int = 1): ReadingCataloguePage {
        require(query.length <= 500 && page in 1..1_000)
        val url = endpoint.newBuilder().addQueryParameter("sort_order", "downloads").addQueryParameter("start_index", ((page - 1) * 25 + 1).toString())
            .apply { if (query.isNotBlank()) addQueryParameter("query", query.trim()) }.build()
        val result = catalogue().browse(url.toString())
        return result.copy(title = if (query.isBlank()) "Popular on Project Gutenberg" else "Project Gutenberg search",
            publications = result.publications.map { publication ->
                val number = Regex("/ebooks/(\\d+)(?:\\.opds)?").find(publication.id)?.groupValues?.get(1)
                publication.copy(id = number?.let { "gutenberg:$it" } ?: publication.id,
                    authors = publication.authors.ifEmpty { listOfNotNull(publication.description.takeIf { it.isNotBlank() && it.length < 200 }) },
                    links = publication.links + listOfNotNull(number?.let { ReadingLink("Project Gutenberg", "https://www.gutenberg.org/ebooks/$it", "text/html", "alternate", ReadingLinkKind.DETAILS) }),
                    accessEvidence = "Project Gutenberg catalogue · check source availability", sourceId = "gutenberg")
            })
    }

    suspend fun details(publication: ReadingPublication): ReadingPublication {
        require(publication.sourceId == "gutenberg")
        val advertised = publication.links.firstOrNull { it.kind == ReadingLinkKind.BROWSE } ?: return publication
        val page = catalogue().browse(advertised.url)
        val books = page.publications.filter { it.links.any { link -> link.kind == ReadingLinkKind.ACQUIRE } }
        val book = books.firstOrNull { it.links.any { link -> link.kind == ReadingLinkKind.ACQUIRE && "noimages" !in link.url } }
            ?: books.firstOrNull() ?: error("Project Gutenberg did not advertise a downloadable edition for this book.")
        val acquisitions = books.flatMap { entry -> entry.links.filter { link -> link.kind == ReadingLinkKind.ACQUIRE }
            .map { link -> link.copy(title = link.title.ifBlank { entry.title }) } }
            .sortedBy { if ("epub3.images" in it.url) 0 else if ("noimages" !in it.url) 1 else 2 }
        return book.copy(id = publication.id, sourceId = "gutenberg", authors = book.authors.ifEmpty { publication.authors },
            links = (acquisitions + book.links.filter { it.kind != ReadingLinkKind.ACQUIRE } + publication.links.filter { it.kind == ReadingLinkKind.DETAILS }).distinct(),
            accessEvidence = "Project Gutenberg source-offered files · source availability applies")
    }
}