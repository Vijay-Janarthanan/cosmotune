package com.cosmotune.music.wear.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An episode kept on the watch, to play with no phone and no connection. */
data class WatchEpisode(val id: String, val title: String, val show: String, val file: File)

/**
 * Podcast episodes saved on the watch itself (Pocket Casts' watch downloads): fetched over the watch's own connection into its
 * private storage, played from there. At most [MAX] are kept, and a save stops past [MAX_BYTES] so one long episode cannot fill
 * the watch.
 */
object WatchPodioDownloads {
    const val MAX = 12
    private const val MAX_BYTES = 300L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _saved = MutableStateFlow<List<WatchEpisode>>(emptyList())
    val saved: StateFlow<List<WatchEpisode>> = _saved.asStateFlow()

    /** How far each running save has got, 0 to 1 (or -1 while the size is unknown), by episode id. */
    private val _running = MutableStateFlow<Map<String, Float>>(emptyMap())
    val running: StateFlow<Map<String, Float>> = _running.asStateFlow()

    private val _failed = MutableStateFlow<String?>(null)
    val failed: StateFlow<String?> = _failed.asStateFlow()

    private fun folder(context: Context) = File(context.filesDir, "podio-downloads").apply { mkdirs() }
    private fun index(context: Context) = File(folder(context), "index.tsv")

    fun load(context: Context) {
        val dir = folder(context)
        _saved.value = runCatching { index(context).readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            WatchEpisode(parts[0], parts[1], parts[2], File(dir, parts[3])).takeIf { it.file.isFile }
        }
    }

    fun save(context: Context, id: String, title: String, show: String, url: String) {
        val app = context.applicationContext
        if (_saved.value.any { it.id == id } || jobs.containsKey(id)) return
        _failed.value = null
        _running.value = _running.value + (id to -1f)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val name = "ep-" + id.hashCode().toUInt().toString(16) + ".audio"
            val part = File(folder(app), "$name.part")
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Cosmotune-Podio/Wear")
                }
                try {
                    check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong
                    check(total <= MAX_BYTES) { "too large" }
                    var done = 0L
                    connection.inputStream.use { input -> part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n); done += n
                            check(done <= MAX_BYTES) { "too large" }
                            _running.value = _running.value + (id to if (total > 0) done.toFloat() / total else -1f)
                        }
                    } }
                } finally { connection.disconnect() }
                val file = File(folder(app), name)
                check(part.renameTo(file)) { "could not keep the file" }
                synchronized(this@WatchPodioDownloads) {
                    val next = (listOf(WatchEpisode(id, title, show, file)) + _saved.value.filterNot { it.id == id })
                    next.drop(MAX).forEach { it.file.delete() }
                    write(app, next.take(MAX))
                }
            } catch (failure: Exception) {
                part.delete()
                if (failure !is kotlinx.coroutines.CancellationException) _failed.value = "Couldn't save $title to the watch."
            } finally {
                _running.value = _running.value - id
                jobs.remove(id)
            }
        }
        jobs[id] = job
        job.start()
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    @Synchronized fun remove(context: Context, id: String) {
        val app = context.applicationContext
        _saved.value.firstOrNull { it.id == id }?.file?.delete()
        write(app, _saved.value.filterNot { it.id == id })
    }

    private fun write(context: Context, list: List<WatchEpisode>) {
        index(context).writeText(list.joinToString("\n") { listOf(it.id, it.title.clean(), it.show.clean(), it.file.name).joinToString("\t") })
        _saved.value = list
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').take(200)
}
