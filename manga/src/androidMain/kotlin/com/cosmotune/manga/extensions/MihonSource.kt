package com.cosmotune.manga.extensions

import com.cosmotune.manga.data.*
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class MihonSource(override val config: MangaSourceConfig, private val source: Source, fallbackClient: OkHttpClient) : MangaSource, MangaImageTransport {
    override val imageClient: OkHttpClient = (source as? HttpSource)?.client ?: fallbackClient
    private val imageRequests = LinkedHashMap<String, okhttp3.Request>()
    override fun imageRequest(page: MangaPage): okhttp3.Request = synchronized(imageRequests) {
        imageRequests[page.id] ?: super.imageRequest(page)
    }

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage = withContext(Dispatchers.IO) {
        val page = offset + 1
        val result = if (query.isBlank()) source.getPopularManga(page) else source.getSearchManga(page, query, source.getFilterList())
        MangaBrowsePage(result.mangas.map(::convert), if (result.hasNextPage) offset + 1 else null)
    }
    override suspend fun details(book: MangaBook): MangaBook = withContext(Dispatchers.IO) {
        val manga = SManga.create().apply {
            url = book.url; title = book.title; author = book.authors.firstOrNull(); thumbnail_url = book.coverUrl
            description = book.description; genre = book.tags.joinToString(", ")
            memo = book.sourceMemo
        }
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val details = update.manga
        val merged = manga.copy().apply {
            title = runCatching { details.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: manga.title
            author = details.author ?: author; artist = details.artist ?: artist
            thumbnail_url = details.thumbnail_url ?: thumbnail_url; description = details.description ?: description
            genre = details.genre ?: genre; status = details.status; initialized = true
            memo = details.memo
        }
        val chapters = update.chapters.sortedBy { it.chapter_number }
        convert(merged).copy(
            id = book.id, addedAtMs = book.addedAtMs,
            chapters = chapters.map { chapter -> MangaChapter("${config.id}:chapter:${hash(chapter.url)}", book.id, config.id,
                chapter.name, chapter.url, chapter.chapter_number, config.language, scanlator = chapter.scanlator, uploadDate = chapter.date_upload, sourceMemo = chapter.memo) },
        )
    }
    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val raw = SChapter.create().apply { url = chapter.url; name = chapter.title; chapter_number = chapter.number; scanlator = chapter.scanlator; date_upload = chapter.uploadDate; memo = chapter.sourceMemo }
        val http = source as? HttpSource
        source.getPageList(raw).mapIndexed { index, page ->
            if (page.imageUrl.isNullOrBlank()) page.imageUrl = http?.getImageUrl(page) ?: error("This source did not provide a page image address")
            val request = http?.cosmotuneImageRequest(page) ?: okhttp3.Request.Builder().url(requireNotNull(page.imageUrl)).build()
            require(request.method in setOf("GET", "POST")) { "This source requires an unsupported image request method" }
            val id = "${chapter.id}:$index"
            synchronized(imageRequests) { imageRequests[id] = request; while (imageRequests.size > 10_000) imageRequests.remove(imageRequests.keys.first()) }
            MangaPage(id, url = request.url.toString(), headers = request.headers.toMap(), requestMethod = request.method)
        }
    }
    private fun convert(raw: SManga) = MangaBook("${config.id}:series:${hash(raw.url)}", config.id, raw.title, raw.url, raw.thumbnail_url,
        listOfNotNull(raw.author, raw.artist).distinct(), raw.description.orEmpty(), raw.getGenres().orEmpty(), config.language, sourceMemo = raw.memo)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
