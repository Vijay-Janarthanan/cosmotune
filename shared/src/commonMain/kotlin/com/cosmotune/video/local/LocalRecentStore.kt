package com.cosmotune.video.local

import com.cosmotune.video.library.DebouncedFileWriter
import com.cosmotune.video.library.LibraryFiles
import com.cosmotune.video.library.libraryLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * One video file the local player has opened: its [id] ([LocalVideo.idFor] of [source]), where it is ([source]: a path on the
 * desktop, a path or a `content://` address on the phone), its [title], its length once known, where it was left, when it was last
 * played and the [subtitle] file the person chose for it (a path or address), if any.
 */
@Serializable
data class LocalRecent(
    val id: String,
    val source: String,
    val title: String,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val lastPlayedMs: Long = 0,
    val subtitle: String? = null,
)

/**
 * The files the local player has opened, newest first, kept in `local-videos.json` beside the other video files: at most
 * [MAX_ENTRIES] (the oldest go first), each with its resume position. Also the one local setting, [rememberPositions] ("Remember
 * position"). Loaded when the store is made (a missing file is an empty list; one that cannot be read is set aside as
 * `<name>.broken-<time>`); a change is written 500 ms after the last, off the caller's thread; [flush] writes at once.
 */
class LocalRecentStore(
    private val files: LibraryFiles,
    scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = ::libraryLog,
) {
    @Serializable
    private data class Document(val version: Int = 1, val rememberPositions: Boolean = true, val recent: List<LocalRecent> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loaded = load()
    private val recentFlow = MutableStateFlow(loaded.recent.take(MAX_ENTRIES))
    private val rememberFlow = MutableStateFlow(loaded.rememberPositions)
    private val writer = DebouncedFileWriter(files, scope, ioDispatcher, log, encode = { encode() })

    /** The files, newest first. */
    val entries: StateFlow<List<LocalRecent>> = recentFlow.asStateFlow()

    /** Whether a file resumes where it was left ("Remember position"). */
    val rememberPositions: StateFlow<Boolean> = rememberFlow.asStateFlow()

    /** The entry of [id], or null. */
    fun get(id: String): LocalRecent? = recentFlow.value.firstOrNull { it.id == id }

    /**
     * Records that [source] is being opened: a new entry at the front, or the existing one (position, length and subtitle kept)
     * moved there with [title] as given. Returns the entry. The oldest entries beyond [MAX_ENTRIES] are dropped.
     */
    fun touch(source: String, title: String): LocalRecent {
        val id = LocalVideo.idFor(source)
        var result: LocalRecent? = null
        change { list ->
            val old = list.firstOrNull { it.id == id }
            val entry = (old ?: LocalRecent(id, source, title)).copy(source = source, title = title, lastPlayedMs = clock())
            result = entry
            (listOf(entry) + list.filter { it.id != id }).take(MAX_ENTRIES)
        }
        return result!!
    }

    /** Remembers where [id] is now, and its length when known (a length of 0 or less keeps the one known). */
    fun updateProgress(id: String, positionMs: Long, durationMs: Long) {
        change { list ->
            list.map {
                if (it.id != id) it else it.copy(positionMs = positionMs.coerceAtLeast(0), durationMs = if (durationMs > 0) durationMs else it.durationMs)
            }
        }
    }

    /** Remembers the subtitle file picked for [id] (null: none). */
    fun setSubtitle(id: String, subtitle: String?) {
        change { list -> list.map { if (it.id == id) it.copy(subtitle = subtitle) else it } }
    }

    /** Forgets [id]. */
    fun remove(id: String) {
        change { list -> list.filter { it.id != id } }
    }

    /** Forgets every file. */
    fun clear() {
        change { emptyList() }
    }

    /** Turns "Remember position" on or off. */
    fun setRememberPositions(on: Boolean) {
        if (rememberFlow.value == on) return
        rememberFlow.value = on
        writer.changed(FILE)
    }

    /** Writes a change that is still waiting for its 500 ms now, on the calling thread. */
    fun flush() = writer.flush()

    private fun change(transform: (List<LocalRecent>) -> List<LocalRecent>) {
        while (true) {
            val old = recentFlow.value
            val new = transform(old)
            if (new == old) return
            if (recentFlow.compareAndSet(old, new)) break
        }
        writer.changed(FILE)
    }

    private fun encode(): String = json.encodeToString(Document.serializer(), Document(1, rememberFlow.value, recentFlow.value))

    private fun load(): Document {
        val text = files.read(FILE) ?: return Document()
        return try {
            json.decodeFromString(Document.serializer(), text)
        } catch (e: Exception) {
            val aside = "$FILE.broken-${clock()}"
            log("$FILE could not be read (${e.message}); keeping it as $aside and starting empty")
            try {
                files.rename(FILE, aside)
            } catch (second: Exception) {
                log("$FILE could not be set aside: ${second.message}")
            }
            Document()
        }
    }

    companion object {
        /** The file's name in the video folder. */
        const val FILE = "local-videos.json"

        /** At most this many files are kept. */
        const val MAX_ENTRIES = 50
    }
}
