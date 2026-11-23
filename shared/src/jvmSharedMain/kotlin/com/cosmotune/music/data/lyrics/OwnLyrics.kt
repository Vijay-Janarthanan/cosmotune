package com.cosmotune.music.data.lyrics

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lyrics the listener chose or timed themselves, kept for good on the device, one file per song.
 *
 * Two ways in: a hit picked from a typed lyrics search, and the timing editor. Either way they outrank every provider for that
 * song: [LyricsStore] answers with them before it asks anyone, and the player shows them the moment they are saved. Kept in the
 * app's own files (not the cache, which the system may clear), so they are there offline and after a restart.
 */
object OwnLyrics {
    /** One song's own lyrics: its [lines], and [from], the search source they were picked from (null: timed by hand). */
    @Serializable
    data class Saved(val videoId: String, val lines: List<LyricLine>, val from: String? = null, val savedAtMs: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var directory: File? = null

    /** Every song looked at so far: its lines, or null for a song known to have none of its own. */
    private val known = MutableStateFlow<Map<String, Saved?>>(emptyMap())

    /** The kept lyrics, by song, of every song read or saved so far; read [lines] for one that may not be loaded yet. */
    val saved: StateFlow<Map<String, Saved?>> get() = known.asStateFlow()

    /** Gives the store its folder (the app's files, "own-lyrics" under it); called once at start-up. */
    fun configure(directory: File) {
        this.directory = directory
    }

    /** Forgets everything in memory (tests). */
    internal fun reset(directory: File? = null) {
        this.directory = directory
        known.value = emptyMap()
    }

    /** The listener's own lines for [videoId], or null when the song has none. Reads the disk the first time a song is asked. */
    fun lines(videoId: String): List<LyricLine>? = entry(videoId)?.lines

    /** Where the kept lines came from ("LRCLIB", or null for lines the listener timed), for the player's source line. */
    fun origin(videoId: String): String? = entry(videoId)?.from

    /** Whether [lines] for [videoId] would read the disk (it has not been asked before): worth leaving the main thread for. */
    fun needsDisk(videoId: String): Boolean = directory != null && videoId.isNotBlank() && !known.value.containsKey(videoId)

    private fun entry(videoId: String): Saved? {
        if (videoId.isBlank()) return null
        known.value[videoId]?.let { return it }
        if (known.value.containsKey(videoId)) return null
        val read = runCatching {
            fileFor(videoId)?.takeIf(File::isFile)?.readText()?.let { json.decodeFromString(Saved.serializer(), it) }
        }.getOrNull()?.takeIf { it.lines.any { line -> !line.isGap } }
        known.update { it + (videoId to read) }
        return read
    }

    /** Keeps [lines] as [videoId]'s own lyrics ([from] names a search hit's source; null for lines timed by hand). */
    fun save(videoId: String, lines: List<LyricLine>, from: String? = null, nowMs: Long = System.currentTimeMillis()) {
        if (videoId.isBlank()) return
        val kept = lines.filter { !it.isGap || it.timeMs > 0 }
        if (kept.none { !it.isGap }) return remove(videoId)
        val saved = Saved(videoId, kept, from, nowMs)
        known.update { it + (videoId to saved) }
        fileFor(videoId)?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.encodeToString(Saved.serializer(), saved))
                if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
            }
        }
        // The provider answer kept for the song is not what it shows any more.
        LyricsStore.forget(videoId)
    }

    /** Drops [videoId]'s own lyrics, so the providers answer for it again. */
    fun remove(videoId: String) {
        if (videoId.isBlank()) return
        known.update { it + (videoId to null) }
        fileFor(videoId)?.let { runCatching { it.delete() } }
        LyricsStore.forget(videoId)
    }

    /** One file per song, its id made safe for any file system. */
    private fun fileFor(videoId: String): File? = directory?.let { File(it, safeName(videoId) + ".json") }

    internal fun safeName(videoId: String): String = buildString {
        videoId.forEach { c -> append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_') }
        // Two ids that differ only in the characters replaced above still get their own files.
        if (videoId.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) append('-').append(Integer.toHexString(videoId.hashCode()))
    }
}
