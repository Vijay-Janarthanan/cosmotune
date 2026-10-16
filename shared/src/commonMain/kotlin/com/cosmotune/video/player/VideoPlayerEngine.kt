package com.cosmotune.video.player

import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import kotlinx.coroutines.flow.StateFlow

/** What an engine is asked to play (spec 4.2, 6.2, 6.3). */
sealed interface MediaInput {
    /** A static DASH [manifest] of one video and one audio representation (spec 6.2, 6.4; the Android engine). */
    data class Dash(val manifest: String, val video: VideoStream, val audio: AudioStream) : MediaInput

    /** Separate progressive streams joined by the engine (spec 6.3: the desktop's EDL address); a muxed stream is [video] alone, with audio inside. */
    data class Progressive(val video: VideoStream?, val audio: AudioStream?) : MediaInput

    /** An HLS address (spec 5.13, 6.2: live streams). */
    data class Hls(val url: String) : MediaInput

    /** A downloaded file ([muxedPath]) or pair ([videoPath] and [audioPath]) on the device (spec 5.4 offline, 5.10). */
    data class LocalFiles(val muxedPath: String?, val videoPath: String?, val audioPath: String?) : MediaInput
}

/**
 * One load of the engine (spec 4.2): the input, the side-loaded subtitles, the headers every request carries, where to
 * start, whether it is live, and the notification's title, channel and thumbnail (spec 6.2).
 */
data class PlaybackRequest(
    val videoId: String,
    val input: MediaInput,
    val subtitles: List<SubtitleTrack>,
    val headers: Map<String, String>,
    val startMs: Long,
    val live: Boolean,
    val title: String,
    val channel: String?,
    val thumbnailUrl: String?,
)

/** The engine's state (spec 4.2). */
enum class PlayerStatus { IDLE, LOADING, READY, BUFFERING, ENDED, ERROR }

/** Why playback failed (spec 4.2, 6.1): [SOURCE_EXPIRED] is googlevideo's HTTP 403, which the controller answers with fresh streams. */
enum class PlayerErrorKind { SOURCE_EXPIRED, NETWORK, DECODER, UNSUPPORTED, OTHER }

/** A playback failure and the engine's message (spec 4.2). */
data class PlayerError(val kind: PlayerErrorKind, val message: String)

/**
 * The engine's state as a whole (spec 4.2): status, playing, duration, buffered, speed, the video's size, audio-only,
 * muted, the error, and the stats card's figures (spec 6.5: bandwidth, decoder, dropped frames). The position is not
 * here: it changes every frame and is read with [VideoPlayerEngine.positionMs].
 */
data class PlayerSnapshot(
    val status: PlayerStatus,
    val playing: Boolean,
    val durationMs: Long,
    val bufferedMs: Long,
    val speed: Float,
    val videoWidth: Int,
    val videoHeight: Int,
    val audioOnly: Boolean,
    val muted: Boolean,
    val error: PlayerError?,
    val bandwidthKbps: Int?,
    val decoderName: String?,
    val droppedFrames: Int,
) {
    companion object {
        /** An engine with nothing loaded. */
        val Idle = PlayerSnapshot(
            status = PlayerStatus.IDLE, playing = false, durationMs = 0, bufferedMs = 0, speed = 1f, videoWidth = 0,
            videoHeight = 0, audioOnly = false, muted = false, error = null, bandwidthKbps = null, decoderName = null,
            droppedFrames = 0,
        )
    }
}

/** One subtitle cue the chrome draws (spec 6.5: subtitles are drawn by the app from the engine's cues). */
data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

/** What a device decodes for one codec (spec 6.2, 6.4): the largest size and frame rate, and whether in hardware. */
data class DecoderCaps(val codec: VideoCodec, val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val hardware: Boolean)

/**
 * What an engine can play (spec 6.2, 6.3, 6.4): the decoders, Opus, an HDR display, the display's height in pixels.
 * [sideloadedDash]: the engine plays [MediaInput.Dash] (Android) or wants [MediaInput.Progressive] (desktop, spec 6.3);
 * [autoCeilingHeight]: Auto never goes above it (desktop 1440).
 */
data class EngineCapabilities(
    val video: List<DecoderCaps>,
    val opus: Boolean,
    val hdrDisplay: Boolean,
    val displayHeightPx: Int,
    val sideloadedDash: Boolean,
    val autoCeilingHeight: Int?,
)

/**
 * One platform player (spec 4.2, 6.2, 6.3): Media3 on Android (plan task Y-11), libmpv on the desktop (Y-12). The
 * controller drives it; the chrome reads [snapshot], [cues] and [positionMs].
 */
interface VideoPlayerEngine {
    /** What this engine decodes and how it wants its input (spec 6.4). */
    val capabilities: EngineCapabilities

    /** The engine's state; changes on events, never per frame (spec 4.2). */
    val snapshot: StateFlow<PlayerSnapshot>

    /** The subtitle cues showing now (spec 6.5). */
    val cues: StateFlow<List<SubtitleCue>>

    /** The playhead; cheap; read it in draw, layout or a ticker, never as composition state per frame. */
    fun positionMs(): Long

    /** Loads [request] (replacing what was loaded) and starts at its `startMs`. */
    fun load(request: PlaybackRequest)

    /** Plays. */
    fun play()

    /** Pauses. */
    fun pause()

    /** Seeks to [ms]. */
    fun seekTo(ms: Long)

    /** Sets the speed, pitch kept (spec 6.5: 0.25 to 4). */
    fun setSpeed(speed: Float)

    /** Sets the volume, 0..1. */
    fun setVolume(volume: Float)

    /** Mutes or unmutes (spec 6.1: SponsorBlock's Mute; the Shorts mute, 5.15). */
    fun setMuted(muted: Boolean)

    /** Stops or resumes video decoding while the same source keeps playing (spec 6.1 background, 6.2, 6.3). */
    fun setAudioOnly(audioOnly: Boolean)

    /** Shows the side-loaded subtitles of [language], or none for null. */
    fun selectSubtitle(language: String?)

    /** Frees the player; the engine is not used again. */
    fun release()
}
