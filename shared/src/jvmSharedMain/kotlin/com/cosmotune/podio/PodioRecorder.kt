package com.cosmotune.podio

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/** A recording kept on this device: the file, the station and, when the recording was split by song, the song. */
data class PodioRecording(val file: File, val station: String, val title: String, val savedMs: Long, val bytes: Long) {
    /** The recording as the player takes it, from its file. */
    fun item(): PodioItem = PodioItem("podio:episode:recording-" + PodioCatalogue.identity(file.absolutePath), title.ifBlank { file.nameWithoutExtension }, station,
        file.toURI().toString(), downloadedUri = file.toURI().toString(), downloadedPath = file.absolutePath)
}

/**
 * Records a station to files (RadioDroid's and Shortwave's recorder, written anew): it opens the station's stream itself, keeps the
 * audio and drops the song-title blocks between it, and, when [split] is on, starts a new file at each new song the station names
 * (Shortwave's track saving), so each song lands in its own file. A recording stops by itself after [MAX_MS] (three hours).
 */
object PodioRecorder {
    const val MAX_MS = 3 * 60 * 60 * 1000L

    data class State(val stationId: String, val station: String, val startedMs: Long, val title: String? = null, val bytes: Long = 0, val saved: Int = 0,
        val split: Boolean = false, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State?>(null)
    /** The recording running now, or null. */
    val state: StateFlow<State?> = _state.asStateFlow()
    private val _done = MutableStateFlow(0)
    /** Goes up each time a file is saved, so lists of recordings know to look again. */
    val saves: StateFlow<Int> = _done.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Synchronized fun start(station: RadioStation, folder: File, split: Boolean, nowMs: () -> Long = System::currentTimeMillis) {
        stop()
        val address = PodioCatalogue.httpUrl(station.url) ?: run { _state.value = State(station.id, station.name, nowMs(), error = "This station's address can't be recorded."); return }
        _state.value = State(station.id, station.name, nowMs(), split = split)
        job = scope.launch {
            try {
                val call = client.newCall(Request.Builder().url(address).header("Icy-MetaData", "1")
                    .header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
                val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { reply ->
                        check(reply.isSuccessful) { "The station answered HTTP ${reply.code}." }
                        val extension = extensionOf(reply.header("Content-Type"), address)
                            ?: error("This station's stream isn't a kind of audio that can be saved.")
                        val interval = reply.header("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_536 }
                        val input = requireNotNull(reply.body).byteStream()
                        record(input, interval, folder, station.name, extension, split, nowMs)
                    }
                } finally { cancel?.dispose() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.value = _state.value?.copy(error = failure.message?.take(160) ?: "The recording stopped.") ; return@launch }
            _state.value = null
        }
    }

    /** Stops the recording; what was recorded so far is kept. */
    @Synchronized fun stop() {
        job?.cancel(); job = null
        _state.value = null
    }

    /** Clears a failed recording's message. */
    fun dismissError() { if (_state.value?.error != null) _state.value = null }

    private suspend fun record(input: InputStream, interval: Int?, folder: File, station: String, extension: String, split: Boolean, nowMs: () -> Long) {
        folder.mkdirs()
        val started = nowMs()
        var out: OutputStream? = null
        var file: File? = null
        var written = 0L
        var total = 0L
        var title: String? = null
        fun close(keep: Boolean) {
            out?.close(); out = null
            val done = file ?: return
            file = null
            if (keep && written >= MIN_BYTES) { _state.value = _state.value?.let { it.copy(saved = it.saved + 1) }; _done.value++ } else done.delete()
            written = 0
        }
        fun open() {
            val name = PodioRecordings.fileName(station, title.takeIf { split }, nowMs())
            file = PodioRecordings.unique(File(folder, "$name.$extension"))
            out = file!!.outputStream().buffered(64 * 1024)
        }
        val splitter = IcyStreamSplitter(interval)
        val buffer = ByteArray(16 * 1024)
        try {
            open()
            while (currentCoroutineContext().isActive && nowMs() - started < MAX_MS) {
                val count = input.read(buffer)
                if (count < 0) break
                splitter.feed(buffer, count, onAudio = { bytes, offset, length ->
                    out?.write(bytes, offset, length); written += length; total += length
                }, onTitle = { next ->
                    if (next != title) {
                        val first = title == null
                        title = next
                        // A new song starts a new file; the part before the first title is kept with the first song only when it is long.
                        if (split) { close(keep = !first || written >= FIRST_PART_BYTES); open() }
                        _state.value = _state.value?.copy(title = next)
                    }
                })
                _state.value = _state.value?.copy(bytes = total)
            }
        } finally { withContext(NonCancellable) { close(keep = true) } }
    }

    /** "mp3", "aac", "ogg" for the stream's type (or its address), null for what is not plain audio (an HLS playlist, a web page). */
    fun extensionOf(contentType: String?, url: String): String? = when (contentType.orEmpty().substringBefore(';').trim().lowercase()) {
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> "mp3"
        "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "", "application/octet-stream" -> PodioDownloads.format(url)?.extension
        else -> null
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

    fun feed(bytes: ByteArray, count: Int, onAudio: (ByteArray, Int, Int) -> Unit, onTitle: (String) -> Unit) {
        if (interval == null) { if (count > 0) onAudio(bytes, 0, count); return }
        var at = 0
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
