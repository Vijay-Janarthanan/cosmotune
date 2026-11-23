package com.cosmotune.podio

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A recording kept on this device: the file, the station and, when the recording was split by song, the song. */
data class PodioRecording(val file: File, val station: String, val title: String, val savedMs: Long, val bytes: Long) {
    /** The recording as the player takes it, from its file. */
    fun item(): PodioItem = PodioItem("podio:episode:recording-" + PodioCatalogue.identity(file.absolutePath), title.ifBlank { file.nameWithoutExtension }, station,
        file.toURI().toString(), downloadedUri = file.toURI().toString(), downloadedPath = file.absolutePath)
}

/**
 * Records the station that plays to files (RadioDroid's and Shortwave's recorder, written anew). It opens nothing itself: the
 * player's own stream passes its bytes through a [Tap] (Android's data source, the desktop's stream reader), so a recording costs
 * no extra data. The song-title blocks between the audio are dropped, and when [State.split] is on each new song the station names
 * starts a new file (Shortwave's track saving). A recording stops by itself after [MAX_MS] (three hours), or when another station or
 * an episode plays.
 */
object PodioRecorder {
    const val MAX_MS = 3 * 60 * 60 * 1000L

    data class State(val stationId: String, val station: String, val url: String, val startedMs: Long, val title: String? = null, val bytes: Long = 0,
        val saved: Int = 0, val split: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow<State?>(null)
    /** The recording running now (or one that failed, with its [State.error]), or null. */
    val state: StateFlow<State?> = _state.asStateFlow()
    private val _saves = MutableStateFlow(0)
    /** Goes up each time a file is saved, so lists of recordings know to look again. */
    val saves: StateFlow<Int> = _saves.asStateFlow()

    @Volatile private var playingUrl: String? = null
    private var session: Session? = null
    private var clock: () -> Long = System::currentTimeMillis

    /** The station the player is on now, or null for anything else; a recording of another station stops. */
    @Synchronized fun playing(station: RadioStation?) {
        playingUrl = station?.url
        val running = _state.value ?: return
        if (running.error == null && (station == null || !same(station.url, running.url))) stop()
    }

    /** Starts recording [station] (which plays) into [folder]; the bytes come from the player's stream as it reads them. */
    @Synchronized fun start(station: RadioStation, folder: File, split: Boolean, nowMs: () -> Long = System::currentTimeMillis) {
        stop()
        clock = nowMs
        playingUrl = station.url
        session = Session(folder, station.name, split, nowMs)
        _state.value = State(station.id, station.name, station.url, nowMs(), split = split)
    }

    /** Stops the recording; what was recorded so far is kept. */
    @Synchronized fun stop() {
        session?.finish()?.let { saved -> if (saved > 0) _saves.value++ }
        session = null
        _state.value = null
    }

    /** Clears a failed recording's message. */
    @Synchronized fun dismissError() { if (_state.value?.error != null) _state.value = null }

    /**
     * The player opened [url]: a tap for its bytes when it is the station that plays (or is recorded), else null. [contentType]
     * and [metaInterval] are the stream's own (Content-Type and icy-metaint), which say what the audio is and where its titles sit.
     */
    fun tap(url: String, contentType: String?, metaInterval: Int?): Tap? {
        val known = playingUrl ?: _state.value?.url ?: return null
        return if (same(url, known)) Tap(url, extensionOf(contentType, url), metaInterval?.takeIf { it in 1..65_536 }) else null
    }

    /** One opening of a station's stream by the player: every byte it reads goes through [write], titles and all. */
    class Tap internal constructor(private val url: String, private val extension: String?, interval: Int?) {
        private val splitter = IcyStreamSplitter(interval)
        fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0 || _state.value?.takeIf { it.error == null && same(it.url, url) } == null) {
                // Not recorded now: the titles are still followed, so a split recording begins on the right song.
                if (length > 0) splitter.feed(bytes, offset, length, onAudio = { _, _, _ -> }, onTitle = { title(url, it) })
                return
            }
            splitter.feed(bytes, offset, length, onAudio = { b, o, l -> audio(url, extension, b, o, l) }, onTitle = { title(url, it) })
        }
    }

    @Synchronized private fun audio(url: String, extension: String?, bytes: ByteArray, offset: Int, length: Int) {
        val running = _state.value?.takeIf { it.error == null && same(it.url, url) } ?: return
        val writer = session ?: return
        if (extension == null) { session?.finish(); session = null; _state.value = running.copy(error = "This station's stream isn't a kind of audio that can be saved."); return }
        if (clock() - running.startedMs >= MAX_MS) { stop(); return }
        try { writer.write(extension, bytes, offset, length) }
        catch (failure: Exception) { session = null; _state.value = running.copy(error = failure.message?.take(160) ?: "Couldn't write the recording."); return }
        _state.value = running.copy(bytes = running.bytes + length, saved = writer.saved)
    }

    @Synchronized private fun title(url: String, title: String) {
        val running = _state.value?.takeIf { same(it.url, url) } ?: return
        if (running.title == title) return
        session?.title(title)
        _state.value = running.copy(title = title, saved = session?.saved ?: running.saved)
        if ((session?.saved ?: 0) > running.saved) _saves.value++
    }

    private fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    /** "mp3", "aac", "ogg" for the stream's type (or its address), null for what is not plain audio (an HLS playlist, a web page). */
    fun extensionOf(contentType: String?, url: String): String? = when (contentType.orEmpty().substringBefore(';').trim().lowercase()) {
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> "mp3"
        "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        // A station that names no type is most often MP3; an HLS playlist is not a stream of audio bytes.
        "", "application/octet-stream" -> PodioDownloads.format(url)?.extension ?: "mp3".takeUnless { url.substringBefore('?').lowercase().endsWith(".m3u8") }
        else -> null
    }

    /** The files of one recording: one file, or one per song when split. */
    private class Session(private val folder: File, private val station: String, private val split: Boolean, private val nowMs: () -> Long) {
        private var out: OutputStream? = null
        private var file: File? = null
        private var written = 0L
        private var current: String? = null
        private var sawTitle = false
        var saved = 0
            private set

        fun write(extension: String, bytes: ByteArray, offset: Int, length: Int) {
            if (out == null) {
                folder.mkdirs()
                val name = PodioRecordings.fileName(station, current.takeIf { split }, nowMs())
                file = PodioRecordings.unique(File(folder, "$name.$extension"))
                out = file!!.outputStream().buffered(64 * 1024)
            }
            out!!.write(bytes, offset, length)
            written += length
        }

        fun title(next: String) {
            if (next == current) return
            val first = !sawTitle
            sawTitle = true
            current = next
            // A new song starts a new file; what came before the first title is kept only when it is long.
            if (split && out != null) close(keep = !first || written >= FIRST_PART_BYTES)
        }

        /** Closes the file; how many files this recording saved. */
        fun finish(): Int { close(keep = true); return saved }

        private fun close(keep: Boolean) {
            runCatching { out?.close() }
            out = null
            val done = file ?: return
            file = null
            if (keep && written >= MIN_BYTES) saved++ else done.delete()
            written = 0
        }
    }

    /** Below this a file is a hiccup, not a recording. */
    private const val MIN_BYTES = 32 * 1024L
    /** The audio before the station's first song title is kept as its own file only when longer than this. */
    private const val FIRST_PART_BYTES = 512 * 1024L
}

/**
 * Takes an ICY stream apart: every [interval] bytes of audio come a length byte and that many 16-byte blocks of metadata
 * ("StreamTitle='…';"). Without an interval it is all audio.
 */
class IcyStreamSplitter(private val interval: Int?) {
    private var untilMeta = interval ?: Int.MAX_VALUE
    private var metaLeft = -1
    private var meta = java.io.ByteArrayOutputStream()

    fun feed(bytes: ByteArray, count: Int, onAudio: (ByteArray, Int, Int) -> Unit, onTitle: (String) -> Unit) = feed(bytes, 0, count, onAudio, onTitle)

    fun feed(bytes: ByteArray, offset: Int, length: Int, onAudio: (ByteArray, Int, Int) -> Unit, onTitle: (String) -> Unit) {
        if (interval == null) { if (length > 0) onAudio(bytes, offset, length); return }
        val count = offset + length
        var at = offset
        while (at < count) {
            when {
                metaLeft > 0 -> {
                    val take = minOf(metaLeft, count - at)
                    meta.write(bytes, at, take); at += take; metaLeft -= take
                    if (metaLeft == 0) { finishMeta(onTitle); metaLeft = -1; untilMeta = interval }
                }
                untilMeta == 0 -> {
                    val blocks = bytes[at].toInt() and 0xFF; at++
                    if (blocks == 0) untilMeta = interval else { metaLeft = blocks * 16; meta.reset() }
                }
                else -> {
                    val take = minOf(untilMeta, count - at)
                    onAudio(bytes, at, take); at += take; untilMeta -= take
                }
            }
        }
    }

    private fun finishMeta(onTitle: (String) -> Unit) {
        PodioCatalogue.streamTitle(meta.toByteArray().toString(Charsets.UTF_8).trimEnd('\u0000'))?.let(onTitle)
        meta.reset()
    }
}

/** The recordings folder and its files. */
object PodioRecordings {
    private val NOT_IN_NAMES = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    /** "Station – Song" when split by song, else "Station 2026-11-23 14.05". */
    fun fileName(station: String, title: String?, nowMs: Long): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm").format(java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()))
        val base = if (title.isNullOrBlank()) "${station.ifBlank { "Radio" }} $stamp" else "${station.ifBlank { "Radio" }} – $title"
        return base.replace(NOT_IN_NAMES, " ").replace(Regex("\\s+"), " ").trim().take(120).trimEnd('.', ' ').ifBlank { "Recording $stamp" }
    }

    /** [file], or "name (2).ext" when it exists already. */
    fun unique(file: File): File {
        if (!file.exists()) return file
        var n = 2
        while (true) { File(file.parentFile, "${file.nameWithoutExtension} ($n).${file.extension}").takeIf { !it.exists() }?.let { return it }; n++ }
    }

    /** The recordings in [folder], the newest first; the station is read from the file's name. */
    fun list(folder: File): List<PodioRecording> = folder.listFiles().orEmpty()
        .filter { it.isFile && it.extension.lowercase() in setOf("mp3", "aac", "ogg", "flac", "m4a") }
        .sortedByDescending { it.lastModified() }
        .map { file ->
            val name = file.nameWithoutExtension
            val station = name.substringBefore(" – ", "").ifBlank { name.replace(Regex(" \\d{4}-\\d{2}-\\d{2} \\d{2}\\.\\d{2}.*$"), "") }
            PodioRecording(file, station, name.substringAfter(" – ", name), file.lastModified(), file.length())
        }

    /** Where the desktop keeps recordings: Music › Cosmotune Radio. */
    fun desktopFolder(): File = File(System.getProperty("user.home"), "Music/Cosmotune Radio")
}
