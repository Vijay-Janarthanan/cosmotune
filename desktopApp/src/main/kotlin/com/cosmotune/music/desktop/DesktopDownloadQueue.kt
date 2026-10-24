package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import com.cosmotune.music.data.downloads.MusicDownloadJournal
import com.cosmotune.music.data.downloads.MusicDownloadPhase
import com.cosmotune.music.data.sources.SourceTrackKeys
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Where a track is between "not on this computer" and "on it". */
sealed interface DesktopDownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DesktopDownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DesktopDownloadState

    data class Failed(val reason: String) : DesktopDownloadState
}

/** One row of the downloads manager. */
data class DesktopDownloadItem(
    val song: Song,
    val state: DesktopDownloadState,
    /** The order it was asked for in, which is the order the queue reaches it in. */
    val sequence: Long,
    val quality: String = "LOSSLESS",
)

/**
 * The download queue, and what came out of it.
 *
 * Android's `Downloads` split, kept: [active] is what is happening now and lives only in memory,
 * because a transfer interrupted by the process dying did not happen; what exists on disk is the
 * files themselves, which the library scan finds on its own.
 *
 * [WORKERS] run at once, each pulling from the same queue, so one slow track does not hold up the
 * rest and a release asked for as a whole arrives in the order it was asked for.
 */
object DesktopDownloadQueue {

    /** How many transfers run at once. Android's `DownloadService` uses the same number. */
    internal const val WORKERS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val sequence = AtomicLong(0)

    /** Asked for, not yet claimed by a worker. Insertion-ordered, which is queue order. */
    private val pending = LinkedHashMap<String, DesktopDownloadItem>()

    /** Claimed by a worker; the job is attached once it exists. */
    private val running = LinkedHashMap<String, Job?>()
    private val runningProviders = HashMap<String, String>()
    private val runningSequence = HashMap<String, Long>()
    private var journal: MusicDownloadJournal? = null
    internal var transfer: suspend (Song, String, (Long, Long?) -> Unit) -> Result<Song> = { song, quality, progress ->
        DesktopDownloadManager.download(song, quality, progress)
    }
    /** The quality a new request is asked at; injectable so tests never read the user's settings. */
    internal var defaultQuality: () -> String = { DesktopPersistence().string("download_quality", "LOSSLESS") }
    private val mutableStorageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = mutableStorageError

    /** Interrupted jobs are visible for Retry; reopening the app does not silently use the network. */
    fun restore(file: java.io.File) {
        val saved = MusicDownloadJournal(file)
        synchronized(lock) {
            if (journal != null) { saved.close(); return }
            journal = saved
            scope.launch { saved.error.collect { mutableStorageError.value = it } }
            val recovered = saved.entries.value.map { entry ->
                sequence.updateAndGet { maxOf(it, entry.sequence) }
                DesktopDownloadItem(entry.track.song(), DesktopDownloadState.Failed(entry.error ?: "Interrupted download. Retry to resume."), entry.sequence, entry.quality)
            }
            _active.update { old -> recovered.associateBy { it.song.videoId } + old }
        }
    }

    private var workers = 0

    private val _active = MutableStateFlow<Map<String, DesktopDownloadItem>>(emptyMap())

    /** Everything queued, running or just failed, keyed by videoId. */
    val active: StateFlow<Map<String, DesktopDownloadItem>> = _active

    private val _finished = MutableSharedFlow<Song>(extraBufferCapacity = 64)

    /** Tracks that reached disk, for the library to record. */
    val finished: SharedFlow<Song> = _finished

    /**
     * Adds [song] to the queue, unless it is already in it.
     *
     * A second tap on a track already queued or running is a no-op rather than a second transfer:
     * the row it would produce is the same row.
     */
    fun enqueue(song: Song) {
        synchronized(lock) {
        val id = song.videoId
        if (id.isBlank()) return
        val item = synchronized(lock) {
            if (id in pending || id in running) return
            val quality = journal?.entries?.value?.find { it.track.id == id }?.quality
                ?: defaultQuality()
            DesktopDownloadItem(song, DesktopDownloadState.Queued, sequence.incrementAndGet(), quality)
                .also { pending[id] = it }
        }
        _active.update { it + (id to item) }
        try { journal?.enqueue(song, item.sequence, item.quality) }
        catch (_: Exception) {
            synchronized(lock) { pending.remove(id) }
            failed(id, item, IllegalStateException("The saved download queue is full.")); return
        }
        ensureWorkers()
        }
    }

    fun enqueueAll(songs: List<Song>) = songs.forEach(::enqueue)

    /**
     * Drops [videoId] from the queue, or stops it if it is one of the ones running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a track being dequeued
     * and its job existing: a cancel landing in that window finds no job to stop, but [onRunning]
     * then finds the id it was told to run is no longer wanted and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            runningProviders.remove(videoId)
            runningSequence.remove(videoId)
            journal?.remove(videoId)
            _active.update { it - videoId }
            running.remove(videoId)
        }
        job?.cancel()
        DesktopDownloadManager.cancel(videoId)
        // A download called off is not something anyone needs reminding to check on, so it leaves
        // the manager rather than sitting in it as a permanent "cancelled" row.
    }

    fun cancelAll() = _active.value.keys.toList().forEach(::cancel)

    /** Drops the finished and failed rows, leaving whatever is still going. */
    fun clearFinished() {
        _active.value.values.filter { it.state is DesktopDownloadState.Failed }.forEach { journal?.remove(it.song.videoId, it.sequence) }
        _active.update { current ->
            current.filterValues { it.state !is DesktopDownloadState.Failed }
        }
    }

    /**
     * Downloads whose saved file is gone and could not be found again: shown as failed with Retry,
     * kept in the journal across restarts, and never fetched until the listener asks.
     */
    fun offerMissing(songs: List<Song>) {
        songs.forEach { song ->
            val id = song.videoId
            val item = synchronized(lock) {
                if (id.isBlank() || id in pending || id in running || id in _active.value) return@forEach
                DesktopDownloadItem(song, DesktopDownloadState.Failed(MISSING_FILE), sequence.incrementAndGet(),
                    defaultQuality())
            }
            _active.update { it + (id to item) }
            runCatching { journal?.enqueue(song, item.sequence, item.quality); journal?.phase(id, item.sequence, com.cosmotune.music.data.downloads.MusicDownloadPhase.FAILED, MISSING_FILE) }
        }
    }

    const val MISSING_FILE = "The saved file is missing. Retry to download it again."

    /** Puts a failed track back at the end of the queue. */
    fun retry(videoId: String) {
        val song = _active.value[videoId]?.song ?: return
        _active.update { it - videoId }
        enqueue(song)
    }

    private fun ensureWorkers() {
        val toStart = synchronized(lock) {
            val available = pending.values.groupBy { SourceTrackKeys.parse(it.song.videoId)?.first ?: "catalogue" }
                .entries.sumOf { (provider, items) -> minOf(items.size, (2 - runningProviders.values.count { it == provider }).coerceAtLeast(0)) }
            val wanted = minOf(WORKERS, available + running.size)
            val starting = (wanted - workers).coerceAtLeast(0)
            workers += starting
            starting
        }
        repeat(toStart) { scope.launch { work() } }
    }

    private suspend fun work() {
        try {
            while (true) {
                val item = takeNext() ?: return
                val id = item.song.videoId
                _active.update { if (it[id]?.sequence == item.sequence) it + (id to item.copy(state = DesktopDownloadState.Running(0f))) else it }
                journal?.phase(id, item.sequence, MusicDownloadPhase.RUNNING)
                val result = supervisorScope { runCatching {
                    val transfer = async {
                    transfer(item.song, item.quality) { downloaded, total ->
                        val fraction = if (total != null && total > 0L) {
                            (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        _active.update { current ->
                            // Only while it is still the running row: a cancel removes it, and
                            // putting it back here would resurrect a row nobody asked for.
                            if (current[id]?.sequence != item.sequence) current
                            else current + (id to item.copy(state = DesktopDownloadState.Running(fraction)))
                        }
                    }
                    }
                    onRunning(id, item.sequence, transfer)
                    transfer.await()
                } }
                currentCoroutineContext().ensureActive()
                onIdle(id, item.sequence)
                result.fold(
                    onSuccess = { outcome ->
                        outcome.fold(
                            onSuccess = { saved ->
                                journal?.remove(id, item.sequence)
                                _active.update { if (it[id]?.sequence == item.sequence) it - id else it }
                                _finished.tryEmit(saved)
                            },
                            onFailure = { failed(id, item, it) },
                        )
                    },
                    onFailure = { failed(id, item, it) },
                )
            }
        } finally {
            synchronized(lock) { workers-- }
            ensureWorkers()
        }
    }

    private fun failed(id: String, item: DesktopDownloadItem, cause: Throwable) {
        // A cancelled transfer already left the manager (cancel removes its row), so the sequence
        // check is what tells a listener's cancel apart from a failure. A cancellation that did not
        // come from the listener (a timeout inside the transfer) is a failure: returning early here
        // used to leave that row "Running" forever, in the manager and in the saved queue.
        if (_active.value[id]?.sequence != item.sequence) return
        val reason = when (cause) {
            is kotlinx.coroutines.TimeoutCancellationException -> "The download timed out. Retry to try again."
            is kotlinx.coroutines.CancellationException -> "Interrupted download. Retry to resume."
            else -> cause.message?.takeIf(String::isNotBlank) ?: "The download did not finish"
        }
        journal?.phase(id, item.sequence, MusicDownloadPhase.FAILED, reason)
        _active.update { it + (id to item.copy(state = DesktopDownloadState.Failed(reason))) }
        DesktopTrackLog.log("download failed for '${item.song.title}' — $reason")
    }

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no instant where a
     * track is in neither the queue nor the running slot and a [cancel] for it would quietly do
     * nothing.
     */
    private fun takeNext(): DesktopDownloadItem? = synchronized(lock) {
        val entry = pending.entries.firstOrNull { candidate ->
            val provider = SourceTrackKeys.parse(candidate.value.song.videoId)?.first ?: "catalogue"
            runningProviders.values.count { it == provider } < 2
        } ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        runningSequence[entry.key] = entry.value.sequence
        runningProviders[entry.key] = SourceTrackKeys.parse(entry.value.song.videoId)?.first ?: "catalogue"
        entry.value
    }

    /** Attaches the job fetching [videoId], unless it has been cancelled meanwhile. */
    private fun onRunning(videoId: String, expectedSequence: Long, job: Job?) {
        val cancelled = synchronized(lock) {
            if (runningSequence[videoId] != expectedSequence) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job?.cancel()
    }

    private fun onIdle(videoId: String, expectedSequence: Long) {
        synchronized(lock) { if (runningSequence[videoId] == expectedSequence) {
            running.remove(videoId); runningProviders.remove(videoId); runningSequence.remove(videoId)
        } }
    }

    /** Tests only: forgets the restored journal so each test can restore its own isolated file. */
    internal fun detachJournalForTest() {
        synchronized(lock) { journal?.close(); journal = null }
        mutableStorageError.value = null
    }

    internal suspend fun awaitIdle() {
        while (synchronized(lock) { workers != 0 }) delay(10)
    }
}
