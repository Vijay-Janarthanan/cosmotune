package com.cosmotune.video.player

import com.cosmotune.video.model.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.jvm.Synchronized

/**
 * An engine in memory (plan task Y-2, step 3), for the controller's tests and the chrome: it records every call, keeps a
 * virtual clock that [advance] moves (the playhead moves with it while playing, at the speed set, and the video ends at
 * its duration), and [fail] reports an error such as googlevideo's 403 ([PlayerErrorKind.SOURCE_EXPIRED]). A load is
 * [PlayerStatus.READY] at once with [durationOnLoadMs] (0 for a live request) and does not start playing.
 */
class FakeVideoPlayerEngine(override val capabilities: EngineCapabilities = DEFAULT_CAPABILITIES) : VideoPlayerEngine {

    /** One call the engine received. */
    sealed interface Call {
        /** [VideoPlayerEngine.load]. */
        data class Load(val request: PlaybackRequest) : Call

        /** [VideoPlayerEngine.play]. */
        data object Play : Call

        /** [VideoPlayerEngine.pause]. */
        data object Pause : Call

        /** [VideoPlayerEngine.seekTo]. */
        data class SeekTo(val ms: Long) : Call

        /** [VideoPlayerEngine.setSpeed]. */
        data class SetSpeed(val speed: Float) : Call

        /** [VideoPlayerEngine.setVolume]. */
        data class SetVolume(val volume: Float) : Call

        /** [VideoPlayerEngine.setMuted]. */
        data class SetMuted(val muted: Boolean) : Call

        /** [VideoPlayerEngine.setAudioOnly]. */
        data class SetAudioOnly(val audioOnly: Boolean) : Call

        /** [VideoPlayerEngine.selectSubtitle]. */
        data class SelectSubtitle(val language: String?) : Call

        /** [VideoPlayerEngine.release]. */
        data object Release : Call
    }

    private val recorded = mutableListOf<Call>()
    private val snapshotFlow = MutableStateFlow(PlayerSnapshot.Idle)
    private val cueFlow = MutableStateFlow<List<SubtitleCue>>(emptyList())
    private var position = 0L

    override val snapshot: StateFlow<PlayerSnapshot> = snapshotFlow.asStateFlow()
    override val cues: StateFlow<List<SubtitleCue>> = cueFlow.asStateFlow()

    /** Every call so far, oldest first. */
    val calls: List<Call>
        @Synchronized get() = recorded.toList()

    /** The request of the last [load], or null. */
    val lastRequest: PlaybackRequest?
        @Synchronized get() = recorded.lastOrNull { it is Call.Load }?.let { (it as Call.Load).request }

    /** The virtual clock: the sum of every [advance]. */
    var nowMs: Long = 0
        private set

    /** The duration a (not live) load reports; set it before [load]. */
    var durationOnLoadMs: Long = 600_000

    /** The last volume set. */
    var volume: Float = 1f
        private set

    /** The subtitle language selected, or null. */
    var subtitleLanguage: String? = null
        private set

    /** Whether [release] was called. */
    var released: Boolean = false
        private set

    @Synchronized
    override fun positionMs(): Long = position

    @Synchronized
    override fun load(request: PlaybackRequest) {
        recorded += Call.Load(request)
        position = request.startMs.coerceAtLeast(0)
        snapshotFlow.update {
            it.copy(
                status = PlayerStatus.READY, playing = false, durationMs = if (request.live) 0 else durationOnLoadMs,
                bufferedMs = 0, error = null, droppedFrames = 0,
            )
        }
    }

    @Synchronized
    override fun play() {
        recorded += Call.Play
        snapshotFlow.update { if (it.status == PlayerStatus.IDLE || it.status == PlayerStatus.ERROR) it else it.copy(playing = true) }
    }

    @Synchronized
    override fun pause() {
        recorded += Call.Pause
        snapshotFlow.update { it.copy(playing = false) }
    }

    @Synchronized
    override fun seekTo(ms: Long) {
        recorded += Call.SeekTo(ms)
        val duration = snapshotFlow.value.durationMs
        position = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        snapshotFlow.update { if (it.status == PlayerStatus.ENDED && (duration <= 0 || position < duration)) it.copy(status = PlayerStatus.READY) else it }
    }

    @Synchronized
    override fun setSpeed(speed: Float) {
        recorded += Call.SetSpeed(speed)
        snapshotFlow.update { it.copy(speed = speed) }
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        recorded += Call.SetVolume(volume)
        this.volume = volume
    }

    @Synchronized
    override fun setMuted(muted: Boolean) {
        recorded += Call.SetMuted(muted)
        snapshotFlow.update { it.copy(muted = muted) }
    }

    @Synchronized
    override fun setAudioOnly(audioOnly: Boolean) {
        recorded += Call.SetAudioOnly(audioOnly)
        snapshotFlow.update { it.copy(audioOnly = audioOnly) }
    }

    @Synchronized
    override fun selectSubtitle(language: String?) {
        recorded += Call.SelectSubtitle(language)
        subtitleLanguage = language
    }

    @Synchronized
    override fun release() {
        recorded += Call.Release
        released = true
        snapshotFlow.value = PlayerSnapshot.Idle
        position = 0
    }

    /**
     * Moves the virtual clock by [ms]. While playing and ready, the playhead moves by [ms] times the speed; reaching the
     * duration (when there is one) ends the video: [PlayerStatus.ENDED], not playing.
     */
    @Synchronized
    fun advance(ms: Long) {
        nowMs += ms
        val s = snapshotFlow.value
        if (!s.playing || s.status != PlayerStatus.READY) return
        position += (ms * s.speed).toLong()
        if (s.durationMs > 0 && position >= s.durationMs) {
            position = s.durationMs
            snapshotFlow.value = s.copy(status = PlayerStatus.ENDED, playing = false)
        }
    }

    /** Reports an error of [kind]: [PlayerStatus.ERROR], not playing, the position kept. */
    @Synchronized
    fun fail(kind: PlayerErrorKind, message: String = kind.name) {
        snapshotFlow.update { it.copy(status = PlayerStatus.ERROR, playing = false, error = PlayerError(kind, message)) }
    }

    /** Changes the snapshot directly (buffering, dropped frames, the video's size, bandwidth). */
    @Synchronized
    fun setSnapshot(transform: (PlayerSnapshot) -> PlayerSnapshot) = snapshotFlow.update(transform)

    /** Sets the playhead directly. */
    @Synchronized
    fun setPosition(ms: Long) {
        position = ms
    }

    /** Sets the cues showing now. */
    fun setCues(cues: List<SubtitleCue>) {
        cueFlow.value = cues
    }

    /** Forgets the recorded calls. */
    @Synchronized
    fun clearCalls() = recorded.clear()

    companion object {
        /** A phone that decodes H.264, VP9 and AV1 up to 2160p60 in hardware, with Opus, no HDR display, 2400 px tall, playing DASH. */
        val DEFAULT_CAPABILITIES = EngineCapabilities(
            video = listOf(
                DecoderCaps(VideoCodec.H264, 3840, 2160, 60, hardware = true),
                DecoderCaps(VideoCodec.VP9, 3840, 2160, 60, hardware = true),
                DecoderCaps(VideoCodec.AV1, 3840, 2160, 60, hardware = true),
            ),
            opus = true, hdrDisplay = false, displayHeightPx = 2400, sideloadedDash = true, autoCeilingHeight = null,
        )
    }
}
