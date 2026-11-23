package com.cosmotune.manga.data

import kotlinx.serialization.json.*

/**
 * Account sync for the Manga library: the series (title, source, address, cover), the read chapters and reading place of each,
 * and the collections. Source settings, keys, reading-list sessions, downloads and local comics never travel. A series added on
 * another device arrives without its chapter list; its read marks wait in [MangaLibraryState.syncedRead] until it is opened.
 */
object SyncMangaLibrary {
    const val KIND = "manga"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun travels(book: MangaBook) = book.sourceId != "local" && book.id.length <= 600

    fun snapshot(state: MangaLibraryState): Map<String, JsonObject> = buildMap {
        state.books.filter(::travels).forEach { book ->
            put("series:${book.id}", buildJsonObject {
                put("sourceId", book.sourceId); put("title", book.title); put("url", book.url); book.coverUrl?.let { put("cover", it) }
                put("addedAtMs", book.addedAtMs); put("language", book.language)
                book.parserData?.publicUrl?.takeIf { it.isNotBlank() }?.let { put("publicUrl", it) }
                book.unlinkedSourceName()?.let { put("from", it) }
            })
            val read = readOf(book, state)
            if (read.isNotEmpty()) put("read:${book.id}", buildJsonObject { putJsonArray("chapters") { read.sorted().forEach { add(it) } } })
            state.progress[book.id]?.let { put("place:${book.id}", json.encodeToJsonElement(it).jsonObject) }
        }
        state.collections.forEach { collection ->
            put("collection:${collection.id}", buildJsonObject {
                put("name", collection.name)
                putJsonArray("series") { collection.bookIds.filter { id -> state.books.any { it.id == id && travels(it) } }.sorted().forEach { add(it) } }
            })
        }
    }

    /** The read chapters of [book] on this device: those it lists, and those that came from another device before it was opened. */
    private fun readOf(book: MangaBook, state: MangaLibraryState): Set<String> =
        book.chapters.map { it.id }.filter { it in state.readChapters }.toSet() + state.syncedRead[book.id].orEmpty()

    fun apply(state: MangaLibraryState, upserts: Map<String, JsonObject>, deletes: Set<String>): MangaLibraryState {
        var next = state
        for (id in deletes - upserts.keys) {
            val key = id.substringAfter(':')
            next = when (id.substringBefore(':')) {
                "series" -> next.withoutBook(key)
                "collection" -> next.copy(collections = next.collections.filterNot { it.id == key })
                "place" -> next.copy(progress = next.progress - key)
                else -> next
            }
        }
        // Series first, so read marks, places and collections find them.
        for ((id, data) in upserts.filterKeys { it.startsWith("series:") }) runCatching {
            val bookId = id.removePrefix("series:")
            val sourceId = data.text("sourceId") ?: return@runCatching
            val title = data.text("title")?.trim()?.take(500)?.takeIf { it.isNotBlank() } ?: return@runCatching
            if (sourceId == "local" || !bookId.startsWith("$sourceId:") || next.books.size >= 10_000) return@runCatching
            val existing = next.books.find { it.id == bookId }
            val book = (existing ?: MangaBook(bookId, sourceId, title, data.text("url").orEmpty(), data.text("cover")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                language = data.text("language").orEmpty(),
                parserData = data.text("publicUrl")?.let { MangaParserBookData(it) },
                sourceMemo = data.text("from")?.let { JsonObject(mapOf(MihonIds.SOURCE_NAME to JsonPrimitive(it.take(120)))) } ?: JsonObject(emptyMap())))
                .copy(addedAtMs = (data["addedAtMs"] as? JsonPrimitive)?.longOrNull ?: existing?.addedAtMs ?: System.currentTimeMillis())
            next = next.copy(books = if (existing == null) next.books + book else next.books.map { if (it.id == bookId) book else it })
        }
        for ((id, data) in upserts) runCatching {
            val key = id.substringAfter(':')
            when (id.substringBefore(':')) {
                "read" -> {
                    val book = next.books.find { it.id == key } ?: return@runCatching
                    val incoming = (data["chapters"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.length <= 600 }.take(20_000).toSet()
                    val listed = book.chapters.map { it.id }.toSet()
                    // The newer list wins: chapters it no longer has are unread again; ones this device does not list yet wait.
                    next = next.copy(readChapters = next.readChapters - (listed - incoming) + incoming.intersect(listed),
                        syncedRead = (incoming - listed).let { waiting -> if (waiting.isEmpty()) next.syncedRead - key else next.syncedRead + (key to waiting) },
                        newChapters = next.newChapters - incoming)
                }
                "place" -> {
                    val place = json.decodeFromJsonElement<MangaProgress>(data)
                    if (place.bookId == key && next.books.any { it.id == key } && place.page in 0..100_000 && place.pageCount in 0..100_000 && place.pageOffsetPx in 0..10_000_000)
                        next = next.copy(progress = next.progress + (key to place))
                }
                "collection" -> {
                    val name = data.text("name")?.trim()?.take(80)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return@runCatching
                    val members = (data["series"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    // Local comics stay in their collections on this device; the rest follow the newer list.
                    val localMembers = next.collections.find { it.id == key }?.bookIds.orEmpty().filter { id -> next.books.any { it.id == id && it.sourceId == "local" } }
                    val ids = members.filter { id -> next.books.any { it.id == id } }.toSet() + localMembers
                    val others = next.collections.filterNot { it.id == key || it.name.equals(name, true) }
                    if (key.length in 1..120 && key != "unassigned" && others.size < 128) next = next.copy(collections = others + MangaCollection(key, name, ids))
                }
            }
        }
        return next
    }

    /** A cheaper view for deciding when to sync: a page turn inside a chapter is not worth a sync, a finished chapter is. */
    fun changeSignal(state: MangaLibraryState): Map<String, JsonObject> = snapshot(state).mapValues { (id, data) ->
        if (!id.startsWith("place:")) data else JsonObject(data.filterKeys { it == "chapterId" || it == "reachedEnd" })
    }

    private fun MangaLibraryState.withoutBook(id: String): MangaLibraryState {
        val book = books.find { it.id == id } ?: return this
        if (book.sourceId == "local") return this
        val chapterIds = book.chapters.map { it.id }.toSet()
        return copy(books = books.filterNot { it.id == id }, progress = progress - id, readChapters = readChapters - chapterIds, newChapters = newChapters - chapterIds,
            collections = collections.map { it.copy(bookIds = it.bookIds - id) }, syncedRead = syncedRead - id, tracks = tracks - id,
            downloads = downloads.filterKeys { it !in chapterIds })
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
