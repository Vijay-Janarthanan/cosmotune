package com.cosmotune.reading.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** The Spanish library's own OPDS catalogue, with direct complete editions only. */
internal class TextosBookCatalogue(client: OkHttpClient) {
    private val opds = PersonalOpdsCatalogue(ID, "https://www.textos.info/catalogo.atom", client = client)

    suspend fun browse(address: String?, query: String?): ReadingCataloguePage {
        val page = if (query != null) opds.search(query) else opds.browse(address)
        return page.copy(publications = page.publications.mapNotNull { book ->
            val downloads = book.links.filter { link ->
                val format = link.downloadFormat ?: return@filter false
                link.url.toHttpUrlOrNull()?.let { isEdition(it, format.name.lowercase()) } == true
            }
            if (downloads.isEmpty() || book.title.isBlank()) return@mapNotNull null
            book.copy(id = "$ID:${book.id}", links = downloads + book.links.filter { it.kind == ReadingLinkKind.DETAILS })
        })
    }

    companion object {
        const val ID = "textos"
        fun isEdition(url: HttpUrl, format: String): Boolean = url.isHttps && url.host == "www.textos.info" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null &&
            url.pathSegments.size == 3 && url.pathSegments.take(2).all(String::isNotBlank) && url.pathSegments.last() == format
    }
}
