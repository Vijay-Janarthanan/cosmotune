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
import com.opencanvas.core.stream.isMediaStreamExpired
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
    val album: String? = null,
) {
    /** Requests with the same key share one session (and so one measurement). */
    val key: String = "$artist|$title|$mode|${resolution.label}|${album.orEmpty()}".lowercase() + "|" + (song?.cacheId ?: "") + "|" + (videoId ?: "")

    /** The song whatever its track id, so a map measured for another upload or service of it is found. */
    val identity: com.opencanvas.core.stream.SongIdentity? = com.opencanvas.core.stream.SongIdentity.of(title, artist, durationSec)
}

/** The long-lived parts a session works with, built from an [OpenCanvasConfig]. */
internal class Environment(val config: OpenCanvasConfig) {
    val backend: StreamBackend = config.streamBackend
    val instantBackend: StreamBackend? = config.instantBackend
    val store = SyncStore(config.cacheDirectory)
    val community = config.communityMapsUrl?.let { CommunityMaps(config.httpClient, it) }
    val search = MusicVideoSearch(config.httpClient, config.log)
    val measurer = SyncMeasurer(AudioFetcher(config.httpClient))
    /** Measurements shared by the lookups of one song ([MeasurementShare]): a second lookup never measures the same video again. */
    val measurements = MeasurementShare()

    init {
        config.instantBackend?.warmUp()
        config.streamBackend.warmUp()
    }

    /** The small progressive MP4 to read audio frame sizes from: the fast backend's first, then the full one's. */
    suspend fun syncSource(videoId: String): AudioStream? =
        listOfNotNull(instantBackend, backend).firstNotNullOfOrNull { tryOrNull { it.resolveAudio(videoId) } }

    /**
     * Whether [videoId] is a still picture by its preview frames ([com.opencanvas.core.stream.PreviewFrames]); null when no backend has its storyboard,
     * the platform cannot open it, or there are too few frames to tell.
     */
    suspend fun previewStill(videoId: String): Boolean? {
        val spec = listOfNotNull(instantBackend, backend).firstNotNullOfOrNull { tryOrNull { it.storyboardSpec(videoId) } } ?: return null
        return com.opencanvas.core.stream.PreviewFrames.isStill(config.httpClient, spec)
    }

    /** The same file through each backend on its own, in the order tried: one can hand out a file whose tables are unreadable where the other's is fine. */
    suspend fun syncSourcesByBackend(videoId: String): List<AudioStream?> =
        listOfNotNull(instantBackend, backend).map { tryOrNull { it.resolveAudio(videoId) } }
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
    foreground: Boolean = true,
) {
    /** True for the playing song; a queued song's lookup (a prefetch) yields to it until [promote]d. */
    private val priority = MutableStateFlow(foreground)

    /** The song this session is for has started playing: its searches no longer wait for anyone. */
    fun promote() {
        priority.value = true
    }

    /**
     * Set when a search went unanswered (a timeout, a dropped connection, a captcha). A session that then ends without
     * a map has not learnt that the song has no video, so it is not kept as the answer: the next request starts over.
     */
    @Volatile private var searchUnanswered = false
    @Volatile private var searchStartedAt = 0L

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

    /** Retry failed results or refresh completed streams whose signed address no longer stands. */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = state.value
        return job.isCancelled ||
            (current.done && current.track == null) ||
            // A network failure is not "no video": only a search that was answered is believed for a while.
            (current.done && searchUnanswered && current.track?.syncStage != SyncStage.MEASURED) ||
            (current.done && current.track?.videoStreamUrl?.let { url ->
                isMediaStreamExpired(url, nowMs) ||
                    (url.startsWith("https://") || url.startsWith("http://")) &&
                    nowMs - startedAtMs >= MAX_STREAM_AGE_MS
            } == true) ||
            (current.done && request.song != null && current.track?.syncStage == SyncStage.NONE &&
                nowMs - startedAtMs > RETRY_AFTER_MS)
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
        var chosenVideoId = request.videoId ?: env.store.verifiedVideoFor(id)
        var known: SyncOffset? = chosenVideoId?.let { env.store[id, it] }
        val started = System.currentTimeMillis()
        val identity = request.identity
        var chosenCandidate: VideoCandidate? = null
        // The same song under another track id (another upload, a lossless copy from another service): the video verified
        // for it is used at once. Of the same length, its map holds as it is; of another length the one video is lined up
        // again before anything is searched for.
        var realigning = false
        if (chosenVideoId == null && identity != null) {
            env.store.songVideo(identity)?.takeIf { it.trackVideoId != id }?.let { other ->
                val map = env.store[other.trackVideoId, other.videoId] ?: return@let
                if (!env.store.isSettled(other.trackVideoId, other.videoId)) return@let
                chosenVideoId = other.videoId
                chosenCandidate = VideoCandidate(other.videoId, other.videoTitle.ifBlank { request.title }, other.channelTitle,
                    0L, other.officialArtist, other.verifiedChannel)
                if (identity.sameLength(other.durationSec)) {
                    known = map
                    env.store.put(id, other.videoId, map, verifiedSelection = true, video = chosen(chosenCandidate!!))
                    env.store.putSong(identity, other.copy(trackVideoId = id, durationSec = identity.durationSec))
                    log("map of ${other.videoId} reused from ${other.trackVideoId}: the same song")
                } else {
                    realigning = true
                    log("lining ${other.videoId} up again: the same song as ${other.trackVideoId}, ${other.durationSec}s there, ${identity.durationSec}s here")
                }
            }
        } else if (chosenVideoId != null && known != null && request.videoId == null && identity != null && env.store.songVideo(identity) == null) {
            // A map verified before song entries existed: remembered for the song as well, so its other copies find it.
            env.store.putSong(identity, SyncStore.SongVideo(id, chosenVideoId!!, identity.durationSec))
        }

        // Everything that does not depend on the answer to another stage starts now.
        // A published timing map establishes sync, not publisher identity. Automatic discovery
        // must use the current verified selection policy, including after upgrading old caches.
        val community = if (known == null && song is SongAudio.YouTube && request.videoId != null) env.community?.let { async { it.fetch(id) } } else null
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
        var ranked: List<VideoCandidate> = chosenVideoId?.let { listOf(chosenCandidate ?: candidate(it)) } ?: emptyList()
        if (ranked.isEmpty()) {
            ranked = search?.await().orEmpty()
            if (ranked.isEmpty()) {
                log(if (searchUnanswered) "no candidate video: the search got no answer, so this is not remembered as a song without one"
                    else "no candidate video for the song")
                return@coroutineScope
            }
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
        verify(id, ranked, songSource!!, started, (song as? SongAudio.YouTube)?.videoId, realigning)
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
    private suspend fun verify(id: String, ranked: List<VideoCandidate>, songSource: Deferred<AudioStream?>, started: Long, songVideoId: String?,
        realigning: Boolean = false) = coroutineScope {
        val track = songSource.await()
        // Three candidates at a time: the usual case is decided by the first batch, and a song whose
        // official video sits further down the results still gets found.
        var winner: Pick? = null
        // Someone else's upload that lines up with only part of the song (a re-upload cut shorter, starting a verse in): kept
        // in case nothing better lines up, but the other candidates and the expanded search are tried first.
        var partial: Pick? = null
        var attemptedCount = 0
        val tried = HashSet<String>()
        suspend fun measureInBatches(candidates: List<VideoCandidate>) {
            val fresh = candidates.filter { tried.add(it.videoId) }
            val rankBase = attemptedCount
            attemptedCount += fresh.size
            for ((batchIndex, batch) in fresh.chunked(BATCH_SIZE).withIndex()) {
                // A queued song's measuring waits while the playing song is searching: both read from youtube.com.
                env.search.traffic.awaitQuiet(priority)
                val pick = bestOf(batch, rankBase + batchIndex * BATCH_SIZE, track, songVideoId) ?: continue
                if (!pick.partial) {
                    winner = pick
                    break
                }
                if (partial.let { it == null || pick.score > it.score }) partial = pick
            }
        }
        measureInBatches(ranked)
        // The video known for this song under another track id did not line up with this copy: searched for as usual.
        if (winner == null && realigning) {
            log("the video known for the song does not line up with this copy; searching")
            measureInBatches(candidates())
        }
        // A highly ranked remaster can have a different soundtrack (for example a 5.1 remix).
        // Exhaust the other bounded queries before treating that first publisher hit as a miss.
        if (winner == null && request.videoId == null) {
            val spent = if (searchStartedAt == 0L) 0L else System.currentTimeMillis() - searchStartedAt
            val alternatives = searchOutcome(env.search.findOutcome(request.title, request.artist, request.durationSec,
                limit = 10, album = request.album, exhaustive = true, excludedVideoIds = HashSet(tried), foreground = priority,
                budgetMs = maxOf(SEARCH_BUDGET_MS - spent, EXPANDED_MIN_BUDGET_MS)))
            measureInBatches(alternatives)
        }
        val found = winner ?: partial?.also { only ->
            log("only ${only.candidate.videoId} by ${only.candidate.channelTitle} lines up, with ${(only.result.coverage * 100).toInt()}% of the song: " +
                "shown, as no other upload does")
        }
        if (found == null) {
            log("no map: none of $attemptedCount candidate video(s) lines up with the song" +
                if (searchUnanswered) " (a search went unanswered, so this is not remembered as a song without a video)" else "")
            sync = SyncView(SyncStage.NONE)
            publish()
            return@coroutineScope
        }
        val candidate = found.candidate
        val result = found.result
        env.store.put(id, candidate.videoId, SyncOffset(result.offsetMs, result.confidence, result.segments, result.coverage),
            verifiedSelection = request.videoId == null, video = if (request.videoId == null) chosen(candidate) else null)
        if (request.videoId == null) request.identity?.let { identity ->
            env.store.putSong(identity, SyncStore.SongVideo(id, candidate.videoId, identity.durationSec, candidate.title,
                candidate.channelTitle, candidate.isOfficialMusicVideo, candidate.isVerifiedChannel))
        }
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
    /** A candidate that lines up, how it [score]d against the others, and whether it matches only part of the song (see [verify]). */
    private class Pick(val candidate: VideoCandidate, val result: AlignResult, val score: Double, val partial: Boolean)

    private suspend fun bestOf(batch: List<VideoCandidate>, rankBase: Int, track: AudioStream?, songVideoId: String?): Pick? = coroutineScope {
        val arrivals = Channel<Triple<Int, VideoCandidate, Pair<Measurement?, Boolean?>>>(batch.size)
        batch.forEachIndexed { index, candidate ->
            launch {
                // The preview frames are read while the audio is measured: a small image, ready long before the map.
                val preview = async { if (request.videoId == null) env.previewStill(candidate.videoId) else null }
                // Shared with any other lookup of the same song: a second lookup (the song's stream id changed) takes the
                // measurement the first made, or waits for the one it is making, instead of measuring the video again beside it.
                val measured = env.measurements.measure(MeasurementShare.key(request.identity, request.song?.cacheId, candidate.videoId)) {
                    measureOne(track, candidate.videoId, songVideoId)
                }
                arrivals.send(Triple(rankBase + index, candidate, measured to preview.await()))
            }
        }

        var winner: Pick? = null
        // Until a usable map exists the batch has this long, and on a device that stalls (long garbage-collection pauses) the
        // stalls are given back, up to as long again; after a usable map, the short grace below.
        val deadline = BatchDeadline(BATCH_TIMEOUT_MS, maxExtraMs = BATCH_TIMEOUT_MS)
        var arrived = 0
        while (arrived < batch.size) {
            val left = deadline.remaining()
            if (left <= 0L) break
            val slice = minOf(left, BatchDeadline.SLICE_MS)
            val asked = System.currentTimeMillis()
            val next = withTimeoutOrNull(slice) { arrivals.receive() }
            if (next == null) {
                deadline.waited(slice, System.currentTimeMillis() - asked)
                continue
            }
            val (rank, candidate, both) = next
            val (measured, previewStill) = both
            arrived++
            val result = measured?.align
            // The preview frames decide when they could be read: the frame sizes of the file cannot tell a full-HD poster with an
            // animated intro from a video, nor a re-upload with even keyframes from a poster. Without them, the sizes decide.
            val still = measured != null && (previewStill ?:
                (measured.videoKbps in 0.01..STILL_PICTURE_KBPS || measured.videoMotion < STILL_PICTURE_MOTION ||
                    (request.videoId == null && measured.keyIrregularity?.let { it < 0.01 } == true &&
                        (!VideoMatcher.advertisesVideo(candidate.title) ||
                            !candidate.isVerifiedChannel && !candidate.isOfficialMusicVideo && !candidate.linkedVerifiedPublisher) &&
                        !VideoMatcher.isCreditedArtistChannel(candidate, request.artist))))
            // Lining up is not enough: a reaction plays the song inside it. An automatic choice must also look like the song's
            // video (no talk-over words, no long stretches of something else, about the song's length).
            val songSec = request.durationSec.takeIf { it > 0L } ?: ((track?.durationMs ?: 0L) / 1_000L)
            val wrong = if (request.videoId == null && result != null && !still && result.error == null && result.segments.isNotEmpty())
                VideoMatcher.alignedButWrong(candidate, request.title, request.artist, songSec, result.segments, result.coverage, request.album) else null
            log("candidate ${candidate.videoId} \"${candidate.title.take(48)}\" by ${candidate.channelTitle} (${candidate.durationSec}s): " +
                when {
                    result == null -> "not readable"
                    still && previewStill == true -> "a still picture (its preview frames do not change), not a music video"
                    still -> "a still picture (${measured.videoKbps.toInt()} kbit/s, motion ${"%.2f".format(measured.videoMotion)}), not a music video"
                    result.error != null -> result.error
                    wrong != null -> "confidence ${"%.2f".format(result.confidence)}, but $wrong"
                    else -> "confidence ${"%.2f".format(result.confidence)}, ${result.segments.size} segment(s), ${(result.coverage * 100).toInt()}% of the song" +
                        if (candidate.filmChannel) ", from the film's channel" else ""
                } + (measured?.let { m -> if (m.videoKbps > 0.0) "; picture ${m.videoKbps.toInt()} kbit/s, motion ${"%.2f".format(m.videoMotion)}" else "; no picture measured" } ?: "") +
                    when (previewStill) { true -> "; preview frames: still"; false -> "; preview frames: moving"; null -> "; preview frames: not read" })
            if (result != null && !still && wrong == null && result.error == null && result.confidence >= YouTubeStreamResolver.MIN_SYNC_CONFIDENCE && result.segments.isNotEmpty()) {
                // The ranking is the main evidence, the match quality settles close calls: a version of the
                // video that is the same recording as the song lines up cleanly, a different cut needs an
                // edit-heavy map; a lower rank costs a little.
                // Someone else's upload that matches less of the song than a publisher's must (a fan's re-upload cut shorter,
                // starting a verse in) loses to any that matches more; the film's own channel counts for more than a title.
                val partial = request.videoId == null && result.coverage < VideoMatcher.PUBLISHER_COVERAGE &&
                    !VideoMatcher.hasPublisherEvidence(candidate, request.title, request.artist, request.album)
                val score = result.confidence - RANK_PENALTY * rank +
                    (if (VideoMatcher.saysVideo(candidate.title)) SAYS_VIDEO_BONUS else 0.0) +
                    (if (candidate.filmChannel) FILM_CHANNEL_BONUS else 0.0) - (if (partial) PARTIAL_PENALTY else 0.0)
                // A partial map does not shorten the wait: the rest of the batch may still match the whole song.
                if (!partial && winner.let { it == null || it.partial }) deadline.restart(OTHER_CANDIDATES_GRACE_MS)
                if (winner.let { it == null || score > it.score }) winner = Pick(candidate, result, score, partial)
                if (rank == 0 && !partial && result.confidence >= CONFIDENT_FIRST_CHOICE) break // no need to wait for the others
            }
        }
        coroutineContext.job.cancelChildren()
        winner
    }

    private suspend fun measureOne(track: AudioStream?, video: String, songVideoId: String?): Measurement? {
        val picture = env.syncSource(video)
        if (track == null || picture == null) return null
        val first = measure(track, picture, video)
        var best = first
        if (best?.isAligned() == true) return best
        // Frame sizes depend on the encoder as well as the recording. A readable but weak match
        // deserves the same bounded second-client check as an unreadable header. Keep the existing
        // confidence threshold; never turn a weak match into a verified map by lowering it.
        if (songVideoId == null) return best
        val songs = env.syncSourcesByBackend(songVideoId)
        val pictures = env.syncSourcesByBackend(video)
        for (index in songs.indices) {
            val song = songs[index] ?: continue
            val other = pictures.getOrNull(index) ?: continue
            if (song.url == track.url && other.url == picture.url) continue // the pair already tried
            measure(song, other, video)?.let { alternative ->
                // An audio-only alternative supplies timing, not evidence that the picture moves.
                // Retain the original muxed stream's picture measurements when it has them.
                val measured = if (alternative.videoKbps <= 0.0 && first != null && first.videoKbps > 0.0)
                    Measurement(alternative.align, first.videoKbps, first.videoMotion, first.keyIrregularity)
                else alternative
                if (measured.isAligned()) {
                    log("measuring $video: the other client's encoding produced a verified map")
                    return measured
                }
                if (best == null || measured.align.confidence > best!!.align.confidence) best = measured
            }
        }
        return best
    }

    private suspend fun measure(song: AudioStream, picture: AudioStream, video: String): Measurement? = try {
        env.measurer.measure(song, picture)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        log("measuring $video failed: ${failure.javaClass.simpleName}: ${failure.message}")
        null
    }

    private suspend fun songSource(song: SongAudio): AudioStream? = when (song) {
        is SongAudio.YouTube -> env.syncSource(song.videoId)
        is SongAudio.Stream -> song.stream
    }

    /** What [candidate] is, kept beside its map so the map can be re-checked when it is read again. */
    private fun chosen(candidate: VideoCandidate) = SyncStore.ChosenVideo(candidate.title, candidate.channelTitle,
        VideoMatcher.hasPublisherEvidence(candidate, request.title, request.artist, request.album))

    private fun candidate(videoId: String) = VideoCandidate(videoId, request.title, "", 0L, true)

    private suspend fun candidates(): List<VideoCandidate> {
        if (searchStartedAt == 0L) searchStartedAt = System.currentTimeMillis()
        return searchOutcome(env.search.findOutcome(request.title, request.artist, request.durationSec, album = request.album,
            foreground = priority, budgetMs = SEARCH_BUDGET_MS))
    }

    private fun searchOutcome(outcome: com.opencanvas.core.stream.VideoSearchOutcome): List<VideoCandidate> {
        if (!outcome.complete) searchUnanswered = true
        return outcome.candidates
    }

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
                sourceAspectRatio = if (current.height > 0 && current.width > 0) current.width.toFloat() / current.height else 16f / 9f,
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
        const val MAX_STREAM_AGE_MS = 20 * 60 * 1_000L
        const val ABANDON_GRACE_MS = 4_000L
        const val WARM_HOLD_MS = 30_000L
        const val RANK_PENALTY = 0.03
        /** Between two videos that line up, one that calls itself the video song beats a poster that does not. */
        const val SAYS_VIDEO_BONUS = 0.15
        /** The film's own channel (it shows the film's other songs as well) over an upload that only says it is the video. */
        const val FILM_CHANNEL_BONUS = 0.2
        /** More than any confidence: a map of part of the song loses to every map of most of it. */
        const val PARTIAL_PENALTY = 1.0
        const val OTHER_CANDIDATES_GRACE_MS = 600L
        const val BATCH_SIZE = 3
        /** A batch's wait before any of it lines up, not counting the device's stalls ([BatchDeadline]). */
        const val BATCH_TIMEOUT_MS = 12_000L
        /** All of the playing song's searches together, first and expanded. */
        const val SEARCH_BUDGET_MS = 15_000L
        /** The expanded search gets at least this, enough for one query and its retry, however long the first took. */
        const val EXPANDED_MIN_BUDGET_MS = 8_000L
        /** Below this the picture is a still image (measured on art-track uploads: 7-27 kbit/s); music videos start well above (slow ones near 80). */
        const val STILL_PICTURE_KBPS = 40.0
        const val STILL_PICTURE_MOTION = 0.12
        const val CONFIDENT_FIRST_CHOICE = 0.85
    }
}
