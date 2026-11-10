package com.cosmotune.reading.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer

internal class MaduraiBookCatalogue(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val lock = Mutex()
    private var catalogue: List<ReadingPublication>? = null

    suspend fun browse(query: String, offset: Int): ReadingSearchPage {
        val books = lock.withLock {
            catalogue ?: http.newCall(Request.Builder().url(INDEX).build()).awaitReading {
                parse(it.catalogueText(2 * 1024 * 1024)).also { items ->
                    check(items.isNotEmpty()) { "Project Madurai returned an empty catalogue." }
                    catalogue = items
                }
            }
        }
        val words = normalize(query.take(200)).split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = if (words.isEmpty()) books else books.filter {
            val entry = normalize("${it.title} ${it.authors.joinToString(" ")} ${it.description}")
            words.all(entry::contains)
        }
        val start = offset.coerceIn(0, matches.size)
        val end = minOf(start + 24, matches.size)
        return ReadingSearchPage(ID, matches.subList(start, end), matches.size.toLong(), end.takeIf { it < matches.size })
    }

    companion object {
        const val ID = "projectmadurai"
        private const val INDEX = "https://projectmadurai.org/pmworks.html"
        private val PDF = Regex("/pm_etexts/pdf/pm[0-9_]+\\.pdf", RegexOption.IGNORE_CASE)
        private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase()

        fun isEdition(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host in setOf("projectmadurai.org", "www.projectmadurai.org") &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null && PDF.matches(url.encodedPath)

        fun parse(html: String): List<ReadingPublication> = Jsoup.parse(html, INDEX).select("#sortabletable tr").mapNotNull { row ->
            val fields = row.children()
            if (fields.size < 6 || fields[0].tagName() != "td") return@mapNotNull null
            val number = fields[0].text().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = fields[1].text().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val files = fields[4].select("a[href]").mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.takeIf(::isEdition) }.distinct()
            if (files.isEmpty()) return@mapNotNull null
            val download = files.mapIndexed { index, url ->
                ReadingLink(if (files.size == 1) "" else "Edition ${index + 1}", url.toString(), "application/pdf", kind = ReadingLinkKind.ACQUIRE)
            }
            ReadingPublication("$ID:$number", title, authors = listOf(fields[2].text()).filter(String::isNotBlank), languages = listOf("tam"),
                description = fields[3].text(), links = download + ReadingLink("Project Madurai catalogue", INDEX.toHttpUrl().toString(), kind = ReadingLinkKind.DETAILS),
                sourceId = ID, accessEvidence = "publisher-offered-download")
        }.distinctBy { it.id }
    }
}
