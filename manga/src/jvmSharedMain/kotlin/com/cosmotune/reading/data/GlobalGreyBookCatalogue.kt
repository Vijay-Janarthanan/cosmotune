package com.cosmotune.reading.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Publisher's recent additions and the full-file links on each individual book page. */
internal class GlobalGreyBookCatalogue(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun browse(): ReadingCataloguePage {
        val doc = Jsoup.parse(read(INDEX), INDEX.toString())
        val covers = doc.select("a.book-cover[href]").associateBy { it.absUrl("href") }
        val publications = doc.select("a[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href").toHttpUrlOrNull()?.takeIf(::isBookPage) ?: return@mapNotNull null
            val title = anchor.text().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val cover = covers[url.toString()]?.selectFirst("img")
            val author = cover?.attr("title")?.substringAfter("$title by ", "").orEmpty()
            ReadingPublication("$ID:${url.pathSegments.last()}", title, authors = listOf(author).filter(String::isNotBlank),
                links = listOf(ReadingLink("View on Global Grey", url.toString(), kind = ReadingLinkKind.DETAILS)),
                coverUrl = cover?.absUrl("src")?.toHttpUrlOrNull()?.takeIf(::sameOrigin)?.toString(),
                sourceId = ID)
        }.distinctBy { it.id }
        check(publications.isNotEmpty()) { "Global Grey returned an empty catalogue." }
        return ReadingCataloguePage(ID, "Recent additions", publications)
    }

    suspend fun details(publication: ReadingPublication): ReadingPublication {
        val page = ROOT.newBuilder().addPathSegment("book-page").addPathSegment(publication.id.removePrefix("$ID:")).build()
        require(publication.id.startsWith("$ID:") && isBookPage(page)) { "Invalid Global Grey book page." }
        val doc = Jsoup.parse(read(page), page.toString())
        val downloads = doc.select("a.btn-download[href]").mapNotNull { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val format = url.pathSegments.last().substringAfterLast('.').lowercase()
            if (!isEdition(url, format)) return@mapNotNull null
            val mime = if (format == "epub") "application/epub+zip" else "application/pdf"
            ReadingLink("", url.toString(), mime, kind = ReadingLinkKind.ACQUIRE)
        }.distinctBy { it.mediaType }
        return publication.copy(description = doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            links = publication.links.filter { it.kind != ReadingLinkKind.ACQUIRE } + downloads)
    }

    private suspend fun read(url: HttpUrl): String = http.newCall(Request.Builder().url(url).build())
        .awaitReading { it.catalogueText(2 * 1024 * 1024) }

    companion object {
        const val ID = "globalgrey"
        private val ROOT = "https://www.globalgreyebooks.com/".toHttpUrl()
        private val INDEX = ROOT.newBuilder().addPathSegment("recently-added.html").build()
        private fun sameOrigin(url: HttpUrl) = url.isHttps && url.host == ROOT.host && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null
        private fun isBookPage(url: HttpUrl) = sameOrigin(url) && url.pathSegments.size == 2 &&
            url.pathSegments[0] == "book-page" && url.pathSegments[1].matches(Regex("[a-zA-Z0-9_-]+-ebook\\.html"))
        fun isEdition(url: HttpUrl, format: String): Boolean = sameOrigin(url) && format in setOf("epub", "pdf") &&
            url.pathSegments.size == 2 && url.pathSegments[0].matches(Regex("ebooks(?:-\\d+)?")) &&
            url.pathSegments[1].matches(Regex("[a-zA-Z0-9_.-]+\\.$format", RegexOption.IGNORE_CASE))
    }
}
