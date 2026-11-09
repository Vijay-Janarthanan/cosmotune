package com.cosmotune.reading.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** The public Atom feed, which advertises DRM-free editions as enclosures. */
internal class StandardEbooksCatalogue(client: OkHttpClient) {
    private val catalogue = PersonalOpdsCatalogue(ID, "https://standardebooks.org/feeds/atom/new-releases", client = client)

    suspend fun browse(): ReadingCataloguePage {
        val page = catalogue.browse()
        return page.copy(publications = page.publications.mapNotNull { publication ->
            val editions = publication.links.filter {
                it.relation == "enclosure" && it.mediaType == EPUB && it.url.toHttpUrlOrNull()?.let(::isEditionUrl) == true
            }
            val edition = editions.firstOrNull { it.title.contains("compatible", ignoreCase = true) }
                ?: editions.firstOrNull() ?: return@mapNotNull null
            publication.copy(
                id = "$ID:${publication.id}",
                links = publication.links.filter { it.kind == ReadingLinkKind.DETAILS } + edition.copy(kind = ReadingLinkKind.ACQUIRE),
                accessEvidence = "source-advertised-acquisition",
            )
        })
    }

    companion object {
        const val ID = "standardebooks"
        const val EPUB = "application/epub+zip"

        fun isEditionUrl(url: HttpUrl): Boolean = url.isHttps && url.host == "standardebooks.org" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.encodedPath.startsWith("/ebooks/") && "/downloads/" in url.encodedPath && url.encodedPath.endsWith(".epub")
    }
}
