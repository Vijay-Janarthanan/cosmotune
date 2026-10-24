package com.cosmotune.manga.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A failed write leaves the previous state readable. Unknown future fields survive app upgrades as defaults. */
class MangaStore(val root: File, internal val protection: MangaFileProtection = UnsealedMangaFiles) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(root.apply { mkdirs(); ownerOnly() }, "library.json")
    private val diskLock = diskLocks.computeIfAbsent(file.canonicalPath) { Any() }
    private val mutex = Mutex()
    private var writeBlocked = false
    var recoveryMessage: String? = null
        private set
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    val archives = MangaArchive(root)

    private fun load(): MangaLibraryState = synchronized(diskLock) {
        if (!file.exists()) return@synchronized MangaLibraryState()
        var original: ByteArray? = null
        try {
            require(file.length() <= MAX_STATE_BYTES + 64 * 1024) { "Manga library is too large" }
            original = file.readBytes()
            val decoded = protection.open(original)
            require(decoded.size <= MAX_STATE_BYTES) { "Manga library is too large" }
            val restored = json.decodeFromString<MangaLibraryState>(decoded.toString(Charsets.UTF_8))
            // An interrupted chapter is retryable cache, never a completed offline download.
            restored.copy(downloads = restored.downloads.filterKeys { !it.startsWith("pending:") },
                readChapters = restored.readChapters + restored.progress.values.filter { it.finished }.map { it.chapterId })
        } catch (error: Exception) {
            val bytes = original
            if (bytes == null) {
                writeBlocked = true
                recoveryMessage = "Saved manga data could not be opened. Its original file was left untouched; check available storage and retry."
            } else try {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val recovered = File(root, "library-unreadable-$hash.json")
                if (!recovered.exists()) {
                    val temporary = File.createTempFile("recovery-", ".part", root)
                    try { temporary.ownerOnly(); temporary.writeBytes(bytes); MangaArchive.move(temporary, recovered) }
                    finally { temporary.delete() }
                }
                recoveryMessage = "Saved manga data could not be opened (${error.javaClass.simpleName}). The original file was preserved."
            } catch (_: Exception) {
                writeBlocked = true
                recoveryMessage = "Saved manga data could not be recovered. Its original file was left untouched; free storage and retry."
            }
            MangaLibraryState()
        }
    }

    suspend fun update(change: (MangaLibraryState) -> MangaLibraryState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(!writeBlocked) { recoveryMessage ?: "Manga library is unavailable" }
            val next = change(mutable.value)
            val encoded = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            require(encoded.size <= MAX_STATE_BYTES) { "Manga library is too large" }
            val temporary = File.createTempFile("library-", ".part", root)
            try {
                temporary.ownerOnly()
                temporary.outputStream().use { stream -> stream.write(protection.seal(encoded)); stream.fd.sync() }
                synchronized(diskLock) {
                    MangaArchive.move(temporary, file)
                    mutable.value = next
                }
            } finally { temporary.delete() }
        }
    }

    suspend fun save(book: MangaBook) = update { old ->
        val previous = old.books.find { it.id == book.id }
        old.copy(books = old.books.filterNot { it.id == book.id } + book.copy(addedAtMs = previous?.addedAtMs ?: book.addedAtMs))
    }

    suspend fun remove(bookId: String) = update { old ->
        val chapterIds = old.books.find { it.id == bookId }?.chapters.orEmpty().map { it.id }.toSet()
        old.copy(books = old.books.filterNot { it.id == bookId }, progress = old.progress - bookId,
            downloads = old.downloads.filterKeys { it !in chapterIds }, readChapters = old.readChapters - chapterIds,
            newChapters = old.newChapters - chapterIds,
            activeBookId = old.activeBookId.takeUnless { it == bookId },
            activeChapterId = old.activeChapterId.takeUnless { it in chapterIds })
    }

    suspend fun progress(value: MangaProgress) {
        require(value.pageCount > 0 && value.page in 0 until value.pageCount && value.pageOffsetPx in 0..10_000_000)
        update { it.copy(progress = it.progress + (value.bookId to value), readerMode = value.mode, newChapters = it.newChapters - value.chapterId,
            readChapters = if (value.finished) it.readChapters + value.chapterId else it.readChapters) }
    }

    suspend fun markRead(chapter: MangaChapter, read: Boolean) = update {
        it.copy(readChapters = if (read) it.readChapters + chapter.id else it.readChapters - chapter.id, newChapters = it.newChapters - chapter.id,
            progress = if (!read && it.progress[chapter.bookId]?.chapterId == chapter.id) it.progress - chapter.bookId else it.progress)
    }

    suspend fun navigate(bookId: String?, chapterId: String?) {
        if (state.value.activeBookId == bookId && state.value.activeChapterId == chapterId) return
        update { it.copy(activeBookId = bookId?.takeIf { id -> it.books.any { book -> book.id == id } }, activeChapterId = chapterId) }
    }

    /** A refreshed series replaces its saved copy (keeping when it was added); chapters it did not have before are marked new. */
    suspend fun refreshed(detail: MangaBook): Int {
        var found = 0
        update { old ->
            val previous = old.books.find { it.id == detail.id } ?: return@update old
            // A series whose chapters were never loaded has nothing to compare: its first list is not "new".
            val known = previous.chapters.map { it.id }.toSet()
            val fresh = if (known.isEmpty()) emptyList() else detail.chapters.map { it.id }.filter { it !in known && it !in old.readChapters }
            found = fresh.size
            old.copy(books = old.books.map { if (it.id == detail.id) detail.copy(addedAtMs = previous.addedAtMs) else it },
                newChapters = (old.newChapters + fresh).let { if (it.size > MAX_NEW_CHAPTERS) it.toList().takeLast(MAX_NEW_CHAPTERS).toSet() else it })
        }
        return found
    }

    suspend fun source(config: MangaSourceConfig) = update {
        it.copy(sources = it.sources.filterNot { source -> source.id == config.id } + config)
    }

    companion object {
        private const val MAX_STATE_BYTES = 16L * 1024 * 1024
        private const val MAX_NEW_CHAPTERS = 10_000
        private val diskLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
