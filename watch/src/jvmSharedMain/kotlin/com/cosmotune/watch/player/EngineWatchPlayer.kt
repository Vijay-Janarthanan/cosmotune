package com.cosmotune.watch.player

import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.player.EngineTrack
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.TrackType
import com.cosmotune.video.player.UrlKind
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.AudioTrack
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [WatchPlayer] on the platform's [VideoPlayerEngine] (Video mode's own engine: Media3 on the phone and TV, libmpv on the
 * desktop). Resolves the target, starts the best copy at the saved position (or [WatchPlayer.open]'s), lists the engine's
 * audio and subtitle tracks, loads external subtitles, fails over to the next copy on an error, saves progress every
 * [saveEveryMs] and when it stops, and moves on to the next episode at the end (with a countdown in the last
 * [countdownMs]). YouTube titles are handed to Video's own player through [onYouTube], which resolves them.
 */
class EngineWatchPlayer(
    private val repository: WatchRepository,
    override val engine: VideoPlayerEngine,
    /** Plays a YouTube video id from a start (Video mode's controller on the same engine). */
    private val onYouTube: (videoId: String, startMs: Long) -> Unit = { _, _ -> },
    /** Stops what Video mode itself plays before Watch loads the engine. */
    private val stopOthers: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
    private val saveEveryMs: Long = 10_000,
    private val countdownMs: Long = 20_000,
    /** How long to wait for more plugins once one copy is found, before starting. */
    private val settleMs: Long = 1_500,
    private val tickMs: Long = 500,
) : WatchPlayer {
    private val mutable = MutableStateFlow(WatchPlayerState())
    override val state: StateFlow<WatchPlayerState> = mutable.asStateFlow()

    private var job: Job? = null
    private var ticker: Job? = null
    private val tried = mutableSetOf<String>()
    private var external = listOf<Subtitle>()
    private val added = mutableSetOf<String>()
    private var lastSavedAt = 0L
    private var countdownCancelled = false
    private var autoNextDone = false

    init {
        scope.launch { engine.tracks.collect { tracks -> mutable.update { it.copy(audioTracks = audioOf(tracks), subtitles = subtitlesOf(tracks)) } } }
    }

    /** The room host's length for what plays (a room item); a copy that differs by more than a minute is noted in the state. */
    @Volatile var roomDurationMs: Long? = null
    private var fallback: Stream? = null

    override fun open(target: PlayTarget, summary: TitleSummary?, stream: Stream?, startMs: Long?, hint: StreamHint?, fallback: Stream?) {
        this.fallback = fallback
        saveNow()
        job?.cancel(); ticker?.cancel()
        tried.clear(); external = emptyList(); added.clear(); countdownCancelled = false; autoNextDone = false
        mutable.value = WatchPlayerState(WatchPlayerStatus.RESOLVING, target, summary, subtitleLine(target), isLive = target is PlayTarget.Live)
        job = scope.launch {
            val saved = if (target is PlayTarget.Live) null else runCatching { repository.progress(target.key) }.getOrNull()
            val start = startMs ?: saved?.takeIf { !it.completed }?.positionMs ?: 0L
            val wanted = hint ?: saved?.lastHint
            if (stream != null) {
                mutable.update { it.copy(streams = listOf(stream)) }
                play(stream, start)
                launch { collectSources(target, wanted, startWith = null) }
            } else collectSources(target, wanted, startWith = start)
            if (target is PlayTarget.EpisodeOf) {
                val next = runCatching { repository.nextEpisode(target.key) }.getOrNull()
                mutable.update { it.copy(nextEpisode = next) }
            }
        }
    }

    /** Fills [WatchPlayerState.streams] as plugins answer; with [startWith], starts the best copy once the list settles. */
    private suspend fun collectSources(target: PlayTarget, hint: StreamHint?, startWith: Long?) {
        var firstAt = 0L
        var started = startWith == null
        repository.sources(target, hint).collect { s ->
            mutable.update { it.copy(streams = s.streams) }
            val best = s.best
            if (!started && best != null) {
                if (firstAt == 0L) firstAt = now()
                if (s.done || now() - firstAt >= settleMs) { started = true; play(best, startWith!!) }
            }
            if (s.done && !started) {
                val found = s.best
                val backup = fallback
                if (found != null) { started = true; play(found, startWith!!) }
                else if (backup != null) { started = true; mutable.update { it.copy(streams = listOf(backup)) }; play(backup, startWith!!) }
                else mutable.update { it.copy(status = WatchPlayerStatus.NOT_FOUND, error = "Not on your sources") }
            }
        }
        // A list that settled while waiting for a slow plugin: start with what there is.
        if (!started) (state.value.streams.firstOrNull { it.isPlayable } ?: fallback)?.let { play(it, startWith!!) }
            ?: mutable.update { if (it.stream == null) it.copy(status = WatchPlayerStatus.NOT_FOUND, error = "Not on your sources") else it }
    }

    private fun play(stream: Stream, startMs: Long) {
        val current = state.value
        val target = current.target ?: return
        tried += stream.url
        lastSavedAt = now()
        mutable.update { it.copy(status = WatchPlayerStatus.LOADING, stream = stream, error = null, durationMs = stream.durationMs ?: 0, isLive = stream.isLive || target is PlayTarget.Live) }
        when (stream.protocol) {
            StreamProtocol.EXTERNAL -> { failover("That source only opens elsewhere"); return }
            StreamProtocol.YOUTUBE -> {
                val id = stream.url.substringAfter("v=").substringBefore('&')
                onYouTube(id, startMs)
            }
            else -> {
                stopOthers()
                engine.load(PlaybackRequest(
                    videoId = target.key.value,
                    input = MediaInput.Url(stream.url, kindOf(stream.protocol), mimeOf(stream)),
                    subtitles = stream.subtitles.filter { it.url != null }.map { SubtitleTrack(it.url!!, mimeOfSubtitle(it), it.language, it.label, false) },
                    headers = stream.headers,
                    startMs = if (stream.isLive) 0 else startMs,
                    live = stream.isLive,
                    title = current.summary?.name ?: (target as? PlayTarget.Live)?.channel?.name ?: "",
                    channel = current.subtitleLine,
                    thumbnailUrl = current.summary?.posterUrl ?: (target as? PlayTarget.Live)?.channel?.logoUrl,
                ))
                engine.play()
            }
        }
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    private fun tick() {
        val snap = engine.snapshot.value
        val s = state.value
        val status = when (snap.status) {
            PlayerStatus.IDLE -> if (s.stream != null) WatchPlayerStatus.LOADING else WatchPlayerStatus.IDLE
            PlayerStatus.LOADING -> WatchPlayerStatus.LOADING
            PlayerStatus.BUFFERING -> WatchPlayerStatus.BUFFERING
            PlayerStatus.READY -> if (snap.playing) WatchPlayerStatus.PLAYING else WatchPlayerStatus.PAUSED
            PlayerStatus.ENDED -> WatchPlayerStatus.ENDED
            PlayerStatus.ERROR -> WatchPlayerStatus.ERROR
        }
        if (status == WatchPlayerStatus.ERROR) { failover(snap.error?.message ?: "That source failed"); return }
        val duration = if (snap.durationMs > 0) snap.durationMs else s.durationMs
        val position = engine.positionMs()
        val remaining = duration - position
        val next = s.nextEpisode
        val countdown = if (next != null && !countdownCancelled && !s.isLive && duration > 0 && remaining in 0..countdownMs) remaining else null
        val note = if (s.isLive) null else com.cosmotune.watch.WatchRoom.lengthNote(roomDurationMs, snap.durationMs.takeIf { it > 0 })
        mutable.update { it.copy(status = status, durationMs = duration, nextEpisodeInMs = countdown, lengthNote = note,
            failover = if (status == WatchPlayerStatus.PLAYING) null else it.failover) }
        if (!s.isLive && (status == WatchPlayerStatus.PLAYING) && now() - lastSavedAt >= saveEveryMs) saveNow()
        if (status == WatchPlayerStatus.PAUSED && now() - lastSavedAt >= saveEveryMs) saveNow()
        if (status == WatchPlayerStatus.ENDED) {
            saveNow(ended = true)
            if (next != null && !countdownCancelled && !autoNextDone) { autoNextDone = true; playNextEpisode() } else ticker?.cancel()
        }
    }

    /** The next copy not tried yet, or the error. */
    private fun failover(reason: String) {
        val s = state.value
        val next = s.streams.firstOrNull { it.isPlayable && it.url !in tried }
        if (next == null) {
            ticker?.cancel()
            mutable.update { it.copy(status = WatchPlayerStatus.ERROR, error = reason, failover = null) }
            return
        }
        mutable.update { it.copy(failover = "That source failed, trying ${next.source.pluginName}") }
        play(next, if (s.isLive) 0 else engine.positionMs().coerceAtLeast(0))
    }

    override fun switchStream(stream: Stream) {
        val position = if (state.value.isLive) 0 else engine.positionMs()
        saveNow()
        tried.remove(stream.url)
        play(stream, position)
    }

    override fun selectAudio(trackId: String) = engine.selectAudioTrack(trackId)

    override fun selectSubtitle(id: String?) {
        mutable.update { it.copy(selectedSubtitleId = id) }
        if (id == null) { engine.selectTextTrack(null); return }
        val sub = state.value.subtitles.firstOrNull { it.id == id } ?: return
        when {
            sub.trackId != null -> engine.selectTextTrack(sub.trackId)
            sub.url != null -> if (added.add(sub.url)) engine.addSubtitle(sub.url, sub.language, sub.label, mimeOfSubtitle(sub))
                else engine.tracks.value.firstOrNull { it.type == TrackType.TEXT && it.external && it.label == sub.label }?.let { engine.selectTextTrack(it.id) }
        }
    }

    override fun searchSubtitles(languages: List<String>) {
        val s = state.value
        val target = s.target ?: return
        scope.launch {
            val found = runCatching { repository.subtitles(target, s.stream, languages) }.getOrDefault(emptyList())
            external = (external + found).distinctBy { it.url ?: it.id }
            mutable.update { it.copy(subtitles = subtitlesOf(engine.tracks.value)) }
        }
    }

    override fun setSubtitleDelay(ms: Long) {
        mutable.update { it.copy(subtitleDelayMs = ms) }
        engine.setSubtitleDelay(ms)
    }

    override fun playNextEpisode() {
        val s = state.value
        val next = s.nextEpisode ?: return
        val current = s.target as? PlayTarget.EpisodeOf ?: return
        saveNow(ended = true)
        val target = current.copy(key = next.key, season = next.season, episode = next.number, episodeIds = next.ids, episodeName = next.name)
        // The same release family plays the next episode (Stremio's binge group, the same plugin).
        val hint = s.stream?.let { StreamHint(pluginId = it.source.pluginId, bingeGroup = it.bingeGroup) }
        open(target, s.summary, startMs = 0, hint = hint)
    }

    override fun cancelNextEpisode() {
        countdownCancelled = true
        mutable.update { it.copy(nextEpisodeInMs = null) }
    }

    override fun stop() {
        saveNow()
        job?.cancel(); ticker?.cancel()
        engine.unload()
        mutable.value = WatchPlayerState()
    }

    /** Saves where this is (films and episodes; never a channel), with the release played so it reopens the same copy. */
    private fun saveNow(ended: Boolean = false) {
        val s = state.value
        val target = s.target ?: return
        val stream = s.stream ?: return
        if (target is PlayTarget.Live || s.isLive) return
        val duration = if (s.durationMs > 0) s.durationMs else engine.snapshot.value.durationMs
        if (duration <= 0) return
        val position = if (ended) duration else engine.positionMs().coerceIn(0, duration)
        if (position < 5_000 && !ended) return
        lastSavedAt = now()
        val progress = WatchProgress(target.key, position, duration, now(), s.summary, (target as? PlayTarget.EpisodeOf)?.episodeName, stream.hint().copy(durationMs = duration))
        scope.launch { runCatching { repository.saveProgress(progress) } }
    }

    private fun audioOf(tracks: List<EngineTrack>) = tracks.filter { it.type == TrackType.AUDIO }.map {
        AudioTrack(it.id, it.language, it.label, it.codec, it.channels, it.selected)
    }

    /** Embedded and side-loaded tracks from the engine, then the fetched ones not loaded yet. */
    private fun subtitlesOf(tracks: List<EngineTrack>): List<Subtitle> {
        val engineSubs = tracks.filter { it.type == TrackType.TEXT }.map {
            Subtitle("engine:${it.id}", null, it.language ?: "und", it.label, it.codec, if (it.external) SubtitleOrigin.STREAM else SubtitleOrigin.EMBEDDED, trackId = it.id)
        }
        val streamSubs = state.value.stream?.subtitles.orEmpty()
        val loadedLabels = engineSubs.map { it.label }.toSet()
        return engineSubs + (streamSubs + external).filter { it.url != null && it.label !in loadedLabels }.distinctBy { it.url }
    }

    private fun subtitleLine(target: PlayTarget): String? = when (target) {
        is PlayTarget.EpisodeOf -> listOfNotNull("S${target.season} · E${target.episode}", target.episodeName).joinToString(" · ")
        is PlayTarget.Live -> "Live"
        else -> null
    }

    companion object {
        fun kindOf(protocol: StreamProtocol) = when (protocol) {
            StreamProtocol.HLS -> UrlKind.HLS
            StreamProtocol.DASH -> UrlKind.DASH
            StreamProtocol.PROGRESSIVE, StreamProtocol.LOCAL_FILE -> UrlKind.PROGRESSIVE
            else -> UrlKind.AUTO
        }

        fun mimeOf(stream: Stream): String? = when (stream.container) {
            com.cosmotune.watch.model.Container.MP4 -> "video/mp4"
            com.cosmotune.watch.model.Container.MKV -> "video/x-matroska"
            com.cosmotune.watch.model.Container.WEBM -> "video/webm"
            com.cosmotune.watch.model.Container.TS -> "video/mp2t"
            else -> null
        }.takeIf { stream.protocol == StreamProtocol.PROGRESSIVE || stream.protocol == StreamProtocol.LOCAL_FILE }

        fun mimeOfSubtitle(sub: Subtitle): String = when ((sub.format ?: sub.url?.substringBefore('?')?.substringAfterLast('.'))?.lowercase()) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> "text/vtt"
        }
    }
}

