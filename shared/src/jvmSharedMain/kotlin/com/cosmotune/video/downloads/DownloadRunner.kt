package com.cosmotune.video.downloads

import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * What a platform gives the download engine (plan task Y-24, steps 5 and 6): what it decodes, where the working files, the list
 * and the finished files go, how two files are joined without re-encoding, the two holds (mobile data, low battery) with a flow
 * that ticks when either may have changed, the free space, and the app's language.
 */
interface DownloadPlatform {
    /** What the device plays back (the sheet's ladder and Auto). */
    val caps: DeviceCaps

    /** Where the video and audio are fetched to before they are joined (`<cache>/.../video-downloads/`). */
    val cacheDir: File

    /** Where `downloads.json` lives (spec 8.1: Android `filesDir/video/`, desktop `~/.local/share/cosmotune/video/`). */
    val stateDir: File

    /** Where finished downloads of [kind] go, under [settings] (the desktop's folder setting). */
    fun outputDir(kind: DownloadKind, settings: VideoSettings): File

    /** Joins [video] and [audio] into [output] as [container] without re-encoding; false keeps the two files (spec 2.5). */
    suspend fun join(video: File, audio: File, output: File, container: DownloadContainer): Boolean

    /** Whether the network is metered now (mobile data); the desktop never is. */
    fun isMetered(): Boolean

    /** Whether the battery is low now (below 15 %, not charging); the desktop never is. */
    fun isBatteryLow(): Boolean

    /** Ticks when the network or the battery may have changed (the engine then measures again). */
    val changes: Flow<Unit>

    /** Bytes free where downloads go. */
    fun freeBytes(settings: VideoSettings): Long

    /** The app's language tag ("en"), for the subtitle rules (spec 5.16). */
    val appLanguage: String

    /** The name of language [tag] in the app's language ("English"). */
    fun languageName(tag: String): String
}

/** [DownloadsFile.Io] on a real folder: a write is flushed to the device; the rename is atomic where the file system allows. */
class FileDownloadsIo(private val dir: File) : DownloadsFile.Io {
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        FileOutputStream(File(dir, name)).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    override fun rename(from: String, to: String) {
        val source = File(dir, from).toPath()
        val target = File(dir, to).toPath()
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * The download engine both platforms run (plan task Y-24): the queue ([DownloadQueueState]) kept in `downloads.json`, one
 * download at a time. For each entry it asks the source for the video's details, takes the concrete streams (the same itags
 * again when part of the bytes are already on disk), fetches the video and audio in 2 MiB ranges into [DownloadPlatform.cacheDir]
 * ([RangeDownloader]), joins them ([DownloadPlatform.join]; when that fails both files are kept and the entry is done with the
 * pair), moves the result into [DownloadPlatform.outputDir], and writes the subtitles ([SubtitleFetcher]) and the sidecar
 * ([DownloadSidecar], with the SponsorBlock segments when [segments] answers) beside it. The holds follow the settings and the
 * platform, measured again at every start. `AndroidDownloadController` and `DesktopDownloadController` are this engine with
 * their platform.
 */
class DownloadRunner(
    private val source: VideoSource,
    private val settings: VideoSettingsRepository,
    private val scope: CoroutineScope,
    private val platform: DownloadPlatform,
    private val segments: SegmentProvider? = null,
    private val downloader: RangeDownloader = RangeDownloader(),
    private val subtitles: SubtitleFetcher = SubtitleFetcher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadController {
    private val lock = Any()
    private val store = FileDownloadsIo(platform.stateDir)
    private var state: DownloadQueueState = DownloadsFile.load(store)
    private val entryFlow = MutableStateFlow(state.entries)
    private val batchFlow = MutableStateFlow(state.batchStates())
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var jobId: String? = null
    private var lastJob: Job? = null

    override val downloads: StateFlow<List<DownloadEntry>> = entryFlow.asStateFlow()
    override val batches: StateFlow<List<BatchState>> = batchFlow.asStateFlow()

    init {
        synchronized(lock) {
            state = state.reduce(QueueEvent.Conditions(conditionsOf(settings.settings.value))).state
            publish()
        }
        scope.launch(io) { for (unused in saves) save() }
        scope.launch(io) {
            combine(settings.settings, platform.changes.onStart { emit(Unit) }) { s, _ -> s }
                .collect { s -> apply(QueueEvent.Conditions(conditionsOf(s))) }
        }
        pump()
    }

    override suspend fun options(item: VideoItem): DownloadOptions {
        if (item.isLive || item.isUpcoming) throw VideoSourceError.NotAvailable("Live and upcoming videos cannot be downloaded")
        return DownloadPlanner.options(source.details(item.id), platform.caps, platform::languageName)
    }

    override suspend fun batchOptions(items: List<VideoItem>): DownloadOptions =
        DownloadPlanner.batchOptions(items, platform.caps, platform.appLanguage, platform.languageName(platform.appLanguage))

    override fun enqueue(item: VideoItem, request: DownloadRequest) = enqueue(listOf(item), request, null)

    override fun enqueueBatch(info: DownloadBatchInfo, items: List<VideoItem>, request: DownloadRequest) = enqueue(items, request, info)

    override fun pause(id: String) { apply(QueueEvent.Pause(id)) }

    override fun resume(id: String) { apply(QueueEvent.Resume(id)) }

    override fun pauseBatch(batchId: String) { apply(QueueEvent.PauseBatch(batchId)) }

    override fun resumeBatch(batchId: String) { apply(QueueEvent.ResumeBatch(batchId)) }

    override fun cancel(id: String) { apply(QueueEvent.Cancel(id)) }

    override fun cancelBatch(batchId: String) { apply(QueueEvent.CancelBatch(batchId)) }

    override fun remove(id: String) { apply(QueueEvent.Remove(id)) }

    override fun retry(id: String) { apply(QueueEvent.Retry(id)) }

    override fun storage(): Pair<Long, Long> = synchronized(lock) { state.usedBytes() } to platform.freeBytes(settings.settings.value)

    private fun enqueue(items: List<VideoItem>, request: DownloadRequest, batch: DownloadBatchInfo?) {
        val caps = platform.caps
        apply(QueueEvent.Enqueue(items.map { QueueEvent.Add(it, DownloadPlanner.plannedChoice(it, request, caps)) }, request, batch, clock()))
    }

    // ---- The queue ------------------------------------------------------------------------------------------------------

    /** Applies [event]: publishes the new state, stops the running job when told, deletes the files of removed entries, saves, starts the next. */
    private fun apply(event: QueueEvent): QueueStep {
        val step: QueueStep
        val stopped: Job?
        synchronized(lock) {
            step = state.reduce(event)
            if (step.state == state && step.removed.isEmpty() && step.stop.isEmpty()) return step
            state = step.state
            publish()
            stopped = if (jobId != null && jobId in step.stop) job.also { job = null; jobId = null } else null
        }
        stopped?.cancel()
        if (step.removed.isNotEmpty()) {
            scope.launch(io) {
                stopped?.join()
                for (entry in step.removed) DownloadQueueState.filesOf(entry, platform.cacheDir.path).forEach { File(it).delete() }
            }
        }
        if (event !is QueueEvent.Progress) saves.trySend(Unit)
        pump()
        return step
    }

    /** Starts the next waiting entry when nothing runs, after measuring the holds again (spec: a battery check at each start). */
    private fun pump() {
        synchronized(lock) {
            if (job != null) return
            val fresh = conditionsOf(settings.settings.value)
            if (fresh != state.conditions) {
                state = state.reduce(QueueEvent.Conditions(fresh)).state
                publish()
                saves.trySend(Unit)
            }
            val next = state.nextToStart() ?: return
            val previous = lastJob
            val started = scope.launch(io, start = CoroutineStart.LAZY) {
                previous?.join()
                run(next.id)
            }
            job = started
            jobId = next.id
            lastJob = started
            started.start()
        }
    }

    private fun publish() {
        entryFlow.value = state.entries
        batchFlow.value = state.batchStates()
    }

    private fun save() {
        val snapshot = synchronized(lock) { state }
        runCatching { DownloadsFile.save(store, snapshot) }
    }

    private fun conditionsOf(s: VideoSettings) = QueueConditions(
        wifiOnly = s.downloadWifiOnly,
        metered = s.downloadWifiOnly && platform.isMetered(),
        pauseOnLowBattery = s.pauseOnLowBattery,
        batteryLow = s.pauseOnLowBattery && platform.isBatteryLow(),
    )

    // ---- One download ---------------------------------------------------------------------------------------------------

    private suspend fun run(id: String) {
        val mine = coroutineContext.job
        val written = mutableListOf<File>()
        try {
            val entry = synchronized(lock) { state.entry(id) } ?: return
            if (entry.state != DownloadState.Waiting) return
            val details = source.details(entry.item.id)
            val videoPart = File(platform.cacheDir, "$id.video.part")
            val audioPart = File(platform.cacheDir, "$id.audio.part")
            val choice = resolve(entry, details, videoPart, audioPart)
            if (apply(QueueEvent.Start(id, choice)).state.entry(id)?.state !is DownloadState.Running) return
            fetch(entry, choice, details.streams, videoPart, audioPart)
            finish(entry.copy(choice = choice), details, videoPart, audioPart, written)
        } catch (e: Exception) {
            if (e is CancellationException || !mine.isActive) {
                // paused, held, cancelled or removed while running: what this run already put beside the downloads goes when
                // the entry went; the working files stay for a resume (or are deleted with the entry)
                if (synchronized(lock) { state.entry(id) } == null) written.forEach { it.delete() }
                throw e as? CancellationException ?: CancellationException("Stopped").apply { initCause(e) }
            }
            apply(QueueEvent.Fail(id, reasonOf(e)))
        } finally {
            synchronized(lock) { if (job === mine) { job = null; jobId = null } }
            pump()
        }
    }

    /** The streams for [entry]: the same itags as before when bytes are on disk (a partial file resumes only from its stream), else fresh ones. */
    private fun resolve(entry: DownloadEntry, details: VideoDetails, videoPart: File, audioPart: File): DownloadChoice {
        val old = entry.choice
        if (!DownloadPlanner.isPlanned(old) && (videoPart.length() > 0 || audioPart.length() > 0)) {
            val video = old.video?.let { DownloadPlanner.matching(details.streams, it) }
            val audio = DownloadPlanner.matching(details.streams, old.audio)
            if ((old.video == null || video != null) && audio != null) return old.copy(video = video, audio = audio)
        }
        videoPart.delete()
        audioPart.delete()
        return DownloadPlanner.choiceFor(entry.request, details, platform.caps)
    }

    private suspend fun fetch(entry: DownloadEntry, choice: DownloadChoice, streams: StreamSet, videoPart: File, audioPart: File) {
        val total = choice.estimatedBytes.coerceAtLeast(1)
        var videoBytes = 0L
        var audioBytes = 0L
        var reported = -1f
        fun report() {
            val fraction = ((videoBytes + audioBytes).toDouble() / total).toFloat().coerceIn(0f, 0.99f)
            if (fraction - reported >= 0.01f) {
                reported = fraction
                apply(QueueEvent.Progress(entry.id, fraction))
            }
        }
        val headers = streams.requestHeaders
        choice.video?.let { video ->
            downloader.download(video.url, headers, videoPart, video.contentLength, refresh = {
                DownloadPlanner.matching(source.streams(entry.item.id), video)?.url
            }) { written, _ -> videoBytes = written; report() }
        }
        downloader.download(choice.audio.url, headers, audioPart, choice.audio.contentLength, refresh = {
            DownloadPlanner.matching(source.streams(entry.item.id), choice.audio)?.url
        }) { written, _ -> audioBytes = written; report() }
    }

    private suspend fun finish(entry: DownloadEntry, details: VideoDetails, videoPart: File, audioPart: File, written: MutableList<File>) {
        val choice = entry.choice
        val now = settings.settings.value
        val outDir = platform.outputDir(entry.kind, now).apply { mkdirs() }
        val base = DownloadPlanner.baseName(entry.item)
        val media: List<File>
        if (choice.video == null) {
            val out = File(outDir, DownloadPlanner.fileName(entry.item, entry.request, choice))
            move(audioPart, out)
            written += out
            media = listOf(out)
        } else {
            apply(QueueEvent.Join(entry.id))
            val joined = File(platform.cacheDir, "${entry.id}.joined.part").apply { delete() }
            val ok = try {
                platform.join(videoPart, audioPart, joined, choice.container) && joined.length() > 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            media = if (ok) {
                val out = File(outDir, DownloadPlanner.fileName(entry.item, entry.request, choice))
                move(joined, out)
                written += out
                videoPart.delete()
                audioPart.delete()
                listOf(out)
            } else {
                // spec 2.5: the join is to be proven on each device; when it fails both files are kept and play inside the app
                joined.delete()
                val (videoName, audioName) = DownloadPlanner.pairNames(entry.item, choice)
                val video = File(outDir, videoName)
                val audio = File(outDir, audioName)
                move(videoPart, video)
                written += video
                move(audioPart, audio)
                written += audio
                listOf(video, audio)
            }
        }

        val picked = SubtitleFiles.pick(entry.request.subtitles, details.subtitles, platform.appLanguage, DownloadPlanner.originalLanguage(details))
        val sidecarSubtitles = mutableListOf<SidecarSubtitle>()
        val track = picked.track
        if (track != null) {
            val file = File(outDir, SubtitleFiles.fileName(base, track.language))
            val fetched = attempt { subtitles.fetch(track, file, details.streams.requestHeaders) } != null
            if (fetched) {
                written += file
                sidecarSubtitles += SidecarSubtitle(file.name, track.language, picked.label, track.autoGenerated)
            }
        }

        val segmentList: List<SkipSegment> = segments?.let { provider ->
            val categories = if (now.sponsorBlock) now.segmentBehaviours.filterValues { it != SegmentBehaviour.OFF }.keys else emptySet()
            if (categories.isEmpty()) null else attempt { provider.segments(entry.item.id, categories) }
        }.orEmpty()

        val sidecar = File(outDir, DownloadSidecar.fileName(base))
        val got = sidecarSubtitles.firstOrNull()?.label
        sidecar.writeText(DownloadSidecar.encode(DownloadSidecar(
            item = entry.item, chapters = details.chapters, segments = segmentList, subtitles = sidecarSubtitles, quality = choice.label,
            container = choice.container, videoCodec = choice.video?.codec, audioCodec = choice.audio.codec, languageGot = got,
            files = media.map { it.name }, downloadedAtMs = clock(),
        )))
        written += sidecar

        apply(QueueEvent.Finish(entry.id, media.first().path, media.sumOf { it.length() }, sidecarSubtitles.map { File(outDir, it.file).path }, got))
    }

    /** Runs [block]; null when it failed for any reason but cancellation (subtitles and segments never fail a download). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        // across file systems (the app's cache to its external folder) this copies, then deletes
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is SourceExpired -> "The source stopped answering"
        is VideoSourceError -> e.message ?: "The source could not answer"
        is IOException -> "Network error"
        else -> e.message ?: "Failed"
    }
}
