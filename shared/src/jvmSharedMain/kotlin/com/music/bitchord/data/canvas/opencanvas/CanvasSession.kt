package com.opencanvas.core

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.AudioFetcher
import com.opencanvas.core.stream.AudioStream
import com.opencanvas.core.stream.CommunityMaps
import com.opencanvas.core.stream.MusicVideoSearch
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.StreamInfo
import com.opencanvas.core.stream.SyncOffset
import com.opencanvas.core.stream.SyncStore
import com.opencanvas.core.stream.YouTubeStreamResolver
import com.opencanvas.core.stream.tryOrNull
import com.opencanvas.core.sync.AlignResult
import com.opencanvas.core.sync.Measurement
import com.opencanvas.core.sync.SyncMeasurer
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What one `resolve` call asks for. */
internal class CanvasRequest(
    val title: String,
    val artist: String,
    val durationSec: Long,
    val mode: OpenCanvasMode,
    val resolution: OpenCanvasResolution,
    val song: SongAudio?,
    val videoId: String?,
) {
    /** Requests with the same key share one session (and so one measurement). */
    val key: String = "$artist|$title|$mode|${resolution.label}".lowercase() + "|" + (song?.cacheId ?: "") + "|" + (videoId ?: "")
}

/** The long-lived parts a session works with, built from an [OpenCanvasConfig]. */
internal class Environment(val config: OpenCanvasConfig) {
    val backend: StreamBackend = config.streamBackend
    val instantBackend: StreamBackend? = config.instantBackend
    val store = SyncStore(config.cacheDirectory)
    val community = config.communityMapsUrl?.let { CommunityMaps(config.httpClient, it) }
    val search = MusicVideoSearch(config.httpClient)
    val measurer = SyncMeasurer(AudioFetcher(config.httpClient))

    init {
        config.instantBackend?.warmUp()
        config.streamBackend.warmUp()
    }

    /** The small progressive MP4 to read audio frame sizes from: the fast backend's first, then the full one's. */
    suspend fun syncSource(videoId: String): AudioStream? =
        listOfNotNull(instantBackend, backend).firstNotNullOfOrNull { tryOrNull { it.resolveAudio(videoId) } }
}

/** A session's latest track; [done] once nothing more will change. */
internal data class SessionState(val track: OpenCanvasTrack? = null, val done: Boolean = false)

/**
 * One resolve, run on the library's own scope so it survives a collector that stops listening and
 * its result still reaches the cache.
 *
 * The stages overlap on purpose. The music-video search, the song's file header and a community-map
 * lookup start together; the top few search results are each measured against the song's audio and
 * the highest-ranked one that really matches wins; the video stream is resolved by the instant and
 * the full backend at the same time and re-published when the taller stream arrives.
 */
internal class CanvasSession(
    private val request: CanvasRequest,
    private val env: Environment,
    private val scope: CoroutineScope,
) {
    private class SyncView(
        val stage: SyncStage = SyncStage.NONE,
        val offsetMs: Long = 0L,
        val confidence: Double = 0.0,
        val segments: List<SyncSegment> = emptyList(),
    )

    private val startedAtMs = System.currentTimeMillis()
    private val references = AtomicInteger()
    private val job: Job

    val state = MutableStateFlow(SessionState())

    private var videoId: String? = null
    private var title: String = request.title
    private var durationMs: Long = 0L
    private var loopStartMs = 0L
    private var loopEndMs = 10_000L
    private var stream: StreamInfo? = null
    private var sync = SyncView()
    private var videoJob: Job? = null

    init {
        job = scope.launch {
            try {
                if (request.mode == OpenCanvasMode.FULL_SYNCED_VIDEO && request.song != null) runSynced(request.song) else runPlain()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                log("failed: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                finish()
            }
        }
    }

    /** True when this session is over without a usable result, so a new request should start afresh. */
    fun isStale(): Boolean {
        val current = state.value
        return job.isCancelled ||
            (current.done && current.track == null) ||
            (current.done && request.song != null && current.track?.syncStage == SyncStage.NONE &&
                System.currentTimeMillis() - startedAtMs > RETRY_AFTER_MS)
    }

    /** The session's tracks: the latest immediately, then every change, ending when the session is done. */
    fun updates(): Flow<OpenCanvasTrack> = state.transformWhile { current ->
        current.track?.let { emit(it) }
        !current.done
    }

    fun attach() {
        references.incrementAndGet()
    }

    /**
     * Drops one listener. A session nobody waits for is abandoned unless its map is already known,
     * but only after a short grace: a UI that restarts its request (a recomposition, a late piece of
     * metadata) detaches and attaches again within moments, and must find the work it started.
     */
    fun detach() {
        if (references.decrementAndGet() == 0 && isUnfinished()) {
            scope.launch {
                delay(ABANDON_GRACE_MS)
                if (references.get() == 0 && isUnfinished()) job.cancel()
            }
        }
    }

    /**
     * Starts the work for a request the host expects to make soon (a track that has just begun) and
     * keeps it alive until the map is known, so the later [attach] finds the answer waiting.
     */
    fun warm() {
        references.incrementAndGet()
        scope.launch {
            try {
                withTimeoutOrNull(WARM_HOLD_MS) { state.first { it.done || it.track?.syncStage == SyncStage.MEASURED } }
            } finally {
                detach()
            }
        }
    }

    private fun isUnfinished() = !state.value.done && state.value.track?.syncStage != SyncStage.MEASURED

    fun cancel() = job.cancel()

    // --- stages -------------------------------------------------------------------------------

    /** Loop mode, or full-video mode without any song audio to line up against. */
    private suspend fun runPlain() = coroutineScope {
        val chosen = request.videoId?.let { candidate(it) } ?: candidates().firstOrNull() ?: return@coroutineScope
        useVideo(chosen)
        if (request.mode == OpenCanvasMode.LOOP_CANVAS) {
            val window = withContext(Dispatchers.IO) { YouTubeStreamResolver.resolveLoopWindow(chosen.videoId, durationMs.takeIf { it > 0L } ?: 200_000L) }
            loopStartMs = window.first
            loopEndMs = window.second
        }
        resolveVideo(chosen.videoId)
    }

    private suspend fun runSynced(song: SongAudio) = coroutineScope {
        val id = song.cacheId
        var chosenVideoId = request.videoId ?: env.store.videoFor(id)
        var known: SyncOffset? = chosenVideoId?.let { env.store[id, it] }
        val started = System.currentTimeMillis()

        // Everything that does not depend on the answer to another stage starts now.
        val community = if (known == null && song is SongAudio.YouTube) env.community?.let { async { it.fetch(id) } } else null
        val search = if (chosenVideoId == null) async { candidates() } else null
        val songSource = if (known == null) async { songSource(song) } else null

        community?.await()?.let { hit ->
            if (request.videoId == null || request.videoId == hit.videoId) {
                chosenVideoId = hit.videoId
                known = hit.offset
                env.store.put(id, hit.videoId, hit.offset)
                search?.cancel()
                songSource?.cancel()
                log("community map hit after ${System.currentTimeMillis() - started} ms")
            }
        }
        var ranked: List<VideoCandidate> = chosenVideoId?.let { listOf(candidate(it)) } ?: emptyList()
        if (ranked.isEmpty()) {
            ranked = search?.await().orEmpty()
            if (ranked.isEmpty()) return@coroutineScope
        }
        val first = ranked.first()
        useVideo(first)

        val cached = known
        sync = if (cached != null) {
            SyncView(SyncStage.MEASURED, cached.offsetMs, cached.confidence, cached.segments.ifEmpty { listOf(SyncSegment(0L, Long.MAX_VALUE, cached.offsetMs)) })
        } else {
            SyncView(SyncStage.PENDING)
        }
        videoJob = launch { resolveVideo(first.videoId) }
        if (cached != null) {
            log("map from cache after ${System.currentTimeMillis() - started} ms")
            return@coroutineScope
        }
        verify(id, ranked, songSource!!, started)
    }

    /**
     * Measures every candidate against the song at the same time and takes the best whose audio really
     * lines up: a channel can publish several near-identical videos, a search can return a live take
     * or a Short, and only the audio tells them apart.
     *
     * Results are taken as they arrive, so a slow or failing candidate never holds the canvas back:
     * the first usable map opens a short grace period in which better-ranked candidates may still
     * overtake it, then the rest are dropped.
     */
    private suspend fun verify(id: String, ranked: List<VideoCandidate>, songSource: Deferred<AudioStream?>, started: Long) = coroutineScope {
        val track = songSource.await()
        // Three candidates at a time: the usual case is decided by the first batch, and a song whose
        // official video sits further down the results still gets found.
        var winner: Pair<VideoCandidate, AlignResult>? = null
        for ((batchIndex, batch) in ranked.chunked(BATCH_SIZE).withIndex()) {
            winner = bestOf(batch, batchIndex * BATCH_SIZE, track)
            if (winner != null) break
        }
        if (winner == null) {
            log("no map: none of ${ranked.size} candidate video(s) lines up with the song")
            sync = SyncView(SyncStage.NONE)
            publish()
            return@coroutineScope
        }
        val (candidate, result) = winner
        env.store.put(id, candidate.videoId, SyncOffset(result.offsetMs, result.confidence, result.segments, result.coverage))
        sync = SyncView(SyncStage.MEASURED, result.offsetMs, result.confidence, result.segments)
        log("map of ${result.segments.size} segment(s) for ${candidate.videoId} in ${System.currentTimeMillis() - started} ms (confidence ${"%.2f".format(result.confidence)})")
        if (candidate.videoId != videoId) {
            videoJob?.cancel()
            useVideo(candidate)
            videoJob = launch { resolveVideo(candidate.videoId) }
        } else {
            publish()
        }
    }

    /**
     * Measures every candidate of a batch against the song at the same time and takes the best whose
     * audio really lines up: a channel can publish several near-identical videos, a search can return a
     * live take or a Short, and only the audio tells them apart.
     *
     * Results are taken as they arrive, so a slow or failing candidate never holds the canvas back:
     * the first usable map opens a short grace period in which better-ranked candidates may still
     * overtake it, then the rest are dropped.
     */
    private suspend fun bestOf(batch: List<VideoCandidate>, rankBase: Int, track: AudioStream?): Pair<VideoCandidate, AlignResult>? = coroutineScope {
        val arrivals = Channel<Triple<Int, VideoCandidate, Measurement?>>(batch.size)
        batch.forEachIndexed { index, candidate -> launch { arrivals.send(Triple(rankBase + index, candidate, measureOne(track, candidate.videoId))) } }

        var winner: Pair<VideoCandidate, AlignResult>? = null
        var winnerScore = Double.NEGATIVE_INFINITY
        // Until a usable map exists the batch has this long; after one does, the short grace below.
        var graceEndsAt = System.currentTimeMillis() + BATCH_TIMEOUT_MS
        var arrived = 0
        while (arrived < batch.size) {
            val (rank, candidate, measured) =
                withTimeoutOrNull((graceEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)) { arrivals.receive() }
                    ?: break
            arrived++
            val result = measured?.align
            val still = measured != null &&
                (measured.videoKbps in 0.01..STILL_PICTURE_KBPS || measured.videoMotion < STILL_PICTURE_MOTION)
            log("candidate ${candidate.videoId} \"${candidate.title.take(48)}\" by ${candidate.channelTitle} (${candidate.durationSec}s): " +
                when {
                    result == null -> "not readable"
                    still -> "a still picture (${measured.videoKbps.toInt()} kbit/s, motion ${"%.2f".format(measured.videoMotion)}), not a music video"
                    result.error != null -> result.error
                    else -> "confidence ${"%.2f".format(result.confidence)}, ${result.segments.size} segment(s)"
                })
            if (result != null && !still && result.error == null && result.confidence >= YouTubeStreamResolver.MIN_SYNC_CONFIDENCE && result.segments.isNotEmpty()) {
                // The ranking is the main evidence, the match quality settles close calls: a version of the
                // video that is the same recording as the song lines up cleanly, a different cut needs an
                // edit-heavy map; a lower rank costs a little.
                val score = result.confidence - RANK_PENALTY * rank + if (VideoMatcher.saysVideo(candidate.title)) SAYS_VIDEO_BONUS else 0.0
                if (winner == null) graceEndsAt = System.currentTimeMillis() + OTHER_CANDIDATES_GRACE_MS
                if (score > winnerScore) {
                    winner = candidate to result
                    winnerScore = score
                }
                if (rank == 0 && result.confidence >= CONFIDENT_FIRST_CHOICE) break // no need to wait for the others
            }
        }
        coroutineContext.job.cancelChildren()
        winner
    }

    private suspend fun measureOne(track: AudioStream?, video: String): Measurement? {
        val picture = env.syncSource(video)
        if (track == null || picture == null) return null
        return try {
            env.measurer.measure(track, picture)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            log("measuring $video failed: ${failure.javaClass.simpleName}: ${failure.message}")
            null
        }
    }

    private suspend fun songSource(song: SongAudio): AudioStream? = when (song) {
        is SongAudio.YouTube -> env.syncSource(song.videoId)
        is SongAudio.Stream -> song.stream
    }

    private fun candidate(videoId: String) = VideoCandidate(videoId, request.title, "", 0L, true)

    private suspend fun candidates(): List<VideoCandidate> = env.search.find(request.title, request.artist, request.durationSec)

    /** Makes [candidate] the video this session is about; its stream arrives through [resolveVideo]. */
    @Synchronized
    private fun useVideo(candidate: VideoCandidate) {
        videoId = candidate.videoId
        stream = null
        if (candidate.title.isNotBlank() && candidate.title != request.title) title = candidate.title
        durationMs = if (candidate.durationSec > 0L) candidate.durationSec * 1000L else 0L
        loopEndMs = durationMs.takeIf { it > 0L } ?: 240_000L
    }

    /**
     * Asks the instant and the full backend at the same time. The first usable stream is published at
     * once; a later one replaces it when it is taller, so the canvas starts immediately and gets
     * sharper without a gap.
     */
    private suspend fun resolveVideo(video: String) = coroutineScope {
        val backends = listOfNotNull(env.instantBackend, env.backend)
        val answers = Channel<Pair<Boolean, StreamInfo?>>(backends.size)
        backends.forEach { backend ->
            launch { answers.send((backend === env.backend) to tryOrNull { backend.resolveVideo(video, request.resolution) }) }
        }
        repeat(backends.size) {
            val (isFull, answer) = answers.receive()
            if (answer != null) adopt(video, answer, isFull)
        }
    }

    @Synchronized
    private fun adopt(forVideo: String, candidate: StreamInfo, fromFullBackend: Boolean) {
        if (forVideo != videoId) return // an answer for a video this session has moved on from
        val current = stream
        val better = current == null || candidate.url != current.url &&
            (candidate.height > current.height || (fromFullBackend && candidate.height == 0))
        if (!better) return
        stream = candidate
        if (candidate.durationMs > 0L) durationMs = candidate.durationMs
        publish()
        log("video ${candidate.height}p ready")
    }

    // --- publishing ---------------------------------------------------------------------------

    @Synchronized
    private fun publish() {
        val current = stream ?: return
        val id = videoId ?: return
        state.value = SessionState(
            OpenCanvasTrack(
                videoId = id,
                videoStreamUrl = current.url,
                title = title,
                artist = request.artist,
                mode = request.mode,
                resolution = request.resolution,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                audioOffsetMs = sync.offsetMs,
                trajectory = null,
                targetAspectRatio = 9f / 16f,
                source = "OpenCanvas by ${OpenCanvas.AUTHOR}",
                syncConfidence = sync.confidence,
                videoDurationMs = durationMs,
                syncSegments = sync.segments,
                syncStage = sync.stage,
                videoHeaders = current.headers,
            ),
            done = false,
        )
    }

    @Synchronized
    private fun finish() {
        state.value = state.value.copy(done = true)
    }

    private fun log(message: String) = env.config.log("[OpenCanvas ${request.artist} - ${request.title}] $message")

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
        const val ABANDON_GRACE_MS = 4_000L
        const val WARM_HOLD_MS = 30_000L
        const val RANK_PENALTY = 0.03
        /** Between two videos that line up, one that calls itself the video song beats a poster that does not. */
        const val SAYS_VIDEO_BONUS = 0.15
        const val OTHER_CANDIDATES_GRACE_MS = 600L
        const val BATCH_SIZE = 3
        const val BATCH_TIMEOUT_MS = 12_000L
        /** Below this the picture is a still image (measured on art-track uploads: 7-27 kbit/s); music videos start well above (slow ones near 80). */
        const val STILL_PICTURE_KBPS = 40.0
        const val STILL_PICTURE_MOTION = 0.12
        const val CONFIDENT_FIRST_CHOICE = 0.85
    }
}
