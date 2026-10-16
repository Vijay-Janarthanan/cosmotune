package com.cosmotune.video.player

import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.jvm.Synchronized

/**
 * A playback whose state you set (plan task Y-2, step 3), for the shell, the chrome and the screens: [set] and [update]
 * replace the state, [position] is the playhead [positionMs] returns, [calls] records every call as `name(argument)`.
 * The actions change the state the obvious way (play shows the item, toggle flips playing, seek moves [position], the
 * quality, track, subtitle, loop, autoplay, queue and mute calls set their fields) so a screen reacts when tapped.
 * [lookup] finds the item a `play(id)` shows (the sample items of [FakeVideoSource] by default).
 */
class FakeVideoPlayback(
    override val engine: FakeVideoPlayerEngine = FakeVideoPlayerEngine(),
    initial: PlaybackUiState = PlaybackUiState.Idle,
    private val lookup: (String) -> VideoItem? = { FakeVideoSource.sampleItem(it) },
) : VideoPlayback {
    private val flow = MutableStateFlow(initial)
    private val recorded = mutableListOf<String>()

    override val state: StateFlow<PlaybackUiState> = flow.asStateFlow()

    /** The playhead [positionMs] returns. */
    var position: Long = 0

    /** Every call so far, as `name(argument)` (for example `play(fakevideo01)`), oldest first. */
    val calls: List<String>
        @Synchronized get() = recorded.toList()

    /** Replaces the state. */
    fun set(state: PlaybackUiState) {
        flow.value = state
    }

    /** Replaces the state with [transform] of the current one. */
    fun update(transform: (PlaybackUiState) -> PlaybackUiState) = flow.update(transform)

    @Synchronized
    private fun record(call: String) {
        recorded += call
    }

    override fun positionMs(): Long = position

    override fun play(videoId: String, startMs: Long?, queue: List<VideoItem>, short: Boolean) {
        record("play($videoId)")
        show(lookup(videoId) ?: placeholder(videoId), startMs, short)
        if (queue.isNotEmpty()) flow.update { it.copy(queue = queue) }
    }

    override fun prefetch(videoId: String) = record("prefetch($videoId)")

    override fun setMuted(muted: Boolean) {
        record("setMuted($muted)")
        engine.setMuted(muted)
        flow.update { it.copy(muted = muted) }
    }

    override fun togglePlayPause() {
        record("togglePlayPause()")
        flow.update { it.copy(playing = !it.playing) }
    }

    override fun seekTo(ms: Long) {
        record("seekTo($ms)")
        val duration = flow.value.durationMs
        position = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
    }

    override fun seekBy(deltaMs: Long) {
        record("seekBy($deltaMs)")
        val duration = flow.value.durationMs
        val target = position + deltaMs
        position = if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0)
    }

    override fun setSpeed(speed: Float) {
        record("setSpeed($speed)")
        flow.update { it.copy(speed = speed) }
    }

    override fun selectQuality(option: QualityOption?) {
        record("selectQuality(${option?.label})")
        flow.update { it.copy(quality = option ?: it.ladder.firstOrNull(), autoQuality = option == null) }
    }

    override fun selectAudioTrack(trackId: String?) {
        record("selectAudioTrack($trackId)")
        flow.update { it.copy(audioTrackId = trackId) }
    }

    override fun selectSubtitles(language: String?) {
        record("selectSubtitles($language)")
        flow.update { it.copy(subtitleLanguage = language) }
    }

    override fun setLoop(loop: Boolean) {
        record("setLoop($loop)")
        flow.update { it.copy(loop = loop) }
    }

    override fun setAutoplay(on: Boolean) {
        record("setAutoplay($on)")
        flow.update { it.copy(autoplay = on) }
    }

    override fun addToQueue(item: VideoItem) {
        record("addToQueue(${item.id})")
        flow.update { it.copy(queue = it.queue + item) }
    }

    override fun playNext() {
        record("playNext()")
        val next = flow.value.queue.firstOrNull() ?: return
        show(next, null, short = false)
        flow.update { it.copy(queue = it.queue.drop(1)) }
    }

    override fun playPrevious() {
        record("playPrevious()")
        position = 0
    }

    override fun skipPending() {
        record("skipPending()")
        val segment = flow.value.pendingSkip ?: return
        position = segment.endMs
        flow.update { it.copy(pendingSkip = null, lastSkip = segment) }
    }

    override fun undoSkip() {
        record("undoSkip()")
        val segment = flow.value.lastSkip ?: return
        position = segment.startMs
        flow.update { it.copy(lastSkip = null) }
    }

    override fun onUiVisible(visible: Boolean) {
        record("onUiVisible($visible)")
        flow.update { it.copy(audioOnly = !visible) }
    }

    override fun stop() {
        record("stop()")
        position = 0
        flow.value = PlaybackUiState.Idle
    }

    override fun playLocal(item: VideoItem, files: MediaInput.LocalFiles, startMs: Long?) {
        record("playLocal(${item.id})")
        show(item, startMs, short = false)
    }

    private fun show(item: VideoItem, startMs: Long?, short: Boolean) {
        position = startMs ?: 0
        flow.update {
            it.copy(
                item = item, details = null, status = PlayerStatus.READY, error = null, playing = true,
                durationMs = (item.durationSec ?: 0) * 1000, bufferedMs = 0, pendingSkip = null, lastSkip = null,
                mutedSegment = null, loop = short || (it.loop && !it.short), live = item.isLive,
                aspect = if (item.isShort) 9f / 16f else 16f / 9f, short = short,
            )
        }
    }

    private fun placeholder(id: String) = VideoItem(
        id = id, title = id, channelId = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
        durationSec = null, viewCount = null, uploadedAtMs = null, uploadedText = null,
    )
}
