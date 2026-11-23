package com.cosmotune.reading.data

/** A book's highlights, notes and bookmarks as a file to keep: Markdown (for notes apps such as Obsidian) or a web page. */
internal object ReadingNotesExport {
    fun highlights(state: ReadingState, bookId: String): List<ReadingHighlight> =
        state.highlights.filter { it.bookId == bookId }.sortedWith(compareBy({ it.section }, { it.start }))

    fun markdown(book: ReadingBook, state: ReadingState): String = buildString {
        append("# ").append(book.title.trim()).append("\n\n")
        if (book.authors.isNotEmpty()) append("by ").append(book.authors.joinToString(", ")).append("\n\n")
        var chapter: String? = null
        highlights(state, book.id).forEach { mark ->
            val heading = mark.chapter.ifBlank { "Chapter ${mark.section + 1}" }
            if (heading != chapter) { append("## ").append(heading).append("\n\n"); chapter = heading }
            mark.text.lines().filter { it.isNotBlank() }.forEach { append("> ").append(it.trim()).append('\n') }
            append('\n')
            if (mark.note.isNotBlank()) append(mark.note.trim()).append("\n\n")
        }
        val marks = state.bookmarks.filter { it.bookId == book.id }
        if (marks.isNotEmpty()) {
            append("## Bookmarks\n\n")
            marks.sortedBy { it.createdAtMs }.forEach { append("- ").append(it.name).append('\n') }
        }
    }

    fun html(book: ReadingBook, state: ReadingState): String = buildString {
        fun e(value: String) = ReadingConvert.escape(value)
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(e(book.title)).append(" · notes</title><style>")
        append("body{font-family:Georgia,serif;max-width:40em;margin:2em auto;padding:0 1em;line-height:1.5}blockquote{margin:1em 0;padding:.2em 1em;border-left:4px solid #e0a93d}")
        append(".green{border-color:#59a85f}.blue{border-color:#4f86c6}.pink{border-color:#d0679a}.underline{border-color:#e0533d}.note{color:#555}")
        append("</style></head><body><h1>").append(e(book.title)).append("</h1>")
        if (book.authors.isNotEmpty()) append("<p>by ").append(e(book.authors.joinToString(", "))).append("</p>")
        var chapter: String? = null
        highlights(state, book.id).forEach { mark ->
            val heading = mark.chapter.ifBlank { "Chapter ${mark.section + 1}" }
            if (heading != chapter) { append("<h2>").append(e(heading)).append("</h2>"); chapter = heading }
            append("<blockquote class=\"").append(mark.style.name.lowercase()).append("\">").append(e(mark.text).replace("\n", "<br>")).append("</blockquote>")
            if (mark.note.isNotBlank()) append("<p class=\"note\">").append(e(mark.note).replace("\n", "<br>")).append("</p>")
        }
        val marks = state.bookmarks.filter { it.bookId == book.id }
        if (marks.isNotEmpty()) {
            append("<h2>Bookmarks</h2><ul>")
            marks.sortedBy { it.createdAtMs }.forEach { append("<li>").append(e(it.name)).append("</li>") }
            append("</ul>")
        }
        append("</body></html>")
    }

    /** A file name for [book]'s notes: its title with only safe characters. */
    fun fileName(book: ReadingBook, extension: String): String =
        book.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(80).ifBlank { "Book" } + " notes.$extension"
}
