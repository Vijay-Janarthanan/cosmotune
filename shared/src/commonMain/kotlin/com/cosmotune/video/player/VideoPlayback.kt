package com.cosmotune.video.player

import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.SkipSegment
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the watch page, the chrome and the mini capsule show of the playing video (spec 6.1, 6.5): the item and its
 * details, the engine's status and error, the quality ladder and choice, audio tracks, subtitles, SponsorBlock's
 * segments and its pending skip, last skip and muted segment, the queue, autoplay, loop, live, the aspect ratio
 * (width / height), audio-only, muted, and whether a Short is playing ([short], spec 5.15). The position is not here: it
 * is read with [VideoPlayback.positionMs] in draw-phase code. [queue] is the items after the current one; [status] stays
 * [PlayerStatus.ENDED] when a video ends and nothing is next.
 */
data class PlaybackUiState(
    val item: VideoItem?,
    val details: VideoDetails?,
    val status: PlayerStatus,
    val error: VideoUiError?,
    val playing: Boolean,
    val durationMs: Long,
    val bufferedMs: Long,
    val speed: Float,
    val ladder: List<QualityOption>,
    val quality: QualityOption?,
    val autoQuality: Boolean,
    val audioTracks: List<AudioStream>,
    val audioTrackId: String?,
    val subtitles: List<SubtitleTrack>,
    val subtitleLanguage: String?,
    val segments: List<SkipSegment>,
    val pendingSkip: SkipSegment?,
    val lastSkip: SkipSegment?,
    val mutedSegment: SkipSegment?,
    val queue: List<VideoItem>,
    val autoplay: Boolean,
    val loop: Boolean,
    val live: Boolean,
    val aspect: Float,
    val audioOnly: Boolean,
    val muted: Boolean = false,
    val short: Boolean = false,
) {
    companion object {
        /** Nothing playing: the state before the first `play` and after `stop` (16:9, autoplay on as in spec 5.11). */
        val Idle = PlaybackUiState(
            item = null, details = null, status = PlayerStatus.IDLE, error = null, playing = false, durationMs = 0,
            bufferedMs = 0, speed = 1f, ladder = emptyList(), quality = null, autoQuality = true, audioTracks = emptyList(),
            audioTrackId = null, subtitles = emptyList(), subtitleLanguage = null, segments = emptyList(), pendingSkip = null,
            lastSkip = null, mutedSegment = null, queue = emptyList(), autoplay = true, loop = false, live = false,
            aspect = 16f / 9f, audioOnly = false,
        )
    }
}

/**
 * The errors the player shows (spec 5.4, 6.1; the `video_error_*` strings): [STOPPED] is "This video stopped loading"
 * after the refresh budget is spent; the others mirror [com.cosmotune.video.source.VideoSourceError].
 */
enum class VideoUiError { STOPPED, UNAVAILABLE, AGE, KIDS, PRIVATE, PAID, GEO, BOT, NETWORK, PARSING }

/**
 * What the screens drive (spec 6.1): the controller (plan task Y-10) behind an interface, so the shell and the chrome are
 * built against [FakeVideoPlayback].
 */
interface VideoPlayback {
    /** The state the screens draw; changes on events, never per frame. */
    val state: StateFlow<PlaybackUiState>

    /** The engine that draws the picture (its surface) and the stats (spec 6.5). */
    val engine: VideoPlayerEngine

    /** The playhead; read it in draw, layout or a ticker, never as composition state per frame. */
    fun positionMs(): Long

    /**
     * Plays [videoId] (spec 6.1): from [startMs], else the resume position; [queue] fills the queue (a playlist's "Play
     * all"). [short]: loop, no resume, no autoplay (spec 5.15).
     */
    fun play(videoId: String, startMs: Long? = null, queue: List<VideoItem> = emptyList(), short: Boolean = false)

    /** Resolves the next Short's details and stream addresses into the cache (spec 5.15); does not touch the engine. */
    fun prefetch(videoId: String)

    /** Mutes or unmutes; the Shorts pager keeps the choice for the session (spec 5.15). */
    fun setMuted(muted: Boolean)

    /** Plays or pauses. */
    fun togglePlayPause()

    /** Seeks to [ms] (a seek into a segment marks it as chosen, spec 7.1). */
    fun seekTo(ms: Long)

    /** Seeks by [deltaMs] (double tap, keys; spec 6.5, 6.6). */
    fun seekBy(deltaMs: Long)

    /** Sets the speed (spec 6.5: 0.25 to 4). */
    fun setSpeed(speed: Float)

    /** Picks a rung of the ladder, or Auto for null (spec 6.4). */
    fun selectQuality(option: QualityOption?)

    /** Picks an audio track, or the default (original) for null (spec 6.4). */
    fun selectAudioTrack(trackId: String?)

    /** Shows the subtitles of [language], or none for null (spec 6.5). */
    fun selectSubtitles(language: String?)

    /** Loops the current video. */
    fun setLoop(loop: Boolean)

    /** Plays the next video at the end (spec 6.1). */
    fun setAutoplay(on: Boolean)

    /** Appends [item] to the queue (spec 6.1). */
    fun addToQueue(item: VideoItem)

    /** Plays the queue's next item. */
    fun playNext()

    /** Plays the queue's previous item. */
    fun playPrevious()

    /** Skips the segment the "Skip" pill offers (spec 6.5). */
    fun skipPending()

    /** Undoes the last skip: back to the segment's start, marked handled (spec 7.1). */
    fun undoSkip()

    /** The UI came or went (spec 6.1 background: audio-only or pause). */
    fun onUiVisible(visible: Boolean)

    /** Stops and records the position (spec 6.1). */
    fun stop()

    /**
     * Plays a download from its [bundle] (spec 5.4 "Offline", 6.1): the files, with the subtitles, the chapters and the
     * SponsorBlock segments saved with them. No source call is made: the state's `details` is a stand-in made of the item and
     * the bundle's chapters and subtitles (no description, no related videos), `segments` are the bundle's (for the
     * categories the settings still enable), and history is written as for any video. Nothing plays on after it ends but the
     * queue (a downloaded video has no related list). [startMs] wins over the history position, as for [play].
     *
     * What screens must know of the state, here and for every video: `queue` is the items after the current one (not the
     * whole list; Previous works from a trail the controller keeps); when a video ends and nothing is next (no loop, no
     * queue, no autoplay pick) the state stays `ENDED` so a page can offer "Replay", and the engine is unloaded; a Short that
     * was paused because the UI left stays paused until the pager plays it again.
     */
    fun playLocal(item: VideoItem, bundle: LocalBundle, startMs: Long? = null)

    /** Plays a download that has only its [files]: [playLocal] with a bundle of no subtitles, chapters or segments. */
    fun playLocal(item: VideoItem, files: MediaInput.LocalFiles, startMs: Long? = null) = playLocal(item, LocalBundle(files), startMs)
}
