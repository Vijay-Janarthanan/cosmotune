package com.cosmotune.manga.data

/**
 * The library's search (Mihon 0.20.2's search syntax): plain words match the title, alternate titles and authors; `title:`, `author:`,
 * `genre:` (or `tag:`) and `source:` look at one part; "quotes" keep words together; a leading `-` leaves matches out; `|` means
 * either; spaces mean all of them. A field it does not know is read as plain text.
 */
class MangaLibraryQuery private constructor(private val terms: List<Term>) {
    private data class Part(val field: String?, val value: String)
    private data class Term(val parts: List<Part>, val negated: Boolean)

    val isEmpty: Boolean get() = terms.isEmpty()

    fun filter(books: List<MangaBook>, sourceNames: Map<String, String>): List<MangaBook> =
        if (terms.isEmpty()) books else books.filter { book -> terms.all { term -> term.parts.any { matches(book, it, sourceNames) } != term.negated } }

    private fun matches(book: MangaBook, part: Part, sourceNames: Map<String, String>): Boolean {
        val value = part.value
        fun has(text: String?) = text != null && text.contains(value, ignoreCase = true)
        val titles = listOf(book.title) + book.parserData?.alternateTitles.orEmpty()
        return when (part.field) {
            "title" -> titles.any(::has)
            "author" -> book.authors.any(::has)
            "genre" -> book.tags.any(::has)
            "source" -> has(sourceNames[book.sourceId]) || has(book.sourceId)
            else -> titles.any(::has) || book.authors.any(::has)
        }
    }

    companion object {
        private val fields = mapOf("title" to "title", "author" to "author", "artist" to "author", "genre" to "genre", "tag" to "genre",
            "source" to "source", "src" to "source")

        /** What a source's own search gets from the same text: the plain words, without fields, left-out words or bars. */
        fun plainWords(text: String): String = tokens(text).filter { token ->
            token != "|" && !(token.length > 1 && token.startsWith("-")) && token.indexOf(':').let { it <= 0 || fields[token.substring(0, it).lowercase()] == null }
        }.joinToString(" ") { unquote(it) }.trim()

        fun parse(text: String): MangaLibraryQuery {
            val terms = ArrayList<Term>()
            var either = false
            for (token in tokens(text)) {
                if (token == "|") { either = terms.isNotEmpty(); continue }
                val term = term(token) ?: continue
                if (either && !term.negated && !terms.last().negated) terms[terms.lastIndex] = terms.last().copy(parts = terms.last().parts + term.parts)
                else terms += term
                either = false
            }
            return MangaLibraryQuery(terms)
        }

        private fun term(token: String): Term? {
            val negated = token.length > 1 && token.startsWith("-")
            val body = if (negated) token.substring(1) else token
            val colon = body.indexOf(':')
            val field = if (colon > 0) fields[body.substring(0, colon).lowercase()] else null
            val values = if (field != null) body.substring(colon + 1) else body
            val parts = split(values).map { unquote(it) }.filter { it.isNotBlank() }.map { Part(field, it) }
            return if (parts.isEmpty()) null else Term(parts, negated)
        }

        /** Words split on spaces outside quotes; a bar standing alone is kept as its own token. */
        private fun tokens(text: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var quoted = false
            for (char in text) {
                when {
                    char == '"' -> { quoted = !quoted; current.append(char) }
                    char.isWhitespace() && !quoted -> { if (current.isNotEmpty()) out += current.toString(); current.clear() }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        /** A value's alternatives, split on bars outside quotes. */
        private fun split(value: String): List<String> {
            if (value == "|") return emptyList()
            val out = ArrayList<String>()
            val current = StringBuilder()
            var quoted = false
            for (char in value) {
                when {
                    char == '"' -> { quoted = !quoted; current.append(char) }
                    char == '|' && !quoted -> { out += current.toString(); current.clear() }
                    else -> current.append(char)
                }
            }
            out += current.toString()
            return out
        }

        private fun unquote(value: String) = value.trim().removeSurrounding("\"").trim()
    }
}
