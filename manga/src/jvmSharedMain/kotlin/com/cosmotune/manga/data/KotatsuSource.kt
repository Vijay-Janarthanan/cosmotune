package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.Manga as ParserBook
import org.koitharu.kotatsu.parsers.model.MangaChapter as ParserChapter

class KotatsuSource(override val config: MangaSourceConfig, context: ParserContext) : MangaSource {
    private val source = MangaParserSource.valueOf(config.id.removePrefix("kotatsu:"))
    val parser = context.newParserInstance(source)

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage = withContext(Dispatchers.IO) {
        val results = parser.getList(offset, parser.availableSortOrders.first(), MangaListFilter(query = query.takeIf { it.isNotBlank() }))
        MangaBrowsePage(results.map(::convert), if (results.isEmpty()) null else offset + results.size)
    }
    override suspend fun details(book: MangaBook): MangaBook = withContext(Dispatchers.IO) {
        val raw = ParserBook(
            id = book.id.substringAfterLast(':').toLong(), title = book.title, altTitles = book.parserData?.alternateTitles.orEmpty(),
            url = book.url, publicUrl = book.parserData?.publicUrl ?: book.url, rating = book.parserData?.rating ?: -1f,
            contentRating = book.parserData?.contentRating?.let { ContentRating.valueOf(it) }, coverUrl = book.coverUrl,
            tags = book.parserData?.tagKeys.orEmpty().map { (key, title) -> MangaTag(title, key, source) }.toSet(),
            state = book.parserData?.state?.let { MangaState.valueOf(it) }, authors = book.authors.toSet(), source = source,
        )
        convert(parser.getDetails(raw)).copy(addedAtMs = book.addedAtMs)
    }
    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val raw = ParserChapter(
            id = chapter.id.substringAfterLast(':').toLong(), title = chapter.title, number = chapter.number,
            volume = chapter.volume, url = chapter.url, scanlator = chapter.scanlator, uploadDate = chapter.uploadDate,
            branch = chapter.branch, source = source,
        )
        val headers = parser.getRequestHeaders().toMap()
        parser.getPages(raw).map { page -> MangaPage("${config.id}:page:${page.id}", url = parser.getPageUrl(page), headers = headers) }
    }
    private fun convert(raw: ParserBook): MangaBook {
        val id = "${config.id}:${raw.id}"
        return MangaBook(
            id = id, sourceId = config.id, title = raw.title, url = raw.url,
            coverUrl = raw.largeCoverUrl ?: raw.coverUrl, authors = raw.authors.toList(),
            description = raw.description.orEmpty(), tags = raw.tags.map { it.title }, language = config.language,
            chapters = raw.chapters.orEmpty().map { chapter ->
                MangaChapter("${config.id}:chapter:${chapter.id}", id, config.id,
                    chapter.title ?: "Chapter ${chapter.number}", chapter.url, chapter.number, chapter.branch.orEmpty(),
                    volume = chapter.volume, scanlator = chapter.scanlator, uploadDate = chapter.uploadDate, branch = chapter.branch)
            },
            parserData = MangaParserBookData(raw.publicUrl, raw.altTitles, raw.rating, raw.contentRating?.name, raw.state?.name,
                raw.tags.associate { it.key to it.title }),
        )
    }
    companion object {
        fun catalogue(): List<MangaSourceDescriptor> = MangaParserSource.entries.filterNot { it.isBroken }.map {
            MangaSourceDescriptor("kotatsu:${it.name}", it.title, it.locale)
        }.sortedWith(compareBy({ it.language }, { it.title }))
    }
}
