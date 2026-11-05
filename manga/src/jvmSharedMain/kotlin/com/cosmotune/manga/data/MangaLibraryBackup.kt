package com.cosmotune.manga.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Portable metadata only: source keys, browser proof, downloads and local filesystem paths are absent. */
@Serializable data class MangaLibraryBackup(
    val version: Int = 1,
    val books: List<MangaBook> = emptyList(),
    val collections: List<MangaCollection> = emptyList(),
    val progress: Map<String, MangaProgress> = emptyMap(),
    val readChapters: Set<String> = emptySet(),
) {
    fun encode(): ByteArray = codec.encodeToString(this).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Library backup exceeds 8 MB" } }
    suspend fun merge(store: MangaStore) {
        validate()
        store.update { old ->
            val existingIds = old.books.mapTo(HashSet()) { it.id }
            val books = old.books + books.filter { it.id !in existingIds }
            require(books.size <= 10_000) { "The combined library exceeds 10,000 series" }
            val known = books.map { it.id }.toSet()
            val chapters = books.flatMap { it.chapters }.associateBy { it.id }
            var groups = old.collections
            collections.forEach { incoming ->
                val index = groups.indexOfFirst { it.id == incoming.id || it.name.equals(incoming.name, true) }
                val members = incoming.bookIds.intersect(known)
                if (index >= 0) groups = groups.mapIndexed { position, current -> if (position == index) current.copy(bookIds = current.bookIds + members) else current }
                else { require(groups.size < 128) { "The combined library exceeds 128 collections" }; groups = groups + incoming.copy(bookIds = members) }
            }
            val restored = progress.filter { (id, value) -> id in known && value.bookId == id && value.chapterId in chapters &&
                chapters[value.chapterId]?.bookId == id && value.pageCount > 0 && value.page in 0 until value.pageCount && value.pageOffsetPx in 0..10_000_000 }
            old.copy(books = books, collections = groups, progress = restored + old.progress, readChapters = old.readChapters + readChapters.intersect(chapters.keys))
        }
    }
    private fun validate() {
        require(version == 1 && books.size <= 10_000 && books.map { it.id }.distinct().size == books.size && collections.size <= 128) { "Unsupported or oversized Manga backup" }
        require(books.sumOf { it.chapters.size } <= 100_000)
        require(collections.all { it.id.length in 1..120 && it.id != "unassigned" && it.name.trim().length in 1..80 && it.name.none(Char::isISOControl) && it.bookIds.size <= 10_000 })
        require(collections.map { it.id }.distinct().size == collections.size)
        require(books.all { book -> book.id.length in 1..1_000 && book.sourceId != "local" && book.sourceMemo.isEmpty() && book.chapters.all { it.bookId == book.id && it.sourceId == book.sourceId && it.archiveFile == null && it.sourceMemo.isEmpty() } }) {
            "This backup contains local file references. Reimport local comic files separately."
        }
    }
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(bytes: ByteArray): MangaLibraryBackup {
            require(bytes.size <= MAX_BYTES) { "Library backup exceeds 8 MB" }
            return codec.decodeFromString<MangaLibraryBackup>(bytes.toString(Charsets.UTF_8)).also { it.validate() }
        }
        fun from(state: MangaLibraryState): MangaLibraryBackup {
            val remote = state.books.filter { it.sourceId != "local" }.map { book -> book.copy(sourceMemo = JsonObject(emptyMap()), chapters = book.chapters.map { it.copy(archiveFile = null, sourceMemo = JsonObject(emptyMap())) }) }
            // Local IDs survive in collections so an already-reimported CBZ can regain its collection on another device.
            return MangaLibraryBackup(books = remote, collections = state.collections, progress = state.progress, readChapters = state.readChapters)
        }
    }
}
