package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.WatchKey
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class WatchBatchSelection { SEASON, UNWATCHED, ALL, CUSTOM }
@Serializable enum class WatchBatchStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }
@Serializable enum class WatchBatchEpisodeStatus { PENDING, DOWNLOADING, DONE, FAILED }

@Serializable data class WatchBatchEpisode(
    val target: PlayTarget.EpisodeOf,
    val status: WatchBatchEpisodeStatus = WatchBatchEpisodeStatus.PENDING,
    val downloadId: String? = null,
    val hint: StreamHint? = null,
    val error: String? = null,
)

/** Only stable catalogue identities and release hints persist here. URLs and headers are resolved afresh after restart. */
@Serializable data class WatchBatchDownload(
    val id: String,
    val profileId: String,
    val summary: TitleSummary,
    val episodes: List<WatchBatchEpisode>,
    val directoryPath: String,
    val maxHeight: Int? = null,
    val status: WatchBatchStatus = WatchBatchStatus.QUEUED,
) {
    val completed: Int get() = episodes.count { it.status == WatchBatchEpisodeStatus.DONE }
}

/** Application-scoped queue (one episode at a time unless the viewer chose more) built on Watch's existing range downloader and device storage policy. */
class WatchBatchDownloads internal constructor(private val downloads: WatchDownloads, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchBatchDownload.serializer())
    private val file = File(downloads.directory, "episode-jobs.json")
    private val state = MutableStateFlow(load())
    val items = state.asStateFlow()
    private var repository: WatchRepository? = null
    private var worker: Job? = null
    private var workingId: String? = null

    @Synchronized fun bind(repository: WatchRepository) {
        if (this.repository === repository) return
        check(this.repository == null) { "Watch downloads are already bound to a repository." }
        this.repository = repository
        scope.launch {
            combine(repository.activeProfile, repository.profileUnlocked) { profile, unlocked -> profile to unlocked }.collect { (profile, unlocked) ->
                synchronized(this@WatchBatchDownloads) {
                    val running = state.value.firstOrNull { it.id == workingId }
                    if (running != null && (!unlocked || running.profileId != profile)) worker?.cancel()
                    pump()
                }
            }
        }
        pump()
    }

    /** Selection is snapshotted now; changes in progress later cannot substitute or duplicate episodes. */
    suspend fun enqueue(title: Title, selection: WatchBatchSelection, season: Int, custom: Set<WatchKey> = emptySet(), maxHeight: Int? = null): String {
        val repo = checkNotNull(repository)
        check(repo.profileUnlocked.value) { "Unlock the Watch profile first." }
        val profile = repo.activeProfile.value
        require(maxHeight == null || maxHeight in setOf(480, 720, 1080, 2160))
        val seasons = if (selection == WatchBatchSelection.SEASON || selection == WatchBatchSelection.CUSTOM) listOf(season)
            else title.seasons.map { it.number }.filter { it > 0 }.distinct().sorted()
        val episodes = seasons.flatMap { repo.episodes(title.key, it) }.filter {
            it.seriesKey == title.key && it.key.seriesKey == title.key && it.key.season == it.season && it.key.episode == it.number
        }.distinctBy { it.key }.sortedWith(compareBy({ it.season }, { it.number })).filter {
            (selection != WatchBatchSelection.CUSTOM || it.key in custom) &&
                (selection != WatchBatchSelection.UNWATCHED || repo.progress(it.key)?.completed != true)
        }.mapNotNull { repo.targetOf(title, it) as? PlayTarget.EpisodeOf }
        check(repo.activeProfile.value == profile && repo.profileUnlocked.value) { "The active profile changed. Choose episodes again." }
        synchronized(this) {
            val reserved = state.value.filter { it.profileId == profile && it.status in setOf(WatchBatchStatus.QUEUED, WatchBatchStatus.RUNNING, WatchBatchStatus.PAUSED) }
                .flatMap { it.episodes }.filter { it.status != WatchBatchEpisodeStatus.FAILED }.map { it.target.key }.toSet()
            val pending = episodes.filter { it.key !in reserved && downloads.doneFor(it.key) == null }
            require(pending.isNotEmpty()) { "No new episodes to download in this selection." }
            require(pending.size <= 2_000) { "Choose up to 2,000 episodes at a time." }
            val job = WatchBatchDownload(UUID.randomUUID().toString(), profile, title.summary,
                pending.map { WatchBatchEpisode(it) }, downloads.destination.value.canonicalPath, maxHeight)
            publish(state.value + job)
            pump()
            return job.id
        }
    }

    @Synchronized fun pause(id: String) {
        edit(id) { it.copy(status = WatchBatchStatus.PAUSED) }
        if (workingId == id) worker?.cancel()
    }

    @Synchronized fun resume(id: String) {
        edit(id) { batch -> batch.copy(status = WatchBatchStatus.QUEUED, episodes = batch.episodes.map {
            if (it.status == WatchBatchEpisodeStatus.FAILED || it.status == WatchBatchEpisodeStatus.DOWNLOADING)
                it.copy(status = WatchBatchEpisodeStatus.PENDING, error = null) else it
        }) }
        pump()
    }

    /** Removes the queue entry, keeping completed files available in Downloads. */
    @Synchronized fun remove(id: String) {
        if (workingId == id) worker?.cancel()
        publish(state.value.filterNot { it.id == id })
    }

    @Synchronized private fun pump() {
        if (worker != null) return
        val repo = repository ?: return
        if (!repo.profileUnlocked.value) return
        val batch = state.value.firstOrNull { it.profileId == repo.activeProfile.value && it.status in setOf(WatchBatchStatus.QUEUED, WatchBatchStatus.RUNNING) } ?: return
        workingId = batch.id
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try { runBatch(batch.id, repo) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(this@WatchBatchDownloads) { edit(batch.id) { it.copy(status = WatchBatchStatus.FAILED) } } }
            finally {
                synchronized(this@WatchBatchDownloads) { worker = null; workingId = null; pump() }
            }
        }
        worker = launched
        launched.start()
    }

    /**
     * Downloads the batch's episodes, as many at once as the viewer chose in Watch settings (one to three, one by default): each
     * worker claims the next episode nobody has, so none is fetched twice.
     */
    private suspend fun runBatch(id: String, repo: WatchRepository) {
        synchronized(this) { edit(id) { it.copy(status = WatchBatchStatus.RUNNING) } }
        val claimed = HashSet<WatchKey>()
        val workers = repo.preferences.value.parallelDownloads.coerceIn(1, MAX_DOWNLOADS_AT_ONCE)
        coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val (batch, episode) = synchronized(this@WatchBatchDownloads) {
                            val batch = state.value.firstOrNull { it.id == id }?.takeIf { it.status == WatchBatchStatus.RUNNING } ?: return@launch
                            val next = batch.episodes.firstOrNull { it.status in setOf(WatchBatchEpisodeStatus.PENDING, WatchBatchEpisodeStatus.DOWNLOADING) && it.target.key !in claimed }
                                ?: return@launch
                            claimed += next.target.key
                            batch to next
                        }
                        downloadOne(id, batch, episode, repo)
                    }
                }
            }
        }
        synchronized(this) { edit(id) { it.copy(status = if (it.episodes.any { e -> e.status == WatchBatchEpisodeStatus.FAILED }) WatchBatchStatus.FAILED else WatchBatchStatus.DONE) } }
    }

    private suspend fun downloadOne(id: String, batch: WatchBatchDownload, episode: WatchBatchEpisode, repo: WatchRepository) {
        var activeDownload: String? = null
        try {
            val kept = downloads.doneFor(episode.target.key)
            if (kept != null) {
                episode(id, episode.copy(status = WatchBatchEpisodeStatus.DONE, downloadId = kept.id)); return
            }
            val candidates = repo.sourcesNow(episode.target, episode.hint).streams.filter(downloads::canDownload)
            val stream = choose(candidates, batch.maxHeight, episode.hint, episode.target)
                ?: error("No matching direct MP4, MKV or WebM copy at this quality. Check your sources and retry.")
            val d = checkNotNull(downloads.start(episode.target, batch.summary, stream,
                "S${episode.target.season} E${episode.target.episode} · ${episode.target.episodeName.orEmpty()}", batch.directoryPath))
            activeDownload = d.id
            val hint = StreamHint(stream.source.pluginId, stream.label, stream.filename, stream.sizeBytes, stream.durationMs, stream.bingeGroup)
            episode(id, episode.copy(status = WatchBatchEpisodeStatus.DOWNLOADING, downloadId = d.id, hint = hint, error = null))
            val result = downloads.items.first { list -> list.none { it.id == d.id && it.status == WatchDownload.Status.DOWNLOADING } }.firstOrNull { it.id == d.id }
            check(result?.status == WatchDownload.Status.DONE) { result?.error ?: "Download stopped. Retry to continue." }
            episode(id, episode.copy(status = WatchBatchEpisodeStatus.DONE, downloadId = d.id, hint = hint, error = null))
            activeDownload = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val current = state.value.firstOrNull { it.id == id }?.episodes?.firstOrNull { it.target.key == episode.target.key } ?: episode
            episode(id, current.copy(status = WatchBatchEpisodeStatus.FAILED, error = e.message?.take(200) ?: "Download failed."))
        } finally {
            activeDownload?.let { withContext(NonCancellable) { downloads.pause(it) } }
        }
    }

    @Synchronized private fun episode(id: String, episode: WatchBatchEpisode) { edit(id) { it.copy(episodes = it.episodes.map { old -> if (old.target.key == episode.target.key) episode else old }) } }
    private fun edit(id: String, change: (WatchBatchDownload) -> WatchBatchDownload) = publish(state.value.map { if (it.id == id) change(it) else it })
    private fun publish(items: List<WatchBatchDownload>) {
        downloads.directory.mkdirs()
        val tmp = File(downloads.directory, "episode-jobs.json.tmp")
        tmp.writeText(json.encodeToString(serializer, items))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        state.value = items
    }
    private fun load(): List<WatchBatchDownload> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        json.decodeFromString(serializer, file.readText().also { require(it.length <= 8 * 1024 * 1024) }).map { batch ->
            batch.copy(status = if (batch.status == WatchBatchStatus.RUNNING) WatchBatchStatus.QUEUED else batch.status,
                episodes = batch.episodes.map { if (it.status == WatchBatchEpisodeStatus.DOWNLOADING) it.copy(status = WatchBatchEpisodeStatus.PENDING) else it })
        }
    }.getOrDefault(emptyList())

    companion object {
        /** Once selected, retries require that release. A similarly named copy is never appended to its partial file. */
        internal fun choose(streams: List<Stream>, maxHeight: Int?, hint: StreamHint?, target: PlayTarget.EpisodeOf? = null): Stream? = streams.asSequence()
            .filter { maxHeight == null || it.height?.let { h -> h <= maxHeight } == true }
            .filter { hint == null || (it.source.pluginId == hint.pluginId && it.label == hint.label && it.filename == hint.filename && it.sizeBytes == hint.sizeBytes) }
            .filter { target == null || matchesEpisode(it, target) }
            .maxByOrNull { it.height ?: 0 }

        internal fun matchesEpisode(stream: Stream, target: PlayTarget.EpisodeOf): Boolean {
            val name = stream.filename ?: return true
            val match = Regex("(?i)(?:\\b|[._ -])s(\\d{1,4})[._ -]*e(\\d{1,5})(?!\\d)").find(name)
                ?: Regex("(?i)(?:\\b|[._ -])(\\d{1,4})x(\\d{1,5})(?!\\d)").find(name) ?: return true
            if (match.groupValues[1].toIntOrNull() != target.season || match.groupValues[2].toIntOrNull() != target.episode) return false
            // A multi-episode release is not an independently downloadable episode.
            return !Regex("(?i)^(?:[._ -]*e\\d+|[._ -]*-\\s*(?:e)?\\d+)").containsMatchIn(name.substring(match.range.last + 1))
        }
    }
}

/** The most episodes a batch downloads at once. */
const val MAX_DOWNLOADS_AT_ONCE = 3
