package com.cosmotune.watch

import com.cosmotune.video.downloads.RangeDownloader
import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.WatchKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** A film or an episode kept on this device, or on its way. Its address is never stored, only the file it became. */
@Serializable
data class WatchDownload(
    val id: String,
    val key: WatchKey,
    val title: String,
    /** "S2 E4 · Low Water" for an episode; the copy's line ("Internet Archive · 1080p") for a film. */
    val line: String? = null,
    val summary: TitleSummary? = null,
    val film: PlayTarget.Film? = null,
    val episode: PlayTarget.EpisodeOf? = null,
    val item: PlayTarget.Item? = null,
    val fileName: String,
    val container: Container = Container.UNKNOWN,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val durationMs: Long? = null,
    val status: Status = Status.DOWNLOADING,
    val error: String? = null,
    val addedAtMs: Long = 0,
) {
    enum class Status { DOWNLOADING, DONE, FAILED }

    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
    val target: PlayTarget? get() = film ?: episode ?: item
}

/**
 * Watch's downloads: a direct file (MP4, MKV or WebM from a user's server, an add-on or a free library such as the Internet
 * Archive) fetched in ranges with Video mode's [RangeDownloader] into [directory], and played from there. Live channels, HLS and
 * DASH are not files and are never offered. The list is kept in `downloads.json` beside the files; a download that was still
 * running when the app closed is marked stopped (its address, which may hold a server's key, is never written down), and is
 * started again from the title's page.
 */
class WatchDownloads(
    val directory: File,
    client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val downloader = RangeDownloader(client)
    private val index = File(directory, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(WatchDownload.serializer())
    private val jobs = HashMap<String, Job>()
    private val streams = HashMap<String, Stream>()
    private val state = MutableStateFlow(load())

    /** Newest first. */
    val items: StateFlow<List<WatchDownload>> = state.asStateFlow()

    fun fileOf(d: WatchDownload): File = File(directory, d.fileName)

    /** Whether [stream] is a direct file that can be kept. */
    fun canDownload(stream: Stream): Boolean = whyNot(stream) == null

    /** Why [stream] cannot be downloaded, in the words the screens show; null when it can. */
    fun whyNot(stream: Stream): String? = when {
        stream.isLive -> "Live channels can't be downloaded."
        stream.protocol == StreamProtocol.HLS || stream.protocol == StreamProtocol.DASH -> "This copy is a stream (HLS or DASH), not a file, so it can't be downloaded."
        stream.protocol == StreamProtocol.YOUTUBE -> "Free on YouTube titles are downloaded in Video mode."
        stream.protocol == StreamProtocol.EXTERNAL -> "This copy opens outside the app."
        stream.protocol == StreamProtocol.LOCAL_FILE -> "This copy is already on this device."
        !stream.url.startsWith("https://") && !stream.url.startsWith("http://") -> "This copy can't be downloaded."
        containerOf(stream) == null -> "Only MP4, MKV and WebM files can be downloaded."
        else -> null
    }

    /** The download of this copy of [target], if there is one. */
    fun find(target: PlayTarget, stream: Stream): WatchDownload? = state.value.firstOrNull { it.id == idOf(target.key, stream) }

    /** Downloads of [key] that are done. */
    fun doneFor(key: WatchKey): WatchDownload? = state.value.firstOrNull { it.key == key && it.status == WatchDownload.Status.DONE && fileOf(it).isFile }

    /**
     * Starts keeping [stream] of [target]; an existing download of the same copy is returned as it is (or started again when it
     * had stopped). Null when the copy is not a direct file.
     */
    @Synchronized
    fun start(target: PlayTarget, summary: TitleSummary?, stream: Stream, line: String? = null): WatchDownload? {
        val container = containerOf(stream) ?: return null
        if (!canDownload(stream) || target is PlayTarget.Live) return null
        val id = idOf(target.key, stream)
        streams[id] = stream
        val existing = state.value.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.status == WatchDownload.Status.FAILED) run(existing.copy(status = WatchDownload.Status.DOWNLOADING, error = null), stream)
            return existing
        }
        val title = when (target) {
            is PlayTarget.EpisodeOf -> target.seriesName
            is PlayTarget.Film -> target.name
            is PlayTarget.Item -> target.name
            is PlayTarget.Live -> target.channel.name
        }
        val d = WatchDownload(
            id = id, key = target.key, title = title.take(200), line = line?.take(200), summary = summary,
            film = target as? PlayTarget.Film, episode = target as? PlayTarget.EpisodeOf, item = target as? PlayTarget.Item,
            fileName = fileNameOf(title, target, id, container), container = container, totalBytes = stream.sizeBytes?.takeIf { it > 0 },
            durationMs = stream.durationMs, addedAtMs = now(),
        )
        state.update { listOf(d) + it }
        save()
        run(d, stream)
        return d
    }

    /** Starts a stopped download again; false when its address is not known in this session (open its page and download again). */
    @Synchronized
    fun retry(id: String): Boolean {
        val d = state.value.firstOrNull { it.id == id } ?: return false
        val stream = streams[id] ?: return false
        run(d.copy(status = WatchDownload.Status.DOWNLOADING, error = null), stream)
        return true
    }

    /** Stops a download and forgets it, or deletes a finished one and its file. */
    @Synchronized
    fun remove(id: String) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        val d = state.value.firstOrNull { it.id == id } ?: return
        runCatching { fileOf(d).delete() }
        runCatching { partOf(d).delete() }
        streams.remove(id)
        state.update { list -> list.filter { it.id != id } }
        save()
    }

    /** A finished download as a copy the Watch player plays (a file on this device). */
    fun streamOf(d: WatchDownload): Stream = Stream(
        StreamSource("downloads", "Downloads", "ON DEVICE"), "On this device", d.line, fileOf(d).toURI().toString(), StreamProtocol.LOCAL_FILE,
        d.container, filename = d.fileName, sizeBytes = d.totalBytes ?: fileOf(d).length(), durationMs = d.durationMs,
    )

    private fun run(start: WatchDownload, stream: Stream) {
        val id = start.id
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            put(start)
            jobs[id] = scope.launch {
                val part = partOf(start)
                var last = 0L
                try {
                    directory.mkdirs()
                    val length = downloader.download(stream.url, stream.headers, part, start.totalBytes) { written, total ->
                        val t = now()
                        if (t - last >= 500) {
                            last = t
                            state.update { list -> list.map { if (it.id == id) it.copy(bytes = written, totalBytes = total ?: it.totalBytes) else it } }
                        }
                    }
                    finish(start, part, length, coroutineContext[Job])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(id, e, coroutineContext[Job])
                } finally {
                    synchronized(jobs) { if (jobs[id] === coroutineContext[Job]) jobs.remove(id) }
                }
            }
        }
    }

    private fun current(id: String) = state.value.firstOrNull { it.id == id }

    @Synchronized
    private fun finish(start: WatchDownload, part: File, length: Long, job: Job?) {
        if (!isCurrentJob(start.id, job)) return
        val current = current(start.id) ?: return
        val file = fileOf(start)
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
        put(current.copy(status = WatchDownload.Status.DONE, bytes = length, totalBytes = length, error = null))
    }

    @Synchronized
    private fun fail(id: String, failure: Exception, job: Job?) {
        if (!isCurrentJob(id, job)) return
        current(id)?.let { put(it.copy(status = WatchDownload.Status.FAILED, error = failure.message?.take(200) ?: "The download stopped.")) }
    }

    private fun isCurrentJob(id: String, job: Job?) = job != null && synchronized(jobs) { jobs[id] === job }

    @Synchronized
    private fun put(d: WatchDownload) {
        fun replace(list: List<WatchDownload>) =
            if (list.any { it.id == d.id }) list.map { if (it.id == d.id) d else it } else listOf(d) + list
        val saved = save(replace(state.value))
        check(d.status != WatchDownload.Status.DONE || saved) { "Could not save the completed download index." }
        state.update(::replace)
    }

    private fun partOf(d: WatchDownload) = File(directory, d.fileName + ".part")

    private fun load(): List<WatchDownload> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        json.decodeFromString(serializer, index.readText()).map {
            if (it.status == WatchDownload.Status.DOWNLOADING) it.copy(status = WatchDownload.Status.FAILED, error = "Stopped when Cosmotune closed.") else it
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(items: List<WatchDownload> = state.value): Boolean =
        runCatching {
            directory.mkdirs()
            val tmp = File(directory, "downloads.json.tmp")
            tmp.writeText(json.encodeToString(serializer, items))
            try {
                Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess

    companion object {
        /** The container of a direct file, from the plugin's guess or the address; null for anything that is not MP4, MKV or WebM. */
        fun containerOf(stream: Stream): Container? {
            val c = stream.container.takeIf { it != Container.UNKNOWN } ?: run {
                val name = (stream.filename ?: stream.url.substringBefore('?').substringAfterLast('/')).lowercase()
                when {
                    name.endsWith(".mp4") || name.endsWith(".m4v") -> Container.MP4
                    name.endsWith(".mkv") -> Container.MKV
                    name.endsWith(".webm") -> Container.WEBM
                    else -> Container.UNKNOWN
                }
            }
            return c.takeIf { it == Container.MP4 || it == Container.MKV || it == Container.WEBM }
        }

        private fun idOf(key: WatchKey, stream: Stream): String {
            val release = listOfNotNull(stream.source.pluginId, stream.filename, stream.sizeBytes?.toString(), stream.label).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(release.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
            return "${key.value}#$digest"
        }

        private fun fileNameOf(title: String, target: PlayTarget, id: String, container: Container): String {
            val base = title.replace(Regex("[^\\p{L}\\p{N} ._()-]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "Watch" }
            val episode = (target as? PlayTarget.EpisodeOf)?.let { " S%02dE%02d".format(it.season, it.episode) }.orEmpty()
            val year = (target as? PlayTarget.Film)?.year?.let { " ($it)" }.orEmpty()
            val ext = when (container) { Container.MKV -> "mkv"; Container.WEBM -> "webm"; else -> "mp4" }
            return "$base$year$episode ${id.substringAfter('#').take(6)}.$ext"
        }
    }
}
