package com.cosmotune.music.data.covers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The covers picked for the person's own playlists, kept in [folder] (the app's own files): for each playlist a large copy (about
 * 1200 px) the pages show and a small one (about 400 px) for the Cosmotune account, both JPEG, listed in `covers.json`. A cover that
 * came from the account has only the small copy, which is then also what shows. YouTube Music never gets either.
 *
 * Each picture has a file name of its own (the playlist id and a digest of the picture), so a new picture is a new address and
 * nothing shows the old one from a cache.
 */
class PlaylistCoverStore(
    private val folder: File,
    /** Where [covers] is published; [PlaylistCovers] passes its own, so what collected it before the store opened sees the covers. */
    private val state: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
) {
    @Serializable
    private data class Entry(
        val file: String,
        val small: String,
        /** When the cover was first picked here or came here: the account takes covers oldest first ([smallPictures]). */
        val at: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(folder, "covers.json")
    private var entries: Map<String, Entry> = load()

    init {
        state.value = addresses(entries)
    }

    /** Playlist id ([PlaylistCoverRules.key]) to the address of its picture, for [PlaylistCoverRules.coverFor]. */
    val covers: StateFlow<Map<String, String>> = state.asStateFlow()

    /** The map the last cloud write produced, so a watcher can tell the sync's own write from a cover picked on this device. */
    @Volatile var lastCloudWrite: Map<String, String>? = null
        private set

    private fun load(): Map<String, Entry> = runCatching {
        json.decodeFromString<Map<String, Entry>>(index.readText())
            .filter { (id, entry) -> PlaylistCoverRules.key(id) == id && File(folder, entry.file).isFile }
    }.getOrDefault(emptyMap())

    private fun addresses(from: Map<String, Entry>): Map<String, String> =
        from.mapValues { (_, entry) -> File(folder, entry.file).toURI().toString() }

    /** Keeps [full] (shown) and [small] (for the account) as the cover of [playlistId]. False when either is not a JPEG or the id is not a playlist's. */
    @Synchronized
    fun set(playlistId: String, full: ByteArray, small: ByteArray): Boolean {
        val id = PlaylistCoverRules.key(playlistId) ?: return false
        if (!CoverJpeg.looksLikeJpeg(full) || !CoverJpeg.looksLikeJpeg(small)) return false
        val stamp = digest(full)
        // A new picture for a playlist keeps the playlist's place in the account's order.
        val entry = Entry("$id-$stamp.jpg", "$id-$stamp-s.jpg", entries[id]?.at ?: System.currentTimeMillis())
        return runCatching {
            write(entry.file, full)
            runCatching { write(entry.small, small) }.onFailure { File(folder, entry.file).delete() }.getOrThrow()
            replace(entries + (id to entry))
        }.isSuccess
    }

    /** "Use the automatic cover": forgets the picture of [playlistId] and deletes its files. */
    @Synchronized
    fun clear(playlistId: String) {
        val id = PlaylistCoverRules.key(playlistId) ?: return
        if (id !in entries) return
        runCatching { replace(entries - id) }
    }

    /** The account's copy of [playlistId]'s cover, or null. */
    @Synchronized
    fun smallJpeg(playlistId: String): ByteArray? {
        val entry = PlaylistCoverRules.key(playlistId)?.let(entries::get) ?: return null
        return runCatching { File(folder, entry.small).readBytes() }.getOrNull()
    }

    /**
     * Every account copy, by playlist id, oldest cover first: what [com.cosmotune.cloud.SyncPlaylistCovers.snapshot] makes the account's
     * entries of. In that order a cover picked later can never push one the account already has out of a full bucket, which the
     * account would take for that cover being removed, on every device.
     */
    @Synchronized
    fun smallPictures(): Map<String, ByteArray> =
        entries.entries.sortedWith(compareBy({ it.value.at }, { it.key }))
            .mapNotNull { (id, entry) -> runCatching { File(folder, entry.small).readBytes() }.getOrNull()?.let { id to it } }
            .toMap(LinkedHashMap())

    /**
     * Lays the account's changes over the covers: [upserts] are small copies from another device (kept as they came, and shown),
     * [deletes] are playlists whose cover went back to the automatic one there. A picture that is not a JPEG is left out.
     */
    @Synchronized
    fun applyFromCloud(upserts: Map<String, ByteArray>, deletes: Set<String>) {
        var next = entries - deletes
        for ((playlistId, bytes) in upserts) {
            val id = PlaylistCoverRules.key(playlistId) ?: continue
            if (!CoverJpeg.looksLikeJpeg(bytes)) continue
            // The same picture the device already has (its own pick coming back) keeps its large copy.
            val current = next[id]
            if (current != null && runCatching { File(folder, current.small).readBytes().contentEquals(bytes) }.getOrDefault(false)) continue
            val name = "$id-${digest(bytes)}-s.jpg"
            if (runCatching { write(name, bytes) }.isFailure) continue
            next = next + (id to Entry(name, name, current?.at ?: System.currentTimeMillis()))
        }
        runCatching { replace(next) }
        lastCloudWrite = state.value
    }

    private fun replace(next: Map<String, Entry>) {
        val before = entries
        folder.mkdirs()
        val temporary = File(folder, index.name + ".tmp")
        temporary.writeText(json.encodeToString(next))
        // Replaced in one step: a delete and a rename could leave no index at all, and every cover forgotten.
        Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        entries = next
        state.value = addresses(next)
        // The files no cover uses any more.
        val used = next.values.flatMap { listOf(it.file, it.small) }.toSet()
        for (entry in before.values) for (name in listOf(entry.file, entry.small)) if (name !in used) File(folder, name).delete()
    }

    private fun write(name: String, bytes: ByteArray) {
        folder.mkdirs()
        val target = File(folder, name)
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val temporary = File(folder, "$name.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) { target.delete(); check(temporary.renameTo(target)) { "could not keep $name" } }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
}

/**
 * The app's one [PlaylistCoverStore], opened by each app at start ([install]) in its own files folder. Until then (and in tests of
 * other things) there are no picked covers, and every playlist shows its automatic one.
 */
object PlaylistCovers {
    private val shown = MutableStateFlow<Map<String, String>>(emptyMap())

    @Volatile var store: PlaylistCoverStore? = null
        private set

    /** Playlist id to the address of its picked cover; empty until [install]. */
    val covers: StateFlow<Map<String, String>> = shown.asStateFlow()

    @Synchronized
    fun install(folder: File): PlaylistCoverStore = store ?: PlaylistCoverStore(folder, shown).also { store = it }

    /** "Use the automatic cover" from a tap: the files are written and deleted off the main thread, which may wait on a sync. */
    fun clearSoon(playlistId: String) {
        val current = store ?: return
        Thread({ current.clear(playlistId) }, "playlist-cover-clear").apply { isDaemon = true }.start()
    }
}
