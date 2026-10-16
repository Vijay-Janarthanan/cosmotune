package com.cosmotune.video.player.controller

import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.LocalBundle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.SegmentDecider
import com.cosmotune.video.player.SegmentDecision
import com.cosmotune.video.player.Selection
import com.cosmotune.video.player.StreamChooser
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.jvm.Synchronized

/**
 * The player controller (plan task Y-10; spec 6.1): what the watch page, the chrome, the mini capsule and the Shorts pager
 * drive. It opens a video (details cached for 10 minutes, the stream choice, the resume position, the request to [engine]),
 * refreshes the streams when the engine reports an expired address (at most twice in five minutes, then "This video stopped
 * loading"), writes history every 10 s of playing and on pause, stop and leaving, keeps the queue and autoplay, applies the
 * SponsorBlock policy at a 250 ms tick, goes audio-only or pauses when the UI leaves, plays Shorts (loop, no resume, no
 * autoplay, the mute kept) and plays downloaded files without a source call. It is pure coroutines: no platform class, no
 * network of its own; [source], [engine], [library], [settings], [segments], [chooser] and [decider] are interfaces.
 *
 * State. [state] is rebuilt from the engine's snapshot flow and the controller's own choices on events only (a snapshot, a
 * call, a finished fetch); the playhead is never in it, [positionMs] reads the engine. `queue` in the state is what plays
 * after the current video; the played ones are kept inside for Previous.
 *
 * What screens must know of the state. `queue` is the items after the current video (not the whole list); Previous works from
 * a trail kept inside. When a video ends and nothing is next (no loop, no queue, no autoplay pick) the state stays `ENDED` so
 * a page can offer "Replay", and the engine is unloaded (`VideoPlayerEngine.unload`); play on an ENDED video, or a seek in
 * it, loads it again. `stop()` pauses, unloads and returns to Idle. A Short that was paused because the UI left stays paused
 * until the pager plays it again. A downloaded video (`playLocal`) makes no source call: its `details` is a stand-in with the
 * bundle's chapters and subtitles, its `segments` are the bundle's.
 *
 * Threads. The calls come from the UI thread and the coroutines run on [scope] (any dispatcher): every entry point takes the
 * controller's own lock, and a suspending fetch never holds it. The lock is held across calls into [engine], [library] and
 * [settings], so an engine must not block on the thread of a caller of the controller from inside a method (the engines
 * post to their own thread, spec 6.2, 6.3).
 *
 * Time. [clock] is milliseconds since the epoch (stream addresses carry an expiry in epoch time); it also times the details
 * cache, the refresh budget, history and the frame-drop window, so virtual time drives all of them in tests.
 */
class VideoPlayerController(
    private val source: VideoSource,
    override val engine: VideoPlayerEngine,
    private val library: VideoLibrary,
    private val settings: VideoSettingsRepository,
    private val segments: SegmentProvider,
    private val chooser: StreamChooser,
    private val decider: SegmentDecider,
    private val metered: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : VideoPlayback {

    /** Details with the moment they were fetched, for the 10 minute cache. */
    private class Cached(val details: VideoDetails, val atMs: Long)

    /** Everything about the video being played; replaced as a whole by every new `play`. Guarded by the controller's lock. */
    private class Load(
        val videoId: String,
        val short: Boolean,
        val local: LocalBundle?,
        var speed: Float,
        var loop: Boolean,
        val budget: RefreshBudget,
    ) {
        /** What the UI can show before the details arrive: a queue item, a cached item, or the downloaded item. */
        var knownItem: VideoItem? = null
        var item: VideoItem? = null
        var details: VideoDetails? = null
        var streams: StreamSet? = null
        var selection: Selection? = null
        var live = false

        /** The rung the user picked, or null for Auto. */
        var manual: QualityOption? = null

        /** Auto's ceiling after it stepped down for dropped frames. */
        var autoCap: QualityOption? = null
        var audioTrackId: String? = null
        var audioTracks: List<AudioStream> = emptyList()
        var subtitleLanguage: String? = null
        var segments: List<SkipSegment> = emptyList()
        var pendingSkip: SkipSegment? = null
        var lastSkip: SkipSegment? = null
        var mutedSegment: SkipSegment? = null

        /** SponsorBlock UUIDs already skipped or undone in this load, and the ones the user seeked into (spec 7.1). */
        val handled = HashSet<String>()
        val seekedInto = HashSet<String>()

        var error: VideoUiError? = null

        /** True when [error] came from the engine, so a later healthy snapshot clears it. */
        var errorFromEngine = false

        /** The video ended with nothing next and the engine was unloaded: the state stays ENDED until it is played again. */
        var endedUnloaded = false

        /** The duration the engine reported when it ended, kept for the state of an unloaded ended video. */
        var endedDurationMs = 0L

        /** Bumped by every reload, so a slow fetch of an older one is dropped. */
        var opSeq = 0
        var recordedPositionMs = -1L
        var recordedDurationMs = -1L
    }

    private val flow = MutableStateFlow(PlaybackUiState.Idle)
    override val state: StateFlow<PlaybackUiState> = flow.asStateFlow()

    // Everything below is guarded by this controller's lock.
    private var load: Load? = null
    private var engineLoaded = false
    private var lastSnap: PlayerSnapshot? = null
    private var endHandled = false
    private var queueState = QueueState()
    private var autoplay: Boolean = settings.settings.value.autoplay
    private var userMuted = false
    private var uiVisible = true
    private var opJob: Job? = null
    private var segmentsJob: Job? = null
    private var tickerJob: Job? = null
    private var skipClearJob: Job? = null
    private val cache = LinkedHashMap<String, Cached>()
    private val inFlight = HashMap<String, Deferred<Result<VideoDetails>>>()
    private val historyTicker = HistoryTicker(clock = clock)
    private val frameDrops = FrameDropWatch(clock = clock)

    init {
        publish()
        scope.launch { engine.snapshot.collect { onSnapshot(it) } }
        scope.launch { settings.settings.collect { onSettings(it) } }
    }

    override fun positionMs(): Long = engine.positionMs()

    // ------------------------------------------------------------------ opening a video

    @Synchronized
    override fun play(videoId: String, startMs: Long?, queue: List<VideoItem>, short: Boolean) {
        val current = load
        if (current != null && current.videoId == videoId && current.local == null && current.short == short &&
            current.error == null && !hasEnded(current)
        ) {
            // The video is already on (a card of the playing video was tapped): keep it going; honour a start and a queue.
            if (queue.isNotEmpty() && !short) {
                queueState = QueueState.playAll(queue, videoId)
                publish()
            }
            if (startMs != null) seekTo(startMs)
            return
        }
        recordNow()
        if (!short) {
            queueState = when {
                queue.isNotEmpty() -> QueueState.playAll(queue, videoId)
                current != null && !current.short && current.videoId != videoId -> queueState.withPlayed(current.item ?: current.knownItem)
                else -> queueState
            }
        }
        begin(videoId, startMs, short, local = null, known = null)
    }

    @Synchronized
    override fun playLocal(item: VideoItem, bundle: LocalBundle, startMs: Long?) {
        val current = load
        if (current != null && current.videoId == item.id && current.local != null && current.error == null && !hasEnded(current)) {
            if (startMs != null) seekTo(startMs)
            return
        }
        recordNow()
        if (current != null && !current.short && current.videoId != item.id) {
            queueState = queueState.withPlayed(current.item ?: current.knownItem)
        }
        begin(item.id, startMs, short = false, local = bundle, known = item)
    }

    @Synchronized
    override fun prefetch(videoId: String) {
        if (cachedDetails(videoId) != null) return
        scope.launch {
            try {
                detailsFor(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A prefetch never shows an error: the play that follows asks again and reports it.
            }
        }
    }

    /** Starts a new load: records nothing (callers did), pauses what was playing, and fetches (remote) or loads (local). */
    @Synchronized
    private fun begin(videoId: String, explicitStartMs: Long?, short: Boolean, local: LocalBundle?, known: VideoItem?) {
        val s = settings.settings.value
        val l = Load(videoId, short, local, s.defaultSpeed, loop = short, budget = RefreshBudget(clock = clock))
        l.knownItem = known ?: queueState.upNext.firstOrNull { it.id == videoId } ?: cache[videoId]?.details?.item
        stopWork()
        if (engineLoaded) {
            engineLoaded = false
            engine.pause()
        }
        lastSnap = null
        endHandled = false
        load = l
        publish()
        if (local != null && known != null) {
            applyLocal(l, explicitStartMs, known, local)
        } else {
            opJob = scope.launch { startRemote(l, explicitStartMs) }
        }
    }

    private suspend fun startRemote(l: Load, explicitStartMs: Long?) {
        val details = try {
            detailsFor(l.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failLoad(l, ErrorMapping.map(e))
            return
        }
        var streams = details.streams
        if (streams.isExpired(clock())) {
            // The cached set is about to stop working: a fresh one before the first request fails (spec 6.1).
            streams = try {
                source.streams(l.videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failLoad(l, ErrorMapping.map(e))
                return
            }
        }
        applyRemote(l, details, streams, explicitStartMs)
    }

    @Synchronized
    private fun failLoad(l: Load, error: VideoUiError) {
        if (load !== l) return
        l.error = error
        l.errorFromEngine = false
        engineLoaded = false
        publish()
    }

    @Synchronized
    private fun applyRemote(l: Load, details: VideoDetails, streams: StreamSet, explicitStartMs: Long?) {
        if (load !== l) return
        val s = settings.settings.value
        val item = if (l.short && !details.item.isShort) details.item.copy(isShort = true) else details.item
        l.item = item
        l.details = details
        l.streams = streams
        l.live = details.kind == StreamKind.LIVE
        l.audioTracks = streams.audio.distinctBy { it.trackId }
        l.subtitleLanguage = pickSubtitle(details.subtitles, s.captionsLanguage)
        if (streams !== details.streams) refreshCachedStreams(l.videoId, streams)
        val start = startPosition(item, explicitStartMs, l.short, l.live, s)
        val selection = choose(l, streams, pinned = null, s)
        l.selection = selection
        doLoad(l, buildRequest(l, item, selection.input, start), play = true)
        startSegments(l, s)
    }

    /** A download: no source call. The bundle's chapters, subtitles and segments stand in for the details and the SponsorBlock fetch. */
    @Synchronized
    private fun applyLocal(l: Load, explicitStartMs: Long?, item: VideoItem, bundle: LocalBundle) {
        if (load !== l) return
        val s = settings.settings.value
        val tracks = bundle.subtitles.map { SubtitleTrack(it.path, "text/vtt", it.language, it.label, it.autoGenerated) }
        val enabled = enabledCategories(s)
        l.item = item
        l.live = false
        l.details = offlineDetails(item, bundle, tracks)
        l.segments = bundle.segments.filter { it.category in enabled }
        l.subtitleLanguage = pickSubtitle(tracks, s.captionsLanguage)
        val start = startPosition(item, explicitStartMs, short = false, live = false, s)
        doLoad(l, buildRequest(l, item, bundle.files, start), play = true)
    }

    /** What the watch page gets for a download: the item, the chapters and the subtitles; no description, related, comments or streams. */
    private fun offlineDetails(item: VideoItem, bundle: LocalBundle, tracks: List<SubtitleTrack>) = VideoDetails(
        item = item,
        description = Description("", emptyList()),
        likeCount = null,
        category = null,
        tags = emptyList(),
        chapters = bundle.chapters,
        related = emptyList(),
        subtitles = tracks,
        storyboard = null,
        kind = StreamKind.VIDEO,
        streams = StreamSet(emptyList(), emptyList(), emptyList(), null, null, null, emptyMap(), null),
        subscriberCount = null,
        fetchedAtMs = clock(),
    )

    /** An explicit start wins; else the history position when resume is on and the entry is not watched (spec 6.1); Shorts and live start at 0. */
    private fun startPosition(item: VideoItem, explicitStartMs: Long?, short: Boolean, live: Boolean, s: VideoSettings): Long {
        if (explicitStartMs != null) return explicitStartMs.coerceAtLeast(0)
        if (short || live || !s.resume) return 0
        val stored = library.positionOf(item.id) ?: return 0
        val entry = library.history.value.firstOrNull { it.item.id == item.id }
        if (entry != null && entry.isWatched) return 0
        return stored.coerceAtLeast(0)
    }

    private fun choose(l: Load, streams: StreamSet, pinned: QualityOption?, s: VideoSettings): Selection = chooser.choose(
        streams = streams,
        caps = engine.capabilities,
        settings = s,
        metered = metered(),
        manual = l.manual ?: pinned ?: l.autoCap,
        audioTrackId = l.audioTrackId,
        bandwidthKbps = engine.snapshot.value.bandwidthKbps,
        durationMs = streams.durationMs ?: knownDurationMs(l),
    )

    private fun buildRequest(l: Load, item: VideoItem, input: MediaInput, startMs: Long) = PlaybackRequest(
        videoId = item.id,
        input = input,
        subtitles = if (l.local != null) emptyList() else l.details?.subtitles.orEmpty(),
        localSubtitles = l.local?.subtitles.orEmpty(),
        headers = l.streams?.requestHeaders.orEmpty(),
        startMs = startMs,
        live = l.live,
        title = item.title,
        channel = item.channelName,
        thumbnailUrl = item.thumbnailUrl,
    )

    /**
     * Hands [request] to the engine and starts it (unless the UI left and the settings do not allow background play), then
     * takes the engine's snapshot as the new truth. The speed, the mute, audio-only and the subtitles are applied again
     * because a new media item can reset them.
     */
    @Synchronized
    private fun doLoad(l: Load, request: PlaybackRequest, play: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        lastSnap = null
        endHandled = false
        engineLoaded = true
        l.error = null
        l.errorFromEngine = false
        val s = settings.settings.value
        engine.load(request)
        engine.setSpeed(l.speed)
        val loaded = engine.snapshot.value
        val mute = userMuted || l.mutedSegment != null
        if (loaded.muted != mute) engine.setMuted(mute)
        val audioOnly = wantsAudioOnly(l, s)
        if (loaded.audioOnly != audioOnly) engine.setAudioOnly(audioOnly)
        l.subtitleLanguage?.let { engine.selectSubtitle(it) }
        if (play && (uiVisible || audioOnly)) engine.play()
        onSnapshot(engine.snapshot.value)
    }

    private fun wantsAudioOnly(l: Load, s: VideoSettings): Boolean = !uiVisible && s.background && !l.short

    private fun pickSubtitle(tracks: List<SubtitleTrack>, language: String?): String? {
        if (language.isNullOrBlank()) return null
        val wanted = language.substringBefore('-')
        val exact = tracks.filter { it.language.equals(language, ignoreCase = true) }
        val candidates = exact.ifEmpty { tracks.filter { it.language.substringBefore('-').equals(wanted, ignoreCase = true) } }
        return (candidates.firstOrNull { !it.autoGenerated } ?: candidates.firstOrNull())?.language
    }

    // ------------------------------------------------------------------ the details cache

    @Synchronized
    private fun cachedDetails(id: String): VideoDetails? {
        val cached = cache[id] ?: return null
        if (clock() - cached.atMs >= DETAILS_TTL_MS) {
            cache.remove(id)
            return null
        }
        return cached.details
    }

    /** The details of [id]: from the cache, or from the source once however many callers ask at the same time. */
    private suspend fun detailsFor(id: String): VideoDetails {
        cachedDetails(id)?.let { return it }
        return fetching(id).await().getOrThrow()
    }

    @Synchronized
    private fun fetching(id: String): Deferred<Result<VideoDetails>> {
        inFlight[id]?.let { return it }
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            val result = try {
                Result.success(source.details(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            finishFetch(id, result)
            result
        }
        inFlight[id] = deferred
        deferred.start()
        return deferred
    }

    @Synchronized
    private fun finishFetch(id: String, result: Result<VideoDetails>) {
        inFlight.remove(id)
        val details = result.getOrNull() ?: return
        cache[id] = Cached(details, clock())
        while (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
    }

    private fun refreshCachedStreams(id: String, streams: StreamSet) {
        val cached = cache[id] ?: return
        cache[id] = Cached(cached.details.copy(streams = streams), cached.atMs)
    }

    // ------------------------------------------------------------------ the engine's snapshot

    @Synchronized
    private fun onSnapshot(snap: PlayerSnapshot) {
        if (!engineLoaded) return
        val l = load ?: return
        val previous = lastSnap
        lastSnap = snap
        val wasPlaying = previous?.playing == true
        if (snap.playing && !wasPlaying) playStarted(l) else if (!snap.playing && wasPlaying) playStopped()

        if (snap.status == PlayerStatus.ERROR) {
            val error = snap.error
            if (previous?.status != PlayerStatus.ERROR || previous.error != error) onEngineError(l, error?.kind ?: PlayerErrorKind.OTHER)
        } else if (l.errorFromEngine) {
            l.error = null
            l.errorFromEngine = false
        }

        if (snap.status == PlayerStatus.ENDED) {
            if (!endHandled) {
                endHandled = true
                onEnded(l)
            }
        } else {
            endHandled = false
        }
        publish()
    }

    @Synchronized
    private fun onSettings(s: VideoSettings) {
        autoplay = s.autoplay
        publish()
    }

    private fun playStarted(l: Load) {
        historyTicker.restart()
        frameDrops.reset(lastSnap?.droppedFrames ?: 0)
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(l)
            }
        }
    }

    private fun playStopped() {
        recordNow()
        tickerJob?.cancel()
        tickerJob = null
    }

    /** An error of the engine. An expired address is mended with fresh streams while the budget lasts (spec 6.1). */
    private fun onEngineError(l: Load, kind: PlayerErrorKind) {
        if (kind == PlayerErrorKind.SOURCE_EXPIRED && l.local == null && l.budget.tryUse()) {
            val position = engine.positionMs()
            engineLoaded = false
            tickerJob?.cancel()
            tickerJob = null
            l.error = null
            l.errorFromEngine = false
            refetchAndReload(l, position, play = true, pinned = l.selection?.option)
            return
        }
        l.error = ErrorMapping.map(kind)
        l.errorFromEngine = true
    }

    /**
     * The video ended (spec 6.1): loop; else a Short waits for its pager; else the queue's next; else autoplay's pick; else
     * nothing is next: the state stays ENDED and the engine is unloaded, so no session or notification outlives the video.
     */
    private fun onEnded(l: Load) {
        recordNow()
        if (l.loop) {
            l.handled.clear()
            l.seekedInto.clear()
            engine.seekTo(0)
            engine.play()
            return
        }
        if (l.short) return
        val step = queueState.next(l.item)
        if (step != null) {
            queueState = step.queue
            begin(step.item.id, null, short = false, local = null, known = step.item)
            return
        }
        if (autoplay && l.local == null) {
            val pick = queueState.autoplayPick(l.item, l.details?.related.orEmpty(), library.history.value)
            if (pick != null) {
                queueState = queueState.withPlayed(l.item)
                begin(pick.id, null, short = false, local = null, known = pick)
                return
            }
        }
        l.endedUnloaded = true
        l.endedDurationMs = durationNow(l)
        engineLoaded = false
        lastSnap = null
        tickerJob?.cancel()
        tickerJob = null
        engine.unload()
    }

    // ------------------------------------------------------------------ the tick: history, segments, dropped frames

    @Synchronized
    private fun tick(l: Load) {
        if (load !== l || !engineLoaded) return
        val snap = lastSnap ?: return
        if (!snap.playing) return
        if (historyTicker.poll()) recordNow()
        evaluateSegments(l, engine.positionMs())
        if (load === l) checkFrameDrops(l, engine.snapshot.value)
    }

    /** Writes the position to history, unless history is paused, the stream is live, or the engine has no valid position yet (spec 6.1). */
    private fun recordNow() {
        val l = load ?: return
        val item = l.item ?: return
        if (l.live || !engineLoaded || library.historyPaused.value) return
        val snap = lastSnap ?: return
        val position = engine.positionMs()
        val valid = when (snap.status) {
            PlayerStatus.READY, PlayerStatus.ENDED -> true
            PlayerStatus.BUFFERING, PlayerStatus.ERROR -> position > 0
            PlayerStatus.IDLE, PlayerStatus.LOADING -> false
        }
        if (!valid) return
        val duration = durationNow(l)
        if (l.recordedPositionMs == position && l.recordedDurationMs == duration) return
        l.recordedPositionMs = position
        l.recordedDurationMs = duration
        library.recordWatch(item, position, duration)
    }

    private fun durationNow(l: Load): Long = lastSnap?.durationMs?.takeIf { it > 0 } ?: knownDurationMs(l)

    private fun knownDurationMs(l: Load): Long {
        if (l.live) return 0
        val fromStreams = l.streams?.durationMs
        if (fromStreams != null && fromStreams > 0) return fromStreams
        val seconds = (l.item ?: l.knownItem)?.durationSec
        return if (seconds != null && seconds > 0) seconds * 1000 else 0
    }

    private fun enabledCategories(s: VideoSettings): Set<SegmentCategory> =
        SegmentCategory.entries.filter { (s.segmentBehaviours[it] ?: it.defaultBehaviour) != SegmentBehaviour.OFF }.toSet()

    private fun startSegments(l: Load, s: VideoSettings) {
        if (!s.sponsorBlock) return
        val categories = enabledCategories(s)
        if (categories.isEmpty()) return
        segmentsJob?.cancel()
        segmentsJob = scope.launch {
            val found = try {
                segments.segments(l.videoId, categories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()   // offline, rate-limited, or down: no segments, and playback does not depend on them
            }
            applySegments(l, found)
        }
    }

    @Synchronized
    private fun applySegments(l: Load, found: List<SkipSegment>) {
        if (load !== l) return
        l.segments = found
        publish()
    }

    /** Applies the SponsorBlock policy at [positionMs] (spec 6.1, 7.1). Publishes when something the UI shows changed. */
    private fun evaluateSegments(l: Load, positionMs: Long) {
        val s = settings.settings.value
        var changed = false
        if (!s.sponsorBlock || l.segments.isEmpty()) {
            if (l.mutedSegment != null) {
                endMute(l)
                changed = true
            }
            if (l.pendingSkip != null) {
                l.pendingSkip = null
                changed = true
            }
            if (changed) publish()
            return
        }
        val decision = decider.decide(positionMs, l.segments, s, l.handled, l.seekedInto)
        val muting = l.mutedSegment
        if (muting != null && (positionMs < muting.startMs || positionMs >= muting.endMs) &&
            !(decision is SegmentDecision.Mute && decision.segment.uuid == muting.uuid)
        ) {
            // The position left the muted segment, whatever the policy says about it (a seek, or its end).
            endMute(l)
            changed = true
        }
        when (decision) {
            SegmentDecision.None, SegmentDecision.Unmute -> {
                if (decision == SegmentDecision.Unmute && l.mutedSegment != null) {
                    endMute(l)
                    changed = true
                }
                if (l.pendingSkip != null) {
                    l.pendingSkip = null
                    changed = true
                }
            }
            is SegmentDecision.SkipTo -> {
                l.handled += decision.segment.uuid
                l.pendingSkip = null
                engine.seekTo(decision.ms)
                setLastSkip(l, decision.segment)
                changed = true
            }
            is SegmentDecision.Mute -> {
                if (l.mutedSegment?.uuid != decision.segment.uuid) {
                    l.mutedSegment = decision.segment
                    engine.setMuted(true)
                    changed = true
                }
                if (l.pendingSkip != null) {
                    l.pendingSkip = null
                    changed = true
                }
            }
            is SegmentDecision.Ask -> if (l.pendingSkip?.uuid != decision.segment.uuid) {
                l.pendingSkip = decision.segment
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun endMute(l: Load) {
        l.mutedSegment = null
        engine.setMuted(userMuted)
    }

    private fun setLastSkip(l: Load, segment: SkipSegment) {
        l.lastSkip = segment
        skipClearJob?.cancel()
        skipClearJob = scope.launch {
            delay(LAST_SKIP_MS)
            clearLastSkip(l, segment)
        }
    }

    @Synchronized
    private fun clearLastSkip(l: Load, segment: SkipSegment) {
        if (load !== l || l.lastSkip !== segment) return
        l.lastSkip = null
        publish()
    }

    /** Auto steps down one rung when more than 10 % of the frames dropped over 10 s (spec 6.3); a rung the user picked stays. */
    private fun checkFrameDrops(l: Load, snap: PlayerSnapshot) {
        if (l.manual != null || l.local != null || l.live) return
        val selection = l.selection ?: return
        val option = selection.option ?: return
        if (!frameDrops.check(snap.droppedFrames, option.fps, l.speed)) return
        val ladder = selection.ladder
        val at = ladder.indexOfFirst { it.height == option.height && it.fps == option.fps && it.codec == option.codec && it.hdr == option.hdr }
        if (at < 0 || at + 1 >= ladder.size) return
        l.autoCap = ladder[at + 1]
        requestReload(l)
    }

    // ------------------------------------------------------------------ reloading: quality, audio track, refresh

    @Synchronized
    override fun selectQuality(option: QualityOption?) {
        val l = load ?: return
        if (l.local != null || l.live) return
        l.manual = option
        if (option == null) l.autoCap = null
        requestReload(l)
    }

    @Synchronized
    override fun selectAudioTrack(trackId: String?) {
        val l = load ?: return
        if (l.local != null) return
        l.audioTrackId = trackId
        requestReload(l)
    }

    /**
     * Loads the video again from the streams in hand, at the current position, playing if it was playing. While a load or a
     * refresh is still in flight there is nothing to reload: it chooses with what [Load.manual] and [Load.audioTrackId] say
     * when it lands.
     */
    private fun requestReload(l: Load) {
        if (!engineLoaded) return
        val streams = l.streams ?: return
        val position = engine.positionMs()
        val playing = lastSnap?.playing == true
        if (streams.isExpired(clock())) refetchAndReload(l, position, playing, pinned = null)
        else reloadWith(l, streams, position, playing, pinned = null)
    }

    private fun refetchAndReload(l: Load, startMs: Long, play: Boolean, pinned: QualityOption?) {
        val seq = ++l.opSeq
        opJob?.cancel()
        opJob = scope.launch {
            val fresh = try {
                source.streams(l.videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reloadFailed(l, seq, e)
                return@launch
            }
            applyFresh(l, seq, fresh, startMs, play, pinned)
        }
    }

    @Synchronized
    private fun applyFresh(l: Load, seq: Int, fresh: StreamSet, startMs: Long, play: Boolean, pinned: QualityOption?) {
        if (load !== l || l.opSeq != seq) return
        reloadWith(l, fresh, startMs, play, pinned)
    }

    @Synchronized
    private fun reloadFailed(l: Load, seq: Int, error: Throwable) {
        if (load !== l || l.opSeq != seq) return
        if (engineLoaded) return          // a quality change that could not fetch: the picture that plays keeps playing
        l.error = ErrorMapping.map(error)
        l.errorFromEngine = false
        publish()
    }

    private fun reloadWith(l: Load, streams: StreamSet, startMs: Long, play: Boolean, pinned: QualityOption?) {
        val item = l.item ?: return
        l.opSeq++
        l.streams = streams
        l.audioTracks = streams.audio.distinctBy { it.trackId }
        refreshCachedStreams(l.videoId, streams)
        val selection = choose(l, streams, pinned, settings.settings.value)
        l.selection = selection
        doLoad(l, buildRequest(l, item, selection.input, startMs), play)
    }

    // ------------------------------------------------------------------ transport

    @Synchronized
    override fun togglePlayPause() {
        val l = load ?: return
        if (l.error != null) {
            retry(l)
            return
        }
        if (l.endedUnloaded) {
            replay(l, 0)
            return
        }
        if (!engineLoaded) return
        val snap = lastSnap ?: engine.snapshot.value
        when {
            snap.status == PlayerStatus.ENDED -> {
                l.handled.clear()
                l.seekedInto.clear()
                endHandled = false
                engine.seekTo(0)
                engine.play()
            }
            snap.playing -> engine.pause()
            else -> engine.play()
        }
    }

    /** "Try again": the same video from where it stopped, with fresh details (the cached streams may be what failed). */
    private fun retry(l: Load) {
        val position = if (engineLoaded) engine.positionMs().takeIf { it > 0 } else null
        val item = l.item ?: l.knownItem
        val files = l.local
        if (files != null && item != null) {
            begin(l.videoId, position, short = false, local = files, known = item)
        } else {
            cache.remove(l.videoId)
            begin(l.videoId, position, l.short, local = null, known = item)
        }
    }

    /** An ended video, unloaded, plays again from [startMs] (a Replay, or a drag back on its scrubber). */
    private fun replay(l: Load, startMs: Long) {
        val item = l.item ?: l.knownItem
        begin(l.videoId, startMs, l.short, local = l.local, known = item)
    }

    @Synchronized
    override fun seekTo(ms: Long) {
        val l = load ?: return
        if (l.endedUnloaded) {
            val duration = l.endedDurationMs
            replay(l, if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0))
            return
        }
        if (!engineLoaded) return
        val duration = durationNow(l)
        val target = if (!l.live && duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        engine.seekTo(target)
        for (segment in l.segments) {
            if (target >= segment.startMs && target < segment.endMs) l.seekedInto += segment.uuid
        }
        evaluateSegments(l, target)
        publish()
    }

    @Synchronized
    override fun seekBy(deltaMs: Long) {
        val l = load ?: return
        when {
            l.endedUnloaded -> seekTo(l.endedDurationMs + deltaMs)
            engineLoaded -> seekTo(engine.positionMs() + deltaMs)
        }
    }

    @Synchronized
    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        load?.speed = clamped
        engine.setSpeed(clamped)
        publish()
    }

    @Synchronized
    override fun setMuted(muted: Boolean) {
        userMuted = muted
        engine.setMuted(muted || load?.mutedSegment != null)
        publish()
    }

    @Synchronized
    override fun selectSubtitles(language: String?) {
        load?.subtitleLanguage = language
        engine.selectSubtitle(language)
        publish()
    }

    @Synchronized
    override fun setLoop(loop: Boolean) {
        load?.loop = loop
        publish()
    }

    @Synchronized
    override fun setAutoplay(on: Boolean) {
        autoplay = on
        settings.update { it.copy(autoplay = on) }
        publish()
    }

    // ------------------------------------------------------------------ queue

    @Synchronized
    override fun addToQueue(item: VideoItem) {
        queueState = queueState.add(item)
        publish()
    }

    @Synchronized
    override fun playNext() {
        val l = load
        if (l?.short == true) return          // the Shorts pager moves on by itself
        val current = l?.item ?: l?.knownItem
        val step = queueState.next(current)
        if (step != null) {
            recordNow()
            queueState = step.queue
            begin(step.item.id, null, short = false, local = null, known = step.item)
            return
        }
        // Nothing queued: an explicit Next plays what autoplay would, switch or no switch.
        val pick = queueState.autoplayPick(current, l?.details?.related.orEmpty(), library.history.value) ?: return
        recordNow()
        queueState = queueState.withPlayed(current)
        begin(pick.id, null, short = false, local = null, known = pick)
    }

    @Synchronized
    override fun playPrevious() {
        val l = load ?: return
        if (l.short) return
        val step = if (engineLoaded && engine.positionMs() > RESTART_AFTER_MS) null else queueState.previous(l.item ?: l.knownItem)
        if (step == null) {
            seekTo(0)                         // more than 3 s in, or nothing before it: back to the start
            return
        }
        recordNow()
        queueState = step.queue
        begin(step.item.id, null, short = false, local = null, known = step.item)
    }

    // ------------------------------------------------------------------ segments, the UI, stop

    @Synchronized
    override fun skipPending() {
        val l = load ?: return
        val segment = l.pendingSkip ?: return
        if (!engineLoaded) return
        l.handled += segment.uuid
        l.pendingSkip = null
        engine.seekTo(segment.endMs)
        setLastSkip(l, segment)
        publish()
    }

    @Synchronized
    override fun undoSkip() {
        val l = load ?: return
        val segment = l.lastSkip ?: return
        if (!engineLoaded) return
        l.handled += segment.uuid
        l.lastSkip = null
        skipClearJob?.cancel()
        skipClearJob = null
        engine.seekTo(segment.startMs)
        publish()
    }

    @Synchronized
    override fun onUiVisible(visible: Boolean) {
        uiVisible = visible
        val l = load
        if (!visible) {
            recordNow()
            if (l != null && engineLoaded) {
                // A Short does not play on out of sight; the rest go audio-only, or pause when the setting says so (spec 6.1).
                if (l.short || !settings.settings.value.background) engine.pause() else engine.setAudioOnly(true)
            }
        } else if (l != null && engineLoaded) {
            engine.setAudioOnly(false)
        }
        publish()
    }

    @Synchronized
    override fun stop() {
        recordNow()
        stopWork()
        if (engineLoaded) engine.pause()
        engine.unload()
        engineLoaded = false
        lastSnap = null
        load = null
        queueState = QueueState()
        publish()
    }

    private fun stopWork() {
        opJob?.cancel()
        segmentsJob?.cancel()
        tickerJob?.cancel()
        skipClearJob?.cancel()
        opJob = null
        segmentsJob = null
        tickerJob = null
        skipClearJob = null
    }

    private fun hasEnded(l: Load): Boolean = l.endedUnloaded || (engineLoaded && lastSnap?.status == PlayerStatus.ENDED)

    // ------------------------------------------------------------------ the state

    @Synchronized
    private fun publish() {
        flow.value = compute()
    }

    private fun compute(): PlaybackUiState {
        val s = settings.settings.value
        val l = load ?: return PlaybackUiState.Idle.copy(queue = queueState.upNext, autoplay = autoplay, muted = userMuted)
        val snap = if (engineLoaded) lastSnap else null
        val error = l.error
        val status = when {
            error != null -> PlayerStatus.ERROR
            l.endedUnloaded -> PlayerStatus.ENDED
            snap == null -> PlayerStatus.LOADING
            else -> snap.status
        }
        val item = l.item ?: l.knownItem
        val selection = l.selection
        return PlaybackUiState(
            item = item,
            details = l.details,
            status = status,
            error = error,
            playing = error == null && snap?.playing == true,
            durationMs = snap?.durationMs?.takeIf { it > 0 } ?: if (l.endedUnloaded) l.endedDurationMs else knownDurationMs(l),
            bufferedMs = snap?.bufferedMs ?: 0,
            speed = l.speed,
            ladder = selection?.ladder ?: emptyList(),
            quality = selection?.option,
            autoQuality = l.manual == null,
            audioTracks = l.audioTracks,
            audioTrackId = selection?.audio?.trackId,
            subtitles = l.details?.subtitles ?: emptyList(),
            subtitleLanguage = l.subtitleLanguage,
            segments = if (s.sponsorBlock) l.segments else emptyList(),
            pendingSkip = l.pendingSkip,
            lastSkip = l.lastSkip,
            mutedSegment = l.mutedSegment,
            queue = queueState.upNext,
            autoplay = autoplay,
            loop = l.loop,
            live = l.live,
            aspect = aspectOf(l, snap, item),
            audioOnly = wantsAudioOnly(l, s),
            muted = userMuted,
            short = l.short,
        )
    }

    /** The video's own shape once the engine knows it, else the chosen stream's, else 16:9 (9:16 for a Short). */
    private fun aspectOf(l: Load, snap: PlayerSnapshot?, item: VideoItem?): Float {
        val width = snap?.videoWidth ?: 0
        val height = snap?.videoHeight ?: 0
        if (width > 0 && height > 0) return width.toFloat() / height
        val video = l.selection?.video
        if (video != null && video.width > 0 && video.height > 0) return video.width.toFloat() / video.height
        return if (l.short || item?.isShort == true) 9f / 16f else 16f / 9f
    }

    companion object {
        /** The segment ticker (spec 6.1): 250 ms while playing. */
        const val TICK_MS = 250L

        /** The "Skipped" toast stays 4 s (spec 6.5). */
        const val LAST_SKIP_MS = 4_000L

        /** Details are cached 10 minutes (spec 6.1). */
        const val DETAILS_TTL_MS = 10 * 60_000L

        /** At most this many details are kept. */
        const val CACHE_MAX = 24

        /** Previous restarts the video when it is more than 3 s in (as every player does). */
        const val RESTART_AFTER_MS = 3_000L

        /** The speed range of spec 6.5. */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}
