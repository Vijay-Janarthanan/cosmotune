package com.cosmotune.watch

import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.watch.model.AudioTrack
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.TitleSummary
import kotlinx.coroutines.flow.StateFlow

enum class WatchPlayerStatus { IDLE, RESOLVING, LOADING, PLAYING, PAUSED, BUFFERING, ENDED, NOT_FOUND, ERROR }

/** What the Watch player chrome draws (the film card: title, episode, time left, audio and subtitle buttons). */
data class WatchPlayerState(
    val status: WatchPlayerStatus = WatchPlayerStatus.IDLE,
    val target: PlayTarget? = null,
    val summary: TitleSummary? = null,
    /** "S2 · E4 · Low Water" for an episode. */
    val subtitleLine: String? = null,
    val stream: Stream? = null,
    /** Every copy found, best first; the Sources sheet switches between them. */
    val streams: List<Stream> = emptyList(),
    val durationMs: Long = 0,
    val audioTracks: List<AudioTrack> = emptyList(),
    /** Embedded tracks, stream subtitles and the ones fetched from OpenSubtitles. */
    val subtitles: List<Subtitle> = emptyList(),
    val selectedSubtitleId: String? = null,
    val subtitleDelayMs: Long = 0,
    /** Set near the end of an episode: the next-episode card counts down from [nextEpisodeInMs]. */
    val nextEpisode: Episode? = null,
    val nextEpisodeInMs: Long? = null,
    /** "That source failed, trying the next". */
    val failover: String? = null,
    val error: String? = null,
    val isLive: Boolean = false,
    val skipIntervals: List<com.cosmotune.watch.model.WatchSkip> = emptyList(),
    /** In a room: "Your copy is 4 min longer: a different cut, so positions may not line up". */
    val lengthNote: String? = null,
    val muted: Boolean = false,
)

/**
 * Plays Watch items on the platform's [VideoPlayerEngine] (Media3 on Android, libmpv on the desktop): resolves the target
 * through the repository, starts the best copy at the saved position, fails over to the next copy, saves progress, loads
 * external subtitles and moves on to the next episode. Position, play, pause and seek go to [engine] directly.
 */
interface WatchPlayer {
    val engine: VideoPlayerEngine
    val state: StateFlow<WatchPlayerState>

    /**
     * Starts [target]. [stream] plays that copy (from the "Play from" list) instead of the best one; [startMs] null resumes
     * from saved progress (0 for a channel); [hint] ranks a matching copy first (rooms, the last release played); [fallback]
     * plays when no plugin has a copy (a room's public stream).
     */
    fun open(target: PlayTarget, summary: TitleSummary? = null, stream: Stream? = null, startMs: Long? = null, hint: StreamHint? = null,
        fallback: Stream? = null)
    fun switchStream(stream: Stream)
    fun toggleMute() {}
    fun setRemotePlayback(active: Boolean) {}
    fun selectAudio(trackId: String)
    /** Null turns subtitles off. A subtitle with a url is loaded on first selection. */
    fun selectSubtitle(id: String?)
    /** Fetches OpenSubtitles (and other subtitle plugins) for what is playing and adds them to [WatchPlayerState.subtitles]. */
    fun searchSubtitles(languages: List<String> = emptyList())
    fun setSubtitleDelay(ms: Long)
    fun playNextEpisode()
    /** Cancels the next-episode countdown for this episode. */
    fun cancelNextEpisode()
    /** Saves progress and unloads. */
    fun stop()
}
