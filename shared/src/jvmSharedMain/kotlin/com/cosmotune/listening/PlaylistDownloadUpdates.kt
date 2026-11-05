package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class PlaylistDownloadWatch(val id: String, val title: String, val source: String, val remoteId: String,
    val seen: Set<String>, val checkedAt: Long = 0, val pending: List<ListeningTrack> = emptyList(), val error: String? = null,
    val enabled: Boolean = true)

/** Opt-in, additions only. Seen IDs deliberately survive removal of downloaded files. */
class PlaylistDownloadUpdates(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val checking = Mutex()
    private val mutable = MutableStateFlow(if (file.exists()) {
        require(file.length() <= 16 * 1024 * 1024) { "Playlist update data is too large; its file was kept." }
        json.decodeFromString<List<PlaylistDownloadWatch>>(file.readText(Charsets.UTF_8))
    } else emptyList())
    val watches = mutable.asStateFlow()

    suspend fun follow(playlist: LinkedPlaylist) = lock.withLock {
        require(playlist.base.isNotEmpty()) { "Check this linked playlist before enabling downloads." }
        val previous = mutable.value.firstOrNull { it.id == playlist.id && it.source == playlist.source && it.remoteId == playlist.remoteId }
        save(mutable.value.filterNot { it.id == playlist.id } + PlaylistDownloadWatch(playlist.id, playlist.name, playlist.source,
            playlist.remoteId, previous?.seen.orEmpty() + playlist.base.map { it.id } + playlist.entries.map { it.track.id }))
    }
    suspend fun unfollow(id: String) = lock.withLock { save(mutable.value.map { if (it.id == id) it.copy(enabled = false, pending = emptyList(), error = null) else it }) }

    /** The fetch callback must return a complete playlist or throw. Large batches always await review. */
    suspend fun check(fetch: suspend (String, String) -> RemotePlaylist, enqueue: suspend (List<Song>, String) -> Unit,
        force: Boolean = false, now: Long = System.currentTimeMillis()) = checking.withLock {
        for (watch in mutable.value.filter { it.enabled }) {
            if (!force && now - watch.checkedAt < INTERVAL_MS) continue
            try {
                val remote = fetch(watch.source, watch.remoteId)
                require(remote.tracks.isNotEmpty() && remote.tracks.size <= ListeningStore.MAX_TRACKS) { "The complete playlist was unavailable." }
                if (mutable.value.firstOrNull { it.id == watch.id } != watch) continue
                val added = remote.tracks.filter { it.available && it.song.videoId !in watch.seen }.map { ListeningTrack.of(it.song) }.distinctBy { it.id }
                if (added.size <= ASK_ABOVE) {
                    enqueue(added.map { it.song() }, watch.title)
                    lock.withLock { if (mutable.value.firstOrNull { it.id == watch.id } == watch)
                        replace(watch.copy(seen = watch.seen + added.map { it.id }, checkedAt = now, pending = emptyList(), error = null)) }
                } else lock.withLock { if (mutable.value.firstOrNull { it.id == watch.id } == watch)
                    replace(watch.copy(checkedAt = now, pending = added, error = null)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lock.withLock { if (mutable.value.firstOrNull { it.id == watch.id } == watch)
                replace(watch.copy(error = "Couldn't finish checking this playlist. No new additions were marked downloaded.")) } }
        }
    }

    suspend fun approve(expected: PlaylistDownloadWatch, enqueue: suspend (List<Song>, String) -> Unit) = lock.withLock {
        val current = mutable.value.firstOrNull { it.id == expected.id }
        require(current != null && current == expected && current.enabled) { "This playlist changed. Review its additions again." }
        enqueue(expected.pending.map { it.song() }, expected.title)
        replace(expected.copy(seen = expected.seen + expected.pending.map { it.id }, pending = emptyList(), error = null))
    }

    private fun replace(watch: PlaylistDownloadWatch) = save(mutable.value.map { if (it.id == watch.id) watch else it })
    private fun save(next: List<PlaylistDownloadWatch>) {
        require(next.size <= 200 && next.sumOf { it.seen.size.toLong() } <= 500_000 && next.all { it.seen.size <= 50_000 && it.pending.size <= ListeningStore.MAX_TRACKS }) {
            "Playlist update history reached its local storage limit. Existing history was kept; no additional tracks were recorded."
        }
        val encoded = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 16 * 1024 * 1024)
        val parent = file.absoluteFile.parentFile
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("playlist-updates-", ".part", parent)
        try {
            temporary.outputStream().use { it.write(encoded); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            mutable.value = next
        } finally { temporary.delete() }
    }
    companion object {
        const val ASK_ABOVE = 50
        const val INTERVAL_MS = 12L * 60 * 60 * 1000
        private val openStores = HashMap<String, PlaylistDownloadUpdates>()
        @Synchronized fun open(file: File): PlaylistDownloadUpdates = openStores.getOrPut(file.canonicalPath) { PlaylistDownloadUpdates(file) }
    }
}
