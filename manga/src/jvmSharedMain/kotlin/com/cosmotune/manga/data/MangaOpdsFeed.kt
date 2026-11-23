package com.cosmotune.manga.data

import java.net.URLEncoder

/**
 * Suwayomi's OPDS feed of the library: an Atom catalogue other readers (KOReader, Panels, Chunky) browse. The root lists the series;
 * a series lists its downloaded chapters, each a CBZ to fetch. [base] is the feed's own address, with its access key in the path.
 */
object MangaOpdsFeed {
    private const val NAV = "application/atom+xml;profile=opds-catalog;kind=navigation"
    private const val ACQ = "application/atom+xml;profile=opds-catalog;kind=acquisition"

    fun root(books: List<MangaBook>, state: MangaLibraryState, base: String, updated: String): String = feed("cosmotune:manga", "Cosmotune Manga", base, updated, NAV,
        books.filter { book -> book.chapters.any { !it.text && state.downloads.containsKey(it.id) } }.sortedBy { it.title.lowercase() }.joinToString("") { book ->
            val count = book.chapters.count { !it.text && state.downloads.containsKey(it.id) }
            entry("cosmotune:series:${book.id}", book.title, updated, "$count downloaded " + if (count == 1) "chapter" else "chapters",
                """<link rel="subsection" href="${xml("$base/series/${encode(book.id)}")}" type="$ACQ"/>""" +
                    """<link rel="http://opds-spec.org/image/thumbnail" href="${xml("$base/cover/${encode(book.id)}")}" type="image/jpeg"/>""")
        })

    fun series(book: MangaBook, state: MangaLibraryState, base: String, updated: String): String = feed("cosmotune:series:${book.id}", book.title, "$base/series/${encode(book.id)}", updated, ACQ,
        book.listedChapters().filter { !it.text && state.downloads.containsKey(it.id) }.joinToString("") { chapter ->
            entry("cosmotune:chapter:${chapter.id}", chapter.title, updated, book.authors.joinToString(", "),
                """<link rel="http://opds-spec.org/acquisition" href="${xml("$base/chapter/${encode(chapter.id)}.cbz")}" type="application/vnd.comicbook+zip"/>""")
        })

    private fun feed(id: String, title: String, self: String, updated: String, kind: String, entries: String) =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<feed xmlns="http://www.w3.org/2005/Atom"><id>${xml(id)}</id><title>${xml(title)}</title><updated>$updated</updated>""" +
            """<link rel="self" href="${xml(self)}" type="$kind"/><link rel="start" href="${xml(self.substringBefore("/series/"))}" type="$NAV"/>""" +
            entries + "</feed>"

    private fun entry(id: String, title: String, updated: String, content: String, links: String) =
        "<entry><id>${xml(id)}</id><title>${xml(title)}</title><updated>$updated</updated>" +
            (if (content.isNotBlank()) """<content type="text">${xml(content)}</content>""" else "") + links + "</entry>"

    fun encode(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    fun xml(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
                else -> if (char.code >= 0x20 || char == '\t' || char == '\n') append(char)
            }
        }
    }
}
