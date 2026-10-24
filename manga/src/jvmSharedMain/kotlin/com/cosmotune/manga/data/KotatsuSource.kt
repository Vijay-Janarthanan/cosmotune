package com.cosmotune.manga.data

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.Manga as ParserBook
import org.koitharu.kotatsu.parsers.model.MangaChapter as ParserChapter

/** A source failure the reader can act on; the message is shown as is. */
class MangaSourceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * One pinned Kotatsu parser behind the shared [MangaSource] contract, on Android and the desktop JVM alike.
 * [parserOverride] exists for offline tests; the app always builds the parser from the pinned catalogue.
 */
class KotatsuSource(override val config: MangaSourceConfig, context: ParserContext, parserOverride: MangaParser? = null) : MangaSource {
    private val source = MangaParserSource.valueOf(config.id.removePrefix("kotatsu:"))
    val parser = parserOverride ?: context.newParserInstance(source)

    /** The language chosen for a multi-language source, as a BCP 47 tag in the source settings; blank for the source's default. */
    private val locale: Locale? get() = config.settings[LOCALE]?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage = guarded {
        if (query.isNotBlank() && !parser.filterCapabilities.isSearchSupported) throw MangaSourceFailure("${config.name} does not support search. Clear the search to browse it.")
        val results = parser.getList(offset, parser.availableSortOrders.first(), MangaListFilter(query = query.takeIf { it.isNotBlank() }, locale = locale))
        MangaBrowsePage(results.map(::convert), if (results.isEmpty()) null else offset + results.size)
    }
    override suspend fun details(book: MangaBook): MangaBook = guarded {
        val raw = ParserBook(
            id = book.id.substringAfterLast(':').toLong(), title = book.title, altTitles = book.parserData?.alternateTitles.orEmpty(),
            url = book.url, publicUrl = book.parserData?.publicUrl ?: book.url, rating = book.parserData?.rating ?: -1f,
            contentRating = book.parserData?.contentRating?.let { ContentRating.valueOf(it) }, coverUrl = book.coverUrl,
            tags = book.parserData?.tagKeys.orEmpty().map { (key, title) -> MangaTag(title, key, source) }.toSet(),
            state = book.parserData?.state?.let { MangaState.valueOf(it) }, authors = book.authors.toSet(), source = source,
        )
        convert(parser.getDetails(raw)).copy(addedAtMs = book.addedAtMs)
    }
    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = guarded {
        val raw = ParserChapter(
            id = chapter.id.substringAfterLast(':').toLong(), title = chapter.title, number = chapter.number,
            volume = chapter.volume, url = chapter.url, scanlator = chapter.scanlator, uploadDate = chapter.uploadDate,
            branch = chapter.branch, source = source,
        )
        val headers = parser.getRequestHeaders().toMap()
        parser.getPages(raw).map { page -> MangaPage("${config.id}:page:${page.id}", url = parser.getPageUrl(page), headers = headers) }
    }

    /** The languages a multi-language source can filter its catalogue by; empty for a single-language source. */
    override suspend fun languages(): List<String> = guarded {
        if (config.language.isNotBlank()) emptyList()
        else parser.getFilterOptions().availableLocales.map { it.toLanguageTag() }.filter { it.isNotBlank() && it != "und" }.distinct().sorted()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AuthRequiredException) {
            throw MangaSourceFailure("${config.name} requires signing in on its website, which Cosmotune cannot do for this source. Choose another source.", failure)
        }
        catch (failure: TooManyRequestExceptions) { throw MangaSourceFailure("${config.name} is limiting requests. Wait a minute, then retry.", failure) }
        catch (failure: NotFoundException) { throw MangaSourceFailure("${config.name} no longer has this page. Refresh the series or search again.", failure) }
        catch (failure: ContentUnavailableException) { throw MangaSourceFailure(failure.message ?: "${config.name} has made this content unavailable.", failure) }
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
        const val LOCALE = MANGA_LANGUAGE_SETTING
        fun catalogue(): List<MangaSourceDescriptor> = MangaParserSource.entries.filterNot { it.isBroken }.map {
            MangaSourceDescriptor("kotatsu:${it.name}", it.title, it.locale)
        }.sortedWith(compareBy({ it.language }, { it.title }))
    }
}
