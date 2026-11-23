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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
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
    /** Approved device-local destination. Null keeps downloads made before folder selection. */
    val directoryPath: String? = null,
    /** One-way digest only: a different freshly resolved address must never append another file's bytes. */
    val sourceFingerprint: String? = null,
    /** A torrent the listener opened, kept in the folder [fileName] names: which of its files, and how it is going. */
    val torrent: TorrentJob? = null,
) {
    enum class Status { DOWNLOADING, DONE, FAILED, PAUSED }

    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
    val target: PlayTarget? get() = film ?: episode ?: item
    /** Seconds left at the speed of the last second, while a torrent downloads; null when it is not moving. */
    val secondsLeft: Long? get() = torrent?.rate?.takeIf { it > 0 && status == Status.DOWNLOADING }?.let { rate -> totalBytes?.let { ((it - bytes).coerceAtLeast(0) + rate - 1) / rate } }
}

/** A torrent download's own part: its info hash, the chosen files (index and path), and the last second's speed, peers and sharing. */
@Serializable
data class TorrentJob(
    val hash: String,
    val indexes: List<Int>,
    val paths: List<String> = emptyList(),
    val trackers: List<String> = emptyList(),
    val rate: Long = 0,
    val peers: Int = 0,
    val uploaded: Long = 0,
    val seeding: Boolean = false,
    /** Set when the listener paused it, so it is not picked up again when Cosmotune starts. */
    val paused: Boolean = false,
)

/** A folder or file of the listener's own being shared as a torrent: its magnet link to send, and how much was sent. */
data class TorrentShare(val id: String, val name: String, val path: String, val magnet: String? = null, val uploaded: Long = 0, val peers: Int = 0,
    val running: Boolean = true, val error: String? = null)

/**
 * Watch's downloads: a direct file (MP4, MKV or WebM from a user's server, an add-on or a free library such as the Internet
 * Archive) fetched in ranges with Video mode's [RangeDownloader] into [directory], and played from there. Live channels, HLS and
 * DASH are not files and are never offered. The list is kept in `downloads.json` beside the files; a download that was still
 * running when the app closed is marked stopped (its address, which may hold a server's key, is never written down).
 * [batches] re-resolves queued episode targets after the host calls [bindRepository]; individual downloads can be restarted
 * from their title/player. Partial bytes resume only for the same one-way address/header fingerprint.
 */
class WatchDownloads(
    val directory: File,
    client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val freeBytes: (File) -> Long = { it.usableSpace },
    destinationOptions: List<File> = emptyList(),
    private val chooseFolder: (suspend () -> File?)? = null,
    /** Whether the device is on mobile data (or another metered network) now: finished torrents are then not shared. */
    private val metered: () -> Boolean = { false },
    /** Picks a file or folder of the listener's own to share as a torrent; null where the device has no such picker. */
    private val chooseShare: (suspend () -> File?)? = null,
) {
    private val downloader = RangeDownloader(client)
    private val index = File(directory, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(WatchDownload.serializer())
    private val jobs = HashMap<String, Job>()
    private val streams = HashMap<String, Stream>()
    @Serializable private data class Folders(val selected: String, val approved: Set<String>)
    private val foldersFile = File(directory, "destinations.json")
    private val initialFolders = runCatching {
        json.decodeFromString(Folders.serializer(), foldersFile.readText().also { require(it.length < 64_000) })
    }.getOrNull()
    private val approved = (initialFolders?.approved.orEmpty() + directory.canonicalPath + destinationOptions.map { it.canonicalPath }).toMutableSet()
    private val destinationState = MutableStateFlow(File(initialFolders?.selected?.takeIf { it in approved } ?: directory.canonicalPath))
    val destination: StateFlow<File> = destinationState.asStateFlow()
    val destinations: List<File> get() = synchronized(this) { approved.map(::File).sortedBy { it.path } }
    val canChooseFolder: Boolean get() = chooseFolder != null
    private val state = MutableStateFlow(load())

    /** Newest first. */
    val items: StateFlow<List<WatchDownload>> = state.asStateFlow()

    /** Owned by this application-scoped download repository, never by a page's composition. */
    val batches: WatchBatchDownloads by lazy { WatchBatchDownloads(this, scope) }
    fun bindRepository(repository: WatchRepository) {
        batches.bind(repository)
        val torrentRepository = repository as? DefaultWatchRepository ?: return
        torrentEngine = torrentRepository.torrents
        torrentSettings = { torrentRepository.torrentSettings.value }
        // Torrents still downloading when Cosmotune closed carry on where they were (their pieces are checked, not fetched again).
        state.value.filter { it.torrent != null && it.status == WatchDownload.Status.PAUSED && it.torrent.paused.not() && it.error == STOPPED_ON_CLOSE }
            .forEach { resumeTorrent(it.id) }
    }

    @Volatile private var torrentEngine: com.cosmotune.watch.torrent.TorrentEngine? = null
    @Volatile private var torrentSettings: () -> com.cosmotune.watch.torrent.TorrentSettings = { com.cosmotune.watch.torrent.TorrentSettings() }
    private val sharesState = MutableStateFlow<List<TorrentShare>>(emptyList())
    /** The listener's own files being shared as torrents in this run. */
    val shares: StateFlow<List<TorrentShare>> = sharesState.asStateFlow()
    private val shareJobs = HashMap<String, Job>()
    val canTorrent: Boolean get() = torrentEngine != null
    val canChooseShare: Boolean get() = chooseShare != null && torrentEngine != null

    /** Whether a finished torrent may be shared now: the listener turned sharing on, and not on mobile data unless they allowed it. */
    private fun seedNow(): Boolean = torrentSettings().let { it.seedAfterDownload && (it.seedOnMobileData || !runCatching(metered).getOrDefault(true)) }

    /**
     * Downloads [indexes] of the torrent [listing] (the listener chose them on its page) into a folder of its own in the chosen
     * destination, with progress, speed and time left. Null when this device has no torrent engine.
     */
    @Synchronized
    fun startTorrent(listing: com.cosmotune.watch.torrent.TorrentListing, indexes: List<Int>, trackers: List<String> = emptyList()): WatchDownload? {
        val engine = torrentEngine ?: return null
        val chosen = listing.files.filter { it.index in indexes }.ifEmpty { listing.videos }
        if (chosen.isEmpty()) return null
        val id = "torrent:${listing.hash}#" + MessageDigest.getInstance("SHA-256").digest(chosen.joinToString(",") { "${it.index}" }.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        current(id)?.let { existing -> if (existing.status != WatchDownload.Status.DONE) resumeTorrent(id); return existing }
        val first = chosen.first()
        val video = chosen.filter { it.video }.sortedWith(compareBy(com.cosmotune.watch.torrent.NaturalOrder) { it.path }).firstOrNull() ?: first
        val target = com.cosmotune.watch.torrent.TorrentItems.target(listing, video)
        val folder = "Torrent " + listing.hash.take(10)
        val d = WatchDownload(
            id = id, key = target.key, title = listing.name.take(200), line = if (chosen.size == 1) first.name.take(200) else "${chosen.size} files",
            item = target, fileName = folder, totalBytes = chosen.sumOf { it.length }, addedAtMs = now(),
            directoryPath = destinationState.value.canonicalPath,
            torrent = TorrentJob(listing.hash, chosen.map { it.index }, chosen.map { it.path }, trackers.take(8)),
        )
        state.update { listOf(d) + it }
        save()
        runTorrent(d, engine)
        return d
    }

    /** Starts a paused or stopped torrent download again, from where its folder is. */
    @Synchronized
    fun resumeTorrent(id: String): Boolean {
        val d = current(id)?.takeIf { it.torrent != null } ?: return false
        val engine = torrentEngine ?: return false
        runTorrent(d.copy(status = if (d.status == WatchDownload.Status.DONE) WatchDownload.Status.DONE else WatchDownload.Status.DOWNLOADING, error = null,
            torrent = d.torrent?.copy(paused = false)), engine, seedOnly = d.status == WatchDownload.Status.DONE)
        return true
    }

    /** Shares a finished torrent download again (from Downloads), for the time chosen in settings. */
    fun shareAgain(id: String): Boolean = current(id)?.takeIf { it.status == WatchDownload.Status.DONE && it.torrent != null }?.let { resumeTorrent(id) } ?: false

    /** Stops sharing a finished torrent; the files stay. */
    suspend fun stopSharing(id: String) {
        val job = synchronized(jobs) { jobs[id] }
        job?.cancelAndJoin()
        synchronized(this) { current(id)?.let { d -> put(d.copy(torrent = d.torrent?.copy(seeding = false, rate = 0, peers = 0))) } }
    }

    private fun runTorrent(start: WatchDownload, engine: com.cosmotune.watch.torrent.TorrentEngine, seedOnly: Boolean = false) {
        val id = start.id
        val job0 = start.torrent ?: return
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            put(start)
            val seed = seedOnly || seedNow()
            val minutes = torrentSettings().seedMinutes
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val folder = fileOf(start)
                    check(folder.isDirectory || folder.mkdirs()) { "The download folder is unavailable. Choose a writable folder and retry." }
                    ensureSpace(folder, start.totalBytes, start.bytes)
                    var failure: String? = null
                    var completed = false
                    engine.download(job0.hash, job0.trackers, folder, job0.indexes, seed, minutes).collect { event ->
                        event.error?.let { failure = it }
                        if (event.complete) completed = true
                        synchronized(this@WatchDownloads) {
                            val d = current(id) ?: return@collect
                            val t = d.torrent ?: return@collect
                            val done = completed || d.status == WatchDownload.Status.DONE
                            val next = d.copy(
                                bytes = if (event.total > 0) event.done else d.bytes, totalBytes = event.total.takeIf { it > 0 } ?: d.totalBytes,
                                status = if (done) WatchDownload.Status.DONE else d.status,
                                torrent = t.copy(rate = event.rateBytesPerSecond, peers = event.peers, uploaded = maxOf(t.uploaded, event.uploaded),
                                    seeding = done && seed))
                            if (next.status != d.status || event.complete) put(next) else state.update { list -> list.map { if (it.id == id) next else it } }
                        }
                    }
                    synchronized(this@WatchDownloads) {
                        val d = current(id) ?: return@synchronized
                        when {
                            failure != null && !completed && d.status != WatchDownload.Status.DONE ->
                                put(d.copy(status = WatchDownload.Status.FAILED, error = failure?.take(200), torrent = d.torrent?.copy(rate = 0, peers = 0)))
                            else -> put(d.copy(status = if (completed || d.status == WatchDownload.Status.DONE) WatchDownload.Status.DONE else WatchDownload.Status.FAILED,
                                error = if (completed || d.status == WatchDownload.Status.DONE) null else "The torrent engine stopped. Resume to carry on.",
                                torrent = d.torrent?.copy(rate = 0, peers = 0, seeding = false)))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(id, e, coroutineContext[Job])
                } finally {
                    synchronized(jobs) { if (jobs[id] === coroutineContext[Job]) jobs.remove(id) }
                }
            }
            jobs[id] = job
            job.start()
        }
    }

    /** The videos of a finished torrent download, in name order, as files on this device. */
    fun torrentVideos(d: WatchDownload): List<File> {
        val t = d.torrent ?: return emptyList()
        val root = fileOf(d).canonicalFile
        return t.paths.filter { p -> VIDEO.any { p.lowercase().endsWith(it) } }.sortedWith(com.cosmotune.watch.torrent.NaturalOrder)
            .map { File(root, it).canonicalFile }.filter { it.path.startsWith(root.path) && it.isFile }
    }

    /** A video of a finished torrent download as a copy the Watch player plays. */
    fun streamOf(d: WatchDownload, video: File): Stream = Stream(
        StreamSource("downloads", "Downloads", "ON DEVICE"), "On this device", video.name, video.toURI().toString(), StreamProtocol.LOCAL_FILE,
        containerOf(Stream(StreamSource("", "", ""), "", url = video.name, protocol = StreamProtocol.LOCAL_FILE, filename = video.name)) ?: Container.UNKNOWN,
        filename = video.name, sizeBytes = video.length(),
    )

    /** Picks a file or folder with the device's picker and shares it ([share]). */
    suspend fun chooseAndShare(): TorrentShare? = chooseShare?.invoke()?.let { share(it) }

    /**
     * Makes a torrent of [path] (a file or a folder of the listener's own) and shares it while Cosmotune runs, for the time chosen in
     * settings (or until stopped); its magnet link arrives in [shares] once the files are read.
     */
    fun share(path: File): TorrentShare? {
        val engine = torrentEngine ?: return null
        val share = TorrentShare("share:" + MessageDigest.getInstance("SHA-256").digest(path.canonicalPath.toByteArray()).take(6).joinToString("") { "%02x".format(it) },
            path.name.ifBlank { path.path }.take(200), path.canonicalPath)
        synchronized(shareJobs) {
            if (shareJobs[share.id]?.isActive == true) return sharesState.value.firstOrNull { it.id == share.id }
            sharesState.update { list -> listOf(share) + list.filter { it.id != share.id } }
            shareJobs[share.id] = scope.launch {
                try {
                    engine.share(path, torrentSettings().seedMinutes).collect { event ->
                        sharesState.update { list -> list.map { s -> if (s.id != share.id) s else s.copy(
                            magnet = event.magnet ?: s.magnet, uploaded = maxOf(s.uploaded, event.uploaded), peers = event.peers, error = event.error ?: s.error) } }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sharesState.update { list -> list.map { s -> if (s.id == share.id) s.copy(error = e.message?.take(200) ?: "Sharing stopped.") else s } }
                } finally {
                    sharesState.update { list -> list.map { s -> if (s.id == share.id) s.copy(running = false, peers = 0) else s } }
                }
            }
        }
        return share
    }

    /** Stops sharing one of the listener's own files and takes it off the list. */
    fun stopShare(id: String) {
        synchronized(shareJobs) { shareJobs.remove(id)?.cancel() }
        sharesState.update { list -> list.filter { it.id != id } }
    }

    fun fileOf(d: WatchDownload): File {
        require(safeFileName(d.fileName)) { "Invalid download filename." }
        val root = d.directoryPath ?: directory.canonicalPath
        require(synchronized(this) { root in approved }) { "Download folder is not approved on this device." }
        return File(root, d.fileName)
    }

    /** Selection affects future downloads; existing files keep their original destination. */
    @Synchronized fun selectDestination(folder: File) {
        val path = folder.canonicalPath
        require(path in approved) { "Choose a folder with the system picker first." }
        check(folder.isDirectory || folder.mkdirs()) { "The download folder is unavailable." }
        check(folder.canWrite()) { "The download folder is not writable." }
        saveFolders(path)
        destinationState.value = File(path)
    }

    suspend fun browseDestination() {
        val folder = chooseFolder?.invoke() ?: return
        synchronized(this) { approved += folder.canonicalPath }
        selectDestination(folder)
    }

    private fun saveFolders(path: String) {
        directory.mkdirs()
        val tmp = File(directory, "destinations.json.tmp")
        tmp.writeText(json.encodeToString(Folders.serializer(), Folders(path, approved.toSet())))
        Files.move(tmp.toPath(), foldersFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

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
    fun start(target: PlayTarget, summary: TitleSummary?, stream: Stream, line: String? = null, destinationPath: String? = null): WatchDownload? {
        val container = containerOf(stream) ?: return null
        if (!canDownload(stream) || target is PlayTarget.Live) return null
        val id = idOf(target.key, stream)
        streams[id] = stream
        val existing = state.value.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.status == WatchDownload.Status.FAILED || existing.status == WatchDownload.Status.PAUSED ||
                (existing.status == WatchDownload.Status.DONE && !fileOf(existing).isFile)) {
                val changed = existing.sourceFingerprint != fingerprint(stream)
                if (changed) partOf(existing).delete()
                run(existing.copy(status = WatchDownload.Status.DOWNLOADING, error = null,
                    bytes = if (changed) 0 else existing.bytes,
                    totalBytes = if (changed) stream.sizeBytes?.takeIf { it > 0 } else existing.totalBytes,
                    sourceFingerprint = fingerprint(stream)), stream)
            }
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
            directoryPath = destinationPath?.also { require(it in approved) { "Download folder is not approved on this device." } } ?: destinationState.value.canonicalPath,
            sourceFingerprint = fingerprint(stream),
        )
        state.update { listOf(d) + it }
        save()
        run(d, stream, fresh = true)
        return d
    }

    /** Starts a stopped download again; false when its address is not known in this session (open its page and download again). */
    @Synchronized
    fun retry(id: String): Boolean {
        val d = state.value.firstOrNull { it.id == id } ?: return false
        if (d.torrent != null) return resumeTorrent(id)
        val stream = streams[id] ?: return false
        run(d.copy(status = WatchDownload.Status.DOWNLOADING, error = null), stream)
        return true
    }

    /** Cancellation joins the writer before publishing Paused, so Resume can never race an old socket. */
    suspend fun pause(id: String) {
        val job = synchronized(jobs) { jobs[id] }
        job?.cancelAndJoin()
        synchronized(this) {
            current(id)?.takeIf { it.status == WatchDownload.Status.DOWNLOADING }?.let {
                put(if (it.torrent != null) it.copy(status = WatchDownload.Status.PAUSED, error = null, torrent = it.torrent.copy(paused = true, rate = 0, peers = 0))
                    else it.copy(status = WatchDownload.Status.PAUSED, bytes = partOf(it).length(), error = null))
            }
        }
    }

    /** Stops a download and forgets it, or deletes a finished one and its file. */
    @Synchronized
    fun remove(id: String) {
        val job = synchronized(jobs) { jobs[id] }
        job?.cancel()
        val d = state.value.firstOrNull { it.id == id } ?: return
        if (d.torrent != null) runCatching { fileOf(d).deleteRecursively() } else runCatching { fileOf(d).delete() }
        runCatching { partOf(d).delete() }
        streams.remove(id)
        state.update { list -> list.filter { it.id != id } }
        save()
        if (job != null) scope.launch {
            job.join()
            // The network writer is now closed; no deleted partial can be recreated by that old request.
            synchronized(this@WatchDownloads) { if (current(id) == null) runCatching { partOf(d).delete() } }
        }
    }

    /** A finished download as a copy the Watch player plays (a file on this device). */
    fun streamOf(d: WatchDownload): Stream = Stream(
        StreamSource("downloads", "Downloads", "ON DEVICE"), "On this device", d.line, fileOf(d).toURI().toString(), StreamProtocol.LOCAL_FILE,
        d.container, filename = d.fileName, sizeBytes = d.totalBytes ?: fileOf(d).length(), durationMs = d.durationMs,
    )

    private fun run(start: WatchDownload, stream: Stream, fresh: Boolean = false) {
        val id = start.id
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            val previous = jobs[id]
            put(start)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.join()
                    val part = partOf(start)
                    if (fresh) part.delete()
                    var last = 0L
                    val folder = part.parentFile
                    check(folder.isDirectory || folder.mkdirs()) { "The download folder is unavailable. Choose a writable folder and retry." }
                    ensureSpace(folder, start.totalBytes, part.length())
                    val length = downloader.download(stream.url, stream.headers, part, start.totalBytes) { written, total ->
                        val t = now()
                        if (t - last >= 500) {
                            last = t
                            ensureSpace(folder, total ?: start.totalBytes, written)
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
            jobs[id] = job
            job.start()
        }
    }

    private fun ensureSpace(folder: File, total: Long?, written: Long) {
        val available = freeBytes(folder)
        val remaining = total?.let { (it - written).coerceAtLeast(0) } ?: 0L
        if (available < 50L * 1024 * 1024 || remaining > (available - 50L * 1024 * 1024).coerceAtLeast(0)) {
            throw java.io.IOException("Not enough free space in the download folder. Free some storage and retry; the partial download is kept.")
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

    private fun partOf(d: WatchDownload) = fileOf(d).let { File(it.parentFile, it.name + ".part") }

    private fun load(): List<WatchDownload> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        json.decodeFromString(serializer, index.readText().also { require(it.length < 8 * 1024 * 1024) })
            .filter { safeFileName(it.fileName) && (it.directoryPath == null || it.directoryPath in approved) }.map {
            when {
                it.torrent != null && it.status == WatchDownload.Status.DOWNLOADING -> it.copy(status = WatchDownload.Status.PAUSED, error = STOPPED_ON_CLOSE,
                    torrent = it.torrent.copy(rate = 0, peers = 0, seeding = false))
                it.torrent != null -> it.copy(torrent = it.torrent.copy(rate = 0, peers = 0, seeding = false))
                it.status == WatchDownload.Status.DOWNLOADING -> it.copy(status = WatchDownload.Status.FAILED, error = "Stopped when Cosmotune closed.")
                else -> it
            }
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
        private const val STOPPED_ON_CLOSE = "Stopped when Cosmotune closed."
        private val VIDEO = listOf(".mkv", ".mp4", ".m4v", ".webm", ".mov", ".avi", ".ts", ".m2ts", ".mpg", ".mpeg")
        private fun safeFileName(name: String) = name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }
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

        private fun fingerprint(stream: Stream): String = MessageDigest.getInstance("SHA-256")
            .digest((stream.url + "\n" + stream.headers.toSortedMap().entries.joinToString("\n")).toByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun fileNameOf(title: String, target: PlayTarget, id: String, container: Container): String {
            val base = title.replace(Regex("[^\\p{L}\\p{N} ._()-]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "Watch" }
            val episode = (target as? PlayTarget.EpisodeOf)?.let { " S%02dE%02d".format(it.season, it.episode) }.orEmpty()
            val year = (target as? PlayTarget.Film)?.year?.let { " ($it)" }.orEmpty()
            val ext = when (container) { Container.MKV -> "mkv"; Container.WEBM -> "webm"; else -> "mp4" }
            val identity = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            return "$base$year$episode $identity.$ext"
        }
    }
}
