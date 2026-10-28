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
            collections = old.collections.map { it.copy(bookIds = it.bookIds - bookId) },
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

    suspend fun createCollection(name: String): String {
        val id = java.util.UUID.randomUUID().toString()
        update { old ->
            require(old.collections.size < 128) { "Choose at most 128 collections" }
            val title = collectionName(name)
            require(old.collections.none { it.name.equals(title, true) }) { "A collection with this name already exists" }
            old.copy(collections = old.collections + MangaCollection(id, title))
        }
        return id
    }

    suspend fun renameCollection(id: String, name: String) = update { old ->
        require(old.collections.any { it.id == id }) { "This collection was removed" }
        val title = collectionName(name)
        require(old.collections.none { it.id != id && it.name.equals(title, true) }) { "A collection with this name already exists" }
        old.copy(collections = old.collections.map { if (it.id == id) it.copy(name = title) else it })
    }

    suspend fun deleteCollection(id: String) = update { old -> old.copy(collections = old.collections.filterNot { it.id == id }) }

    suspend fun assignCollection(bookId: String, collectionId: String, included: Boolean) = update { old ->
        require(old.books.any { it.id == bookId } && old.collections.any { it.id == collectionId }) { "Save the series and collection first" }
        old.copy(collections = old.collections.map { collection -> if (collection.id != collectionId) collection else
            collection.copy(bookIds = if (included) collection.bookIds + bookId else collection.bookIds - bookId) })
    }

    suspend fun updateSchedule(hours: Int) { require(hours in setOf(0, 24, 168)); update { it.copy(updateIntervalHours = hours) } }

    internal fun collectionName(value: String): String = value.trim().also {
        require(it.length in 1..80 && it.none(Char::isISOControl)) { "Use a collection name with 1 to 80 characters" }
    }

    /** Commit after the replacement APK has loaded. Recheck against the current state under the
     * write lock, so concurrent reviews cannot overwrite a newer version or a different signer. */
    suspend fun installExtension(packageName: String, configs: List<MangaSourceConfig>) {
        require(configs.isNotEmpty() && configs.size <= 512 && configs.map { it.id }.distinct().size == configs.size) {
            "The extension contains no supported catalogue sources or duplicate source identities"
        }
        require(configs.all { it.kind == "mihon" && it.settings["package"] == packageName }) { "Extension source identity differs from the reviewed APK" }
        val metadata = configs.first().settings
        val signers = metadata.getValue("signers").split(',').toSet()
        val version = metadata.getValue("versionCode").toLong()
        require(version >= 0 && metadata["sha256"]?.matches(Regex("[0-9a-f]{64}")) == true) { "The reviewed APK identity is invalid" }
        require(signers.isNotEmpty() && signers.all { it.matches(Regex("[0-9a-f]{64}")) }) { "The reviewed APK has no valid publisher certificate" }
        require(configs.all { it.settings["signers"] == metadata["signers"] && it.settings["versionCode"] == metadata["versionCode"] &&
            it.settings["sha256"] == metadata["sha256"] }) { "The reviewed sources disagree on their APK identity" }
        update { old ->
            val previous = old.sources.filter { it.kind == "mihon" && it.settings["package"] == packageName }
            require(previous.all { it.settings["signers"].orEmpty().split(',').all { signer -> signer in signers } }) {
                "The signer differs from the installed extension. Remove it before switching publishers."
            }
            require(previous.all { (it.settings["versionCode"]?.toLongOrNull() ?: 0L) <= version }) { "A newer extension version is already installed" }
            val installed = configs.map { config -> config.copy(enabled = previous.find { it.id == config.id }?.enabled ?: true) }
            old.copy(sources = old.sources.filterNot { it.kind == "mihon" && it.settings["package"] == packageName } + installed)
        }
    }

    suspend fun saveExtensionStore(value: MangaExtensionStore) = update { old ->
        require(old.extensionStores.none { it.indexUrl == value.indexUrl && it.signingKey != value.signingKey }) {
            "Remove the saved store before changing its publisher key."
        }
        old.copy(extensionStores = old.extensionStores.filterNot { it.indexUrl == value.indexUrl } + value)
    }

    /** Removal changes only subscriptions; previously trusted installed sources remain usable. */
    suspend fun removeExtensionStore(indexUrl: String) = update { old ->
        old.copy(extensionStores = old.extensionStores.filterNot { it.indexUrl == indexUrl })
    }

    companion object {
        private const val MAX_STATE_BYTES = 16L * 1024 * 1024
        private const val MAX_NEW_CHAPTERS = 10_000
        private val diskLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
