@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.cosmotune.manga.data

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * A Mihon / Tachiyomi backup (.tachibk, .proto.gz): gzip around protobuf. The field numbers follow Mihon's backup models
 * (Apache-2.0, see NOTICE.md); only what the library needs is read, everything else in the file is skipped.
 */

@Serializable internal class TachiBackup(
    @ProtoNumber(1) val manga: List<TachiManga> = emptyList(),
    @ProtoNumber(2) val categories: List<TachiCategory> = emptyList(),
    @ProtoNumber(101) val sources: List<TachiSource> = emptyList(),
)

@Serializable internal class TachiManga(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(16) val chapters: List<TachiChapter> = emptyList(),
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(104) val history: List<TachiHistory> = emptyList(),
)

@Serializable internal class TachiChapter(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = 0f,
)

@Serializable internal class TachiCategory(@ProtoNumber(1) val name: String = "", @ProtoNumber(2) val order: Long = 0)
@Serializable internal class TachiSource(@ProtoNumber(1) val name: String = "", @ProtoNumber(2) val sourceId: Long = 0)
@Serializable internal class TachiHistory(@ProtoNumber(1) val url: String = "", @ProtoNumber(2) val lastRead: Long = 0)

/** Ids Cosmotune gives a Mihon extension's series and chapters: the first 16 bytes of the SHA-256 of their address, in hex. */
object MihonIds {
    fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    /** The source id a backed-up series stands under when no installed extension has its source. */
    const val UNLINKED = "mihon-backup:"
    internal const val SOURCE_NAME = "cosmotune.mihonSource"
}

/**
 * What a Mihon backup adds to the library: [books] (with their chapters and read marks), [read] chapters, the newest reading
 * place of each series in [progress], and the categories as collections by name. [linked] of the series open with an installed
 * extension; the rest stand under their old source's name until they are found on another source.
 */
class MihonBackupImport internal constructor(
    val books: List<MangaBook>,
    val read: Set<String>,
    val progress: Map<String, MangaProgress>,
    val collections: Map<String, Set<String>>,
    val linked: Int,
) {
    val unlinked: Int get() = books.size - linked

    /** Adds the backup to [store]: series already in the library keep their own state; collections merge by name. */
    suspend fun merge(store: MangaStore) = store.update { old ->
        val known = old.books.mapTo(HashSet()) { it.id }
        val added = books.filter { it.id !in known }
        require(old.books.size + added.size <= 10_000) { "The combined library exceeds 10,000 series" }
        var groups = old.collections
        collections.forEach { (name, members) ->
            val index = groups.indexOfFirst { it.name.equals(name, true) }
            groups = if (index >= 0) groups.mapIndexed { position, group -> if (position == index) group.copy(bookIds = group.bookIds + members) else group }
            else { require(groups.size < 128) { "The combined library exceeds 128 collections" }; groups + MangaCollection(java.util.UUID.randomUUID().toString(), name, members) }
        }
        val addedIds = added.map { it.id }.toSet()
        old.copy(books = old.books + added, collections = groups, readChapters = old.readChapters + read,
            progress = progress.filterKeys { it in addedIds } + old.progress)
    }

    companion object {
        private const val MAX_UNPACKED = 64 * 1024 * 1024

        /** True when [bytes] start like a gzip file (a Mihon backup), not a Cosmotune JSON backup. */
        fun looksLikeOne(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        /**
         * Reads a Mihon backup. A series whose source is an installed extension ([sources], Cosmotune's "mihon:<package>:<id>")
         * gets the ids that extension gives it, so it opens and refreshes straight away; the others keep their title, cover,
         * chapters and read marks under [MihonIds.UNLINKED].
         */
        fun read(bytes: ByteArray, sources: List<MangaSourceConfig>): MihonBackupImport {
            val unpacked = try { GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(MAX_UNPACKED + 1) } }
                catch (broken: java.io.IOException) { throw IllegalArgumentException("This backup file is damaged or incomplete", broken) }
            require(unpacked.size <= MAX_UNPACKED) { "This backup is too large" }
            val backup = try { ProtoBuf.decodeFromByteArray<TachiBackup>(unpacked) }
                catch (failure: Exception) { throw IllegalArgumentException("This file is not a Mihon or Tachiyomi backup", failure) }
            return convert(backup, sources)
        }

        internal fun convert(backup: TachiBackup, sources: List<MangaSourceConfig>): MihonBackupImport {
            val names = backup.sources.associate { it.sourceId to it.name }
            val installed = sources.filter { it.kind == "mihon" }.associateBy { it.id.substringAfterLast(':').toLongOrNull() }
            val categoryNames = backup.categories.associate { it.order to it.name.trim().take(80) }.filterValues { it.isNotBlank() && it.none(Char::isISOControl) }
            val books = ArrayList<MangaBook>(); val read = HashSet<String>(); val progress = HashMap<String, MangaProgress>()
            val collections = LinkedHashMap<String, MutableSet<String>>()
            var linked = 0
            backup.manga.filter { it.favorite && it.url.isNotBlank() && it.title.isNotBlank() }.take(10_000).forEach { manga ->
                val config = installed[manga.source]
                val sourceId = config?.id ?: (MihonIds.UNLINKED + manga.source)
                val bookId = "$sourceId:series:${MihonIds.key(manga.url)}"
                if (books.any { it.id == bookId }) return@forEach
                if (config != null) linked++
                val sourceName = names[manga.source].orEmpty()
                val chapters = manga.chapters.take(5_000).map { chapter ->
                    MangaChapter("$sourceId:chapter:${MihonIds.key(chapter.url)}", bookId, sourceId, chapter.name.ifBlank { "Chapter ${chapter.chapterNumber}" },
                        chapter.url, chapter.chapterNumber.coerceAtLeast(0f), config?.language.orEmpty(), scanlator = chapter.scanlator, uploadDate = chapter.dateUpload)
                }
                manga.chapters.zip(chapters).filter { it.first.read }.forEach { read += it.second.id }
                manga.history.maxByOrNull { it.lastRead }?.let { last ->
                    val index = manga.chapters.indexOfFirst { it.url == last.url }
                    if (index >= 0) {
                        val chapter = chapters[index]; val source = manga.chapters[index]
                        // Mihon keeps the page, not the chapter's length: an unfinished place reopens at its page.
                        progress[bookId] = MangaProgress(bookId, chapter.id, if (source.read) 0 else source.lastPageRead.toInt().coerceIn(0, 100_000),
                            if (source.read) 1 else 0, updatedAtMs = last.lastRead, reachedEnd = source.read)
                    }
                }
                books += MangaBook(bookId, sourceId, manga.title.take(500), manga.url, manga.thumbnailUrl?.takeIf { it.startsWith("http") },
                    listOfNotNull(manga.author, manga.artist).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    manga.description.orEmpty(), manga.genre.take(30), config?.language.orEmpty(), chapters,
                    addedAtMs = manga.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sourceMemo = if (config == null && sourceName.isNotBlank()) kotlinx.serialization.json.JsonObject(mapOf(MihonIds.SOURCE_NAME to kotlinx.serialization.json.JsonPrimitive(sourceName.take(120))))
                        else kotlinx.serialization.json.JsonObject(emptyMap()))
                manga.categories.mapNotNull { categoryNames[it] }.forEach { name -> collections.getOrPut(name) { LinkedHashSet() } += bookId }
            }
            return MihonBackupImport(books, read, progress, collections, linked)
        }
    }
}

/** The name of the source a series came from in a Mihon backup ("MangaDex"), while no installed source has it; null otherwise. */
fun MangaBook.unlinkedSourceName(): String? = if (!sourceId.startsWith(MihonIds.UNLINKED)) null
    else (sourceMemo[MihonIds.SOURCE_NAME] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "Mihon"
