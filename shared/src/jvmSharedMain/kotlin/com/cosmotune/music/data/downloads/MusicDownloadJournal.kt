package com.cosmotune.music.data.downloads

import com.cosmotune.listening.ListeningTrack
import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class MusicDownloadPhase { QUEUED, RUNNING, FAILED }
@Serializable data class MusicDownloadIntent(val track: ListeningTrack, val sequence: Long,
    val quality: String = "LOSSLESS", val from: String? = null, val phase: MusicDownloadPhase = MusicDownloadPhase.QUEUED,
    val error: String? = null)
@Serializable private data class MusicDownloadJournalState(val version: Int = 1, val entries: List<MusicDownloadIntent> = emptyList())

/** Durable intent metadata; expiring stream URLs and transfer credentials are resolved again after restart. */
class MusicDownloadJournal(private val file: File) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val writeLock = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var blocked = false
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutable = MutableStateFlow(load())
    val entries = mutable.asStateFlow()

    init { scope.launch { for (ignored in changes) try { flush() } catch (_: Exception) { /* surfaced by error */ } } }

    private fun load(): List<MusicDownloadIntent> {
        if (!file.exists()) return emptyList()
        return try {
            require(file.length() <= MAX_BYTES)
            val state = json.decodeFromString<MusicDownloadJournalState>(file.readText(Charsets.UTF_8))
            require(state.version == 1 && state.entries.size <= MAX_ENTRIES)
            require(state.entries.all { it.track.id.isNotBlank() && it.sequence > 0 })
            require(state.entries.map { it.track.id }.distinct().size == state.entries.size)
            state.entries.sortedBy { it.sequence }
        } catch (_: Exception) {
            blocked = true; mutableError.value = "Saved download queue could not be opened. Its file was left untouched."
            emptyList()
        }
    }

    fun enqueue(song: Song, sequence: Long, quality: String, from: String? = null) = change { old ->
        require(song.videoId.isNotBlank() && sequence > 0)
        old.filterNot { it.track.id == song.videoId } + MusicDownloadIntent(ListeningTrack.of(song), sequence, quality, from)
    }

    fun phase(id: String, sequence: Long, phase: MusicDownloadPhase, reason: String? = null) = change { old ->
        old.map { if (it.track.id == id && it.sequence == sequence) it.copy(phase = phase, error = reason?.take(300)) else it }
    }

    fun remove(id: String, sequence: Long? = null) = change { old -> old.filterNot { it.track.id == id && (sequence == null || it.sequence == sequence) } }

    private fun change(transform: (List<MusicDownloadIntent>) -> List<MusicDownloadIntent>) {
        synchronized(lock) {
            if (blocked) return
            val next = transform(mutable.value)
            require(next.size <= MAX_ENTRIES) { "Download queue is limited to $MAX_ENTRIES tracks" }
            if (next == mutable.value) return
            mutable.value = next
            changes.trySend(Unit)
        }
    }

    suspend fun flush() = writeLock.withLock { withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { if (blocked) return@withContext else mutable.value }
        var temporary: File? = null
        try {
            val encoded = json.encodeToString(MusicDownloadJournalState(entries = snapshot)).toByteArray(Charsets.UTF_8)
            require(encoded.size <= MAX_BYTES) { "Download queue exceeds its storage limit" }
            val parent = file.absoluteFile.parentFile
            check(parent.isDirectory || parent.mkdirs())
            temporary = File.createTempFile("queue-", ".part", parent)
            temporary.setReadable(false, false); temporary.setWritable(false, false)
            temporary.setReadable(true, true); temporary.setWritable(true, true)
            temporary.outputStream().use { it.write(encoded); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            mutableError.value = null
        } catch (error: Exception) {
            mutableError.value = "Download queue could not be saved. Check available storage."
            throw error
        } finally { temporary?.delete() }
    } }

    override fun close() { changes.close(); scope.cancel() }
    companion object { const val MAX_ENTRIES = 2_000; const val MAX_BYTES = 4L * 1024 * 1024 }
}
