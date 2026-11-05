package com.cosmotune.video.host

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.playback.PartyFollow
import com.cosmotune.music.playback.RoomKinds
import com.cosmotune.music.playback.RoomNotices
import com.cosmotune.music.ui.room.RoomVideoHost
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.graph.rememberVideoGraph
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A room's video on the phone: Video mode's own player plays it on each device, following the room's shared clock with the rules
 * the music player follows ([PartyFollow.decide], with [RoomKinds.Player.VIDEO]), and nothing is streamed between devices. Quality,
 * captions and speed are each person's, set on Video's watch page; the room never changes them.
 *
 * The graph Video mode plays with lives as long as the process but is built by Video's screen, so the player is reached once Video has
 * been drawn ([Attach]). A video the room starts while another mode is on screen is opened in Video the way "Watch the music video"
 * opens one ([opening], read by [PhoneModeHost]): the video first, then the mode. Video's own rule then holds: a video stops when the
 * mode is not Video, and this device sits the room's video out until Video is back.
 *
 * What the person does on the watch page stays on this device: a pause there sits the sound out until they play again (and is
 * never sent to the room), a seek there is put back on the room's playhead. The room's controls are in the room screen.
 */
object RoomVideo : RoomVideoHost {
    private const val TAG = "RoomVideo"

    /** How often the video is checked against the room, as the music player is. */
    private const val TICK_MS = 700L

    /** A load not seen through by then is tried again. */
    private const val LOAD_RETRY_MS = 10_000L

    /** After a play or a pause is asked of the player, how long before it is asked again: the state shows the change a moment late. */
    private const val TOGGLE_SETTLE_MS = 1_500L

    /** A video left by Video's autoplay this close to its end had ended. */
    private const val END_WINDOW_MS = 3_000L

    private const val DRIFT_STRIKES = 2
    private const val DRIFT_COOLDOWN_MS = 6_000L

    /** Video's player, once Video mode has been drawn in this process. */
    var playback by mutableStateOf<VideoPlayback?>(null)
        private set
    private var surface by mutableStateOf<VideoSurfaceProvider?>(null)

    /** The look's settings: the stored mode, and the way to switch it. Set by the room's root ([follow]). */
    @Volatile
    private var settings: CosmoLookSettings? = null

    /**
     * The room screen is up and shows this video ([media]): Video's own pages step aside meanwhile ([PhoneModeHost]), so the one player
     * has one picture. Their saved state is kept, as on a mode flip.
     */
    var covered by mutableStateOf(false)
        private set

    override fun cover(covered: Boolean) {
        if (this.covered != covered) this.covered = covered
    }

    override fun follow(settings: CosmoLookSettings?) {
        this.settings = settings
        start()
    }

    override fun positionOf(videoId: String): Long? = playback?.takeIf { it.state.value.item?.id == videoId }?.positionMs()

    /** A video the room wants on the watch page; [PhoneModeHost] hands it to Video and makes Video the mode, then clears it. */
    val opening = MutableStateFlow<PendingVideo?>(null)

    /** Whether this phone plays videos at all: the new look (Video needs its shell) and Video mode switched on. */
    fun available(): Boolean = settings != null && AppModes.available(AppMode.VIDEO)

    fun inVideoMode(): Boolean = settings?.mode?.value == AppMode.VIDEO

    /** Keeps Video's player in reach once Video's screen has built its graph. Called beside Video's screen, with the same links. */
    @Composable
    fun Attach(links: VideoHostLinks) {
        val graph: VideoGraph = rememberVideoGraph(links)
        SideEffect {
            if (playback !== graph.playback) playback = graph.playback
            if (surface !== graph.surface) surface = graph.surface
        }
    }

    /** The room screen's picture for [track] when it is a video and Video's player is in reach; null draws the artwork. */
    override fun media(track: PartyTrack?): (@Composable () -> Unit)? {
        val shown = surface ?: return null
        if (track == null || RoomKinds.playerOf(track) != RoomKinds.Player.VIDEO || playback == null) return null
        return { shown.Surface(Modifier.fillMaxSize(), VideoScale.FIT) }
    }

    /** The watch page of the room's video, for its quality, captions and speed: this device's own, never the room's. */
    override fun showWatchPage(videoId: String) {
        opening.value = PendingVideo(videoId, null)
    }

    // ---- following the room ----

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var startJob: Job? = null

    /** The room's video this device put on its player, so that only that one is stopped when the room moves to something else. */
    private var roomVideoId: String? = null
    private var roomItem: String? = null
    private var openedFor: String? = null
    private var loadingId: String? = null
    private var loadingAtMs = 0L

    private var commandedPlaying = false
    private var commandAtMs = 0L
    private var locallyPaused = false

    private var alignedSeq = -1L
    private var lastAnchor: Triple<Long, Long, Boolean>? = null
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L

    private var lastSeenPositionMs = 0L
    private var lastSeenDurationMs = 0L
    private var lastSeenId: String? = null
    private var endedSeq = -1L
    private var advancedSeq = -1L

    private val unplayable = LinkedHashSet<String>()
    private var announcedSkip: String? = null

    /** Once per process. */
    private fun start() {
        if (started) return
        started = true
        scope.launch {
            ListenTogether.state
                .map { Triple(it.playback.seq, it.code, it.clockSynced) }
                .distinctUntilChanged()
                .collect { reconcile() }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                reconcile()
            }
        }
    }

    private fun reconcile() {
        val party = ListenTogether.state.value
        if (!party.inParty) {
            reset()
            return
        }
        val target = party.playback
        val track = target.track ?: return
        val pb = playback
        val state = pb?.state?.value
        val loadedId = state?.item?.id
        val now = SystemClock.elapsedRealtime()

        // A new item: whatever this device did about the last one (a pause of its own, a failed load) is forgotten.
        if (track.videoId != roomItem) {
            roomItem = track.videoId
            locallyPaused = false
            openedFor = null
            loadingId = null
            alignedSeq = -1L
            announcedSkip = null
        }

        if (RoomKinds.playerOf(track) != RoomKinds.Player.VIDEO) {
            // The room moved on to music, a station or an episode, which the music player plays: the room's video stops here.
            if (pb != null && roomVideoId != null && loadedId == roomVideoId) {
                Log.i(TAG, "the room moved on from its video; stopping it here")
                pb.stop()
            }
            roomVideoId = null
            commandedPlaying = false
            return
        }

        // The end of the room's video: Video ends it (ENDED) or its autoplay has already moved on to a video of its own.
        if (loadedId == track.videoId) {
            if (state.status == PlayerStatus.ENDED && target.isPlaying) endedSeq = target.seq
            lastSeenPositionMs = pb.positionMs()
            lastSeenDurationMs = state.durationMs
        } else if (lastSeenId == track.videoId && loadedId != null && lastSeenDurationMs > 0 &&
            lastSeenPositionMs >= lastSeenDurationMs - END_WINDOW_MS && target.isPlaying
        ) {
            endedSeq = target.seq
        }
        lastSeenId = loadedId
        if (endedSeq == target.seq) {
            if (pb != null && loadedId != null && loadedId != track.videoId) pb.stop()
            if (advancedSeq != target.seq && !party.controlsLocked) {
                advancedSeq = target.seq
                ListenTogether.nextAfter(target.seq)
            }
            return
        }
        if (loadedId == track.videoId && state.error != null) unplayable += track.videoId

        // A pause of the person's own (or of the app leaving the screen): theirs until they play again, which catches up.
        if (pb != null && state != null && loadedId == track.videoId) {
            if (locallyPaused && state.playing) {
                locallyPaused = false
                alignedSeq = -1L
                commandedPlaying = true
            } else if (PartyFollow.pausedHere(
                    commandedPlaying = commandedPlaying,
                    roomPlaying = target.isPlaying,
                    playing = state.playing,
                    ready = state.status == PlayerStatus.READY,
                    msSinceCommand = now - commandAtMs,
                )
            ) {
                Log.i(TAG, "paused on this device; sitting the room's video out until played again")
                locallyPaused = true
            }
        }

        val anchor = Triple(target.anchorMs, target.positionMs, target.isPlaying)
        val step = PartyFollow.decide(
            shared = PartyFollow.Shared(
                playback = target,
                clockSynced = party.clockSynced,
                wantMs = if (party.clockSynced && target.isPlaying) ListenTogether.partyPositionMs() else null,
                msUntilStart = ListenTogether.msUntilStart(),
            ),
            local = PartyFollow.Local(
                mediaId = loadedId,
                playWhenReady = state?.playing == true,
                ready = state?.status == PlayerStatus.READY,
                positionMs = pb?.positionMs() ?: 0L,
                ownPace = state != null && state.speed != 1f,
            ),
            locallyPaused = locallyPaused,
            unplayableHere = !RoomKinds.playableHere(track, available()) || track.videoId in unplayable,
            alignedSeq = alignedSeq,
            anchorChanged = anchor != lastAnchor,
            player = RoomKinds.Player.VIDEO,
        )

        when (step) {
            PartyFollow.Step.Leave, PartyFollow.Step.Elsewhere, PartyFollow.Step.Ended, PartyFollow.Step.Muted,
            PartyFollow.Step.AwaitClock, PartyFollow.Step.NoPosition -> Unit

            PartyFollow.Step.SitOut -> {
                if (pb != null && loadedId == track.videoId && state.playing) toggle(pb, playing = false, now)
                if (announcedSkip != track.videoId) {
                    announcedSkip = track.videoId
                    RoomNotices.skipped(track)
                }
            }

            PartyFollow.Step.Load -> load(track, pb, now)

            is PartyFollow.Step.Hold -> {
                if (pb != null && state?.playing == true) toggle(pb, playing = false, now)
                commandedPlaying = false
                step.seekTo?.let { pb?.seekTo(it) }
            }

            is PartyFollow.Step.WaitForStart -> {
                startJob?.cancel()
                startJob = scope.launch {
                    delay(step.ms)
                    reconcile()
                }
            }

            is PartyFollow.Step.Start -> if (pb != null) {
                step.seekTo?.let(pb::seekTo)
                if (state?.playing != true) toggle(pb, playing = true, now)
                commandedPlaying = true
                alignedSeq = target.seq
                lastAnchor = anchor
            }

            PartyFollow.Step.Settling -> driftStrikes = 0

            is PartyFollow.Step.Align, is PartyFollow.Step.Drift, PartyFollow.Step.InStep -> {
                if (target.seq != alignedSeq) {
                    alignedSeq = target.seq
                    lastAnchor = anchor
                }
                when (step) {
                    is PartyFollow.Step.Align -> {
                        Log.i(TAG, "aligning ${step.driftMs}ms onto room control ${target.seq}")
                        pb?.seekTo(step.seekTo)
                    }
                    is PartyFollow.Step.Drift -> if (now >= driftCooldownUntilMs && ++driftStrikes >= DRIFT_STRIKES) {
                        Log.i(TAG, "correcting ${step.driftMs}ms of drift against the room")
                        driftStrikes = 0
                        driftCooldownUntilMs = now + DRIFT_COOLDOWN_MS
                        pb?.seekTo(step.seekTo)
                    }
                    else -> driftStrikes = 0
                }
            }
        }
    }

    /**
     * Puts the room's video on Video's player at the room's playhead, and on the watch page. With Video not on screen (or never drawn
     * yet) the watch page is asked for once, and Video, as it opens it, plays it; the next reconcile lands it on the room's playhead.
     */
    private fun load(track: PartyTrack, pb: VideoPlayback?, now: Long) {
        roomVideoId = track.videoId
        if (pb != null && inVideoMode()) {
            if (loadingId == track.videoId && now - loadingAtMs < LOAD_RETRY_MS) return
            loadingId = track.videoId
            loadingAtMs = now
            val start = ListenTogether.partyPositionMs()?.coerceAtLeast(0L)
            pb.play(track.videoId, startMs = start)
            commandedPlaying = true
            commandAtMs = now
        }
        if (openedFor != track.videoId) {
            openedFor = track.videoId
            // No start here: the watch page may open late (behind the room screen), and the room's playhead is applied on arrival.
            opening.value = PendingVideo(track.videoId, null)
        }
    }

    private var lastToggleAtMs = 0L

    /** Play or pause, once: the player's state shows a toggle a moment late, and asking again in that moment would undo it. */
    private fun toggle(pb: VideoPlayback, playing: Boolean, now: Long) {
        commandedPlaying = playing
        commandAtMs = now
        if (now - lastToggleAtMs < TOGGLE_SETTLE_MS) return
        lastToggleAtMs = now
        pb.togglePlayPause()
    }

    private fun reset() {
        roomVideoId = null
        roomItem = null
        openedFor = null
        loadingId = null
        commandedPlaying = false
        locallyPaused = false
        alignedSeq = -1L
        lastAnchor = null
        driftStrikes = 0
        endedSeq = -1L
        advancedSeq = -1L
        lastSeenId = null
        unplayable.clear()
        announcedSkip = null
        startJob?.cancel()
    }
}
